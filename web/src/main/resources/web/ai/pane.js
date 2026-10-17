/* Quillbox AI — interface pieces: icons, the busy chip with Stop, the AI pane (results and
 * chat), dialogs, the streaming preview used before AI text changes a draft, and the
 * "What was sent" disclosure attached to every result.
 *
 * Browser only; uses app.js helpers (el, icon, esc, toast, menu, state). Loaded after app.js.
 */
(function () {
  "use strict";
  const A = window.QbAiApply, S = window.QbAiStore, P = window.QbAiProviders;
  const qbAi = (window.qbAi = window.qbAi || {});

  // ------------------------------------------------------------------ icons
  const ICONS = {
    "ai-summary": '<path d="M4 5h16M4 10h16M4 15h10M4 20h7"/><path d="m17 15 1.2 2.8L21 19l-2.8 1.2L17 23l-1.2-2.8L13 19l2.8-1.2z"/>',
    "ai-thread": '<path d="M4 4h12v8H8l-4 3z"/><path d="M10 15v1h7l3 3v-9h-2"/>',
    "ai-reply": '<path d="M9 14 4 9l5-5"/><path d="M4 9h9a7 7 0 0 1 7 7v3"/><path d="m17 2 .8 1.7 1.7.8-1.7.8L17 7l-.8-1.7-1.7-.8 1.7-.8z"/>',
    "ai-wand": '<path d="m3 21 12-12"/><path d="m15 9 3-3-3-3-3 3z"/><path d="M19 13l.7 1.5 1.5.7-1.5.7L19 17.4l-.7-1.5-1.5-.7 1.5-.7zM8 3l.6 1.3L10 5l-1.4.6L8 7l-.6-1.4L6 5l1.4-.7z"/>',
    "ai-chat": '<path d="M4 5h16v11H9l-5 4z"/><path d="m12 7 .9 2.1L15 10l-2.1.9L12 13l-.9-2.1L9 10l2.1-.9z"/>',
    "ai-key": '<circle cx="8" cy="15" r="4"/><path d="m11 12 9-9M17 6l3 3M15 8l2 2"/>',
    "ai-shield": '<path d="M12 2 4 5v6c0 5 3.4 9.4 8 11 4.6-1.6 8-6 8-11V5z"/><path d="M9 12h6M12 9v6"/>',
    "ai-chip": '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
    "ai-library": '<path d="M4 4h4v16H4zM10 4h4v16h-4z"/><path d="m16 5 3.8-1 3 15.5-3.8 1z"/>',
    "ai-setup": '<path d="M12 3v3M12 18v3M3 12h3M18 12h3"/><circle cx="12" cy="12" r="4"/><path d="m12 10 .6 1.4L14 12l-1.4.6L12 14l-.6-1.4L10 12l1.4-.6z"/>',
    "ai-switch": '<path d="M4 8h13l-3-3M20 16H7l3 3"/>',
  };
  function injectIcons() {
    if (document.getElementById("qb-ai-icons")) return;
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("id", "qb-ai-icons"); svg.setAttribute("width", "0"); svg.setAttribute("height", "0"); svg.setAttribute("aria-hidden", "true");
    svg.style.position = "absolute";
    svg.innerHTML = "<defs>" + Object.entries(ICONS).map(([id, d]) => `<symbol id="i-${id}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${d}</symbol>`).join("") + "</defs>";
    document.body.prepend(svg);
  }

  // ------------------------------------------------------------------ busy chip with Stop
  let current = null;
  /** Starts a request: shows "label … Stop" and returns {signal, label(t), done()}. A new request stops the previous one. */
  function busy(label) {
    if (current) current.ctl.abort();
    const ctl = new AbortController();
    document.getElementById("aiBusy")?.remove();
    const text = el("span", { class: "ai-busy-label", text: label });
    const chip = el("div", { id: "aiBusy", class: "ai-busy", role: "status", "aria-live": "polite" }, el("span", { class: "spinner" }), text,
      el("button", { class: "btn sm", onclick: () => ctl.abort() }, icon("stop", 14), "Stop"));
    // Above an open modal dialog, so Stop stays reachable while a preview streams.
    const modal = Array.from(document.querySelectorAll("dialog[open]")).filter((d) => d.matches(":modal")).pop();
    (modal || document.body).appendChild(chip);
    document.body.classList.add("ai-running");
    const b = { ctl, signal: ctl.signal, label: (t) => { text.textContent = t; }, done: () => { if (current === b) { current = null; chip.remove(); document.body.classList.remove("ai-running"); } } };
    ctl.signal.addEventListener("abort", () => b.done());
    current = b;
    qbAi.paneRefreshHead && qbAi.paneRefreshHead();
    return b;
  }
  function stop() { if (current) { current.ctl.abort(); return true; } return false; }
  function running() { return !!current; }

  // ------------------------------------------------------------------ dialogs
  /**
   * A modal dialog. buttons: [{label, value, primary, danger, run}] — run(close) may keep it open
   * by not calling close. Resolves with the value of the button that closed it (null on Esc/X).
   */
  function dialog({ title, body, buttons = [{ label: "Close", value: null }], wide = true, cls = "", onOpen = null }) {
    return new Promise((resolve) => {
      const dlg = el("dialog", { class: `ai-dialog ${wide ? "wide" : "narrow"} ${cls}` });
      let done = false;
      const close = (v) => { if (done) return; done = true; dlg.close(); dlg.remove(); resolve(v); };
      const foot = el("div", { class: "dlg-foot" });
      for (const b of buttons) {
        const btn = el("button", { class: "btn" + (b.primary ? " primary" : "") + (b.danger ? " danger" : ""), disabled: b.disabled || null }, b.icon ? icon(b.icon) : null, b.label);
        btn.onclick = () => (b.run ? b.run(close, btn) : close(b.value === undefined ? b.label : b.value));
        if (b.ref) b.ref(btn);
        foot.appendChild(btn);
      }
      dlg.append(
        el("div", { class: "dlg-head" }, icon("sparkle", 18), el("h3", { text: title }), el("button", { class: "icon-btn", title: "Close", onclick: () => close(null) }, icon("close"))),
        el("div", { class: "dlg-body ai-dlg-body" }, body), foot);
      dlg.addEventListener("cancel", (e) => { e.preventDefault(); close(null); });
      document.body.appendChild(dlg);
      dlg.showModal();
      if (onOpen) onOpen(close);
    });
  }

  function confirmDialog(title, text, ok = "Continue", extra = null) {
    return dialog({ title, wide: false, body: el("div", {}, el("p", { text }), extra), buttons: [{ label: "Cancel", value: false }, { label: ok, value: true, primary: true }] });
  }

  // ------------------------------------------------------------------ "What was sent"
  const hostOf = (u) => { try { return new URL(u).host; } catch { return u || ""; } };
  /** A <details> block showing the exact messages sent, where to, and how. Never shows keys. */
  function sentDetails(info) {
    if (!info || !info.messages) return null;
    const where = info.route === "device" ? "processed on this device" : `${info.providerName || "AI service"} · ${info.model || ""} · ${hostOf(info.endpoint)}${info.route === "proxy" ? " (relayed by the Quillbox backend)" : ""}`;
    const chars = info.messages.reduce((n, m) => n + String(m.content).length, 0);
    return el("details", { class: "ai-sent" },
      el("summary", { text: `What was sent (${chars.toLocaleString()} characters)` }),
      el("div", { class: "small muted", text: where }),
      ...info.messages.map((m) => el("div", { class: "ai-sent-msg" }, el("div", { class: "ai-sent-role", text: m.role }), el("pre", { text: String(m.content) }))));
  }

  // ------------------------------------------------------------------ the AI pane
  const pane = {
    el: null, log: null, input: null, include: null, history: [],
    build() {
      if (this.el) return;
      this.input = el("textarea", { rows: 3, placeholder: "Ask about this message or folder, or ask for a reply…", "aria-label": "Message to the AI" });
      this.include = el("select", { class: "ai-include", title: "What the AI may see", "aria-label": "Context to send" },
        el("option", { value: "message", text: "Send: the open message" }),
        el("option", { value: "thread", text: "Send: the open conversation" }),
        el("option", { value: "mailbox", text: "Send: this folder's message list (headers)" }),
        el("option", { value: "none", text: "Send: only my question" }));
      const send = () => { const q = this.input.value.trim(); if (!q) return; this.input.value = ""; qbAi.paneAsk && qbAi.paneAsk(q, this.include.value); };
      this.input.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } });
      this.head = el("div", { class: "ai-pane-model small muted" });
      this.log = el("div", { class: "ai-log", "aria-live": "polite" });
      this.el = el("aside", { id: "aiPane", class: "ai-pane hidden", "aria-label": "AI assistant" },
        el("div", { class: "ai-pane-head" }, icon("ai-chat", 18), el("h3", { text: "AI assistant" }), el("span", { class: "grow" }),
          el("button", { class: "icon-btn", title: "Clear the conversation", onclick: () => { this.history = []; this.render(); } }, icon("trash")),
          el("button", { class: "icon-btn", title: "AI & privacy", onclick: () => qbAi.privacy && qbAi.privacy() }, icon("ai-shield")),
          el("button", { class: "icon-btn", title: "Close", onclick: () => this.close() }, icon("close"))),
        this.head, this.log,
        el("div", { class: "ai-compose" }, this.input,
          el("div", { class: "ai-compose-row" }, this.include, el("span", { class: "grow" }),
            el("button", { class: "btn sm ai-stop", title: "Stop the AI", onclick: () => stop() }, icon("stop", 14), "Stop"),
            el("button", { class: "btn primary sm", onclick: send }, icon("send", 14), "Send"))));
      document.body.appendChild(this.el);
      S.onChange(() => this.refreshHead());
    },
    refreshHead() {
      if (!this.head) return;
      const s = S.settings();
      const p = s.active ? P.providerById(s.active) : null;
      this.head.innerHTML = "";
      this.head.append(p ? `${p.name} · ${s.providers[s.active]?.model || ""}` : "No AI provider set up", " ",
        el("button", { class: "link", onclick: () => qbAi.providers && qbAi.providers() }, p ? "Change" : "Set up AI"));
    },
    isOpen() { return !!this.el && !this.el.classList.contains("hidden"); },
    open({ include } = {}) {
      this.build();
      this.el.classList.remove("hidden");
      document.body.classList.add("ai-pane-open");
      if (include) this.include.value = include;
      this.refreshHead();
      this.render();
    },
    close() { if (this.el) { this.el.classList.add("hidden"); document.body.classList.remove("ai-pane-open"); } },
    toggle() { this.isOpen() ? this.close() : this.open(); },
    focusInput() { setTimeout(() => this.input && this.input.focus(), 30); },
    /** Adds a user turn. */
    user(text) { this.open(); this.history.push({ role: "user", text }); this.render(); },
    /** Adds an assistant turn and returns {update, done, fail, html}. */
    result(title) {
      this.open();
      const turn = { role: "assistant", title, text: "", pending: true };
      this.history.push(turn);
      this.render();
      return {
        turn,
        update: (t) => { turn.text = t; this.drawText(turn); },
        done: (t, { actions = [], sent = null, html = null, note = null } = {}) => { Object.assign(turn, { text: t, actions, sent, html, note, pending: false }); this.render(); },
        fail: (msg, sent = null) => { Object.assign(turn, { text: msg, error: true, sent, pending: false }); this.render(); },
        stopped: (sent = null) => { Object.assign(turn, { note: "Stopped.", sent, pending: false }); this.render(); },
      };
    },
    drawText(turn) {
      const node = this.log && this.log.querySelector(`[data-turn="${this.history.indexOf(turn)}"] .ai-msg-text`);
      if (node) { node.innerHTML = A.mdToHtml(turn.text || "…"); this.log.scrollTop = this.log.scrollHeight; }
    },
    render() {
      if (!this.log) return;
      this.log.innerHTML = "";
      if (!this.history.length) {
        const s = S.settings();
        this.log.appendChild(el("p", { class: "small muted", text: s.active ? "Ask about the open message or this folder, or use the AI tab's commands. Nothing is sent until you ask." : "AI is not set up yet. Nothing is sent anywhere until you choose a provider and ask something — free options, including one that runs on this device, are at the top." }));
        if (!s.active) this.log.appendChild(el("button", { class: "btn primary", onclick: () => qbAi.setup && qbAi.setup() }, icon("ai-setup"), "Set up AI…"));
        for (const [q, inc] of [["Summarize this message in two sentences", "message"], ["What do I need to do after reading this?", "message"], ["Which messages in this folder look urgent?", "mailbox"]]) {
          this.log.appendChild(el("button", { class: "ai-suggest", onclick: () => { this.input.value = q; this.include.value = inc; this.input.focus(); } }, q));
        }
      }
      this.history.forEach((t, i) => {
        const body = el("div", { class: "ai-msg-text" + (t.error ? " error" : "") });
        if (t.html) body.appendChild(t.html);
        else body.innerHTML = t.role === "user" ? A.escHtml(t.text) : A.mdToHtml(t.text || (t.pending ? "…" : ""));
        const actions = el("div", { class: "ai-msg-actions" });
        if (t.role === "assistant" && !t.pending) {
          for (const a of t.actions || []) actions.appendChild(el("button", { class: "btn sm", onclick: a.run }, a.icon ? icon(a.icon, 14) : null, a.label));
          if (t.text && !t.error) actions.appendChild(el("button", { class: "icon-btn", title: "Copy", onclick: async () => { try { await navigator.clipboard.writeText(t.text); toast("Copied"); } catch { toast("The clipboard is not available here."); } } }, icon("copy", 14)));
        }
        this.log.appendChild(el("div", { class: `ai-msg ai-${t.role}`, "data-turn": String(i) },
          t.title ? el("div", { class: "ai-msg-title", text: t.title }) : null, body,
          t.note ? el("div", { class: "small muted", text: t.note }) : null,
          actions.childNodes.length ? actions : null, sentDetails(t.sent)));
      });
      this.log.scrollTop = this.log.scrollHeight;
    },
  };

  // ------------------------------------------------------------------ streaming preview
  /**
   * Shows generated text as it streams, lets the user edit it, and offers explicit actions
   * (Insert / Replace / Copy…). generate(onToken, signal) resolves {text, sent}.
   * Resolves when the dialog closes.
   */
  function preview({ title, original = "", originalLabel = "Original", generate, actions, note = "" }) {
    let ctl = null, sent = null;
    const out = el("textarea", { class: "ai-preview-out", rows: 12, spellcheck: "true", "aria-label": "Generated text" });
    out.value = "…";
    const status = el("div", { class: "small muted ai-preview-status" });
    const sentHost = el("div");
    const actionBtns = [];
    const setReady = (ready) => actionBtns.forEach((b) => (b.disabled = !ready));
    const run = async () => {
      ctl = new AbortController();
      setReady(false);
      out.readOnly = true; out.value = ""; status.textContent = "Writing…"; sentHost.innerHTML = "";
      try {
        const r = await generate((full) => { out.value = full; out.scrollTop = out.scrollHeight; }, ctl.signal);
        out.value = A.cleanOutput(r.text);
        sent = r.sent;
        status.textContent = r.truncated ? "The answer was cut short (length limit). Review before using it." : "Review and edit the text, then choose what to do with it.";
        setReady(true);
      } catch (e) {
        status.textContent = e && e.code === "aborted" ? "Stopped. You can still use the partial text." : String((e && e.message) || e);
        if (e && e.sent) sent = e.sent;
        if (out.value.trim()) setReady(true);
      } finally {
        out.readOnly = false; ctl = null;
        if (sent) sentHost.appendChild(sentDetails(sent));
      }
    };
    const body = el("div", { class: "ai-preview" },
      note ? el("p", { class: "small muted", text: note }) : null,
      original ? el("details", { class: "ai-original" }, el("summary", { text: originalLabel }), el("pre", { text: original })) : null,
      out, status, sentHost);
    const buttons = [
      { label: "Stop", icon: "stop", run: () => ctl && ctl.abort() },
      { label: "Try again", icon: "refresh", run: () => { if (!ctl) run(); } },
      { label: "Cancel", value: null },
      // Actions that change the draft run after the dialog has closed: while a modal dialog is
      // open the rest of the page is inert and cannot take the edit. keepOpen actions (Copy) don't close it.
      ...actions.map((a) => ({ label: a.label, primary: a.primary, icon: a.icon, ref: (b) => actionBtns.push(b), run: async (close) => {
        if (ctl) return;
        const text = out.value.trim();
        if (a.keepOpen) { await a.run(text); return; }
        close(a.label);
        await a.run(text);
      } })),
    ];
    const p = dialog({ title, body, buttons });
    setTimeout(run, 0);
    return p.then((v) => { if (ctl) ctl.abort(); return v; });
  }

  Object.assign(qbAi, { injectIcons, busy, stop, running, dialog, confirmDialog, sentDetails, pane, preview, hostOf });
  qbAi.paneRefreshHead = () => pane.refreshHead();
})();
