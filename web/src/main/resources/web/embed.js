/* QuillPen embedded editor — QuillPost's side of the suite protocol (EMBED.md).
 *
 * When QuillPen (the suite's word processor) is installed, the compose window can edit the message
 * body in a complete, embedded copy of it: an <iframe> that fills the compose body, talking to this
 * page with window.postMessage. Loaded after app.js; it only adds to the compose object and the
 * Compose settings page, so app.js needs no QuillPen-specific code.
 *
 * Where QuillPen comes from (detect()):
 *   Android  the WebView shell answers https://quillpen.embed/<path> from QuillPen's ContentProvider
 *            and reports availability through QuillboxAndroid.quillPenAvailable();
 *   desktop  the local server serves QuillPen's resources/embed folder at /quillpen-embed/<path>
 *            (GET /api/quillpen reports platform "desktop" and whether it was found);
 *   web      a QuillPen web deployment URL entered in Settings ▸ Compose (stored in this browser);
 *   iOS      not available — the option is hidden.
 *
 * Command hook: openInQuillPen() (global) opens the current compose body in QuillPen and resolves to
 * true when the editor is starting. For a command registry / Quick Access Bar entry:
 *   { id: "compose.openInQuillPen", label: "QuillPen", title: "Edit this message in QuillPen",
 *     run: () => openInQuillPen(), visible: () => quillPen.isAvailable(), enabled: () => quillPen.canOpen() }
 * quillPen.isAvailable() is the last detection result (synchronous); quillPen.detect() re-checks.
 */
"use strict";

const quillPen = (() => {
  const PREFIX = "quill-embed:";
  const READY_TIMEOUT_MS = 8000;      // EMBED.md §4: no "ready" within 8 s → error + built-in editor
  const REQUEST_TIMEOUT_MS = 6000;
  const SANDBOX = "allow-scripts allow-same-origin allow-popups allow-modals allow-downloads";
  const MODES = [["off", "Off"], ["ask", "Ask"], ["always", "Always"]];
  const QUILL_ICON = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 3c-5 1-9 5-11 10l-1 4 3-2c3-1 6-4 7-7-1 0-3 0-4-1 2-1 4-3 6-4z"/><path d="M8 17 4 21"/></svg>';

  let info = { platform: null, available: false, base: null, status: null };
  let infoToken = undefined;          // the session the detection belongs to (desktop answers need a token)
  let detecting = null;

  // The open editor (one compose window at a time).
  let frame = null, host = null, origin = null, ready = false, readyTimer = null;
  let originalHtml = "", mirrorHtml = "", syncing = null;
  let nextId = 1;
  const pending = new Map();          // request id -> { resolve, timer }

  // ------------------------------------------------------------------ detection
  function shellPlatform() {
    if (typeof NATIVE !== "undefined" && NATIVE === "android") return "android";
    if (typeof NATIVE !== "undefined" && NATIVE === "ios") return "ios";
    return null;
  }
  function normaliseBase(url) {
    if (!String(url || "").trim()) return null;
    try {
      const u = new URL(String(url || "").trim(), location.href);
      if (u.protocol !== "https:" && u.protocol !== "http:") return null;
      u.search = ""; u.hash = "";
      u.pathname = u.pathname.replace(/index\.html?$/i, "");
      if (!u.pathname.endsWith("/")) u.pathname += "/";
      return u.toString();
    } catch { return null; }
  }

  /**
   * Desktop: QuillPen must not share QuillPost's origin, or (with allow-same-origin) it could read
   * QuillPost's storage, including the session token and a remembered password. The launcher opens
   * QuillPost at http://127.0.0.1:<port>; the same server answers http://localhost:<port>, which the
   * browser treats as a different origin with separate storage and cookies. The server only serves
   * /quillpen-embed/ on the "localhost" host name.
   */
  function embedOrigin() {
    const u = new URL(location.href);
    u.hostname = "localhost";
    return u.origin;
  }

  /** Finds QuillPen for this platform. Resolves to { platform, available, base, status }. */
  function detect(force = false) {
    const token = state.token || null;
    if (!force && infoToken === token && info.platform) return Promise.resolve(info);
    if (detecting && !force) return detecting;
    detecting = (async () => {
      const shell = shellPlatform();
      let next;
      if (shell === "ios") next = { platform: "ios", available: false, base: null, status: null };
      else if (shell === "android") {
        let ok = false;
        try { ok = typeof window.QuillboxAndroid.quillPenAvailable === "function" && !!window.QuillboxAndroid.quillPenAvailable(); } catch {}
        next = { platform: "android", available: ok, base: "https://quillpen.embed/", status: null };
      } else {
        let status = null;
        if (token) { try { status = await api("/api/quillpen"); } catch {} }
        if (status && status.platform === "desktop") {
          // If QuillPost itself was opened on "localhost" there is no separate origin left: do not embed.
          next = { platform: "desktop", available: !!status.available && location.hostname !== "localhost", base: embedOrigin() + "/quillpen-embed/", status };
        } else {
          const base = normaliseBase(prefs.get("quillpenUrl", ""));
          let ok = false;
          if (base) {
            // Opaque (no-cors) fetch: succeeds whenever the server answers, without needing CORS.
            try { await fetch(new URL("index.html", base), { mode: "no-cors", cache: "no-store" }); ok = true; } catch {}
          }
          next = { platform: "web", available: ok, base, status };
        }
      }
      info = next; infoToken = token;
      updateButton();
      return info;
    })();
    detecting.finally(() => { detecting = null; });
    return detecting;
  }

  const mode = () => { const m = prefs.get("quillpenMode", "ask"); return MODES.some(([v]) => v === m) ? m : "ask"; };
  const isAvailable = () => !!info.available;
  const isOpen = () => !!frame;
  const canOpen = () => isAvailable() && !!state.compose && !state.compose.plain;

  // ------------------------------------------------------------------ compose button and offer
  function button() {
    let b = $("cQuillPen");
    if (b) return b;
    const head = qs("#composeDialog .compose-head");
    if (!head) return null;
    b = el("button", { id: "cQuillPen", class: "btn subtle sm qp-pen-btn hidden", title: "Edit this message in QuillPen", html: QUILL_ICON + "<span>QuillPen</span>" });
    b.onclick = () => (isOpen() ? closeKeepingText() : openInQuillPen());
    head.insertBefore(b, $("cMinimize"));
    return b;
  }
  function updateButton() {
    const b = button(); if (!b) return;
    b.classList.toggle("hidden", !isAvailable() || (mode() === "off" && !isOpen()));
    b.classList.toggle("active", isOpen());
    b.title = isOpen() ? "Close QuillPen and keep its text in the built-in editor" : "Edit this message in QuillPen";
  }
  function removeOffer() { const o = $("cQuillPenOffer"); if (o) o.remove(); }
  function showOffer() {
    removeOffer();
    const editor = $("cEditor"); if (!editor) return;
    const offer = el("div", { id: "cQuillPenOffer", class: "qp-pen-offer", role: "note" },
      el("span", { html: QUILL_ICON }),
      el("span", { class: "grow", text: "Write this message in QuillPen, the suite's word processor?" }),
      el("button", { class: "btn primary sm", text: "Open in QuillPen", onclick: () => { removeOffer(); openInQuillPen(); } }),
      el("button", { class: "btn subtle sm", text: "Not now", onclick: () => { removeOffer(); editor.focus(); } }));
    editor.parentNode.insertBefore(offer, editor);
  }

  // ------------------------------------------------------------------ opening and closing
  async function open() {
    if (!state.compose) { toast("Start a message first, then open it in QuillPen."); return false; }
    if (frame) { frame.focus(); return true; }
    const found = await detect();
    if (!found.available || !found.base) { toast("QuillPen is not available. Install it, or set it up in Settings ▸ Compose and reply.", { danger: true }); return false; }
    if (!state.compose) return false;
    if (state.compose.plain) { toast("This message is plain text. Switch it to HTML (Format text ▸ HTML) to edit it in QuillPen.", { danger: true }); return false; }
    removeOffer();
    const dlg = $("composeDialog"), editor = $("cEditor");
    originalHtml = editor.innerHTML; mirrorHtml = originalHtml;
    const src = new URL("index.html", found.base);
    src.searchParams.set("embed", "compose"); src.searchParams.set("host", "quillpost"); src.searchParams.set("v", "1");
    origin = src.origin;
    host = el("div", { class: "qp-pen-host" },
      el("div", { class: "qp-pen-loading", role: "status" }, el("span", { class: "spinner" }), el("span", { text: "Opening QuillPen…" })));
    frame = el("iframe", { class: "qp-pen-frame", title: "QuillPen editor", sandbox: SANDBOX, allow: "clipboard-read; clipboard-write", referrerpolicy: "no-referrer" });
    host.prepend(frame);
    editor.parentNode.insertBefore(host, editor);
    dlg.classList.add("qp-pen-open");
    armReadyTimer();
    frame.src = src.toString();
    updateButton();
    return true;
  }
  function armReadyTimer() {
    ready = false;
    clearTimeout(readyTimer);
    if (host) host.classList.remove("ready");
    readyTimer = setTimeout(() => {
      if (!frame || ready) return;
      teardown();
      toast("QuillPen did not start (no answer within 8 seconds). Keep writing in the built-in editor.", { danger: true, timeout: 9000 });
      $("cEditor")?.focus();
    }, READY_TIMEOUT_MS);
  }
  /** Removes the editor. The compose body (#cEditor) keeps whatever was last put into it. */
  function teardown() {
    clearTimeout(readyTimer); readyTimer = null;
    for (const [, p] of pending) { clearTimeout(p.timer); p.resolve(null); }
    pending.clear();
    if (host) host.remove();
    frame = null; host = null; origin = null; ready = false; syncing = null;
    $("composeDialog")?.classList.remove("qp-pen-open");
    updateButton();
  }
  function post(msg) {
    if (!frame || !frame.contentWindow || !origin) return false;
    frame.contentWindow.postMessage({ ...msg, v: 1 }, origin);
    return true;
  }
  function sendLoad() {
    post({
      type: PREFIX + "load", html: mirrorHtml, subject: $("cSubject")?.value || "",
      theme: document.documentElement.dataset.theme === "dark" ? "dark" : "light",
      lang: document.documentElement.lang || navigator.language || "en",
    });
  }

  /** Asks QuillPen for its current content; resolves to { html, text } or null (no answer). */
  function request() {
    if (!frame || !ready) return Promise.resolve(null);
    const id = "q" + nextId++;
    return new Promise((resolve) => {
      const timer = setTimeout(() => { pending.delete(id); resolve(null); }, REQUEST_TIMEOUT_MS);
      pending.set(id, { resolve, timer });
      if (!post({ type: PREFIX + "request", id })) { clearTimeout(timer); pending.delete(id); resolve(null); }
    });
  }
  /** Puts QuillPen's HTML into the compose body (sanitised), so drafts, Send and undo use it. */
  function apply(html) {
    const clean = cleanFragment(html);
    mirrorHtml = clean;
    const editor = $("cEditor");
    if (editor && editor.innerHTML !== clean) { editor.innerHTML = clean; if (state.compose) state.compose.dirty = true; }
  }
  /**
   * Brings the compose body up to date with the open editor. With [ask], an editor that does not
   * answer leads to a question whether to go on with the last copy it reported.
   */
  async function sync({ ask = false } = {}) {
    if (!frame) return true;
    if (!ready) {
      if (!ask) return true;
      return confirm("QuillPen is still starting. Continue with the message as it was before QuillPen opened?");
    }
    // Wait for a copy already on its way, then ask again: the person may have typed since.
    if (syncing) await syncing.catch(() => {});
    const r = await request();
    if (r && typeof r.html === "string") { apply(r.html); return true; }
    if (!ask) return true;
    return confirm("QuillPen did not return the message in time. Continue with the last version it reported?");
  }
  async function closeKeepingText() {
    if (!frame) return;
    if (!(await sync({ ask: true }))) return;
    teardown();
    $("cEditor")?.focus();
  }

  // ------------------------------------------------------------------ messages from QuillPen
  addEventListener("message", (e) => {
    if (!frame || e.source !== frame.contentWindow || e.origin !== origin) return;
    const m = e.data;
    if (!m || typeof m !== "object" || typeof m.type !== "string" || !m.type.startsWith(PREFIX) || m.v !== 1) return;
    switch (m.type.slice(PREFIX.length)) {
      case "ready":
        ready = true; clearTimeout(readyTimer);
        host?.classList.add("ready");
        sendLoad();
        break;
      case "changed":
        if (state.compose) state.compose.dirty = true;
        // Keep a near-live copy in the compose body (drafts, minimise, moving the window).
        if (!syncing) syncing = request().then((r) => { if (r && typeof r.html === "string" && frame) apply(r.html); return r; }).finally(() => { syncing = null; });
        break;
      case "content": {
        const p = pending.get(String(m.id));
        if (p) { pending.delete(String(m.id)); clearTimeout(p.timer); p.resolve({ html: typeof m.html === "string" ? m.html : "", text: typeof m.text === "string" ? m.text : "" }); }
        break;
      }
      case "done":
        if (typeof m.html === "string") apply(m.html);
        teardown();
        $("cEditor")?.focus();
        break;
      case "cancel": {
        const editor = $("cEditor");
        if (editor) editor.innerHTML = originalHtml;
        mirrorHtml = originalHtml;
        teardown();
        editor?.focus();
        break;
      }
    }
  });

  // ------------------------------------------------------------------ sanitising
  const DROP = "script,style,iframe,frame,frameset,object,embed,applet,base,meta,link,title,form,input,button,textarea,select,option,noscript,template,portal,dialog";
  const URL_ATTRS = new Set(["href", "src", "xlink:href", "action", "formaction", "background", "poster", "cite", "longdesc", "lowsrc", "dynsrc"]);
  /** Email-body fragment from QuillPen → safe to put into the compose editor. */
  function cleanFragment(html) {
    const doc = new DOMParser().parseFromString("<!doctype html><html><body>" + String(html || "") + "</body></html>", "text/html");
    doc.body.querySelectorAll(DROP).forEach((n) => n.remove());
    for (const n of doc.body.querySelectorAll("*")) {
      for (const a of Array.from(n.attributes)) {
        const name = a.name.toLowerCase();
        const v = a.value.replace(/[\s\u0000-\u001f]/g, "").toLowerCase();
        if (name.startsWith("on") || name === "id" || name === "srcdoc" || name === "contenteditable") n.removeAttribute(a.name);
        else if (URL_ATTRS.has(name) && (/^(javascript|vbscript|livescript):/.test(v) || (/^data:/.test(v) && !/^data:image\/(png|gif|jpe?g|webp|bmp|svg\+xml);/.test(v)))) n.removeAttribute(a.name);
        else if (name === "style" && /expression\(|javascript:|url\(\s*['"]?\s*javascript:/i.test(a.value)) n.removeAttribute(a.name);
      }
    }
    return doc.body.innerHTML;
  }

  // ------------------------------------------------------------------ compose hooks
  const base = { open: compose.open, close: compose.close, show: compose.show, minimize: compose.minimize, send: compose.send, saveDraft: compose.saveDraft };
  compose.open = async function (...args) {
    const r = await base.open.apply(this, args);
    updateButton();
    const found = await detect();
    if (!state.compose || !found.available) return r;
    if (mode() === "always") openInQuillPen();
    else if (mode() === "ask") showOffer();
    return r;
  };
  compose.close = function (...args) { teardown(); removeOffer(); return base.close.apply(this, args); };
  compose.show = function (...args) {
    // Moving the dialog (dock ⇄ window) reloads the iframe; QuillPen says "ready" again and gets
    // the latest copy of the body back.
    const before = frame && frame.contentWindow;
    const r = base.show.apply(this, args);
    if (frame && frame.contentWindow !== before) armReadyTimer();
    return r;
  };
  compose.minimize = function (...args) { if (frame) sync(); return base.minimize.apply(this, args); };
  compose.send = async function (...args) {
    if (frame && !(await sync({ ask: true }))) return;
    return base.send.apply(this, args);
  };
  compose.saveDraft = async function (...args) {
    if (frame) await sync();
    return base.saveDraft.apply(this, args);
  };

  // ------------------------------------------------------------------ settings ▸ compose
  function renderSettings(hostEl, ui) {
    const plat = info.platform || shellPlatform();
    if (plat === "ios") return;
    const box = el("div", { class: "qp-pen-settings" });
    const tail = hostEl.querySelector(":scope > h4:last-of-type");
    hostEl.insertBefore(box, tail || null);
    const draw = () => {
      box.innerHTML = "";
      const p = info.platform;
      if (p === "ios" || (p === "android" && !info.available)) return;
      box.appendChild(el("h4", { text: "QuillPen" }));
      if (info.available) {
        box.appendChild(ui.row("Edit messages in QuillPen", "Write in an embedded copy of QuillPen, the suite's word processor. Ask offers it in every new message; Always opens it straight away. The QuillPen button in the message window works in every mode.",
          ui.pref("quillpenMode", "ask", MODES, updateButton)));
      }
      if (p === "desktop") {
        const st = info.status || {};
        const input = el("input", { type: "text", value: st.chosen || "", placeholder: st.detected || "Folder that contains QuillPen's index.html", spellcheck: "false", "aria-label": "QuillPen folder" });
        const msg = el("div", { class: "small muted qp-pen-status" });
        const save = async (folder) => {
          try { await api("/api/quillpen/folder", { method: "POST", body: { folder } }); await detect(true); draw(); }
          catch (e) { msg.textContent = e.message; msg.classList.add("danger-text"); }
        };
        const controls = el("div", { class: "qp-pen-field" }, input,
          st.canBrowse ? el("button", { class: "btn sm", text: "Browse…", onclick: async () => { try { const r = await api("/api/quillpen/browse", { method: "POST", body: {} }); if (r && r.folder) { input.value = r.folder; save(r.folder); } } catch (e) { msg.textContent = e.message; } } }) : null,
          el("button", { class: "btn sm", text: "Use folder", onclick: () => save(input.value) }),
          st.chosen ? el("button", { class: "btn subtle sm", text: "Automatic", title: "Forget the chosen folder and look in the standard places", onclick: () => save(null) }) : null);
        msg.textContent = info.available
          ? `Using QuillPen from ${st.folder}.`
          : `QuillPen was not found${st.searched?.length ? " in " + st.searched.join(", ") : ""}. Choose its resources/embed folder (AppImage users: the folder you unpacked).`;
        box.appendChild(ui.row("QuillPen folder", "QuillPen's installer puts its editor in resources/embed next to the app. Leave empty to find it automatically.", el("div", { class: "qp-pen-col" }, controls, msg)));
      } else if (p === "web") {
        const input = el("input", { type: "url", value: prefs.get("quillpenUrl", ""), placeholder: "https://quillpen.example.org/", spellcheck: "false", "aria-label": "QuillPen web address" });
        const msg = el("div", { class: "small muted qp-pen-status" });
        const save = async () => {
          const v = input.value.trim();
          if (v && !normaliseBase(v)) { msg.textContent = "Enter a web address starting with https://"; return; }
          prefs.set("quillpenUrl", v ? normaliseBase(v) : "");
          msg.textContent = "Checking…";
          await detect(true); draw();
        };
        msg.textContent = !info.base ? "Not set. Enter the address of a QuillPen web deployment (for example a self-hosted copy)."
          : info.available ? `QuillPen at ${info.base} answers.` : `QuillPen at ${info.base} could not be reached.`;
        box.appendChild(ui.row("QuillPen web address", "Edit messages in a QuillPen web deployment. Only the message text is exchanged with it.",
          el("div", { class: "qp-pen-col" }, el("div", { class: "qp-pen-field" }, input, el("button", { class: "btn sm", text: "Save", onclick: save })), msg)));
      }
    };
    draw();
    detect(true).then(() => { if (box.isConnected) draw(); });
  }
  const baseRenderCompose = settingsUI.render_compose;
  settingsUI.render_compose = function (hostEl) { baseRenderCompose.call(this, hostEl); renderSettings(hostEl, this); };

  button();
  return { detect, isAvailable, isOpen, canOpen, open, request, sync, mode, cleanFragment };
})();

/** Opens the current compose body in the embedded QuillPen editor (command / Quick Access Bar hook). */
function openInQuillPen() { return quillPen.open(); }
window.quillPen = quillPen;
window.openInQuillPen = openInQuillPen;

// Command registry: "Edit in QuillPen" can go on the Quick Access Bar, a tab or a shortcut.
if (window.Commands) {
  Commands.def({
    id: "compose.openInQuillPen", label: "Edit in QuillPen", icon: "edit", tab: "Message", group: "QuillPen", scope: "compose",
    tip: "Edit this message in the embedded QuillPen word processor (when QuillPen is installed)",
    origin: "QuillPost",
    isEnabled: () => quillPen.isAvailable() && quillPen.canOpen(),
    run: () => openInQuillPen(),
  });
}
