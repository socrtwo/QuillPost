/* Anne's Post web client — desktop-mail-client layout: mail with built-in junk protection,
 * calendar, people and to-do. Talks to the Ktor backend at the same origin; session tokens live
 * in sessionStorage, remembered accounts in localStorage.
 */
"use strict";

// ============================================================================ utilities
const $ = (id) => document.getElementById(id);
const qs = (sel, root = document) => root.querySelector(sel);
const qsa = (sel, root = document) => Array.from(root.querySelectorAll(sel));

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "class") node.className = v;
    else if (k === "html") node.innerHTML = v;
    else if (k === "text") node.textContent = v;
    else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2), v);
    else if (k === "style" && typeof v === "object") for (const [sk, sv] of Object.entries(v)) { if (sk.startsWith("--")) node.style.setProperty(sk, String(sv)); else node.style[sk] = sv; }
    else if (k === "dataset") Object.assign(node.dataset, v);
    else if (k === "checked" || k === "value" || k === "selected") node[k] = v;
    else node.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
  }
  return node;
}
const icon = (name, size = 16) => {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("width", size); svg.setAttribute("height", size);
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", "#i-" + name);
  svg.appendChild(use);
  return svg;
};
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

function fmtDate(ms, { long = false } = {}) {
  if (!ms) return "";
  const d = new Date(ms);
  if (long) return d.toLocaleString(undefined, { weekday: "short", year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  const diff = (now - d) / 86400000;
  if (diff < 7 && diff > 0 && d.getFullYear() === now.getFullYear()) return d.toLocaleDateString(undefined, { weekday: "short" }) + " " + d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  if (d.getFullYear() === now.getFullYear()) return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
function dayGroup(ms) {
  const d = new Date(ms), now = new Date();
  const start = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.floor((start(now) - start(d)) / 86400000);
  if (days < 0) return "Upcoming";
  if (days === 0) return "Today";
  if (days === 1) return "Yesterday";
  const weekStart = start(now) - ((now.getDay() - (Number(prefs.get("weekStart", 0)) || 0) + 7) % 7) * 86400000;
  if (d.getTime() >= weekStart) return "This week";
  if (d.getTime() >= weekStart - 7 * 86400000) return "Last week";
  if (d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()) return "Earlier this month";
  const lm = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  if (d.getFullYear() === lm.getFullYear() && d.getMonth() === lm.getMonth()) return "Last month";
  return "Older";
}
function fmtSize(b) {
  if (b == null || b < 0) return "";
  if (b >= 1048576) return (b / 1048576).toFixed(1) + " MB";
  if (b >= 1024) return Math.round(b / 1024) + " KB";
  return b + " B";
}
function initials(name, address) {
  const src = (name || "").trim() || (address || "").split("@")[0] || "?";
  const parts = src.replace(/[^\p{L}\p{N} ._-]/gu, "").split(/[\s._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return src.slice(0, 2).toUpperCase();
}
function avatarColor(key) {
  let h = 0;
  for (const c of String(key || "")) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  const palette = ["#6b69d6", "#0f6cbd", "#c239b3", "#ca5010", "#038387", "#8764b8", "#498205", "#c19c00", "#e3008c", "#4f6bed", "#00b294", "#d13438"];
  return palette[h % palette.length];
}
function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }
const select = (options, value, attrs = {}) => { const s = el("select", attrs); for (const [v, label] of options) s.appendChild(el("option", { value: v, text: label })); s.value = value; return s; };

// ------------------------------------------------------------------ native shells
// The Android and iOS apps show this page in a WebView. They identify themselves (a JS bridge
// on Android, a user-agent token on iOS) so that pop-up windows become in-app dialogs,
// printing goes through the platform, and wording fits a phone.
const NATIVE = window.QuillPostAndroid ? "android" : /QuillPostApp\/[\w.]+ \(iOS\)/.test(navigator.userAgent) ? "ios" : null;
const IOS_BRIDGE = (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.quillpost) || null;
function openExternal(url) {
  if (NATIVE === "android") { try { window.QuillPostAndroid.openExternal(url); return; } catch {} }
  if (NATIVE === "ios" && IOS_BRIDGE) { try { IOS_BRIDGE.postMessage({ type: "open", url }); return; } catch {} }
  // Not window.open(url, "_blank", "noopener"): that always returns null, which sent this page
  // away to the link as well.
  const w = window.open(url, "_blank");
  if (w) { try { w.opener = null; } catch {} } else location.href = url;
}
/** Shows a document in a new browser window, or in an in-app dialog where pop-ups are unavailable. */
function popup(title, { html, text, node }) {
  if (!NATIVE && !node) {
    const w = window.open("", "_blank");
    if (w) { w.document.write(html || `<title>${esc(title)}</title><pre style="white-space:pre-wrap;font-family:monospace">${esc(text || "")}</pre>`); w.document.close(); return; }
  }
  $("popupTitle").textContent = title;
  const body = $("popupBody"); body.innerHTML = "";
  if (node) body.appendChild(node);
  else if (html) { const f = el("iframe", { class: "body-frame popup-frame", sandbox: "allow-same-origin", title }); body.appendChild(f); f.srcdoc = html; f.onload = () => { try { f.style.height = Math.min(Math.max(f.contentDocument.documentElement.scrollHeight + 24, 200), 4000) + "px"; } catch {} }; }
  else body.appendChild(el("pre", { class: "body-text mono", text: text || "" }));
  $("popupDialog").showModal();
}
function nativePrint(title) {
  if (NATIVE === "android") { try { window.QuillPostAndroid.print(title || "Anne's Post"); return true; } catch {} }
  if (NATIVE === "ios" && IOS_BRIDGE) { try { IOS_BRIDGE.postMessage({ type: "print", title: title || "Anne's Post" }); return true; } catch {} }
  return false;
}
/**
 * Downloads a URL (an API file). Every download endpoint answers with Content-Disposition:
 * attachment, so in the phone apps navigating to it hands the file to the native download hook
 * (Android DownloadListener → Downloads/QuillPost, iOS WKDownload → share sheet) and the page stays.
 */
function download(url) {
  if (NATIVE) { location.href = url; return; }
  const a = el("a", { href: url, download: "" }); document.body.appendChild(a); a.click(); a.remove();
}
/** Lets the user pick files; resolves with an array of File objects. */
function pickFiles({ accept = "", multiple = false } = {}) {
  return new Promise((resolve) => {
    const input = el("input", { type: "file", accept, class: "hidden" });
    if (multiple) input.multiple = true;
    input.onchange = () => { resolve(Array.from(input.files || [])); input.remove(); };
    document.body.appendChild(input); input.click();
  });
}
function readFileText(f) { return new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result)); r.onerror = rej; r.readAsText(f); }); }
function readFileDataUrl(f) { return new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result)); r.onerror = rej; r.readAsDataURL(f); }); }
/** "Jane <jane@x.com>, bob@y.org; …" → address strings as typed. */
function splitAddresses(s) {
  const out = []; let cur = "", q = false, angle = 0;
  for (const ch of String(s || "")) {
    if (ch === '"') q = !q;
    if (ch === "<") angle++; if (ch === ">") angle = Math.max(0, angle - 1);
    if ((ch === "," || ch === ";") && !q && !angle) { if (cur.trim()) out.push(cur.trim()); cur = ""; } else cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}
/** The bare email address inside "Name <email>". */
function emailOf(a) { const m = String(a || "").match(/<([^<>]+)>/); return (m ? m[1] : String(a || "")).trim(); }
function domainOf(addr) { const i = (addr || "").lastIndexOf("@"); return i >= 0 ? addr.slice(i + 1).toLowerCase() : ""; }
const isEmail = (a) => /^[^@\s<>]+@[^@\s<>]+\.[^@\s<>]+$/.test(emailOf(a));

// ------------------------------------------------------------------ toasts
/**
 * The toast host lives in the top-most modal dialog while one is open: anything outside a modal
 * dialog is drawn under it and cannot be clicked (Undo, Dismiss), so it moves in and back out.
 */
let toastHostEl = null;
/** Modal dialogs in the order they were opened: the last open one is drawn on top. */
const modalStack = [];
/** The modal dialog on top, or null (DOM order is not drawing order: a dialog opened later is on top). */
function topModal() {
  for (let i = modalStack.length - 1; i >= 0; i--) { const d = modalStack[i]; if (d.isConnected && d.open && d.matches(":modal")) return d; }
  return [...document.querySelectorAll("dialog[open]")].filter((d) => d.matches(":modal")).pop() || null;
}
function toastHost() {
  const host = toastHostEl || (toastHostEl = $("toasts"));
  const home = topModal() || document.body;
  if (host && host.parentNode !== home) home.appendChild(host);
  return host;
}
if (typeof HTMLDialogElement !== "undefined" && !HTMLDialogElement.prototype.showModal.__qbToasts) {
  const showModal = HTMLDialogElement.prototype.showModal;
  HTMLDialogElement.prototype.showModal = function (...a) {
    const r = showModal.apply(this, a);
    const i = modalStack.indexOf(this); if (i >= 0) modalStack.splice(i, 1);
    modalStack.push(this);
    if (toastHostEl && toastHostEl.childNodes.length) toastHost();
    return r;
  };
  HTMLDialogElement.prototype.showModal.__qbToasts = true;
  document.addEventListener("close", (e) => { const i = modalStack.indexOf(e.target); if (i >= 0) modalStack.splice(i, 1); setTimeout(toastHost, 0); }, true);
}
function toast(message, { action, onAction, danger = false, timeout = 6000 } = {}) {
  const host = toastHost();
  const t = el("div", { class: "toast" + (danger ? " danger" : "") }, el("span", { text: message }));
  if (action) t.appendChild(el("button", { text: action, onclick: () => { onAction && onAction(); t.remove(); } }));
  t.appendChild(el("button", { html: "&#x2715;", title: "Dismiss", onclick: () => t.remove(), style: { color: "inherit", opacity: ".7" } }));
  host.appendChild(t);
  if (timeout) setTimeout(() => t.remove(), timeout);
  return t;
}

// ============================================================================ state
const prefs = {
  get(k, d) { try { const v = localStorage.getItem("qb." + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem("qb." + k, JSON.stringify(v)); } catch {} },
};
const state = {
  token: null, account: null, settings: null, capabilities: null, module: "mail",
  folders: [], folder: "INBOX", folderRole: "inbox",
  messages: [], total: 0, pending: 0, version: 0, offset: 0, pageSize: 50,
  selected: new Set(), open: null, openDetail: null, thread: [], filter: "all", search: null, searchOpts: null,
  focus: "focused", expanded: new Set(), outbox: [],
  loading: false, pollTimer: null, lastActivity: Date.now(), imagesOnce: false, compose: null,
  lastFocusedKey: null, ollamaModels: [], lastInboxUids: null,
};
/** Selection / identity key of a message (UIDs are only unique within one folder). */
const mkey = (m) => (m.folder || state.folder) + "\u0001" + m.uid;

// ============================================================================ API
async function api(path, { method = "GET", body, query, raw = false, token, rawBody, contentType } = {}) {
  const url = new URL(path, location.origin);
  if (query) for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, v);
  const headers = { Accept: "application/json" };
  const tk = token || state.token;
  if (tk) headers.Authorization = "Bearer " + tk;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (rawBody !== undefined) headers["Content-Type"] = contentType || "application/octet-stream";
  const res = await fetch(url, { method, headers, body: rawBody !== undefined ? rawBody : body !== undefined ? JSON.stringify(body) : undefined });
  if (res.status === 401 && !token) { signOut(true); throw new Error("Session expired — please sign in again"); }
  if (raw) return res;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (!res.ok) throw new Error((data && data.error) || res.statusText || "Request failed");
  return data;
}
function apiUrl(path, query = {}) {
  const u = new URL(path, location.origin);
  u.searchParams.set("token", state.token);
  for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== null && v !== "") u.searchParams.set(k, v);
  return u.toString();
}
function attachmentUrl(folder, uid, { index, cid, inline } = {}) {
  return apiUrl("/api/attachment", { folder, uid, index, cid, inline: inline ? "true" : "" });
}

// ============================================================================ theme & prefs
function applyTheme() {
  const theme = prefs.get("theme", "system");
  const dark = theme === "dark" || (theme === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  const root = document.documentElement;
  root.dataset.theme = dark ? "dark" : "light";
  root.dataset.accent = prefs.get("accent", "blue");
  root.dataset.density = prefs.get("density", "comfortable");
  root.dataset.preview = prefs.get("showPreview", true) ? "on" : "off";
  root.dataset.avatars = prefs.get("showAvatars", true) ? "on" : "off";
  const t = qs("#btnTheme use"); if (t) t.setAttribute("href", dark ? "#i-sun" : "#i-moon");
  const ws = $("workspace");
  if (ws) {
    ws.classList.remove("pane-bottom", "pane-off");
    const pane = prefs.get("pane", "right");
    if (pane === "bottom") ws.classList.add("pane-bottom");
    if (pane === "off") ws.classList.add("pane-off");
    ws.classList.toggle("nav-collapsed", !!prefs.get("navCollapsed", false) && innerWidth > 700);
  }
  const z = prefs.get("readZoom", 100);
  document.documentElement.style.setProperty("--read-zoom", String(z / 100));
  const zl = $("vwZoomLevel"); if (zl) zl.textContent = z + "%";
}
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);

// ============================================================================ setup wizard
const setup = {
  discovered: null, provider: null, catalogue: null, regionFilter: "all", rememberTouched: false, canned: false,
  /** Shows the wizard; [adding] keeps the signed-in accounts and offers Cancel, [email] pre-fills a re-sign-in. */
  show({ adding = false, email = "", name = "" } = {}) {
    $("app").classList.add("hidden"); $("setup").classList.remove("hidden");
    $("suOrigin").textContent = NATIVE === "android" ? "the Anne's Post app on this phone" : location.host;
    if (NATIVE === "android" && !this.rememberTouched) $("suRemember").checked = true;
    $("suCancelRow").classList.toggle("hidden", !(adding && state.token));
    if (adding || email) { $("suEmail").value = email || ""; $("suName").value = name || ""; $("suPassword").value = ""; }
    this.step(0);
    this.loadProviders();
  },
  step(n) {
    for (const i of [0, 1, 2]) { $("setupStep" + i).classList.toggle("hidden", n !== i); $("step" + i).classList.toggle("done", i <= n); }
    // Don't steal focus if the user (or a password manager) has already moved into a field.
    if (n === 1) setTimeout(() => { if (!document.activeElement || !document.activeElement.closest("#setupStep1 input")) ($("suName").value ? $("suEmail") : $("suName")).focus(); }, 50);
  },
  error(msg, second = false) {
    const box = $(second ? "suError2" : "suError");
    box.classList.toggle("hidden", !msg); box.textContent = msg || "";
  },
  // ---- step 0: provider picker
  async loadProviders() {
    if (!this.catalogue) {
      try { this.catalogue = await api("/api/providers"); }
      catch (e) { this.catalogue = { regions: [], providers: [] }; toast("Could not load the provider list: " + e.message, { danger: true }); }
      this.renderRegions();
    }
    this.renderProviders();
  },
  renderRegions() {
    const host = $("suRegionTabs"); host.innerHTML = "";
    const present = new Set(this.catalogue.providers.filter((p) => p.domains.length).map((p) => p.region));
    const tabs = [{ id: "all", label: "All" }, ...this.catalogue.regions.filter((r) => present.has(r.id) && r.id !== "popular")];
    for (const r of tabs) host.appendChild(el("button", { class: r.id === this.regionFilter ? "active" : "", onclick: () => { this.regionFilter = r.id; this.renderRegions(); this.renderProviders(); } }, r.label));
  },
  providerMark(p) { return el("span", { class: "provider-mark", style: { background: avatarColor(p.id) }, text: initials(p.label.replace(/\(.*?\)/g, "")) }); },
  tile(p) {
    const domains = p.domains.slice(0, 3).join(", ") + (p.domains.length > 3 ? ", …" : "");
    return el("button", { class: "provider-tile" + (p.unsupported ? " unsupported" : ""), type: "button", title: p.status || domains, onclick: () => this.choose(p) },
      this.providerMark(p), el("span", { class: "txt" }, el("b", { text: p.label }), el("span", { text: p.status || domains })));
  },
  otherTile() {
    return el("button", { class: "provider-tile other", type: "button", onclick: () => this.chooseOther() },
      el("span", { class: "provider-mark", style: { background: "var(--text-4)" } }, icon("mail", 18)), el("span", { class: "txt" }, el("b", { text: "Other" }), el("span", { text: "Any IMAP or POP3 mailbox — looked up from your address" })));
  },
  renderProviders() {
    const host = $("suProviders"); host.innerHTML = "";
    const q = $("suProviderSearch").value.trim().toLowerCase();
    const all = this.catalogue.providers.filter((p) => p.domains.length);
    const byLabel = (a, b) => a.label.localeCompare(b.label);
    if (q) {
      const hits = all.filter((p) => p.label.toLowerCase().includes(q) || p.id.includes(q) || p.domains.some((d) => d.includes(q)) || p.regionLabel.toLowerCase().includes(q))
        .sort((a, b) => (b.popular > 0) - (a.popular > 0) || (a.popular || 99) - (b.popular || 99) || byLabel(a, b));
      if (!hits.length) host.appendChild(el("div", { class: "provider-empty", text: `No provider matches "${q}". Choose Other and Anne's Post will look the settings up from your address.` }));
      hits.forEach((p) => host.appendChild(this.tile(p)));
      host.appendChild(this.otherTile());
      return;
    }
    if (this.regionFilter === "all") {
      host.appendChild(el("div", { class: "section", text: "Most popular" }));
      all.filter((p) => p.popular > 0).sort((a, b) => a.popular - b.popular).forEach((p) => host.appendChild(this.tile(p)));
      for (const r of this.catalogue.regions) {
        if (r.id === "popular") continue;
        const items = all.filter((p) => p.region === r.id).sort(byLabel);
        if (!items.length) continue;
        host.appendChild(el("div", { class: "section", text: r.label }));
        items.forEach((p) => host.appendChild(this.tile(p)));
      }
    } else {
      const region = this.catalogue.regions.find((r) => r.id === this.regionFilter);
      host.appendChild(el("div", { class: "section", text: region ? region.label : "" }));
      all.filter((p) => p.region === this.regionFilter).sort(byLabel).forEach((p) => host.appendChild(this.tile(p)));
    }
    host.appendChild(el("div", { class: "section", text: "Not listed" }));
    host.appendChild(this.otherTile());
  },
  choose(p) {
    this.provider = p;
    $("suChosen").classList.remove("hidden");
    const mark = $("suChosenMark"); mark.style.background = avatarColor(p.id); mark.textContent = initials(p.label.replace(/\(.*?\)/g, ""));
    $("suChosenLabel").textContent = p.label;
    $("suChosenDomains").textContent = p.domains.slice(0, 4).join(", ") + (p.domains.length > 4 ? ", …" : "");
    $("suLead1").textContent = `Enter your ${p.label.replace(/ \(.*?\)/g, "")} address and password.`;
    $("suEmail").placeholder = "you@" + p.domains[0];
    const notes = [];
    if (p.unsupported) notes.push(`<div class="error-box" style="margin:0 0 8px">${esc(p.notes[0] || "This provider offers no IMAP/SMTP access.")}</div>`);
    else {
      if (p.status) notes.push(`<div><b>${esc(p.status)}</b></div>`);
      notes.push(`<div class="small muted">IMAP ${esc(p.incomingHost)}:${p.incomingPort} · SMTP ${esc(p.smtpHost)}:${p.smtpPort}</div>`);
      if (p.oauthOnly) notes.push(`<div class="error-box" style="margin:8px 0 0">This provider may no longer accept password sign-in for IMAP. Try an app password; if it is rejected, the provider requires OAuth, which Anne's Post does not support yet.</div>`);
      if (p.notes.length) notes.push(`<ul>${p.notes.map((n) => `<li>${esc(n)}</li>`).join("")}</ul>`);
      if (p.appPasswordUrl) notes.push(`<div style="margin-top:6px"><a href="${esc(p.appPasswordUrl)}" target="_blank" rel="noopener">Create an app password ↗</a></div>`);
    }
    const box = $("suProviderNotes"); box.innerHTML = notes.join(""); box.classList.remove("hidden");
    $("suPasswordHint").textContent = p.appPasswordUrl ? `${p.label.replace(/ \(.*?\)/g, "")} requires an app password rather than your normal password.` : (p.usernameLocalPart ? "Sign in with the part of your address before the @." : "Your mailbox password.");
    this.error(null);
    this.step(1);
  },
  chooseOther() {
    this.provider = null;
    $("suChosen").classList.add("hidden"); $("suProviderNotes").classList.add("hidden");
    $("suLead1").textContent = "Enter your address and password. Anne's Post finds the server settings for you, the same way Outlook does.";
    $("suEmail").placeholder = "you@example.com";
    $("suPasswordHint").innerHTML = "Gmail, Yahoo, iCloud, AOL and Fastmail require an <em>app password</em> rather than your normal password.";
    this.error(null);
    this.step(1);
  },
  // ---- step 1: credentials
  async continueFromStep1() {
    const email = $("suEmail").value.trim().toLowerCase();
    const pw = $("suPassword").value;
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return this.error("Enter a valid email address.");
    if (!pw) return this.error("Enter your password (or an app password).");
    if (this.provider && this.provider.unsupported) return this.error(`${this.provider.label} cannot be used with a mail client: ${this.provider.notes[0] || "no IMAP/SMTP access."}`);
    this.error(null);
    const btn = $("suContinue"); btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> ' + (this.provider ? "Preparing settings…" : "Looking up your provider…");
    try {
      const d = this.provider
        ? await api("/api/provider", { query: { id: this.provider.id, email } })
        : await api("/api/autodiscover", { query: { email } });
      this.discovered = d;
      this.fill(d, email);
      // "Canned" = settings that came from the built-in table (a chosen tile, or a domain the
      // lookup recognised). If they fail, the wizard explains and opens the manual form.
      this.canned = !!(this.provider || d.providerId);
      $("suAdvanced").querySelector("summary").textContent = "Server settings";
      $("suStatus").textContent = "";
      const offDomain = this.provider && !this.provider.domains.includes(email.split("@")[1]) && !["google-workspace", "microsoft365"].includes(this.provider.id);
      if (offDomain) this.error(`${email.split("@")[1]} is not one of ${this.provider.label}'s usual domains. The settings below are ${this.provider.label}'s — check them, or go back and choose Other to look the domain up.`, true);
      this.step(2);
      if (this.canned && d.found && !offDomain) this.verify({ auto: true });
    } catch (e) {
      this.error("Could not look up settings: " + e.message);
    } finally { btn.disabled = false; btn.textContent = "Continue"; }
  },
  fill(d, email) {
    $("suProtocol").value = d.protocol || "IMAP";
    $("suIncomingSecurity").value = d.incomingSecurity || "SSL_TLS";
    $("suIncomingHost").value = d.incomingHost || "";
    $("suIncomingPort").value = d.incomingPort || 993;
    $("suSmtpHost").value = d.smtpHost || "";
    $("suSmtpPort").value = d.smtpPort || 587;
    $("suSmtpSecurity").value = d.smtpSecurity || "STARTTLS";
    $("suUsername").value = d.username || email;
    const box = $("suProvider");
    box.classList.remove("hidden");
    const notes = (d.notes || []).map((n) => `<li>${esc(n)}</li>`).join("");
    if (d.found) {
      $("suLead").textContent = "Check the details below, then sign in.";
      box.innerHTML = `<b>${esc(d.provider || d.domain)}</b> — settings from ${esc(d.source)}.<div class="small muted">IMAP ${esc(d.incomingHost)}:${d.incomingPort} · SMTP ${esc(d.smtpHost)}:${d.smtpPort}</div>` +
        (d.oauthOnly ? `<div class="error-box" style="margin:8px 0 0">This provider may no longer accept password sign-in for IMAP. Try an app password; if it is rejected, the provider requires OAuth, which Anne's Post does not support yet.</div>` : "") +
        (notes ? `<ul>${notes}</ul>` : "") +
        (d.appPasswordUrl ? `<div style="margin-top:6px"><a href="${esc(d.appPasswordUrl)}" target="_blank" rel="noopener">Create an app password ↗</a></div>` : "");
      $("suAdvanced").open = false;
    } else {
      $("suLead").textContent = "We could not find settings automatically. Enter them below.";
      box.innerHTML = `<b>No automatic settings for ${esc(d.domain)}</b>${notes ? `<ul>${notes}</ul>` : ""}`;
      $("suAdvanced").open = true;
    }
  },
  account() {
    const email = $("suEmail").value.trim().toLowerCase();
    return {
      displayName: $("suName").value.trim() || email,
      email,
      incomingHost: $("suIncomingHost").value.trim(),
      incomingPort: parseInt($("suIncomingPort").value, 10) || 993,
      protocol: $("suProtocol").value,
      incomingSecurity: $("suIncomingSecurity").value,
      smtpHost: $("suSmtpHost").value.trim(),
      smtpPort: parseInt($("suSmtpPort").value, 10) || 587,
      smtpSecurity: $("suSmtpSecurity").value,
      username: $("suUsername").value.trim() || email,
      password: $("suPassword").value,
    };
  },
  // When a pre-configured provider fails, say so and open the manual server settings.
  cannedFailed(reason) {
    const name = this.provider ? this.provider.label.replace(/ \(.*?\)/g, "") : (this.discovered && this.discovered.provider) || "the provider";
    const auth = /password|credential|authenticat|login|sign-in/i.test(reason);
    this.error(`The pre-configured settings for ${name} did not work: ${reason} ` +
      (auth ? "Check the password (most providers need an app password) and the user name, or edit the server settings below." : "Check or edit the server settings below, then test the connection again."), true);
    const adv = $("suAdvanced"); adv.open = true;
    adv.querySelector("summary").textContent = "Server settings (manual configuration)";
    setTimeout(() => { $("suError2").scrollIntoView({ behavior: "smooth", block: "nearest" }); $("suIncomingHost").focus({ preventScroll: true }); }, 50);
  },
  async verify({ auto = false } = {}) {
    this.error(null, true);
    const seq = (this.verifySeq = (this.verifySeq || 0) + 1);
    $("suStatus").innerHTML = '<span class="spinner"></span> ' + (auto ? "Checking the pre-configured servers…" : "Testing incoming and outgoing servers…");
    try {
      const r = await api("/api/verify", { method: "POST", body: { account: this.account() } });
      if (seq !== this.verifySeq || $("setupStep2").classList.contains("hidden")) return false;
      $("suStatus").innerHTML = `Incoming: <b>${esc(r.incoming)}</b> · SMTP: <b>${esc(r.smtp)}</b>`;
      if (r.ok) return true;
      const reason = [r.incoming !== "ok" ? `incoming server: ${r.incoming}` : null, r.smtp !== "ok" ? `outgoing server: ${r.smtp}` : null].filter(Boolean).join("; ") + ".";
      if (this.canned) this.cannedFailed(reason); else this.error("One of the servers rejected the connection. Check the settings and password.", true);
    } catch (e) { if (seq !== this.verifySeq || $("setupStep2").classList.contains("hidden")) return false; $("suStatus").textContent = ""; if (this.canned) this.cannedFailed(e.message + "."); else this.error(e.message, true); }
    return false;
  },
  async signIn() {
    this.error(null, true);
    const btn = $("suSignIn"); btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> Signing in…';
    try {
      const account = this.account();
      await session.open(account, $("suRemember").checked);
    } catch (e) {
      if (this.canned) this.cannedFailed(e.message + "."); else this.error(e.message, true);
    } finally { btn.disabled = false; btn.textContent = "Sign in"; }
  },
};

// ============================================================================ accounts & sessions
// Several accounts can be signed in at once; one is active. Remembered accounts (with their
// password) live in localStorage, the others in sessionStorage only; tokens are per tab.
const accounts = {
  read(store, key) { try { return JSON.parse(store.getItem(key) || "[]"); } catch { return []; } },
  migrate() {
    try {
      const single = localStorage.getItem("qb.account") || sessionStorage.getItem("qb.account");
      if (single && !localStorage.getItem("qb.accounts") && !sessionStorage.getItem("qb.saccounts")) {
        const a = JSON.parse(single);
        const remembered = !!localStorage.getItem("qb.account");
        (remembered ? localStorage : sessionStorage).setItem(remembered ? "qb.accounts" : "qb.saccounts", JSON.stringify([{ account: a, remember: remembered }]));
        const tk = sessionStorage.getItem("qb.token");
        if (tk) this.setToken(a.email, tk);
        sessionStorage.setItem("qb.active", a.email);
      }
      localStorage.removeItem("qb.account"); sessionStorage.removeItem("qb.account"); sessionStorage.removeItem("qb.token");
    } catch {}
  },
  all() {
    const seen = new Set(), out = [];
    for (const x of [...this.read(localStorage, "qb.accounts"), ...this.read(sessionStorage, "qb.saccounts")]) {
      const e = (x.account?.email || "").toLowerCase();
      if (e && !seen.has(e)) { seen.add(e); out.push(x); }
    }
    return out;
  },
  get(email) { return this.all().find((x) => x.account.email.toLowerCase() === String(email).toLowerCase()) || null; },
  tokens() { try { return JSON.parse(sessionStorage.getItem("qb.tokens") || "{}"); } catch { return {}; } },
  token(email) { return this.tokens()[String(email).toLowerCase()] || null; },
  setToken(email, token) { const t = this.tokens(); if (token) t[String(email).toLowerCase()] = token; else delete t[String(email).toLowerCase()]; sessionStorage.setItem("qb.tokens", JSON.stringify(t)); },
  active() { return sessionStorage.getItem("qb.active") || localStorage.getItem("qb.lastActive") || this.all()[0]?.account.email || null; },
  setActive(email) { sessionStorage.setItem("qb.active", email); try { localStorage.setItem("qb.lastActive", email); } catch {} },
  save(account, remember) {
    const e = account.email.toLowerCase();
    const strip = (list) => list.filter((x) => x.account.email.toLowerCase() !== e);
    const local = strip(this.read(localStorage, "qb.accounts")), sess = strip(this.read(sessionStorage, "qb.saccounts"));
    if (remember) local.push({ account, remember: true }); else sess.push({ account, remember: false });
    localStorage.setItem("qb.accounts", JSON.stringify(local)); sessionStorage.setItem("qb.saccounts", JSON.stringify(sess));
  },
  remove(email) {
    const e = email.toLowerCase();
    localStorage.setItem("qb.accounts", JSON.stringify(this.read(localStorage, "qb.accounts").filter((x) => x.account.email.toLowerCase() !== e)));
    sessionStorage.setItem("qb.saccounts", JSON.stringify(this.read(sessionStorage, "qb.saccounts").filter((x) => x.account.email.toLowerCase() !== e)));
    this.setToken(email, null);
    if ((this.active() || "").toLowerCase() === e) { sessionStorage.removeItem("qb.active"); localStorage.removeItem("qb.lastActive"); }
  },
  forgetPassword(email) {
    const x = this.get(email); if (!x) return;
    const a = { ...x.account }; delete a.password;
    this.remove(email); this.save(a, false);
  },
};

const session = {
  async open(account, remember) {
    const r = await api("/api/session", { method: "POST", body: { account }, token: "-" });
    accounts.save(account, remember);
    accounts.setToken(account.email, r.token);
    accounts.setActive(account.email);
    state.token = r.token; state.account = account; state.settings = r.settings; state.capabilities = r.capabilities;
    await app.start();
  },
  /** Activates [email]: reuses its token, or signs in again with the stored password. */
  async activate(email) {
    const x = accounts.get(email);
    const tk = accounts.token(email);
    if (tk) {
      try {
        const r = await api("/api/session", { token: tk });
        accounts.setActive(email);
        state.token = tk; state.account = x ? x.account : { email: r.email, displayName: r.displayName, protocol: r.protocol };
        state.settings = r.settings; state.capabilities = r.capabilities;
        await app.start();
        return true;
      } catch { accounts.setToken(email, null); }
    }
    if (x && x.account.password) { await this.open(x.account, !!x.remember); return true; }
    return false;
  },
  async resume() {
    accounts.migrate();
    const email = accounts.active();
    if (!email) return false;
    try { if (await this.activate(email)) return true; } catch {}
    const x = accounts.get(email);
    if (x) { $("suEmail").value = x.account.email || ""; $("suName").value = x.account.displayName || ""; }
    return false;
  },
  async switchTo(email) {
    if (state.compose && !confirm("Switch accounts? The message you are writing will be saved as a draft first.")) return;
    if (state.compose) { await compose.saveDraft(true).catch(() => {}); compose.close(true); }
    try {
      if (!(await this.activate(email))) {
        const x = accounts.get(email);
        setup.show({ adding: true, email: x?.account.email, name: x?.account.displayName });
        toast("Enter the password for " + email + " to switch to it.");
      }
    } catch (e) { toast("Could not switch account: " + e.message, { danger: true }); }
  },
};

function signOut(expired = false) {
  const token = state.token, email = state.account?.email;
  state.token = null; state.messages = []; state.selected.clear(); state.open = null;
  clearTimeout(state.pollTimer); reminders.stop();
  if (email) {
    accounts.setToken(email, null);
    if (!expired) accounts.remove(email);
  }
  if (token && !expired) fetch("/api/session", { method: "DELETE", headers: { Authorization: "Bearer " + token } }).catch(() => {});
  for (const d of qsa("dialog[open]")) d.close();
  if (state.compose) compose.close(true);
  const next = accounts.all().find((x) => x.account.email !== email);
  if (!expired && next) { session.switchTo(next.account.email); return; }
  setup.show(expired && email ? { email, name: state.account?.displayName } : {});
  if (expired) toast("Your session expired. Please sign in again.", { danger: true });
}

// ============================================================================ app shell
const app = {
  async start() {
    $("setup").classList.add("hidden"); $("app").classList.remove("hidden");
    applyTheme();
    const email = state.account.email;
    const av = $("accountAvatar"); av.textContent = initials(state.account.displayName, email); av.style.background = avatarColor(email); av.title = `${state.account.displayName || ""} ${email} — accounts`.trim();
    state.lastActivity = Date.now(); state.lastInboxUids = null;
    state.folder = "INBOX"; state.search = null; state.selected.clear(); state.focus = "focused";
    reading.close();
    renderJunkStatus();
    pim.data = { calendars: [], events: [], contacts: [], contactLists: [], taskLists: [], tasks: [] }; pim.loaded = false;
    await folders.load();
    await list.load("INBOX");
    pim.load().then(() => { mailplus.renderMyDay(); reminders.start(); });
    mailplus.loadOutbox(); mailplus.loadQuota(); mailplus.refreshOtherAccounts();
    if (!state.capabilities.folders) toast("POP3 account: junk mail is tracked locally because POP3 servers have no folders.", { timeout: 9000 });
    shell.setModule(prefs.get("startModule", "mail") === "last" ? prefs.get("lastModule", "mail") : prefs.get("startModule", "mail"), { silent: true });
    mailplus.askNotifications();
  },
};

/** Switches between Mail, Calendar, People and To Do. */
const shell = {
  modules: { mail: "modMail", calendar: "modCalendar", people: "modPeople", todo: "modTodo" },
  placeholders: { mail: "Search mail (press /)", calendar: "Search calendar", people: "Search people", todo: "Search tasks" },
  setModule(name, { silent = false } = {}) {
    if (!this.modules[name]) name = "mail";
    state.module = name; prefs.set("lastModule", name);
    for (const [k, id] of Object.entries(this.modules)) $(id).classList.toggle("hidden", k !== name);
    qsa("[data-module]").forEach((b) => b.classList.toggle("active", b.dataset.module === name));
    $("searchInput").placeholder = this.placeholders[name];
    $("searchInput").value = name === "mail" && state.search ? state.search : "";
    $("searchClear").classList.toggle("hidden", !$("searchInput").value);
    $("searchOptions").classList.toggle("hidden", name !== "mail");
    $("navToggle").title = name === "mail" ? "Show or hide the folder pane" : "Show or hide the navigation pane";
    if (name === "calendar") calendarUI.show();
    if (name === "people") peopleUI.show();
    if (name === "todo") todoUI.show();
    if (!silent && name === "mail") list.render();
  },
};

function renderJunkStatus() {
  const s = state.settings?.spam || {};
  const dot = $("junkDot");
  dot.className = "dot " + (s.enabled ? (state.pending > 0 ? "busy" : "") : "off");
  $("junkStatus").textContent = !s.enabled ? "Junk protection off" : state.pending > 0 ? `Scanning ${state.pending} message${state.pending === 1 ? "" : "s"}…` : (s.autoMoveToJunk ? "Junk protection on · auto-filing" : "Junk protection on · flag only");
}
async function refreshBayesStatus() {
  try {
    const st = await api("/api/spam/status");
    $("bayesStatus").textContent = `Learned from ${st.bayes.spam} junk / ${st.bayes.ham} good · ${st.autoMoved} auto-filed`;
  } catch {}
}
async function settingsSave(next) {
  state.settings = await api("/api/settings", { method: "PUT", body: next });
  renderJunkStatus();
  return state.settings;
}

// ============================================================================ folders
const FOLDER_ICONS = { inbox: "inbox", junk: "junk", sent: "send", drafts: "drafts", trash: "trash", archive: "archive", snoozed: "clock", scheduled: "send-later", other: "folder" };
const SCHEDULED = "__scheduled__";
const folders = {
  async load() {
    try {
      state.folders = await api("/api/folders");
      this.render();
      const inbox = this.byRole("inbox");
      $("railMailCount").textContent = inbox && inbox.unread > 0 ? String(inbox.unread) : "";
      document.title = (inbox && inbox.unread > 0 ? `(${inbox.unread}) ` : "") + (this.byPath(state.folder)?.name || "Mail") + " — Anne's Post";
    } catch (e) { toast("Could not load folders: " + e.message, { danger: true }); }
  },
  collapsed() { return new Set(prefs.get("collapsedTree", [])); },
  toggleCollapsed(id) { const c = this.collapsed(); if (c.has(id)) c.delete(id); else c.add(id); prefs.set("collapsedTree", [...c]); this.render(); },
  favorites() {
    const fav = state.settings?.favorites || [];
    if (fav.length) return fav.map((p) => this.byPath(p)).filter(Boolean);
    return ["inbox", "sent", "drafts"].map((r) => this.byRole(r)).filter(Boolean);
  },
  item(f, { inFavorites = false } = {}) {
    const isActive = f.path === state.folder && !state.search;
    const li = el("li", { class: (isActive ? "active " : "") + f.role, title: f.path === SCHEDULED ? "Messages waiting to be sent" : f.path, dataset: { path: f.path }, style: { "--depth": inFavorites ? 0 : (f.depth || 0) } },
      icon(FOLDER_ICONS[f.role] || "folder", 18), el("span", { class: "name", text: f.name }),
      f.unread > 0 ? el("span", { class: "count", text: String(f.unread) }) : (["junk", "drafts", "scheduled"].includes(f.role) && f.total > 0 ? el("span", { class: "count muted", text: String(f.total) }) : null));
    li.onclick = () => { list.load(f.path); $("workspace").classList.remove("nav-open"); shell.setModule("mail", { silent: true }); };
    if (f.path !== SCHEDULED) {
      li.oncontextmenu = (e) => { e.preventDefault(); mailplus.folderMenu(e, f); };
      li.addEventListener("dragover", (e) => { if (e.dataTransfer.types.includes("application/x-quillpost")) { e.preventDefault(); li.classList.add("drop-target"); } });
      li.addEventListener("dragleave", () => li.classList.remove("drop-target"));
      li.addEventListener("drop", (e) => {
        e.preventDefault(); li.classList.remove("drop-target");
        try {
          const items = JSON.parse(e.dataTransfer.getData("application/x-quillpost"));
          if (!items.length || items[0].folder === f.path) return;
          if (f.role === "junk") actions.junk(items, true); else if (e.ctrlKey || e.altKey) actions.copy(items, f.path); else actions.move(items, f.path);
        } catch {}
      });
    }
    return li;
  },
  render() {
    const host = $("folderTree"); host.innerHTML = "";
    const collapsed = this.collapsed();
    const section = (id, label, children, extra = {}) => {
      const head = el("div", { class: "tree-head" + (collapsed.has(id) ? " collapsed" : "") + (extra.inactive ? " inactive" : ""), title: extra.title || label },
        icon("chev-down", 14), el("span", { class: "name", text: label }), extra.count ? el("span", { class: "count", text: String(extra.count) }) : null);
      head.querySelector("svg").classList.add("chev");
      head.onclick = extra.onclick || (() => this.toggleCollapsed(id));
      if (extra.oncontextmenu) head.oncontextmenu = extra.oncontextmenu;
      host.appendChild(head);
      if (children) { const sub = el("div", { class: "tree-sub" + (collapsed.has(id) ? " collapsed" : "") }, children); host.appendChild(sub); }
    };
    const fav = el("ul", { class: "folder-list" });
    this.favorites().forEach((f) => fav.appendChild(this.item(f, { inFavorites: true })));
    section("favorites", "Favorites", fav);
    const ul = el("ul", { class: "folder-list", id: "folderList" });
    const special = state.folders.filter((f) => f.role !== "other");
    const others = state.folders.filter((f) => f.role === "other");
    special.forEach((f) => ul.appendChild(this.item(f)));
    if (state.outbox.length) ul.appendChild(this.item({ path: SCHEDULED, name: "Scheduled", role: "scheduled", unread: 0, total: state.outbox.length, depth: 0 }));
    others.forEach((f) => ul.appendChild(this.item(f)));
    if (state.capabilities?.folders) {
      const add = el("li", { class: "add-folder" }, icon("folder-plus", 18), el("span", { class: "name", text: "Create new folder" }));
      add.onclick = () => mailplus.createFolder(null);
      ul.appendChild(add);
    }
    const me = state.account?.email || "";
    section("acct:" + me, me, ul, { title: me, oncontextmenu: (e) => { e.preventDefault(); mailplus.accountMenu(e); } });
    for (const x of accounts.all()) {
      if (x.account.email === me) continue;
      const n = mailplus.otherUnread[x.account.email.toLowerCase()];
      section("acct:" + x.account.email, x.account.email, null, { inactive: true, count: n, title: "Switch to " + x.account.email, onclick: () => session.switchTo(x.account.email) });
    }
    host.appendChild(el("button", { class: "add-account", onclick: () => setup.show({ adding: true }) }, icon("person-add"), "Add account"));
  },
  byPath(p) { return p === SCHEDULED ? { path: SCHEDULED, name: "Scheduled", role: "scheduled" } : state.folders.find((f) => f.path === p); },
  byRole(r) { return state.folders.find((f) => f.role === r); },
};

// ============================================================================ message list
const SORTS = [["date", "Date"], ["from", "From"], ["subject", "Subject"], ["size", "Size"], ["importance", "Importance"], ["flag", "Flag status"], ["category", "Category"]];
const FILTERS = [["all", "All"], ["unread", "Unread"], ["flagged", "Flagged"], ["toMe", "To me"], ["files", "Has files"], ["mentions", "Mentions me"], ["pinned", "Pinned"], ["suspicious", "Suspicious"]];
const normSubject = (s) => String(s || "").replace(/^\s*((re|fw|fwd|aw|wg|sv|vs|antw|rif|tr|r)\s*(\[\d+\])?\s*:\s*)+/i, "").trim().toLowerCase();

const list = {
  async load(folder, { keepOpen = false, silent = false, rescan = false } = {}) {
    if (folder !== state.folder || state.search) { state.selected.clear(); state.offset = 0; state.search = null; state.searchOpts = null; $("searchInput").value = ""; $("searchClear").classList.add("hidden"); state.expanded.clear(); }
    state.folder = folder;
    const f = folders.byPath(folder);
    state.folderRole = f ? f.role : (folder === "INBOX" ? "inbox" : "other");
    $("folderTitle").textContent = f ? f.name : folder;
    folders.render();
    if (!keepOpen) reading.close();
    await this.fetch({ silent, rescan });
  },
  async fetch({ silent = false, rescan = false, more = false } = {}) {
    if (state.search) return this.search(state.search, { silent, opts: state.searchOpts });
    if (state.folder === SCHEDULED) { await mailplus.loadOutbox(); state.messages = mailplus.outboxRows(); state.total = state.messages.length; this.render(); $("listStatus").textContent = plural(state.total, "scheduled message"); $("btnLoadMore").classList.add("hidden"); return; }
    if (!silent) { state.loading = true; $("listStatus").innerHTML = '<span class="spinner"></span>'; }
    try {
      const limit = more ? state.messages.length + state.pageSize : Math.max(state.pageSize, state.messages.length || state.pageSize);
      const r = await api("/api/messages", { query: { folder: state.folder, limit, offset: 0, rescan: rescan ? "true" : "" } });
      if (r.folder !== state.folder) return;
      const before = new Set(state.messages.map(mkey));
      state.messages = [...r.messages].sort((a, b) => b.date - a.date); state.total = r.total; state.pending = r.pending; state.version = r.version;
      this.render();
      renderJunkStatus();
      $("analysisBanner").classList.toggle("hidden", !(state.pending > 0));
      $("analysisText").textContent = `Checking ${state.pending} message${state.pending === 1 ? "" : "s"} against blocklists and the impersonation detector…`;
      $("listStatus").textContent = r.total ? `${state.messages.length} of ${r.total}` : "";
      $("btnLoadMore").classList.toggle("hidden", state.messages.length >= r.total);
      if (state.open && state.folder === state.open.folder && !state.messages.some((m) => mkey(m) === mkey(state.open)) && before.has(mkey(state.open))) reading.close();
      if (state.folderRole === "inbox") mailplus.newMailCheck(state.messages);
      this.schedulePoll();
      await this.checkActivity();
    } catch (e) {
      if (!silent) { $("listStatus").textContent = ""; $("messageList").innerHTML = ""; $("messageList").appendChild(el("div", { class: "empty-list" }, icon("warning", 56), el("div", { text: e.message }))); }
    } finally { state.loading = false; }
  },
  /** Simple search (the box) or advanced search ([opts] from the search options dialog). */
  async search(q, { silent = false, opts = null } = {}) {
    state.search = q || "(advanced)"; state.searchOpts = opts;
    const scopeAll = opts ? opts.scope === "all" : prefs.get("searchScope", "all") === "all";
    $("folderTitle").textContent = `Results${q ? ": " + q : ""}`;
    $("focusTabs").classList.add("hidden");
    if (!silent) $("listStatus").innerHTML = '<span class="spinner"></span>';
    try {
      const query = { folder: state.folder, scope: scopeAll ? "all" : "folder", limit: 200, text: q || "" };
      if (opts) Object.assign(query, { from: opts.from, to: opts.to, subject: opts.subject, body: opts.body, hasAttachments: opts.hasAttachments ? "true" : "", unread: opts.unread ? "true" : "", flagged: opts.flagged ? "true" : "", since: opts.since, before: opts.before });
      const r = await api("/api/search/advanced", { query });
      state.messages = [...r.messages].sort((a, b) => b.date - a.date); state.pending = 0;
      this.render();
      $("analysisBanner").classList.add("hidden");
      $("listStatus").textContent = `${plural(r.messages.length, "result")} in ${scopeAll ? "all folders" : (folders.byPath(state.folder)?.name || state.folder)}`;
      $("btnLoadMore").classList.add("hidden");
      folders.render();
    } catch (e) { toast("Search failed: " + e.message, { danger: true }); $("listStatus").textContent = ""; }
  },
  clearSearch() { $("searchInput").value = ""; $("searchClear").classList.add("hidden"); state.search = null; state.searchOpts = null; this.load(state.folder); },
  schedulePoll() {
    clearTimeout(state.pollTimer);
    const ms = state.pending > 0 ? 2500 : (prefs.get("autoRefresh", 60) || 60) * 1000;
    state.pollTimer = setTimeout(async () => {
      if (!state.token || document.hidden) { this.schedulePoll(); return; }
      if (qs("dialog[open]:not(.docked)") && state.pending === 0) { this.schedulePoll(); return; }
      await this.fetch({ silent: true });
      if (state.pending === 0) { folders.load(); mailplus.refreshOtherAccounts(); }
    }, ms);
  },
  async checkActivity() {
    try {
      const acts = await api("/api/spam/activity", { query: { since: state.lastActivity } });
      if (acts.length) {
        state.lastActivity = Math.max(...acts.map((a) => a.at));
        const junk = folders.byRole("junk");
        const label = acts.length === 1 ? `Anne's Post moved "${acts[0].subject.slice(0, 50)}" to Junk` : `Anne's Post moved ${acts.length} messages to Junk`;
        toast(label, { action: "Open Junk", onAction: () => junk && list.load(junk.path), timeout: 9000 });
        folders.load();
      }
    } catch {}
  },
  focusedActive() { return state.folderRole === "inbox" && !state.search && state.settings?.focused?.enabled !== false && prefs.get("focusedInbox", true); },
  myAddresses() { return new Set([state.account?.email, ...(state.settings?.aliases || [])].filter(Boolean).map((x) => x.toLowerCase())); },
  matchesFilter(m, f = state.filter) {
    if (f === "all") return true;
    if (f === "unread") return !m.read;
    if (f === "flagged") return m.flagged || (m.flagDue && !m.flagCompleted);
    if (f === "toMe") { const me = this.myAddresses(); return (m.to || []).some((a) => me.has(a.toLowerCase())); }
    if (f === "files") return m.hasAttachments;
    if (f === "mentions") { const first = (state.account?.displayName || "").split(/\s+/)[0]; return !!first && (m.preview || "").toLowerCase().includes("@" + first.toLowerCase()); }
    if (f === "pinned") return m.pinned;
    if (f === "suspicious") return m.verdict && m.verdict.level !== "CLEAN";
    if (f.startsWith("cat:")) return (m.categories || []).includes(f.slice(4));
    return true;
  },
  visible() {
    let msgs = state.messages.filter((m) => this.matchesFilter(m));
    if (this.focusedActive()) msgs = msgs.filter((m) => (m.focused !== false) === (state.focus === "focused"));
    return this.sorted(msgs);
  },
  sorted(msgs) {
    const { by, dir } = prefs.get("sort", { by: "date", dir: "desc" });
    const k = {
      date: (m) => m.date, from: (m) => (m.fromName || m.fromAddress || "").toLowerCase(), subject: (m) => normSubject(m.subject), size: (m) => m.size,
      importance: (m) => ({ high: 2, normal: 1, low: 0 }[m.importance || "normal"]), flag: (m) => (m.flagged ? 2 : m.flagCompleted ? 1 : 0), category: (m) => (m.categories || [])[0] || "￿",
    }[by] || ((m) => m.date);
    const sign = dir === "asc" ? 1 : -1;
    return [...msgs].sort((a, b) => { const x = k(a), y = k(b); return (x < y ? -1 : x > y ? 1 : 0) * sign || b.date - a.date; });
  },
  /** Groups messages into conversations by References / In-Reply-To (and matching subject with a shared participant). */
  threads(msgs) {
    const parent = new Map();
    const find = (x) => { while (parent.has(x) && parent.get(x) !== x) { parent.set(x, parent.get(parent.get(x)) || parent.get(x)); x = parent.get(x); } return x; };
    const union = (a, b) => { if (!a || !b) return; if (!parent.has(a)) parent.set(a, a); if (!parent.has(b)) parent.set(b, b); const ra = find(a), rb = find(b); if (ra !== rb) parent.set(ra, rb); };
    const id = (m) => m.messageId || "uid:" + mkey(m);
    for (const m of msgs) { union(id(m), id(m)); for (const r of [...(m.references || []), m.inReplyTo].filter(Boolean)) union(id(m), r); }
    const bySubject = new Map();
    for (const m of msgs) {
      const s = normSubject(m.subject); if (!s || s.length < 4) continue;
      const people = new Set([m.fromAddress, ...(m.to || [])].map((x) => (x || "").toLowerCase()));
      const prev = bySubject.get(s);
      if (prev && prev.some((p) => [p.fromAddress, ...(p.to || [])].some((x) => people.has((x || "").toLowerCase()))) && (/^\s*(re|fw|fwd)\s*:/i.test(m.subject) || prev.some((p) => /^\s*(re|fw|fwd)\s*:/i.test(p.subject)))) union(id(m), id(prev[0]));
      bySubject.set(s, [...(prev || []), m]);
    }
    const groups = new Map();
    for (const m of msgs) { const r = find(id(m)); if (!groups.has(r)) groups.set(r, []); groups.get(r).push(m); }
    return [...groups.values()].map((g) => g.sort((a, b) => b.date - a.date));
  },
  conversationsOn() { return prefs.get("conversations", true) && !state.search && state.folder !== SCHEDULED && state.folderRole !== "drafts"; },
  render() {
    const host = $("messageList");
    host.innerHTML = "";
    const isJunk = state.folderRole === "junk";
    $("cmdJunk").parentElement.classList.toggle("hidden", isJunk);
    $("cmdNotJunk").classList.toggle("hidden", !isJunk);
    // Focused / Other tabs
    const ft = $("focusTabs");
    const focusOn = this.focusedActive();
    ft.classList.toggle("hidden", !focusOn);
    if (focusOn) {
      const filtered = state.messages.filter((m) => this.matchesFilter(m));
      const otherUnread = filtered.filter((m) => m.focused === false && !m.read).length, focUnread = filtered.filter((m) => m.focused !== false && !m.read).length;
      qsa("button", ft).forEach((b) => {
        b.classList.toggle("active", b.dataset.focus === state.focus);
        const n = b.dataset.focus === "other" ? otherUnread : focUnread;
        b.innerHTML = ""; b.append(b.dataset.focus === "other" ? "Other" : "Focused");
        if (n && b.dataset.focus !== state.focus) b.appendChild(el("span", { class: "n", text: `${n} new` }));
      });
    }
    // filter chips
    const chips = $("filterChips"); chips.innerHTML = "";
    if (state.filter !== "all") {
      const label = state.filter.startsWith("cat:") ? state.filter.slice(4) : (FILTERS.find((f) => f[0] === state.filter) || [0, state.filter])[1];
      chips.appendChild(el("span", { class: "badge info", title: "Clear filter", onclick: () => { state.filter = "all"; this.render(); } }, icon("filter", 12), `Filter: ${label}`, icon("close", 12)));
    }
    chips.classList.toggle("hidden", state.filter === "all");
    $("listFilter").classList.toggle("active", state.filter !== "all");
    const banner = $("listBanner");
    if (isJunk && !state.search) {
      const auto = state.messages.filter((m) => m.verdict?.autoMoved).length;
      banner.className = "banner"; banner.classList.remove("hidden");
      banner.innerHTML = `<svg width="16" height="16"><use href="#i-junk"/></svg><span class="grow">Messages here were filed as junk${auto ? ` — Anne's Post moved ${auto} of them automatically` : ""}. Use <b>Not junk</b> to restore one and teach the filter.</span>`;
    } else if (state.folder === SCHEDULED) {
      banner.className = "banner"; banner.classList.remove("hidden");
      banner.innerHTML = `<svg width="16" height="16"><use href="#i-send-later"/></svg><span class="grow">These messages are sent at the scheduled time while Anne's Post is running. Open one to cancel it or edit it as a draft.</span>`;
    } else banner.classList.add("hidden");
    const msgs = this.visible();
    $("cmdSelectAll").checked = msgs.length > 0 && msgs.every((m) => state.selected.has(mkey(m)));
    if (!msgs.length) {
      const empty = state.messages.length ? (focusOn ? `Nothing in ${state.focus === "focused" ? "Focused" : "Other"}${state.filter !== "all" ? " for this filter" : ""}` : "Nothing matches this filter") : (state.loading ? "Loading…" : state.search ? "No results" : "This folder is empty");
      host.appendChild(el("div", { class: "empty-list" }, icon("empty", 56), el("div", { text: empty })));
      $("listPane").classList.toggle("selecting", state.selected.size > 0);
      return;
    }
    const frag = document.createDocumentFragment();
    const pinned = msgs.filter((m) => m.pinned), rest = msgs.filter((m) => !m.pinned);
    const { by } = prefs.get("sort", { by: "date", dir: "desc" });
    const emit = (items, heading) => {
      let group = null;
      const units = this.conversationsOn() ? this.threads(items) : items.map((m) => [m]);
      if (this.conversationsOn()) units.sort((a, b) => items.indexOf(a[0]) - items.indexOf(b[0]));
      for (const u of units) {
        const m = u[0];
        if (heading) { if (group !== heading) { group = heading; frag.appendChild(el("div", { class: "day-sep" }, icon("pin", 13), heading)); } }
        else if (!state.search && by === "date") { const g = dayGroup(m.date); if (g !== group) { group = g; frag.appendChild(el("div", { class: "day-sep", text: g })); } }
        frag.appendChild(this.row(m, { thread: u }));
        if (u.length > 1 && state.expanded.has(this.threadKey(u))) for (const c of u) frag.appendChild(this.row(c, { child: true }));
      }
    };
    if (pinned.length) emit(pinned, "Pinned");
    emit(rest, null);
    host.appendChild(frag);
    $("listPane").classList.toggle("selecting", state.selected.size > 0);
  },
  threadKey(u) { return u.map(mkey).sort()[0]; },
  row(m, { thread = null, child = false } = {}) {
    if (m.scheduled) return mailplus.outboxRow(m);
    const group = thread && thread.length > 1 ? thread : null;
    const unread = group ? group.some((x) => !x.read) : !m.read;
    const v = m.verdict;
    const level = v ? v.level : null;
    const keys = group ? group.map(mkey) : [mkey(m)];
    const isSel = keys.every((k) => state.selected.has(k));
    const isOpen = state.open && (group ? group.some((x) => mkey(x) === mkey(state.open)) : mkey(state.open) === mkey(m));
    const div = el("div", {
      class: "msg" + (unread ? " unread" : "") + (isSel ? " selected" : "") + (isOpen && (!group || !state.expanded.has(this.threadKey(group))) ? " open" : "") + (level === "SPAM" ? " spam" : "") + (m.pinned ? " pinned" : "") + (child ? " child" : ""),
      dataset: { uid: m.uid, folder: m.folder, key: mkey(m) },
      tabindex: -1, draggable: "true",
    });
    const cb = el("input", { type: "checkbox" }); cb.checked = isSel;
    cb.onclick = (e) => { e.stopPropagation(); this.toggleSelect(keys, e.shiftKey); };
    div.appendChild(el("label", { class: "cb", onclick: (e) => e.stopPropagation() }, cb));
    const people = group ? [...new Set(group.map((x) => x.fromName || x.fromAddress))].slice(0, 3).join(", ") : (state.folderRole === "sent" || state.folderRole === "drafts" ? (m.to || []).join(", ") : (m.fromName || m.fromAddress));
    div.appendChild(el("div", { class: "avatar", text: initials(m.fromName, m.fromAddress), style: { background: avatarColor(m.fromAddress || m.fromName) } }));
    const badges = [];
    if (v) {
      if (v.brand?.mismatch && v.brand.knownBrand) badges.push(el("span", { class: "badge danger", title: v.brand.explanation }, icon("shield-x", 12), `Impersonates ${v.brand.claimedBrand}`));
      else if (level === "SPAM") badges.push(el("span", { class: "badge danger", title: `Junk score ${v.score}/100` }, icon("junk", 12), v.blacklistHits?.length ? "Blocklisted" : "Junk"));
      else if (level === "SUSPICIOUS") badges.push(el("span", { class: "badge warn", title: `Junk score ${v.score}/100` }, icon("warning", 12), "Suspicious"));
      else if (v.brand?.verified && v.brand.knownBrand && v.auth?.dmarc === "pass") badges.push(el("span", { class: "badge ok", title: v.brand.explanation }, icon("shield-ok", 12)));
    }
    const any = (f) => (group ? group.some(f) : f(m));
    const icons = el("span", { class: "msg-icons" });
    if (any((x) => x.importance === "high")) icons.appendChild(el("b", { class: "imp", title: "High importance", text: "!" }));
    if (any((x) => x.importance === "low")) icons.appendChild(el("b", { class: "imp low", title: "Low importance", text: "↓" }));
    if (any((x) => x.hasAttachments)) icons.appendChild(icon("attach", 14));
    if (m.snoozeUntil) icons.appendChild(el("span", { class: "snooze-note", title: "Snoozed", text: "⏰ " + fmtDate(m.snoozeUntil) }));
    if (any((x) => x.answered)) icons.appendChild(Object.assign(icon("reply", 14), { title: "Replied" }));
    if (any((x) => x.forwarded)) icons.appendChild(icon("forward", 14));
    if (any((x) => x.flagCompleted) && !any((x) => x.flagged)) { const f = icon("check-circle", 14); f.classList.add("done"); icons.appendChild(f); }
    if (any((x) => x.flagged)) { const f = icon("flag-fill", 14); f.classList.add("flag"); icons.appendChild(f); }
    if (m.pinned) { const p = icon("pin-fill", 14); p.classList.add("pin"); icons.appendChild(p); }
    const catChips = (m.categories || []).length ? el("span", { class: "cat-chips" }, ...(m.categories || []).slice(0, 3).map((c) => el("span", { class: "cat-dot", title: c, style: { background: mailplus.categoryColor(c) } }))) : null;
    const countBadge = group ? el("span", { class: "thread-n", title: state.expanded.has(this.threadKey(group)) ? "Collapse conversation" : "Show the messages in this conversation", text: String(group.length), onclick: (e) => { e.stopPropagation(); const k = this.threadKey(group); if (state.expanded.has(k)) state.expanded.delete(k); else state.expanded.add(k); this.render(); } }) : null;
    const main = el("div", { class: "msg-main" },
      el("div", { class: "msg-line" }, el("span", { class: "msg-from", text: people || "(unknown sender)", title: m.fromAddress }), ...badges, countBadge, el("span", { class: "msg-date", text: fmtDate(m.date) })),
      el("div", { class: "msg-line" }, el("span", { class: "msg-subject", text: m.subject || "(no subject)" }), catChips, icons),
      el("div", { class: "msg-line msg-preview-line" }, el("span", { class: "msg-preview", text: m.preview || "" })));
    div.appendChild(main);
    const targets = () => (group ? group : [m]);
    const hover = el("div", { class: "msg-hover" },
      el("button", { class: "icon-btn", title: "Delete", onclick: (e) => { e.stopPropagation(); actions.delete(targets()); } }, icon("trash")),
      el("button", { class: "icon-btn", title: unread ? "Mark as read" : "Mark as unread", onclick: (e) => { e.stopPropagation(); actions.read(targets(), unread); } }, icon(unread ? "read" : "unread")),
      el("button", { class: "icon-btn", title: m.flagged ? "Clear flag" : "Flag", onclick: (e) => { e.stopPropagation(); actions.flag(targets(), !m.flagged); } }, icon(m.flagged ? "flag-fill" : "flag")),
      el("button", { class: "icon-btn", title: m.pinned ? "Unpin" : "Pin", onclick: (e) => { e.stopPropagation(); actions.pin(targets(), !m.pinned); } }, icon(m.pinned ? "pin-fill" : "pin")),
      state.folderRole === "junk"
        ? el("button", { class: "icon-btn", title: "Not junk", onclick: (e) => { e.stopPropagation(); actions.junk(targets(), false); } }, icon("shield-ok"))
        : el("button", { class: "icon-btn", title: "Junk", onclick: (e) => { e.stopPropagation(); actions.junk(targets(), true); } }, icon("junk")));
    div.appendChild(hover);
    div.onclick = (e) => {
      if (e.ctrlKey || e.metaKey) { this.toggleSelect(keys, false); return; }
      if (e.shiftKey) { this.toggleSelect(keys, true); return; }
      reading.open(m);
    };
    div.ondblclick = () => { if (state.folderRole === "drafts") return; mailplus.openInWindow(m); };
    div.oncontextmenu = (e) => { e.preventDefault(); contextMenu.show(e, m, targets()); };
    div.addEventListener("dragstart", (e) => {
      const items = state.selected.has(mkey(m)) ? this.targets() : targets();
      e.dataTransfer.setData("application/x-quillpost", JSON.stringify(actions.refs(items)));
      e.dataTransfer.setData("text/plain", items.map((x) => x.subject).join("\n"));
      e.dataTransfer.effectAllowed = "copyMove";
    });
    mailplus.attachSwipe(div, targets);
    return div;
  },
  toggleSelect(keys, range) {
    keys = Array.isArray(keys) ? keys : [keys];
    if (range && state.lastFocusedKey != null) {
      const ids = this.visible().map(mkey);
      const a = ids.indexOf(state.lastFocusedKey), b = ids.indexOf(keys[0]);
      if (a >= 0 && b >= 0) for (const id of ids.slice(Math.min(a, b), Math.max(a, b) + 1)) state.selected.add(id);
    } else {
      const all = keys.every((k) => state.selected.has(k));
      for (const k of keys) { if (all) state.selected.delete(k); else state.selected.add(k); }
    }
    state.lastFocusedKey = keys[0];
    this.render();
  },
  selectAll(on) { state.selected.clear(); if (on) this.visible().forEach((m) => state.selected.add(mkey(m))); this.render(); },
  targets() {
    if (state.selected.size) return state.messages.filter((m) => state.selected.has(mkey(m)));
    if (state.open) { const m = state.messages.find((x) => mkey(x) === mkey(state.open)); return m ? [m] : (state.openDetail ? [state.openDetail] : [state.open]); }
    return [];
  },
  removeLocal(items) {
    const ids = new Set(items.map(mkey));
    const vis = this.visible();
    const idx = vis.findIndex((m) => ids.has(mkey(m)));
    state.messages = state.messages.filter((m) => !ids.has(mkey(m)));
    for (const id of ids) state.selected.delete(id);
    if (state.open && ids.has(mkey(state.open))) {
      const remaining = this.visible();
      const next = remaining[Math.min(Math.max(idx, 0), remaining.length - 1)];
      if (next && prefs.get("openNext", true) && innerWidth > 1000) reading.open(next); else reading.close();
    }
    this.render();
  },
  patchLocal(items, patch) {
    const ids = new Set(items.map(mkey));
    state.messages = state.messages.map((m) => (ids.has(mkey(m)) ? { ...m, ...patch } : m));
    if (state.open && ids.has(mkey(state.open))) Object.assign(state.open, patch);
    this.render();
    if (state.openDetail && ids.has(mkey(state.openDetail))) { Object.assign(state.openDetail, patch); reading.renderToolbarState(); reading.renderInfo(state.openDetail); }
  },
  /** Applies server-side metadata changes (categories, pins, flags) to every loaded copy of those messages. */
  patchByMessageId(map) {
    // Only the user's own metadata; the stored subject/from are bookkeeping and may be blank.
    const pick = (x) => ({ categories: x.categories, pinned: x.pinned, flagDue: x.flagDue, flagCompleted: x.flagCompleted, reminder: x.reminder });
    state.messages = state.messages.map((m) => (map[m.messageId] ? { ...m, ...pick(map[m.messageId]) } : m));
    if (state.openDetail && map[state.openDetail.messageId]) { Object.assign(state.openDetail, pick(map[state.openDetail.messageId])); reading.renderInfo(state.openDetail); reading.renderCats(state.openDetail); }
    this.render();
  },
  moveFocus(delta) {
    const ids = this.visible();
    if (!ids.length) return;
    let i = state.open ? ids.findIndex((m) => mkey(m) === mkey(state.open)) : -1;
    i = Math.max(0, Math.min(ids.length - 1, i + delta));
    reading.open(ids[i]);
    const row = qs(`.msg[data-key="${CSS.escape(mkey(ids[i]))}"]`);
    row && row.scrollIntoView({ block: "nearest" });
  },
};

// ============================================================================ actions
const actions = {
  refs(items) { return items.map((m) => ({ folder: m.folder || state.folder, uid: m.uid })); },
  ids(items) { return [...new Set(items.map((m) => m.messageId).filter(Boolean))]; },
  async junk(items, junk, { blockSender = false, safeSender = false } = {}) {
    if (!items.length) return;
    try {
      const r = await api("/api/junk", { method: "POST", body: { items: this.refs(items), junk, blockSender, safeSender } });
      if (state.capabilities.move || !state.capabilities.folders) list.removeLocal(items);
      const n = r.ok;
      const back = (r.moved && r.moved.length) ? r.moved : null;
      toast(junk ? `Moved ${plural(n, "message")} to Junk and learned from ${n === 1 ? "it" : "them"}` : `Restored ${plural(n, "message")} to the Inbox and learned from ${n === 1 ? "it" : "them"}`,
        back ? { action: "Undo", onAction: () => this.junk(back, !junk) } : {});
      if (r.errors?.length) toast(r.errors[0], { danger: true });
      folders.load(); refreshBayesStatus();
      return r;
    } catch (e) { toast("Junk action failed: " + e.message, { danger: true }); }
  },
  async delete(items, permanent = false) {
    if (!items.length) return;
    if (permanent && !confirm(`Permanently delete ${plural(items.length, "message")}? This cannot be undone.`)) return;
    try {
      const r = await api("/api/delete", { method: "POST", body: { items: this.refs(items), permanent } });
      list.removeLocal(items);
      const from = items[0].folder || state.folder;
      toast(`Deleted ${plural(r.ok, "message")}`, r.moved?.length ? { action: "Undo", onAction: () => this.move(r.moved, from) } : {});
      if (r.errors?.length) toast(r.errors[0], { danger: true });
      folders.load();
      return r;
    } catch (e) { toast("Delete failed: " + e.message, { danger: true }); }
  },
  async move(items, to, { quiet = false } = {}) {
    if (!items.length || !to) return;
    try {
      const r = await api("/api/move", { method: "POST", body: { items: this.refs(items), to } });
      list.removeLocal(items);
      const from = items[0].folder || state.folder;
      if (!quiet) toast(`Moved ${plural(r.ok, "message")} to ${folders.byPath(to)?.name || to}`, r.moved?.length ? { action: "Undo", onAction: () => this.move(r.moved, from) } : {});
      if (r.errors?.length) toast(r.errors[0], { danger: true });
      folders.load();
      return r;
    } catch (e) { toast("Move failed: " + e.message, { danger: true }); }
  },
  async copy(items, to) {
    if (!items.length || !to) return;
    try { const r = await api("/api/copy", { method: "POST", body: { items: this.refs(items), to } }); toast(`Copied ${plural(r.ok, "message")} to ${folders.byPath(to)?.name || to}`); folders.load(); }
    catch (e) { toast("Copy failed: " + e.message, { danger: true }); }
  },
  async archive(items) {
    let a = folders.byRole("archive");
    if (!a && state.capabilities.folders) {
      try { const r = await api("/api/folders/create", { method: "POST", body: { name: "Archive" } }); await folders.load(); a = folders.byPath(r.path); } catch {}
    }
    if (!a) { toast("No Archive folder on this account.", { danger: true }); return; }
    return this.move(items, a.path);
  },
  async read(items, read) {
    if (!items.length) return;
    if (!read && state.open && items.some((m) => mkey(m) === mkey(state.open))) clearTimeout(reading.markTimer);
    list.patchLocal(items, { read });
    try { await api("/api/flags", { method: "POST", body: { items: this.refs(items), read } }); folders.load(); }
    catch (e) { toast("Could not update: " + e.message, { danger: true }); }
  },
  async flag(items, flagged, due = undefined) {
    if (!items.length) return;
    list.patchLocal(items, { flagged, ...(flagged ? { flagCompleted: false } : {}) });
    try {
      await api("/api/flags", { method: "POST", body: { items: this.refs(items), flagged } });
      if (flagged && due !== undefined) await this.meta(items, { flagDue: due || "" });
      if (!flagged) await this.meta(items, { clearFlag: true });
    } catch (e) { toast("Could not update: " + e.message, { danger: true }); }
  },
  async completeFlag(items) {
    if (!items.length) return;
    list.patchLocal(items, { flagged: false, flagCompleted: true });
    try { await api("/api/flags", { method: "POST", body: { items: this.refs(items), flagged: false } }); await this.meta(items, { flagCompleted: true }); }
    catch (e) { toast("Could not update: " + e.message, { danger: true }); }
  },
  async meta(items, patch) {
    const ids = this.ids(items);
    if (!ids.length) { toast("These messages have no Message-ID, so this cannot be stored.", { danger: true }); return; }
    const map = await api("/api/meta", { method: "POST", body: { messageIds: ids, ...patch } });
    list.patchByMessageId(map);
    return map;
  },
  async pin(items, pinned) { try { await this.meta(items, { pinned }); toast(pinned ? "Pinned to the top of the folder" : "Unpinned"); } catch (e) { toast(e.message, { danger: true }); } },
  async categorize(items, name, on) { try { await this.meta(items, on ? { categoriesAdd: [name] } : { categoriesRemove: [name] }); } catch (e) { toast(e.message, { danger: true }); } },
  async clearCategories(items) { try { await this.meta(items, { categoriesSet: [] }); } catch (e) { toast(e.message, { danger: true }); } },
  async snooze(items, until) {
    if (!items.length) return;
    try {
      const r = await api("/api/snooze", { method: "POST", body: { items: this.refs(items), until } });
      list.removeLocal(items);
      if (until) toast(`Snoozed until ${fmtDate(until, { long: true })}`, { action: "Show Snoozed", onAction: () => { const f = folders.byRole("snoozed"); if (f) list.load(f.path); } });
      else toast("Returned to the Inbox");
      if (r.errors?.length) toast(r.errors[0], { danger: true });
      folders.load();
    } catch (e) { toast("Snooze failed: " + e.message, { danger: true }); }
  },
  async ignore(items) {
    if (!items.length) return;
    if (!confirm("Ignore this conversation? Its messages move to Deleted Items, and any future replies go there too.")) return;
    try {
      const r = await api("/api/ignore", { method: "POST", body: { items: this.refs(items), ignore: true } });
      list.removeLocal(items);
      toast(`Conversation ignored · ${plural(r.ok, "message")} deleted`);
      folders.load();
    } catch (e) { toast("Could not ignore: " + e.message, { danger: true }); }
  },
  pickFolder(items, { copy = false, title = null } = {}) {
    const ul = $("mvList"); const search = $("mvSearch");
    $("mvTitle").textContent = title || (copy ? "Copy to" : "Move to");
    const render = () => {
      ul.innerHTML = "";
      const q = search.value.trim().toLowerCase();
      for (const f of state.folders) {
        if (f.path === state.folder && !copy) continue;
        if (q && !f.name.toLowerCase().includes(q) && !f.path.toLowerCase().includes(q)) continue;
        const li = el("li", { class: f.role, style: { "--depth": q ? 0 : f.depth } }, icon(FOLDER_ICONS[f.role] || "folder", 18), el("span", { class: "name", text: f.name }));
        li.onclick = () => { $("moveDialog").close(); if (copy) this.copy(items, f.path); else if (f.role === "junk") this.junk(items, true); else this.move(items, f.path); };
        ul.appendChild(li);
      }
      if (state.capabilities.folders && !q) {
        const add = el("li", { class: "add-folder" }, icon("folder-plus", 18), el("span", { class: "name", text: "New folder…" }));
        add.onclick = async () => { $("moveDialog").close(); const p = await mailplus.createFolder(null); if (p) { if (copy) this.copy(items, p); else this.move(items, p); } };
        ul.appendChild(add);
      }
    };
    search.value = ""; search.oninput = render; render();
    $("moveDialog").showModal();
    setTimeout(() => search.focus(), 30);
  },
  async blockSender(m) {
    const addr = m.fromAddress;
    if (!addr) return;
    await this.junk([m], true, { blockSender: true });
    toast(`${addr} added to blocked senders`);
  },
  async trustSender(m) {
    const addr = m.fromAddress; if (!addr) return;
    const s = state.settings;
    if (!s.spam.safeSenders.includes(addr)) await settingsSave({ ...s, spam: { ...s.spam, safeSenders: [...s.spam.safeSenders, addr], blockedSenders: s.spam.blockedSenders.filter((x) => x !== addr) } });
    toast(`${addr} added to safe senders`);
    if (state.openDetail && state.openDetail.uid === m.uid) reading.open(m, { force: true });
  },
  async phish(items) {
    if (!items.length) return;
    if (!confirm("Report as phishing? The message moves to Junk, the classifier learns from it and the sender is blocked.")) return;
    await this.junk(items, true, { blockSender: true });
  },
};

// ============================================================================ reading pane
const reading = {
  markTimer: null,
  close() {
    state.open = null; state.openDetail = null; state.thread = [];
    $("reading").classList.add("hidden"); $("readingEmpty").classList.remove("hidden");
    $("workspace").classList.remove("show-reading");
    qsa(".msg.open").forEach((r) => r.classList.remove("open"));
    if ("speechSynthesis" in window) speechSynthesis.cancel();
  },
  async open(m, { force = false, keepThread = false } = {}) {
    if (m.scheduled) { mailplus.openScheduled(m); return; }
    const folder = m.folder || state.folder;
    if (folders.byPath(folder)?.role === "drafts" && state.capabilities.drafts) {
      try { const d = await api("/api/message", { query: { folder, uid: m.uid, tz: VIEW_TZ } }); compose.open({ mode: "draft", draft: { ...d, folder } }); return; }
      catch (e) { toast(e.message, { danger: true }); return; }
    }
    if (state.compose && compose.isDocked() && !state.compose.minimized) compose.minimize();
    if (!force && state.open && mkey(state.open) === mkey(m) && state.openDetail) { $("workspace").classList.add("show-reading"); return; }
    state.open = { uid: m.uid, folder };
    state.lastFocusedKey = mkey(m); state.imagesOnce = false;
    qsa(".msg.open").forEach((r) => r.classList.remove("open"));
    qs(`.msg[data-key="${CSS.escape(mkey(m))}"]`)?.classList.add("open");
    $("readingEmpty").classList.add("hidden"); $("reading").classList.remove("hidden");
    $("workspace").classList.add("show-reading");
    $("rdSubject").textContent = m.subject || "(no subject)";
    for (const id of ["rdVerdict", "rdAttachments", "rdBadges", "rdInfo", "rdInvite", "rdCats"]) $(id).innerHTML = "";
    $("rdTranslation").classList.add("hidden");
    if (!keepThread) { $("rdOlder").innerHTML = ""; $("rdNewer").innerHTML = ""; $("rdThreadCount").textContent = ""; state.thread = []; }
    $("rdFrame").classList.add("hidden"); $("rdText").classList.remove("hidden"); $("rdText").textContent = "Loading…";
    $("rdImagesBanner").classList.add("hidden");
    this.renderSender(m, null);
    try {
      const d = await api("/api/message", { query: { folder, uid: m.uid, tz: VIEW_TZ } });
      if (!state.open || mkey(state.open) !== mkey({ folder, uid: m.uid })) return;
      d.folder = folder;
      state.openDetail = d;
      this.render(d);
      if (!d.read) {
        clearTimeout(this.markTimer);
        const delay = prefs.get("markReadDelay", 1);
        if (delay >= 0) this.markTimer = setTimeout(() => { if (state.open && state.open.uid === d.uid) actions.read([{ uid: d.uid, folder }], true); }, delay * 1000);
      }
      if (!keepThread && prefs.get("conversations", true)) this.loadThread(d);
      else this.renderThread();
    } catch (e) { $("rdText").textContent = "Could not load this message: " + e.message; }
  },
  async loadThread(d) {
    try {
      const r = await api("/api/thread", { query: { folder: d.folder, uid: d.uid } });
      if (!state.openDetail || state.openDetail.uid !== d.uid) return;
      state.thread = r.messages || [];
      this.renderThread();
    } catch {}
  },
  renderThread() {
    const older = $("rdOlder"), newer = $("rdNewer"); older.innerHTML = ""; newer.innerHTML = "";
    const t = state.thread || [];
    const cur = state.openDetail;
    $("rdThreadCount").textContent = t.length > 1 ? plural(t.length, "message") : "";
    if (t.length < 2 || !cur) return;
    const newestFirst = prefs.get("newestOnTop", true);
    for (const m of t) {
      if (mkey(m) === mkey(cur) || (m.messageId && m.messageId === cur.messageId)) continue;
      const card = el("div", { class: "msg-card collapsed", title: "Show this message", onclick: () => reading.open(m, { keepThread: true }) },
        el("div", { class: "avatar", text: initials(m.fromName, m.fromAddress), style: { background: avatarColor(m.fromAddress || m.fromName) } }),
        el("div", { class: "c-main" }, el("div", { class: "c-from", text: (m.fromName || m.fromAddress) + (folders.byPath(m.folder)?.role === "sent" ? " (sent)" : "") }), el("div", { class: "c-preview", text: m.preview || m.subject })),
        el("div", { class: "c-date", text: fmtDate(m.date) }));
      const isOlder = m.date < cur.date;
      ((isOlder !== newestFirst) ? older : newer).appendChild(card);
    }
    if (newestFirst) { const kids = Array.from(older.children).reverse(); older.innerHTML = ""; kids.forEach((k) => older.appendChild(k)); const n2 = Array.from(newer.children).reverse(); newer.innerHTML = ""; n2.forEach((k) => newer.appendChild(k)); }
  },
  renderSender(m, d) {
    const v = (d && d.verdict) || m.verdict;
    const name = m.fromName || "", addr = m.fromAddress || "";
    const contact = pim.contactByEmail(addr);
    $("rdAvatar").textContent = initials(name, addr); $("rdAvatar").style.background = avatarColor(addr || name);
    $("rdAvatar").onclick = () => peopleUI.card({ name, email: addr });
    $("rdFromName").textContent = contact ? pim.contactName(contact) : (name || addr.split("@")[0]);
    const a = $("rdFromAddress");
    a.innerHTML = "";
    const at = addr.lastIndexOf("@");
    if (at > 0) { a.appendChild(document.createTextNode(addr.slice(0, at + 1))); a.appendChild(el("wbr")); a.appendChild(el("span", { class: "domain", text: addr.slice(at + 1) })); }
    else a.textContent = addr || "(no address)";
    a.className = "sender-address";
    if (v) {
      if (v.level === "SPAM" || (v.brand?.mismatch && v.brand.knownBrand)) a.classList.add("danger");
      else if (v.level === "SUSPICIOUS" || v.brand?.mismatch) a.classList.add("warn");
      else if (v.brand?.verified && v.brand.knownBrand) a.classList.add("ok");
    }
    a.title = addr;
    $("rdDate").textContent = fmtDate(m.date, { long: true });
    const meta = $("rdMeta"); meta.innerHTML = "";
    const to = d ? d.to : m.to;
    if (to && to.length) meta.appendChild(el("span", {}, el("b", { text: "To: " }), to.join(", ")));
    if (d && d.cc && d.cc.length) meta.appendChild(el("span", {}, el("b", { text: "Cc: " }), d.cc.join(", ")));
    if (d && d.replyTo) meta.appendChild(el("span", { style: { color: "var(--warn)" } }, el("b", { text: "Replies go to: " }), d.replyTo));
    const badges = $("rdBadges"); badges.innerHTML = "";
    if (v) {
      const b = v.brand || {};
      if (b.mismatch && b.knownBrand) badges.appendChild(el("span", { class: "badge danger", title: b.explanation }, icon("shield-x", 12), `Claims to be ${b.claimedBrand} — domain is not ${b.claimedBrand}'s`));
      else if (b.mismatch) badges.appendChild(el("span", { class: "badge warn", title: b.explanation }, icon("warning", 12), `"${b.claimedBrand}" does not match ${b.senderDomain}`));
      else if (b.verified && b.knownBrand) badges.appendChild(el("span", { class: "badge ok", title: b.explanation }, icon("shield-ok", 12), `Genuine ${b.claimedBrand} domain`));
      if (b.lookalike) badges.appendChild(el("span", { class: "badge danger" }, "Look-alike domain"));
      if (b.freemailSender && b.claimedBrand) badges.appendChild(el("span", { class: "badge warn" }, "Sent from free webmail"));
      const au = v.auth || {};
      const authBadge = (label, val) => val && badges.appendChild(el("span", { class: "badge " + (val === "pass" ? "ok" : val === "fail" ? "danger" : ""), title: `${label} result reported by your mail provider` }, `${label} ${val}`));
      authBadge("DMARC", au.dmarc); authBadge("SPF", au.spf); authBadge("DKIM", au.dkim);
      for (const h of v.blacklistHits || []) badges.appendChild(el("span", { class: "badge danger", title: h.note }, icon("block", 12), `${h.list}: ${h.subject}`));
      if (v.safeSender) badges.appendChild(el("span", { class: "badge ok" }, "Safe sender"));
      if (v.trained === "ham") badges.appendChild(el("span", { class: "badge ok" }, "You marked: not junk"));
      if (v.trained === "spam") badges.appendChild(el("span", { class: "badge danger" }, "You marked: junk"));
      if (v.matchedRule) badges.appendChild(el("span", { class: "badge info", title: "A rule matched this message" }, icon("rules", 12), `Rule: ${v.matchedRule}`));
    }
  },
  render(d) {
    const m = { ...d };
    $("rdSubject").textContent = d.subject || "(no subject)";
    this.renderSender(m, d);
    this.renderToolbarState();
    this.renderVerdict(d);
    this.renderInfo(d);
    this.renderCats(d);
    mailplus.renderInvite(d);
    mailplus.renderAttachments(d);
    // body
    const allowImages = state.imagesOnce || (state.settings.imageSenders || []).includes((d.fromAddress || "").toLowerCase()) || (d.verdict?.brand?.verified && d.verdict.level === "CLEAN" && prefs.get("imagesVerified", false)) || !!pim.contactByEmail(d.fromAddress) && prefs.get("imagesContacts", false);
    if (d.bodyHtml) {
      const { html, blockedImages } = sanitizeHtml(d.bodyHtml, { allowImages, folder: d.folder, uid: d.uid });
      $("rdText").classList.add("hidden");
      const frame = $("rdFrame"); frame.classList.remove("hidden");
      frame.style.height = "160px";
      frame.srcdoc = html;
      frame.onload = () => { this.fitFrame(); setTimeout(() => this.fitFrame(), 400); setTimeout(() => this.fitFrame(), 1500); };
      $("rdImagesBanner").classList.toggle("hidden", !(blockedImages > 0));
    } else {
      $("rdFrame").classList.add("hidden"); $("rdFrame").srcdoc = "";
      const pre = $("rdText"); pre.classList.remove("hidden");
      pre.innerHTML = "";
      linkify(d.bodyText || "(no text content)", pre);
      $("rdImagesBanner").classList.add("hidden");
    }
    $("readingScroll").scrollTop = 0;
  },
  renderCats(d) {
    const host = $("rdCats"); host.innerHTML = "";
    for (const c of d.categories || []) host.appendChild(el("span", { class: "cat-chip", style: { background: mailplus.categoryColor(c) }, title: "Click to remove", onclick: () => actions.categorize([d], c, false) }, c));
  },
  /** Information bars above the message: importance, replies, receipts, follow-up, snooze. */
  renderInfo(d) {
    const host = $("rdInfo"); host.innerHTML = "";
    const bar = (ic, text, cls = "", ...buttons) => host.appendChild(el("div", { class: "info-bar " + cls }, icon(ic), el("span", { class: "grow", text }), ...buttons));
    if (d.importance === "high") bar("warning", "This message was sent with High importance.", "danger");
    if (d.importance === "low") bar("info", "This message was sent with Low importance.");
    if (d.flagCompleted) bar("check-circle", "Follow up: completed.");
    else if (d.flagged || d.flagDue) bar("flag", d.flagDue ? `Follow up. Due ${fmtDay(parseYmd(d.flagDue), { weekday: "short", month: "short", day: "numeric" })}.` : "Flagged for follow up.", "", el("button", { class: "btn", onclick: () => actions.completeFlag([d]) }, icon("check"), "Mark complete"));
    if (d.snoozeUntil) bar("clock", `Snoozed until ${fmtDate(d.snoozeUntil, { long: true })}.`, "", el("button", { class: "btn", onclick: () => actions.snooze([d], null) }, "Unsnooze"));
    if (d.answered) bar("reply", "You replied to this message.");
    if (d.forwarded) bar("forward", "You forwarded this message.");
    if (d.readReceiptTo) bar("mail", `${d.fromName || d.fromAddress} asked for a read receipt.`, "",
      el("button", { class: "btn", onclick: async () => { try { await api("/api/mdn", { method: "POST", body: { folder: d.folder, uid: d.uid } }); d.readReceiptTo = null; this.renderInfo(d); toast("Read receipt sent"); } catch (e) { toast(e.message, { danger: true }); } } }, "Send receipt"),
      el("button", { class: "btn", onclick: async () => { try { await api("/api/mdn/decline", { method: "POST", body: { folder: d.folder, uid: d.uid } }); } catch {} d.readReceiptTo = null; this.renderInfo(d); } }, "Don't send"));
    if (state.folderRole === "snoozed" && !d.snoozeUntil) bar("clock", "This message is in Snoozed.", "", el("button", { class: "btn", onclick: () => actions.snooze([d], null) }, "Return to Inbox"));
  },
  fitFrame() {
    const f = $("rdFrame");
    try { const h = f.contentDocument?.documentElement?.scrollHeight || f.contentDocument?.body?.scrollHeight; if (h) f.style.height = Math.min(Math.max(h + 24, 80), 20000) + "px"; } catch {}
  },
  renderToolbarState() {
    const d = state.openDetail; if (!d) return;
    const isJunk = folders.byPath(d.folder)?.role === "junk";
    $("rdJunk").classList.toggle("hidden", isJunk);
    $("rdNotJunk").classList.toggle("hidden", !isJunk);
    qs("#rdFlag use").setAttribute("href", d.flagged ? "#i-flag-fill" : "#i-flag");
    $("rdFlag").classList.toggle("active", !!d.flagged);
  },
  renderVerdict(d) {
    const host = $("rdVerdict"); host.innerHTML = "";
    const v = d.verdict; if (!v) return;
    const b = v.brand || {};
    const inJunk = folders.byPath(d.folder)?.role === "junk";
    let cls, title, iconName;
    if (v.level === "SPAM") { cls = "danger"; iconName = "shield-x"; title = b.mismatch && b.knownBrand ? `This message pretends to be from ${b.claimedBrand} — it is not.` : v.blockedSender ? "From a sender you blocked." : v.matchedRule ? `Filed by your rule "${v.matchedRule}".` : "This message looks like junk or a scam."; }
    else if (v.level === "SUSPICIOUS") { cls = "warn"; iconName = "warning"; title = b.mismatch ? "The sender's name does not match its domain — be careful." : "Some things about this message look suspicious."; }
    else if (v.safeSender) { cls = "ok"; iconName = "shield-ok"; title = "From a safe sender."; }
    else if (b.verified && b.knownBrand) { cls = "ok"; iconName = "shield-ok"; title = `Sent from a genuine ${b.claimedBrand} domain${v.auth?.dmarc === "pass" ? " and authenticated (DMARC pass)" : ""}.`; }
    else if (inJunk) { cls = "info"; iconName = "info"; title = "This message is in Junk but currently scores as clean."; }
    else return this.renderVerdictDetailsOnly(host, v);
    const body = el("div", { class: "v-body" }, el("div", { class: "v-title", text: `${title} ` }, el("span", { class: "small", style: { fontWeight: 400 }, text: `Junk score ${v.score}/100` })));
    const expl = b.explanation && (b.mismatch || b.verified) ? b.explanation : (v.reasons[0] ? `${v.reasons[0].title}: ${v.reasons[0].detail}` : "");
    if (expl) body.appendChild(el("div", { text: expl }));
    if (v.autoMoved) body.appendChild(el("div", { class: "small", text: `Anne's Post moved this message to ${folders.byPath(v.autoMovedTo)?.name || v.autoMovedTo || "Junk"} automatically.` }));
    const act = el("div", { class: "v-actions" });
    if (!inJunk && v.level !== "CLEAN") act.appendChild(el("button", { class: "btn", onclick: () => actions.junk([state.openDetail], true) }, icon("junk"), "Move to Junk"));
    if (inJunk || (v.level !== "CLEAN")) act.appendChild(el("button", { class: "btn", onclick: () => inJunk ? actions.junk([state.openDetail], false) : actions.trustSender(state.openDetail) }, icon("shield-ok"), inJunk ? "Not junk" : "It's safe — trust sender"));
    if (v.level !== "CLEAN" && !v.blockedSender) act.appendChild(el("button", { class: "btn", onclick: () => actions.blockSender(state.openDetail) }, icon("block"), "Block sender"));
    act.appendChild(el("button", { class: "btn", onclick: () => analysis.open(state.openDetail) }, icon("sparkle"), "Analyse & make rule"));
    body.appendChild(act);
    body.appendChild(this.detailsBlock(v));
    host.appendChild(el("div", { class: "verdict " + cls }, icon(iconName, 22), body));
  },
  renderVerdictDetailsOnly(host, v) {
    const det = this.detailsBlock(v, "Junk check: clean (score " + v.score + "/100)");
    det.classList.add("small");
    host.appendChild(det);
  },
  detailsBlock(v, summary = "Why?") {
    const det = el("details", {}, el("summary", { text: summary }));
    const ul = el("ul", { class: "reason-list" });
    for (const r of v.reasons) {
      const w = r.weight;
      ul.appendChild(el("li", {}, el("span", { class: "w " + (w > 0 ? "pos" : w < 0 ? "neg" : "zero"), text: (w > 0 ? "+" : "") + w }), el("span", {}, el("span", { class: "t", text: r.title }), r.detail ? el("span", { class: "d", text: " — " + r.detail }) : null)));
    }
    if (!v.reasons.length) ul.appendChild(el("li", {}, el("span", { class: "w zero", text: "0" }), el("span", { class: "d", text: "Nothing suspicious was found." })));
    det.appendChild(ul);
    const kv = el("dl", { class: "kv" });
    const add = (k, val) => { if (val) { kv.appendChild(el("dt", { text: k })); kv.appendChild(el("dd", { text: val })); } };
    add("Sender domain", v.senderDomain);
    if (v.brand?.claimedBrand) add("Claims to be", `${v.brand.claimedBrand}${v.brand.claimSource ? ` (from ${v.brand.claimSource})` : ""}`);
    if (v.brand?.legitimateDomains?.length) add("Real domains", v.brand.legitimateDomains.slice(0, 6).join(", "));
    add("Sending servers", (v.originatingIps || []).join(", "));
    add("Link domains", (v.linkDomains || []).slice(0, 8).join(", "));
    const bl = (v.blacklistStatus || []).map((s) => `${s.list}: ${s.refused ? "refused" : s.error ? "error" : s.hits ? `${s.hits} hit` : s.queried ? "clear" : "n/a"}`).join(" · ");
    add("Blocklists", bl);
    if (v.bayesProbability != null) add("Learned classifier", Math.round(v.bayesProbability * 100) + "% spam-like");
    add("Checked", v.analyzedAt ? new Date(v.analyzedAt).toLocaleString() : "");
    det.appendChild(kv);
    return det;
  },
};

function linkify(text, host) {
  const re = /(https?:\/\/[^\s<>"']+)/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    host.appendChild(document.createTextNode(text.slice(last, m.index)));
    host.appendChild(el("a", { href: m[1], target: "_blank", rel: "noopener noreferrer", text: m[1], title: "Opens " + m[1] }));
    last = m.index + m[1].length;
  }
  host.appendChild(document.createTextNode(text.slice(last)));
}

/** Sanitises email HTML for display in the sandboxed frame. Scripts, forms, external CSS and
 *  (optionally) remote images are removed; cid: images are routed through the attachment API. */
function sanitizeHtml(html, { allowImages, folder, uid, cidUrl = null }) {
  const doc = new DOMParser().parseFromString(html, "text/html");
  let blockedImages = 0;
  doc.querySelectorAll("script, iframe, object, embed, link, meta, base, form, input, button, textarea, select, applet, frame, frameset, noscript, svg").forEach((n) => n.remove());
  const transparent = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";
  for (const n of doc.querySelectorAll("*")) {
    for (const a of Array.from(n.attributes)) {
      const name = a.name.toLowerCase(), val = a.value.trim();
      if (name.startsWith("on") || name === "srcdoc") { n.removeAttribute(a.name); continue; }
      if ((name === "href" || name === "src" || name === "action" || name === "formaction" || name === "xlink:href" || name === "background" || name === "poster") && /^\s*(javascript|vbscript|data:text\/html)/i.test(val)) { n.removeAttribute(a.name); continue; }
      if (name === "style" && /expression\s*\(|javascript:/i.test(val)) n.removeAttribute("style");
      if (name === "style" && cidUrl && /url\(\s*["']?cid:/i.test(val)) n.setAttribute("style", val.replace(/url\(\s*["']?cid:([^"')]+)["']?\s*\)/gi, (m0, id) => `url("${cidUrl(id)}")`));
      if (name === "style" && !allowImages && /url\s*\(/i.test(n.getAttribute("style") || "")) { n.setAttribute("style", n.getAttribute("style").replace(/url\s*\(([^)]*)\)/gi, (m0, u) => cidUrl && u.includes(cidUrl("")) ? m0 : "none")); blockedImages++; }
    }
    const tag = n.tagName.toLowerCase();
    if (tag === "img" || tag === "image" || tag === "source" || tag === "video" || tag === "audio") {
      const src = n.getAttribute("src") || "";
      if (/^cid:/i.test(src)) { const id = src.slice(4).replace(/^<|>$/g, ""); n.setAttribute("src", cidUrl ? cidUrl(id) : attachmentUrl(folder, uid, { cid: id })); }
      else if (/^data:image\//i.test(src)) { /* inline data image: fine */ }
      else if (src && !allowImages) { n.setAttribute("data-blocked-src", src); n.setAttribute("src", transparent); n.setAttribute("title", "Remote image blocked"); blockedImages++; }
      n.removeAttribute("srcset"); n.removeAttribute("loading");
      if (tag !== "img") n.remove();
    }
    if (tag === "a") { n.setAttribute("target", "_blank"); n.setAttribute("rel", "noopener noreferrer"); const href = n.getAttribute("href") || ""; if (href) n.setAttribute("title", "Opens " + href); }
  }
  if (cidUrl) for (const s of doc.querySelectorAll("style")) s.textContent = s.textContent.replace(/url\(\s*["']?cid:([^"')]+)["']?\s*\)/gi, (m0, id) => `url("${cidUrl(id)}")`);
  if (!allowImages) for (const s of doc.querySelectorAll("style")) { if (/url\s*\(/i.test(s.textContent)) { s.textContent = s.textContent.replace(/url\s*\(([^)]*)\)/gi, (m0, u) => { if (cidUrl && u.includes(cidUrl(""))) return m0; blockedImages++; return "none"; }); } s.textContent = s.textContent.replace(/@import[^;]+;/gi, ""); }
  const csp = `default-src 'none'; img-src data: 'self'${allowImages ? " https: http:" : ""}; style-src 'unsafe-inline'; font-src 'none'; media-src 'none'; frame-src 'none'; form-action 'none'`;
  const head = `<meta http-equiv="Content-Security-Policy" content="${csp}"><meta charset="utf-8"><base target="_blank"><style>html,body{margin:0;padding:4px 2px;font-family:"Segoe UI",system-ui,sans-serif;font-size:14px;line-height:1.45;color:#242424;background:#fff;word-wrap:break-word;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}blockquote{border-left:3px solid #ccc;margin:8px 0;padding-left:10px;color:#555}</style>`;
  const body = doc.body ? doc.body.innerHTML : "";
  return { html: `<!doctype html><html><head>${head}</head><body>${body}</body></html>`, blockedImages };
}

// ============================================================================ compose
const compose = {
  timer: null,
  signatureHtml(mode) {
    const s = state.settings || {};
    const id = mode === "new" ? s.defaultSignatureNew : s.defaultSignatureReply;
    const sig = (s.signatures || []).find((x) => x.id === id);
    return sig ? `<div class="qb-signature">${sig.html}</div>` : "";
  },
  quoteHeader(d) {
    const who = (n, a) => `${esc(n || "")}${a ? ` &lt;${esc(a)}&gt;` : ""}`;
    return `<hr style="border:0;border-top:1px solid #e1e1e1;margin:12px 0 8px"><div style="font-size:13px"><b>From:</b> ${who(d.fromName, d.fromAddress)}<br><b>Sent:</b> ${esc(fmtDate(d.date, { long: true }))}<br><b>To:</b> ${esc((d.to || []).join("; "))}${d.cc?.length ? `<br><b>Cc:</b> ${esc(d.cc.join("; "))}` : ""}<br><b>Subject:</b> ${esc(d.subject)}</div><br>`;
  },
  async open({ mode = "new", detail = null, draft = null, to = "", cc = "", subject = "", html = "", attachments = [] } = {}) {
    if (state.compose) {
      if (state.compose.minimized || this.isDocked()) {
        const prev = state.compose;
        await this.saveDraft(true).catch(() => {});
        this.close(true);
        if (prev.draftUid) toast("Your other message was saved to Drafts.");
      } else this.close(true);
    }
    const ref = detail ? { folder: detail.folder || state.open?.folder || state.folder, uid: detail.uid } : null;
    state.compose = { mode, ref, attachments: [...attachments], draftUid: draft ? draft.uid : null, inReplyTo: null, references: null, importance: "normal", plain: false, dirty: false, forwardOf: null, attachMessages: [], minimized: false, popped: false };
    for (const id of ["cTo", "cCc", "cBcc", "cSubject", "cReplyToAddr"]) $(id).value = "";
    $("cEditor").innerHTML = ""; $("cAtts").innerHTML = ""; $("cStatus").textContent = "";
    $("cReadReceipt").checked = false; $("cDeliveryReceipt").checked = false;
    $("cCcRow").classList.add("hidden"); $("cBccRow").classList.add("hidden");
    const from = $("cFrom"); from.innerHTML = "";
    const aliases = [state.account.email, ...(state.settings?.aliases || [])];
    aliases.forEach((a) => from.appendChild(el("option", { value: a, text: `${state.account.displayName ? state.account.displayName + " " : ""}<${a}>` })));
    $("cFromRow").classList.toggle("hidden", aliases.length < 2);
    const me = new Set(aliases.map((a) => a.toLowerCase()));
    const font = prefs.get("composeFont", "");
    $("cEditor").style.fontFamily = font || ""; $("cEditor").style.fontSize = prefs.get("composeSize", "") || "";
    if (mode === "reply" || mode === "replyAll") {
      const fromMe = me.has((detail.fromAddress || "").toLowerCase());
      const replyTo = fromMe ? (detail.to || []).join(", ") : (detail.replyTo || detail.fromAddress);
      $("cTo").value = replyTo;
      if (mode === "replyAll") {
        const cc = [...(fromMe ? [] : detail.to || []), ...(detail.cc || [])].filter((a) => !me.has(a.toLowerCase()) && a.toLowerCase() !== String(replyTo).toLowerCase());
        if (cc.length) { $("cCc").value = [...new Set(cc)].join(", "); $("cCcRow").classList.remove("hidden"); }
      }
      // Reply from the address the message was sent to, when it is one of our aliases.
      const toMe = [...(detail.to || []), ...(detail.cc || [])].map((x) => x.toLowerCase()).find((x) => me.has(x));
      if (toMe) from.value = aliases.find((a) => a.toLowerCase() === toMe) || state.account.email;
      $("cSubject").value = /^\s*re\s*:/i.test(detail.subject) ? detail.subject : "RE: " + detail.subject;
      state.compose.inReplyTo = detail.messageId || null;
      state.compose.references = [detail.references, detail.messageId].filter(Boolean).join(" ") || null;
      $("cEditor").innerHTML = `<div><br></div>${this.signatureHtml("reply")}<div class="qb-quote">${this.quoteHeader(detail)}${quoted(detail)}</div>`;
      $("cTitle").textContent = mode === "replyAll" ? "Reply all" : "Reply";
    } else if (mode === "forward" || mode === "forwardAttach") {
      $("cSubject").value = /^\s*(fw|fwd)\s*:/i.test(detail.subject) ? detail.subject : "FW: " + detail.subject;
      state.compose.forwardOf = ref;
      if (mode === "forward") {
        $("cEditor").innerHTML = `<div><br></div>${this.signatureHtml("reply")}<div class="qb-quote">${this.quoteHeader(detail)}${quoted(detail)}</div>`;
        this.attachOriginals(detail);
      } else {
        $("cEditor").innerHTML = `<div><br></div>${this.signatureHtml("reply")}`;
        state.compose.attachMessages = [ref];
      }
      $("cTitle").textContent = "Forward";
    } else if (mode === "editAsNew") {
      $("cTo").value = (detail.to || []).join(", ");
      if (detail.cc?.length) { $("cCc").value = detail.cc.join(", "); $("cCcRow").classList.remove("hidden"); }
      $("cSubject").value = detail.subject || "";
      $("cEditor").innerHTML = quoted(detail);
      this.attachOriginals(detail);
      $("cTitle").textContent = "New message";
    } else if (mode === "draft" && draft) {
      $("cTo").value = (draft.to || []).join(", "); $("cSubject").value = draft.subject;
      if (draft.cc?.length) { $("cCc").value = draft.cc.join(", "); $("cCcRow").classList.remove("hidden"); }
      $("cEditor").innerHTML = draft.bodyHtml ? sanitizeHtml(draft.bodyHtml, { allowImages: true, folder: draft.folder || state.folder, uid: draft.uid }).html.replace(/^[\s\S]*<body>|<\/body>[\s\S]*$/g, "") : esc(draft.bodyText).replace(/\n/g, "<br>");
      state.compose.inReplyTo = draft.inReplyTo; state.compose.references = draft.references;
      state.compose.importance = draft.importance || "normal";
      this.attachOriginals(draft);
      $("cTitle").textContent = "Draft";
    } else {
      $("cTo").value = to; if (cc) { $("cCc").value = cc; $("cCcRow").classList.remove("hidden"); }
      $("cSubject").value = subject;
      $("cEditor").innerHTML = (html || "<div><br></div>") + this.signatureHtml("new");
      $("cTitle").textContent = "New message";
    }
    this.renderImportance();
    this.renderAtts();
    this.show();
    (mode === "new" || mode === "forward" || mode === "forwardAttach") && !to ? $("cTo").focus() : $("cEditor").focus();
    if (mode === "reply" || mode === "replyAll") placeCaretAtStart($("cEditor"));
    clearInterval(this.timer);
    this.timer = setInterval(() => { if (state.compose && state.compose.dirty && state.capabilities.drafts && prefs.get("autosave", true)) this.saveDraft(true).catch(() => {}); }, 60000);
  },
  /** Downloads the original message's attachments so they travel with a forward / edit-as-new. */
  async attachOriginals(d) {
    const files = (d.attachments || []).filter((a) => !a.inline);
    if (!files.length) return;
    const folder = d.folder || state.folder;
    $("cStatus").textContent = `Attaching ${plural(files.length, "original file")}…`;
    for (const a of files) {
      try {
        const res = await fetch(attachmentUrl(folder, d.uid, { index: a.index }));
        if (!res.ok) throw new Error(res.statusText);
        const blob = await res.blob();
        const base64 = (await readFileDataUrl(blob)).split(",")[1];
        if (!state.compose) return;
        state.compose.attachments.push({ fileName: a.fileName, mimeType: a.mimeType || "application/octet-stream", base64 });
        this.renderAtts();
      } catch (e) { toast(`Could not attach ${a.fileName}: ${e.message}`, { danger: true }); }
    }
    if (state.compose) $("cStatus").textContent = "";
  },
  isDocked() { return $("composeDialog").classList.contains("docked") && $("composeDialog").open; },
  show() {
    const dlg = $("composeDialog");
    const dock = prefs.get("composeDock", true) && innerWidth > 1000 && state.module === "mail" && !state.compose.popped;
    if (dlg.open) dlg.close();
    if (dock) { $("composeDock").appendChild(dlg); dlg.classList.add("docked"); dlg.show(); }
    else { document.body.appendChild(dlg); dlg.classList.remove("docked"); dlg.showModal(); }
    qs("#cPopOut use").setAttribute("href", dock ? "#i-maximize" : "#i-minimize");
    $("cPopOut").title = dock ? "Open in a larger window" : "Dock in the reading pane";
    $("draftChips").innerHTML = "";
    state.compose.minimized = false;
  },
  popOut() { if (!state.compose) return; state.compose.popped = !state.compose.popped; this.show(); },
  minimize() {
    if (!state.compose) return;
    state.compose.minimized = true;
    $("composeDialog").close();
    const subject = $("cSubject").value.trim() || "(no subject)";
    const chip = el("div", { class: "draft-chip", title: "Continue writing" }, icon("compose"), el("span", { text: "Draft: " + subject }),
      el("button", { class: "icon-btn", title: "Discard", onclick: (e) => { e.stopPropagation(); if (confirm("Discard this message?")) this.close(); } }, icon("close")));
    chip.onclick = () => this.show();
    $("draftChips").innerHTML = ""; $("draftChips").appendChild(chip);
  },
  close(silent = false) {
    clearInterval(this.timer);
    const dlg = $("composeDialog"); if (dlg.open) dlg.close();
    $("draftChips").innerHTML = ""; $("acPopup").classList.add("hidden");
    state.compose = null;
    if (!silent && state.open && innerWidth > 1000) $("reading").classList.remove("hidden");
  },
  snapshot() {
    return { compose: { ...state.compose }, to: $("cTo").value, cc: $("cCc").value, bcc: $("cBcc").value, subject: $("cSubject").value, html: $("cEditor").innerHTML, from: $("cFrom").value, replyToAddr: $("cReplyToAddr").value };
  },
  restore(snap) {
    if (state.compose) this.close(true);
    state.compose = { ...snap.compose, minimized: false };
    $("cTo").value = snap.to; $("cCc").value = snap.cc; $("cBcc").value = snap.bcc; $("cSubject").value = snap.subject; $("cEditor").innerHTML = snap.html; $("cReplyToAddr").value = snap.replyToAddr || "";
    $("cCcRow").classList.toggle("hidden", !snap.cc); $("cBccRow").classList.toggle("hidden", !snap.bcc);
    this.renderAtts(); this.renderImportance();
    $("cStatus").textContent = "";
    this.show();
    if (snap.from) $("cFrom").value = snap.from;
  },
  renderImportance() {
    const imp = state.compose?.importance || "normal";
    $("cImportanceHigh").classList.toggle("active", imp === "high");
    $("cImportanceLow").classList.toggle("active", imp === "low");
    $("cImportance").value = imp;
    const b = $("cImpBadge");
    b.classList.toggle("hidden", imp === "normal");
    b.className = "badge " + (imp === "high" ? "danger" : "info") + (imp === "normal" ? " hidden" : "");
    b.textContent = imp === "high" ? "High importance" : "Low importance";
  },
  setImportance(v) { if (!state.compose) return; state.compose.importance = state.compose.importance === v ? "normal" : v; state.compose.dirty = true; this.renderImportance(); },
  recipients(field) { return mailplus.expandRecipients(splitAddresses($(field).value)); },
  payload() {
    const c = state.compose;
    const editor = $("cEditor");
    const font = prefs.get("composeFont", "") || "Segoe UI, system-ui, sans-serif";
    const html = c.plain ? null : `<!doctype html><html><body style="font-family:${font};font-size:${prefs.get("composeSize", "") || "14px"}">${editor.innerHTML}</body></html>`;
    const fromAddr = $("cFrom").value;
    return {
      to: this.recipients("cTo"), cc: this.recipients("cCc"), bcc: this.recipients("cBcc"),
      subject: $("cSubject").value.trim(), body: htmlToPlain(editor), html, attachments: c.attachments,
      inReplyTo: c.inReplyTo, references: c.references,
      replyTo: (c.mode === "reply" || c.mode === "replyAll") ? c.ref : null,
      forwardOf: c.forwardOf, attachMessages: c.attachMessages,
      draftUid: c.draftUid,
      from: fromAddr && fromAddr.toLowerCase() !== state.account.email.toLowerCase() ? fromAddr : null,
      replyToAddress: $("cReplyToAddr").value.trim() || null,
      importance: c.importance !== "normal" ? c.importance : null,
      readReceipt: $("cReadReceipt").checked, deliveryReceipt: $("cDeliveryReceipt").checked,
    };
  },
  validate(p) {
    if (!p.to.length && !p.cc.length && !p.bcc.length) { toast("Add at least one recipient.", { danger: true }); $("cTo").focus(); return false; }
    const bad = [...p.to, ...p.cc, ...p.bcc].find((a) => !isEmail(a));
    if (bad) { toast(`"${bad}" is not a valid address. Use Check names (Options) to resolve contact names.`, { danger: true }); return false; }
    if (!p.subject && !confirm("Send without a subject?")) return false;
    if (/\b(attached|attachment|enclosed)\b/i.test(p.body.split(/\n(From|On .+ wrote):/)[0]) && !p.attachments.length && !p.attachMessages.length && !confirm("You mention an attachment but nothing is attached. Send anyway?")) return false;
    return true;
  },
  async send({ sendAt = null } = {}) {
    if (!state.compose) return;
    const p = this.payload();
    if (!this.validate(p)) return;
    const undo = Number(prefs.get("undoSend", 10)) || 0;
    const scheduled = !!sendAt && sendAt > Date.now() + 60000;
    if (scheduled) p.sendAt = sendAt;
    else if (undo > 0) p.sendAt = Date.now() + undo * 1000;
    $("cSend").disabled = true; $("cStatus").innerHTML = '<span class="spinner"></span> Sending…';
    try {
      const r = await api("/api/send", { method: "POST", body: p });
      const snap = this.snapshot();
      const reply = p.replyTo, fwd = p.forwardOf;
      this.close();
      mailplus.rememberRecipients([...p.to, ...p.cc, ...p.bcc]);
      if (r.status === "scheduled" && !scheduled) {
        toast("Sending…", { action: "Undo", timeout: undo * 1000, onAction: async () => {
          try { await api(`/api/outbox/${encodeURIComponent(r.id)}`, { method: "DELETE" }); this.restore(snap); toast("Sending cancelled — your message is open again."); mailplus.loadOutbox(); }
          catch { toast("Too late — the message has already been sent.", { danger: true }); }
        } });
        setTimeout(() => { folders.load(); mailplus.loadOutbox(); }, undo * 1000 + 2500);
      } else if (r.status === "scheduled") {
        toast(`Scheduled for ${fmtDate(r.sendAt, { long: true })}`, { action: "View", onAction: () => list.load(SCHEDULED) });
      } else toast("Message sent");
      mailplus.loadOutbox();
      if (reply) list.patchLocal([{ uid: reply.uid, folder: reply.folder }], { answered: true });
      if (fwd) list.patchLocal([{ uid: fwd.uid, folder: fwd.folder }], { forwarded: true });
      folders.load();
    } catch (e) { $("cStatus").textContent = ""; toast("Send failed: " + e.message, { danger: true, timeout: 10000 }); }
    finally { $("cSend").disabled = false; }
  },
  async saveDraft(quiet = false) {
    if (!state.compose) return;
    if (!state.capabilities.drafts) { if (!quiet) toast("Drafts need an IMAP account.", { danger: true }); return; }
    const p = this.payload();
    if (!quiet) $("cStatus").innerHTML = '<span class="spinner"></span> Saving draft…';
    try {
      const r = await api("/api/draft", { method: "POST", body: { uid: state.compose.draftUid, to: p.to, cc: p.cc, bcc: p.bcc, subject: p.subject, body: p.body, html: p.html, inReplyTo: p.inReplyTo, references: p.references, attachments: p.attachments, importance: p.importance } });
      if (!state.compose) return;
      state.compose.draftUid = r.uid > 0 ? r.uid : null;
      state.compose.dirty = false;
      $("cStatus").textContent = "Draft saved " + new Date().toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
      folders.load();
    } catch (e) { $("cStatus").textContent = ""; if (!quiet) toast("Could not save draft: " + e.message, { danger: true }); }
  },
  async addFiles(files) {
    for (const f of files) {
      if (f.size > 25 * 1048576) { toast(`${f.name} is larger than 25 MB.`, { danger: true }); continue; }
      const base64 = (await readFileDataUrl(f)).split(",")[1];
      state.compose.attachments.push({ fileName: f.name, mimeType: f.type || "application/octet-stream", base64 });
    }
    state.compose.dirty = true;
    this.renderAtts();
  },
  renderAtts() {
    const host = $("cAtts"); host.innerHTML = "";
    const c = state.compose; if (!c) return;
    c.attachments.forEach((a, i) => host.appendChild(el("span", { class: "att" }, icon("attach"), el("span", { text: a.fileName }), el("span", { class: "size", text: fmtSize(Math.round(a.base64.length * 0.75)) }), el("button", { title: "Remove", html: "&#x2715;", onclick: () => { c.attachments.splice(i, 1); this.renderAtts(); } }))));
    (c.attachMessages || []).forEach((r, i) => host.appendChild(el("span", { class: "att" }, icon("mail"), el("span", { text: "Original message (.eml)" }), el("button", { title: "Remove", html: "&#x2715;", onclick: () => { c.attachMessages.splice(i, 1); this.renderAtts(); } }))));
  },
};
function quoted(detail) {
  if (detail.bodyHtml) return sanitizeHtml(detail.bodyHtml, { allowImages: true, folder: detail.folder || state.open?.folder || state.folder, uid: detail.uid }).html.replace(/^[\s\S]*<body>|<\/body>[\s\S]*$/g, "");
  return esc(detail.bodyText || "").replace(/\n/g, "<br>");
}
function htmlToPlain(node) {
  const clone = node.cloneNode(true);
  clone.querySelectorAll("br").forEach((b) => b.replaceWith("\n"));
  clone.querySelectorAll("div, p, li, tr, h1, h2, h3, h4, blockquote, hr").forEach((b) => { b.prepend("\n"); });
  clone.querySelectorAll("hr").forEach((b) => b.replaceWith("\n________________________________\n"));
  clone.querySelectorAll("blockquote").forEach((b) => { b.textContent = b.textContent.split("\n").map((l) => "> " + l).join("\n"); });
  clone.querySelectorAll("a[href]").forEach((a) => { if (a.href && a.textContent.trim() && a.textContent.trim() !== a.href && !a.href.startsWith("mailto:")) a.append(` <${a.href}>`); });
  return clone.textContent.replace(/\n{3,}/g, "\n\n").trim();
}
function placeCaretAtStart(node) {
  const range = document.createRange(); range.setStart(node, 0); range.collapse(true);
  const sel = getSelection(); sel.removeAllRanges(); sel.addRange(range);
}

// ============================================================================ rule editor (shared)
const FIELDS = [["SENDER", "Sender address"], ["SENDER_NAME", "Sender name"], ["SENDER_DOMAIN", "Sender domain"], ["SUBJECT", "Subject"], ["BODY", "Message body"], ["RECIPIENT", "To"], ["CC", "Cc"], ["TO_OR_CC", "To or Cc"], ["ATTACHMENT_NAME", "Attachment name"], ["HAS_ATTACHMENT", "Has attachment"], ["IMPORTANCE", "Importance"]];
const OPERATORS = [["CONTAINS", "contains"], ["NOT_CONTAINS", "does not contain"], ["EQUALS", "is exactly"], ["STARTS_WITH", "starts with"], ["ENDS_WITH", "ends with"], ["MATCHES_REGEX", "matches regex"]];
const ACTIONS = [["MOVE_TO_FOLDER", "Move to folder"], ["COPY_TO_FOLDER", "Copy to folder"], ["DELETE", "Delete"], ["MARK_READ", "Mark as read"], ["FLAG", "Flag"], ["PIN", "Pin"], ["CATEGORIZE", "Categorize"], ["FORWARD_TO", "Forward to (original attached)"], ["MARK_SAFE", "Never treat as junk"]];

function describeCriterion(c) {
  if (c.field === "HAS_ATTACHMENT") return c.value === "false" ? "has no attachment" : "has an attachment";
  if (c.field === "IMPORTANCE") return `importance is ${c.value}`;
  const f = (FIELDS.find((x) => x[0] === c.field) || [c.field, c.field])[1];
  const o = (OPERATORS.find((x) => x[0] === c.operator) || [c.operator, c.operator])[1];
  return `${f} ${o} "${c.value}"`;
}
function describeAction(r) {
  if (r.action === "MOVE_TO_FOLDER") return `move to ${r.targetFolder || "Junk"}`;
  if (r.action === "COPY_TO_FOLDER") return `copy to ${r.targetFolder}`;
  if (r.action === "CATEGORIZE") return `categorize as ${(r.categories || []).join(", ")}`;
  if (r.action === "FORWARD_TO") return `forward to ${r.forwardTo}`;
  return (ACTIONS.find((x) => x[0] === r.action) || ["", r.action])[1].toLowerCase();
}

/** Renders an editable rule form into [host]; returns { read() } producing a RuleDto. */
function ruleForm(host, rule, { rationales = {} } = {}) {
  host.innerHTML = "";
  const name = el("input", { type: "text", value: rule.name || "" });
  const logic = select([["OR", "any condition matches (OR)"], ["AND", "all conditions match (AND)"]], rule.logic || "OR");
  const action = select(ACTIONS, rule.action || "MOVE_TO_FOLDER");
  const folderOpts = [["Junk", "Junk Email"], ...state.folders.filter((f) => f.role !== "junk" && f.role !== "inbox").map((f) => [f.path, f.name])];
  if (rule.targetFolder && !folderOpts.some((o) => o[0] === rule.targetFolder)) folderOpts.push([rule.targetFolder, rule.targetFolder]);
  const target = select(folderOpts, rule.targetFolder || "Junk");
  const targetWrap = el("div", { class: "field" }, el("label", { text: "Folder" }), target);
  const cats = el("div", { class: "cat-pick" });
  for (const c of state.settings.categories || []) {
    const cb = el("input", { type: "checkbox", value: c.name }); cb.checked = (rule.categories || []).includes(c.name);
    cats.appendChild(el("label", { class: "check", style: { margin: "2px 0" } }, cb, el("span", { class: "cat-chip", style: { background: c.color } }, c.name)));
  }
  const catWrap = el("div", { class: "field" }, el("label", { text: "Categories" }), cats);
  const fwd = el("input", { type: "text", value: rule.forwardTo || "", placeholder: "name@example.com" });
  const fwdWrap = el("div", { class: "field" }, el("label", { text: "Forward to" }), fwd);
  const sync = () => {
    targetWrap.classList.toggle("hidden", !["MOVE_TO_FOLDER", "COPY_TO_FOLDER"].includes(action.value));
    catWrap.classList.toggle("hidden", action.value !== "CATEGORIZE");
    fwdWrap.classList.toggle("hidden", action.value !== "FORWARD_TO");
  };
  action.onchange = sync; sync();
  const crit = el("div", { class: "criteria" });
  const rows = [];
  const addRow = (c, why) => {
    const field = select(FIELDS, c.field || "SENDER"), op = select(OPERATORS, c.operator || "CONTAINS");
    let val;
    const valueFor = () => {
      if (field.value === "HAS_ATTACHMENT") return select([["true", "yes"], ["false", "no"]], c.value === "false" ? "false" : "true");
      if (field.value === "IMPORTANCE") return select([["high", "high"], ["normal", "normal"], ["low", "low"]], c.value || "high");
      return el("input", { type: "text", value: c.value || "", placeholder: "text to match" });
    };
    val = valueFor();
    const entry = { field, op, get val() { return val; } };
    const row = el("div", { class: "crit" }, field, op, val, el("button", { class: "icon-btn", title: "Remove condition", onclick: () => { row.remove(); rows.splice(rows.indexOf(entry), 1); } }, icon("close")));
    field.onchange = () => { const nv = valueFor(); row.replaceChild(nv, val); val = nv; op.classList.toggle("hidden", ["HAS_ATTACHMENT", "IMPORTANCE"].includes(field.value)); };
    op.classList.toggle("hidden", ["HAS_ATTACHMENT", "IMPORTANCE"].includes(field.value));
    if (why) row.appendChild(el("div", { class: "why", html: `<b>Why:</b> ${esc(why)}` }));
    rows.push(entry); crit.appendChild(row);
  };
  (rule.criteria || []).forEach((c, i) => addRow(c, rationales[i]));
  if (!rows.length) addRow({ field: "SENDER_DOMAIN", operator: "ENDS_WITH", value: "" });
  host.append(
    el("div", { class: "field" }, el("label", { text: "Rule name" }), name),
    el("div", { class: "field" }, el("label", { text: "Apply when" }), logic),
    el("div", { class: "field" }, el("label", { text: "Conditions" }), crit, el("div", {}, el("button", { class: "btn sm", onclick: () => addRow({}) }, icon("plus"), "Add condition"))),
    el("div", { class: "grid-2" }, el("div", { class: "field" }, el("label", { text: "Action" }), action), targetWrap, catWrap, fwdWrap)
  );
  return {
    read() {
      return {
        id: rule.id || ("rule-" + Math.random().toString(36).slice(2, 10)),
        name: name.value.trim() || "Untitled rule", enabled: rule.enabled !== false, logic: logic.value,
        criteria: rows.map((r) => ({ field: r.field.value, operator: ["HAS_ATTACHMENT", "IMPORTANCE"].includes(r.field.value) ? "EQUALS" : r.op.value, value: String(r.val.value).trim() })).filter((c) => c.value),
        action: action.value, targetFolder: ["MOVE_TO_FOLDER", "COPY_TO_FOLDER"].includes(action.value) ? target.value : null,
        categories: action.value === "CATEGORIZE" ? qsa("input:checked", cats).map((x) => x.value) : [],
        forwardTo: action.value === "FORWARD_TO" ? fwd.value.trim() : "",
        priority: rule.priority ?? 100, createdBy: rule.createdBy || "user", createdAt: rule.createdAt || Date.now(), note: rule.note || "", hitCount: rule.hitCount || 0,
      };
    },
  };
}

// ============================================================================ analysis dialog
const analysis = {
  form: null, result: null, message: null,
  async open(detail) {
    this.message = detail;
    const body = $("anBody"), foot = $("anFoot");
    body.innerHTML = ""; foot.innerHTML = "";
    body.appendChild(el("div", { style: { padding: "30px", textAlign: "center" } }, el("span", { class: "spinner" }), el("div", { class: "muted", style: { marginTop: "10px" }, text: state.settings.ollama?.enabled ? `Analysing headers, links and content, then asking the local ${state.settings.ollama.model} model…` : "Analysing headers, sending servers, links and content…" })));
    $("analysisDialog").showModal();
    try {
      const r = await api("/api/analyze", { method: "POST", body: { folder: detail.folder || state.open?.folder || state.folder, uid: detail.uid } });
      this.result = r; this.render(r);
    } catch (e) { body.innerHTML = `<div class="error-box">${esc(e.message)}</div>`; }
  },
  render(r) {
    const v = r.verdict, b = v.brand || {}, p = r.proposal;
    const body = $("anBody"), foot = $("anFoot");
    body.innerHTML = "";
    const cls = v.level === "SPAM" ? "danger" : v.level === "SUSPICIOUS" ? "warn" : "ok";
    const left = el("div", {});
    const verdictCard = el("div", { class: "card" }, el("h4", { text: "Verdict" }),
      el("div", { class: "score " + cls }, el("div", { class: "num", text: String(v.score) }), el("div", { class: "bar" }, el("i", { style: { width: v.score + "%" } })), el("div", { style: { fontWeight: 700 }, text: v.level === "SPAM" ? "Junk" : v.level === "SUSPICIOUS" ? "Suspicious" : "Looks legitimate" })),
      el("div", { text: p.verdictSummary }));
    const who = el("div", { class: "card", style: { marginTop: "12px" } }, el("h4", { text: "Who is it really from?" }),
      el("dl", { class: "kv", style: { marginTop: 0 } },
        el("dt", { text: "Claims to be" }), el("dd", { text: b.claimedBrand ? `${b.claimedBrand}${b.claimSource ? ` (seen in the ${b.claimSource})` : ""}` : "No organisation claim detected" }),
        el("dt", { text: "Actually sent from" }), el("dd", { style: { fontWeight: 700, color: b.mismatch ? "var(--danger)" : b.verified ? "var(--ok)" : "inherit" }, text: b.senderDomain || "(unknown)" }),
        ...(b.legitimateDomains?.length ? [el("dt", { text: `${b.claimedBrand} really uses` }), el("dd", { text: b.legitimateDomains.slice(0, 5).join(", ") })] : []),
        el("dt", { text: "Assessment" }), el("dd", { text: b.explanation || "—" })));
    left.append(verdictCard, who);
    if (r.llm) {
      const l = r.llm;
      left.appendChild(el("div", { class: "card", style: { marginTop: "12px" } }, el("h4", { text: `Local AI model (${l.model})` }),
        l.error ? el("div", { class: "muted", text: "Unavailable: " + l.error }) :
          el("div", {}, el("div", {}, el("b", { text: l.isSpam === true ? "Spam / scam" : l.isSpam === false ? "Legitimate" : "Unsure" }), l.confidence != null ? el("span", { class: "muted", text: ` · ${Math.round(l.confidence * 100)}% confident` }) : null),
            l.claimedOrganisation ? el("div", { text: `Claims to be: ${l.claimedOrganisation}` }) : null, el("div", { class: "small", text: l.summary }))));
    }
    const det = reading.detailsBlock(v, "All signals considered"); det.open = false;
    left.appendChild(el("div", { class: "card", style: { marginTop: "12px" } }, det));
    const right = el("div", {});
    right.appendChild(el("div", { class: "card" }, el("h4", { text: "Proposed rule" }), el("p", { class: "small muted", style: { margin: "0 0 10px" }, text: p.summary }), el("div", { id: "anRuleForm" })));
    const preview = el("div", { id: "anPreview", class: "small muted", style: { marginTop: "10px" } });
    right.appendChild(preview);
    body.appendChild(el("div", { class: "analysis-grid" }, left, right));
    this.form = ruleForm(qs("#anRuleForm"), p.rule, { rationales: Object.fromEntries(p.criteria.map((c, i) => [i, c.rationale])) });
    const inJunk = folders.byPath(this.message?.folder)?.role === "junk";
    const alsoThis = el("label", { class: "check", style: { margin: 0 } }, el("input", { type: "checkbox", checked: v.level !== "CLEAN" && !inJunk }), el("span", { text: "Also move this message to Junk now" }));
    foot.append(
      el("span", { class: "grow" }, alsoThis),
      el("button", { class: "btn", onclick: () => this.preview() }, icon("eye"), "Preview matches"),
      el("button", { class: "btn", onclick: () => this.save(false, alsoThis.querySelector("input").checked) }, "Save rule"),
      el("button", { class: "btn primary", onclick: () => this.save(true, alsoThis.querySelector("input").checked) }, icon("check"), "Save & apply to current mail")
    );
  },
  async preview() {
    const rule = this.form.read();
    if (!rule.criteria.length) return toast("Add at least one condition.", { danger: true });
    const host = qs("#anPreview"); host.innerHTML = '<span class="spinner"></span> Checking the current folder…';
    try {
      const r = await api("/api/rules/preview", { method: "POST", body: { rule, folder: state.folder === SCHEDULED ? "INBOX" : state.folder, limit: 300 } });
      host.innerHTML = "";
      host.appendChild(el("div", {}, el("b", { text: `${r.matches.length} of the ${r.scanned} most recent messages in ${folders.byPath(state.folder)?.name || state.folder} match.` }), r.matches.length === r.scanned && r.scanned > 5 ? el("div", { class: "error-box", style: { marginTop: "6px" }, text: "This rule matches everything — it is probably too broad." }) : null));
      if (r.matches.length) {
        const pl = el("div", { class: "preview-list" });
        r.matches.slice(0, 50).forEach((m) => pl.appendChild(el("div", { class: "row" }, el("span", { text: `${m.fromName || m.fromAddress}`, title: m.fromAddress }), el("span", { text: m.subject }))));
        host.appendChild(pl);
      }
    } catch (e) { host.textContent = "Preview failed: " + e.message; }
  },
  async save(apply, alsoThis) {
    const rule = this.form.read();
    if (!rule.criteria.length) return toast("Add at least one condition.", { danger: true });
    try {
      const s = state.settings;
      await settingsSave({ ...s, rules: [...s.rules.filter((r) => r.id !== rule.id), rule] });
      let msg = `Rule "${rule.name}" saved`;
      if (apply) {
        const r = await api("/api/rules/apply", { method: "POST", body: { rule, folder: state.folder, limit: 300 } });
        msg += ` · applied to ${plural(r.ok, "message")}`;
        if (r.errors?.length) toast(r.errors[0], { danger: true });
      }
      if (alsoThis && this.message && folders.byPath(this.message.folder)?.role !== "junk") await actions.junk([this.message], true);
      $("analysisDialog").close();
      toast(msg, { action: "Manage rules", onAction: () => settingsUI.open("rules") });
      await list.fetch({ silent: true }); folders.load();
    } catch (e) { toast("Could not save: " + e.message, { danger: true }); }
  },
};

// ============================================================================ settings
const settingsUI = {
  tab: "general",
  open(tab = "general") { this.tab = tab; this.render(); $("settingsDialog").showModal(); },
  render() {
    qsa("#stNav button").forEach((b) => b.classList.toggle("active", b.dataset.tab === this.tab));
    const host = $("stBody"); host.innerHTML = "";
    const own = this["render_" + this.tab];
    if (own) own.call(this, host);
    else mailplus.settings["render_" + this.tab]?.call(mailplus.settings, host);
  },
  row(title, desc, control) { return el("div", { class: "setting-row" }, el("div", { class: "txt" }, el("b", { text: title }), desc ? el("span", { text: desc }) : null), control); },
  toggle(checked, onchange) { const i = el("input", { type: "checkbox" }); i.checked = !!checked; i.onchange = () => onchange(i.checked); return el("label", { class: "switch" }, i, el("span")); },
  pref(key, def, options, after) {
    const s = select(options, String(prefs.get(key, def)), { style: { width: "240px" } });
    s.onchange = () => { const v = s.value; prefs.set(key, /^-?\d+$/.test(v) ? parseInt(v, 10) : v === "true" ? true : v === "false" ? false : v); applyTheme(); after && after(); };
    return s;
  },
  async patchSpam(patch) { const s = state.settings; await settingsSave({ ...s, spam: { ...s.spam, ...patch } }); },

  render_general(host) {
    host.append(el("h3", { text: "Appearance" }), el("p", { text: "Theme, colours and the start-up view. Stored in this browser." }));
    host.appendChild(this.row("Theme", "", this.pref("theme", "system", [["system", "Use system setting"], ["light", "Light"], ["dark", "Dark"]])));
    const sw = el("div", { class: "swatches" });
    const accents = [["blue", "#0f6cbd", "Blue"], ["navy", "#0c3b5e", "Navy"], ["teal", "#038387", "Teal"], ["green", "#107c10", "Green"], ["purple", "#8764b8", "Purple"], ["pink", "#c239b3", "Pink"], ["red", "#c4314b", "Red"], ["orange", "#ca5010", "Orange"], ["graphite", "#4f4f4f", "Graphite"]];
    for (const [id, color, label] of accents) sw.appendChild(el("button", { class: "swatch" + (prefs.get("accent", "blue") === id ? " active" : ""), title: label, style: { background: color }, onclick: () => { prefs.set("accent", id); applyTheme(); this.render(); } }));
    host.appendChild(this.row("Colour theme", "Title bar, buttons and highlights.", sw));
    host.appendChild(this.row("Open at start-up", "", this.pref("startModule", "mail", [["mail", "Mail"], ["calendar", "Calendar"], ["people", "People"], ["todo", "To Do"], ["last", "Where I left off"]])));
    host.appendChild(this.row("Reading pane text size", "", this.pref("readZoom", 100, [["80", "80%"], ["90", "90%"], ["100", "100%"], ["110", "110%"], ["125", "125%"], ["150", "150%"]])));
  },
  render_layout(host) {
    host.append(el("h3", { text: "Layout" }), el("p", { text: "How the message list and reading pane look and behave. Stored in this browser." }));
    host.appendChild(this.row("Focused Inbox", "Sort the Inbox into Focused and Other — newsletters and notifications go to Other.", this.toggle(prefs.get("focusedInbox", true) && state.settings.focused?.enabled !== false, async (v) => { prefs.set("focusedInbox", v); await settingsSave({ ...state.settings, focused: { ...(state.settings.focused || {}), enabled: v } }); list.render(); })));
    host.appendChild(this.row("Show messages as conversations", "Group replies with the message they answer.", this.toggle(prefs.get("conversations", true), (v) => { prefs.set("conversations", v); list.render(); })));
    host.appendChild(this.row("Newest message on top in conversations", "", this.toggle(prefs.get("newestOnTop", true), (v) => prefs.set("newestOnTop", v))));
    host.appendChild(this.row("Reading pane", "", this.pref("pane", "right", [["right", "Show on the right"], ["bottom", "Show at the bottom"], ["off", "Hide (open messages full-width)"]])));
    host.appendChild(this.row("Message list density", "", this.pref("density", "comfortable", [["comfortable", "Comfortable"], ["compact", "Compact"]])));
    host.appendChild(this.row("Show message preview text", "", this.toggle(prefs.get("showPreview", true), (v) => { prefs.set("showPreview", v); applyTheme(); })));
    host.appendChild(this.row("Show sender pictures", "", this.toggle(prefs.get("showAvatars", true), (v) => { prefs.set("showAvatars", v); applyTheme(); })));
    host.appendChild(this.row("Mark as read when opened", "", this.pref("markReadDelay", 1, [["0", "Immediately"], ["1", "After 1 second"], ["5", "After 5 seconds"], ["-1", "Never (mark manually)"]])));
    host.appendChild(this.row("After moving or deleting an item", "", this.pref("openNext", true, [["true", "Open the next item"], ["false", "Return to the list"]])));
    host.appendChild(this.row("Check for new mail", "", this.pref("autoRefresh", 60, [["30", "Every 30 seconds"], ["60", "Every minute"], ["300", "Every 5 minutes"], ["0", "Manual only"]], () => list.schedulePoll())));
    host.appendChild(this.row("Search in", "Where the search box looks first.", this.pref("searchScope", "all", [["all", "All folders"], ["folder", "The current folder"]])));
    host.appendChild(this.row("Show remote images from verified brands", "Load images automatically when the sender is a genuine, authenticated brand domain.", this.toggle(prefs.get("imagesVerified", false), (v) => prefs.set("imagesVerified", v))));
    host.appendChild(this.row("Show remote images from my contacts", "", this.toggle(prefs.get("imagesContacts", false), (v) => prefs.set("imagesContacts", v))));
    const f = state.settings.focused || {};
    if ((f.alwaysFocused || []).length || (f.alwaysOther || []).length) {
      host.appendChild(el("h4", { text: "Focused Inbox overrides" }));
      const box = el("div", { class: "list-editor" });
      for (const [key, label] of [["alwaysFocused", "Always Focused"], ["alwaysOther", "Always Other"]]) for (const v of f[key] || []) box.appendChild(el("div", { class: "item" }, el("span", { text: `${label}: ${v}` }), el("button", { class: "icon-btn", onclick: async () => { await settingsSave({ ...state.settings, focused: { ...f, [key]: f[key].filter((x) => x !== v) } }); this.render(); list.fetch({ silent: true }); } }, icon("close"))));
      host.appendChild(box);
    }
  },
  render_compose(host) {
    host.append(el("h3", { text: "Compose and reply" }), el("p", { text: "Message format, undo send and drafts." }));
    host.appendChild(this.row("Undo send", "Hold messages for a few seconds so you can cancel them.", this.pref("undoSend", 10, [["0", "Off"], ["5", "5 seconds"], ["10", "10 seconds"], ["20", "20 seconds"], ["30", "30 seconds"]])));
    host.appendChild(this.row("Write new messages and replies", "", this.pref("composeDock", true, [["true", "In the reading pane"], ["false", "In a separate window"]])));
    host.appendChild(this.row("Default font", "", this.pref("composeFont", "", [["", "Segoe UI / system"], ["Georgia, 'Times New Roman', serif", "Serif"], ["Arial, Helvetica, sans-serif", "Arial"], ["Calibri, Carlito, sans-serif", "Calibri"], ["Verdana, sans-serif", "Verdana"], ["Consolas, 'Courier New', monospace", "Fixed width"]])));
    host.appendChild(this.row("Default size", "", this.pref("composeSize", "", [["", "14 px"], ["12px", "12 px"], ["13px", "13 px"], ["15px", "15 px"], ["16px", "16 px"], ["18px", "18 px"]])));
    host.appendChild(this.row("Save drafts automatically", "Every minute while you write (IMAP accounts).", this.toggle(prefs.get("autosave", true), (v) => prefs.set("autosave", v))));
    host.appendChild(this.row("Warn about forgotten attachments", "", this.toggle(prefs.get("attachWarn", true), (v) => prefs.set("attachWarn", v))));
    host.appendChild(el("h4", { text: "Signatures, templates and the other compose settings have their own pages." }));
  },

  render_junk(host) {
    const s = state.settings.spam;
    host.append(el("h3", { text: "Junk email protection" }), el("p", { text: "New mail is checked against open DNS blocklists, sender authentication results, the impersonation detector, your rules and a classifier that learns from your Junk / Not junk decisions. Nothing leaves your server except plain DNS queries." }));
    host.appendChild(this.row("Junk protection", "Analyse incoming mail and show verdicts.", this.toggle(s.enabled, (v) => this.patchSpam({ enabled: v }))));
    host.appendChild(this.row("Move junk to the Junk folder automatically", "When off, junk is only flagged in the Inbox.", this.toggle(s.autoMoveToJunk, (v) => this.patchSpam({ autoMoveToJunk: v }))));
    host.appendChild(this.row("Scan mail already in the Inbox", "On first sign-in, existing messages are analysed and filed too (otherwise only new arrivals are).", this.toggle(s.scanExisting, (v) => this.patchSpam({ scanExisting: v }))));
    host.appendChild(this.row("A confirmed blocklist hit is enough to call it junk", "Strong lists only (Spamhaus, SpamCop, Barracuda, or the sender's own domain on a domain list).", this.toggle(s.blacklistHitIsSpam, (v) => this.patchSpam({ blacklistHitIsSpam: v }))));
    host.appendChild(this.row("Impersonating a known brand is enough to call it junk", "e.g. \"PayPal\" in the sender name but sent from another domain.", this.toggle(s.brandMismatchIsSpam, (v) => this.patchSpam({ brandMismatchIsSpam: v }))));
    host.appendChild(this.row("Brand / organisation impersonation detection", "", this.toggle(s.brandDetection, (v) => this.patchSpam({ brandDetection: v }))));
    host.appendChild(this.row("Learned (Bayesian) classifier", "", this.toggle(s.useBayes, (v) => this.patchSpam({ useBayes: v }))));
    host.appendChild(this.row("Check link domains against domain blocklists", "", this.toggle(s.checkLinkDomains, (v) => this.patchSpam({ checkLinkDomains: v }))));
    host.appendChild(el("h4", { text: "Thresholds" }));
    const slider = (label, key, min, max) => {
      const r = el("input", { type: "range", min, max, value: s[key] }); const out = el("output", { text: s[key] });
      r.oninput = () => (out.textContent = r.value); r.onchange = () => this.patchSpam({ [key]: parseInt(r.value, 10) });
      return el("div", { class: "setting-row" }, el("div", { class: "txt", style: { flex: "0 0 200px" } }, el("b", { text: label })), el("div", { class: "range-row", style: { flex: 1 } }, r, out));
    };
    host.append(slider("Junk at score ≥", "spamThreshold", 30, 100), slider("Suspicious at score ≥", "suspiciousThreshold", 5, 80));
    host.appendChild(el("h4", { text: "DNS blocklists (free, open, no API key)" }));
    const table = el("table", { class: "table" }, el("tr", {}, el("th", { text: "On" }), el("th", { text: "List" }), el("th", { text: "Zone" }), el("th", { text: "Type" }), el("th", { text: "Weight" }), el("th")));
    s.blacklists.forEach((b, i) => {
      const on = el("input", { type: "checkbox" }); on.checked = b.enabled;
      on.onchange = () => { const bl = s.blacklists.map((x, j) => (j === i ? { ...x, enabled: on.checked } : x)); this.patchSpam({ blacklists: bl }); };
      const w = el("input", { type: "number", min: 5, max: 100, value: b.weight });
      w.onchange = () => { const bl = s.blacklists.map((x, j) => (j === i ? { ...x, weight: parseInt(w.value, 10) || 30 } : x)); this.patchSpam({ blacklists: bl }); };
      table.appendChild(el("tr", {}, el("td", {}, on), el("td", {}, b.homepage ? el("a", { href: b.homepage, target: "_blank", rel: "noopener", text: b.label }) : el("span", { text: b.label })), el("td", { class: "mono", text: b.zone }), el("td", { text: b.kind === "ip" ? "sending IP" : "domain / link" }), el("td", {}, w),
        el("td", {}, b.builtIn ? null : el("button", { class: "icon-btn", title: "Remove", onclick: () => this.patchSpam({ blacklists: s.blacklists.filter((_, j) => j !== i) }).then(() => this.render()) }, icon("close")))));
    });
    host.appendChild(table);
    const addLabel = el("input", { type: "text", placeholder: "Name" }), addZone = el("input", { type: "text", placeholder: "zone, e.g. bl.example.org" }), addKind = select([["ip", "sending IP"], ["domain", "domain / link"]], "ip");
    host.appendChild(el("div", { class: "list-editor" }, el("div", { class: "add" }, addLabel, addZone, addKind, el("button", { class: "btn sm", onclick: () => {
      if (!addZone.value.trim()) return;
      const id = "custom-" + addZone.value.trim().toLowerCase().replace(/[^a-z0-9]/g, "-");
      this.patchSpam({ blacklists: [...s.blacklists, { id, label: addLabel.value.trim() || addZone.value.trim(), zone: addZone.value.trim().toLowerCase(), kind: addKind.value, weight: 30, enabled: true, builtIn: false, homepage: "" }] }).then(() => this.render());
    } }, icon("plus"), "Add list"))));
    host.appendChild(el("p", { class: "small muted", text: "Spamhaus refuses queries that arrive through open public resolvers (Google 8.8.8.8, Cloudflare 1.1.1.1). If its status shows \"refused\", point the server at your ISP's or router's resolver, or run a local one such as Unbound." }));
    host.appendChild(el("h4", { text: "Learned classifier" }));
    const stats = el("div", { class: "small muted", text: "Loading…" });
    api("/api/spam/status").then((st) => { stats.textContent = `Trained on ${st.bayes.spam} junk and ${st.bayes.ham} legitimate messages (${st.bayes.vocabulary} tokens). ${st.autoMoved} messages have been filed automatically; ${st.cached} analysed.`; }).catch(() => {});
    host.appendChild(stats);
    host.appendChild(el("p", { class: "small muted", text: "Teach it with the Junk / Not junk buttons: each click trains the model on that message. Restoring a message from Junk also prevents it from ever being auto-filed again." }));
  },

  render_rules(host) {
    const s = state.settings;
    host.append(el("h3", { text: "Rules" }), el("p", { text: "Rules run on every new message in the Inbox before the junk score is computed; the first matching rule wins. Rules proposed by the analyser are marked AI." }));
    host.appendChild(el("div", { class: "btn-row", style: { marginBottom: "12px" } }, el("button", { class: "btn primary", onclick: () => this.editRule(null) }, icon("plus"), "New rule")));
    const rules = [...s.rules].sort((a, b) => a.priority - b.priority || a.createdAt - b.createdAt);
    if (!rules.length) host.appendChild(el("p", { class: "muted", text: "No rules yet." }));
    for (const r of rules) {
      const card = el("div", { class: "rule-card" },
        this.toggle(r.enabled, async (v) => { await settingsSave({ ...state.settings, rules: state.settings.rules.map((x) => (x.id === r.id ? { ...x, enabled: v } : x)) }); }),
        el("div", { class: "txt" }, el("div", {}, el("b", { text: r.name }), " ", r.createdBy === "ai" ? el("span", { class: "badge info" }, icon("sparkle", 12), "AI") : null, r.hitCount ? el("span", { class: "badge", style: { marginLeft: "6px" }, text: `${r.hitCount} hit${r.hitCount === 1 ? "" : "s"}` }) : null),
          el("div", { class: "crit-summary", text: r.criteria.map(describeCriterion).join(r.logic === "AND" ? "  AND  " : "  OR  ") }),
          el("div", { class: "action", text: "→ " + describeAction(r) + (r.note ? `  ·  ${r.note}` : "") })),
        el("button", { class: "btn sm", onclick: () => this.editRule(r) }, "Edit"),
        el("button", { class: "btn sm", title: "Apply to the current folder now", onclick: async () => { const x = await api("/api/rules/apply", { method: "POST", body: { rule: r, folder: state.folder, limit: 300 } }); toast(`Applied to ${plural(x.ok, "message")} in ${folders.byPath(state.folder)?.name || state.folder}`); list.fetch({ silent: true }); folders.load(); } }, "Run now"),
        el("button", { class: "icon-btn", title: "Delete rule", onclick: async () => { if (!confirm(`Delete rule "${r.name}"?`)) return; await settingsSave({ ...state.settings, rules: state.settings.rules.filter((x) => x.id !== r.id) }); this.render(); } }, icon("trash")));
      host.appendChild(card);
    }
  },
  editRule(rule, preset = null) {
    const isNew = !rule;
    $("ruleTitle").textContent = isNew ? "New rule" : "Edit rule";
    const form = ruleForm($("ruleBody"), rule || preset || { name: "", criteria: [], action: "MOVE_TO_FOLDER", targetFolder: "Junk" });
    const foot = $("ruleFoot"); foot.innerHTML = "";
    const prio = el("input", { type: "number", value: rule?.priority ?? 100, title: "Priority: lower runs first", style: { width: "80px" } });
    foot.append(el("span", { class: "grow small muted" }, "Priority ", prio),
      el("button", { class: "btn", onclick: () => $("ruleDialog").close() }, "Cancel"),
      el("button", { class: "btn primary", onclick: async () => {
        const r = form.read(); r.priority = parseInt(prio.value, 10) || 100;
        if (!r.criteria.length) return toast("Add at least one condition.", { danger: true });
        await settingsSave({ ...state.settings, rules: [...state.settings.rules.filter((x) => x.id !== r.id), r] });
        $("ruleDialog").close(); if ($("settingsDialog").open) this.render(); toast("Rule saved");
      } }, "Save"));
    $("ruleDialog").showModal();
  },

  render_senders(host) {
    host.append(el("h3", { text: "Safe and blocked senders" }), el("p", { text: "Enter full addresses or bare domains (sub-domains match). Safe senders are never treated as junk; blocked senders always are." }));
    const editor = (title, key) => {
      host.appendChild(el("h4", { text: title }));
      const box = el("div", { class: "list-editor" });
      const items = state.settings.spam[key];
      if (!items.length) box.appendChild(el("div", { class: "small muted", text: "None" }));
      items.forEach((v) => box.appendChild(el("div", { class: "item" }, el("span", { text: v }), el("button", { class: "icon-btn", title: "Remove", onclick: () => this.patchSpam({ [key]: items.filter((x) => x !== v) }).then(() => this.render()) }, icon("close")))));
      const input = el("input", { type: "text", placeholder: "name@example.com or example.com" });
      const add = async () => { const v = input.value.trim().toLowerCase(); if (!v) return; await this.patchSpam({ [key]: [...new Set([...items, v])] }); this.render(); };
      input.onkeydown = (e) => { if (e.key === "Enter") add(); };
      box.appendChild(el("div", { class: "add" }, input, el("button", { class: "btn sm", onclick: add }, icon("plus"), "Add")));
      host.appendChild(box);
    };
    editor("Safe senders", "safeSenders");
    editor("Blocked senders", "blockedSenders");
    host.appendChild(el("h4", { text: "Senders allowed to show remote images" }));
    const imgs = state.settings.imageSenders || [];
    const ib = el("div", { class: "list-editor" });
    if (!imgs.length) ib.appendChild(el("div", { class: "small muted", text: "None" }));
    imgs.forEach((v) => ib.appendChild(el("div", { class: "item" }, el("span", { text: v }), el("button", { class: "icon-btn", onclick: async () => { await settingsSave({ ...state.settings, imageSenders: imgs.filter((x) => x !== v) }); this.render(); } }, icon("close")))));
    host.appendChild(ib);
  },

  render_ai(host) {
    const o = state.settings.ollama || {};
    host.append(el("h3", { text: "AI models" }), el("p", { text: "The built-in analyser needs no setup: an offline organisation dictionary, look-alike domain detection, header forensics and a Bayesian classifier that learns from you. Optionally, a local open-source language model served by Ollama can add a second opinion, extra rule conditions and message translation. Nothing is sent to any cloud service." }));
    host.appendChild(el("div", { class: "card" }, el("h4", { text: "Built-in engine" }), el("div", { class: "small", text: "Always on. Runs on this server, no API keys, no network calls other than DNS blocklist lookups." })));
    host.appendChild(el("h4", { text: "Local language model (Ollama)" }));
    const url = el("input", { type: "url", value: o.url || "http://localhost:11434" });
    const model = el("input", { type: "text", value: o.model || "llama3.2", list: "ollamaModels" });
    const dl = el("datalist", { id: "ollamaModels" }); state.ollamaModels.forEach((m) => dl.appendChild(el("option", { value: m })));
    const enabled = this.toggle(o.enabled, async (v) => { await settingsSave({ ...state.settings, ollama: { enabled: v, url: url.value.trim(), model: model.value.trim() } }); });
    host.appendChild(this.row("Use a local Ollama model", "Adds a model assessment to \"Analyse & make rule\" and enables Translate. Slower, but catches subtler scams.", enabled));
    host.appendChild(el("div", { class: "grid-2" }, el("div", { class: "field" }, el("label", { text: "Ollama URL" }), url), el("div", { class: "field" }, el("label", { text: "Model" }), model, dl)));
    const status = el("span", { class: "small muted" });
    host.appendChild(el("div", { class: "btn-row" },
      el("button", { class: "btn", onclick: async () => {
        status.innerHTML = '<span class="spinner"></span>';
        try { const r = await api("/api/ollama/test", { method: "POST", body: { enabled: true, url: url.value.trim(), model: model.value.trim() } }); state.ollamaModels = r.models || []; dl.innerHTML = ""; state.ollamaModels.forEach((m) => dl.appendChild(el("option", { value: m }))); status.textContent = r.ok ? `Connected. Models: ${r.models.join(", ") || "none installed — run: ollama pull llama3.2"}` : "Not reachable: " + r.error; }
        catch (e) { status.textContent = "Not reachable: " + e.message; }
      } }, "Test connection"),
      el("button", { class: "btn primary", onclick: async () => { await settingsSave({ ...state.settings, ollama: { enabled: state.settings.ollama?.enabled || false, url: url.value.trim(), model: model.value.trim() } }); toast("Saved"); } }, "Save"), status));
    host.appendChild(el("p", { class: "small muted", html: "Install from <a href='https://ollama.com' target='_blank' rel='noopener'>ollama.com</a>, then run <code>ollama pull llama3.2</code> (or any other model) on the machine that hosts Anne's Post." }));
  },
};

// ============================================================================ context menu (messages)
const contextMenu = {
  show(e, m, items = null) {
    const targets = state.selected.has(mkey(m)) ? list.targets() : (items || [m]);
    menu.openAt(e.clientX, e.clientY, mailplus.messageMenu(targets, m));
  },
  hide() { menu.close(); },
};

// ============================================================================ keyboard
document.addEventListener("keydown", (e) => {
  if (!state.token) return;
  const target = e.target;
  const typing = target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT" || target.isContentEditable);
  const ctrl = e.ctrlKey || e.metaKey;
  const k = e.key;
  if (state.compose && $("composeDialog").open && ctrl && k === "Enter") { e.preventDefault(); compose.send(); return; }
  if (state.compose && $("composeDialog").open && ctrl && (k === "k" || k === "K") && target === $("cEditor")) { e.preventDefault(); mailplus.insertLink(); return; }
  if (ctrl && ["1", "2", "3", "4"].includes(k)) { e.preventDefault(); shell.setModule(["mail", "calendar", "people", "todo"][Number(k) - 1]); return; }
  if (k === "F9") { e.preventDefault(); list.fetch(); folders.load(); return; }
  if (typing || qs("dialog[open]:not(.docked)")) { if (k === "Escape" && target === $("searchInput")) { list.clearSearch(); } return; }
  if (state.module !== "mail") { if (k === "/") { e.preventDefault(); $("searchInput").focus(); } if (k === "?") $("shortcutsDialog").showModal(); return; }
  const t = list.targets();
  if (k === "/") { e.preventDefault(); $("searchInput").focus(); return; }
  if (k === "?") { $("shortcutsDialog").showModal(); return; }
  if ((k === "n" || k === "N") && !ctrl) { compose.open({ mode: "new" }); return; }
  if (ctrl && (k === "n" || k === "N")) { e.preventDefault(); compose.open({ mode: "new" }); return; }
  if (k === "ArrowDown") { e.preventDefault(); list.moveFocus(1); return; }
  if (k === "ArrowUp") { e.preventDefault(); list.moveFocus(-1); return; }
  if (k === "Escape") { if (state.selected.size) list.selectAll(false); else reading.close(); return; }
  if (ctrl && (k === "a" || k === "A") && !e.shiftKey) { e.preventDefault(); list.selectAll(true); return; }
  if (!t.length) return;
  if (k === "Delete" && e.shiftKey) { e.preventDefault(); actions.delete(t, true); return; }
  if (k === "Delete" || k === "Backspace") { e.preventDefault(); actions.delete(t); return; }
  if (ctrl && e.shiftKey && (k === "V" || k === "v")) { e.preventDefault(); actions.pickFolder(t); return; }
  if (ctrl && (k === "q" || k === "Q")) { e.preventDefault(); actions.read(t, true); return; }
  if (ctrl && (k === "u" || k === "U")) { e.preventDefault(); actions.read(t, false); return; }
  if (ctrl && (k === "p" || k === "P") && state.openDetail) { e.preventDefault(); mailplus.print(state.openDetail); return; }
  if (k === "Insert") { actions.flag(t, !t[0].flagged); return; }
  if (ctrl) {
    if (!state.openDetail) return;
    if ((k === "r" || k === "R") && e.shiftKey) { e.preventDefault(); compose.open({ mode: "replyAll", detail: state.openDetail }); return; }
    if ((k === "r" || k === "R")) { e.preventDefault(); compose.open({ mode: "reply", detail: state.openDetail }); return; }
    if ((k === "f" || k === "F") && e.shiftKey) { e.preventDefault(); compose.open({ mode: "forward", detail: state.openDetail }); return; }
    return;
  }
  if (k === "e" || k === "E") { actions.archive(t); return; }
  if (k === "J") { actions.junk(t, false); return; }
  if (k === "j") { actions.junk(t, state.folderRole !== "junk"); return; }
  if (k === "u" || k === "U") { actions.read(t, false); return; }
  if (k === "s" || k === "S") { actions.flag(t, !t[0].flagged); return; }
  if (k === "x" || k === "X") { if (state.open) list.toggleSelect(mkey(state.open), false); return; }
  if (!state.openDetail) return;
  if (k === "r") { compose.open({ mode: "reply", detail: state.openDetail }); return; }
  if (k === "a") { compose.open({ mode: "replyAll", detail: state.openDetail }); return; }
  if (k === "f") { compose.open({ mode: "forward", detail: state.openDetail }); return; }
});

// ============================================================================ wiring
function wire() {
  $("suContinue").onclick = () => setup.continueFromStep1();
  $("suPassword").onkeydown = (e) => { if (e.key === "Enter") setup.continueFromStep1(); };
  $("suEmail").onkeydown = (e) => { if (e.key === "Enter") $("suPassword").focus(); };
  $("suBack").onclick = () => setup.step(1);
  $("suBack1").onclick = () => setup.step(0);
  $("suChangeProvider").onclick = () => setup.step(0);
  $("suProviderSearch").addEventListener("input", () => setup.catalogue && setup.renderProviders());
  $("suProviderSearch").addEventListener("keydown", (e) => { if (e.key === "Enter") { const first = qs("#suProviders .provider-tile"); if (first) first.click(); } });
  $("suVerify").onclick = () => setup.verify();
  $("suSignIn").onclick = () => setup.signIn();
  $("suProtocol").onchange = () => { if ($("suProtocol").value === "POP3" && $("suIncomingPort").value === "993") $("suIncomingPort").value = "995"; if ($("suProtocol").value === "IMAP" && $("suIncomingPort").value === "995") $("suIncomingPort").value = "993"; };
  $("suRemember").addEventListener("change", () => { setup.rememberTouched = true; });
  $("suCancelAdd").onclick = () => { if (state.token) { $("setup").classList.add("hidden"); $("app").classList.remove("hidden"); } };

  $("navToggle").onclick = () => {
    const ws = $("workspace");
    if (state.module !== "mail") { const m = $(shell.modules[state.module]); m.classList.toggle("nav-hidden"); m.classList.toggle("nav-open"); return; }
    if (innerWidth <= 700) ws.classList.toggle("nav-open");
    else { prefs.set("navCollapsed", !ws.classList.contains("nav-collapsed")); applyTheme(); }
  };
  // On phones the folder pane is a drawer: a tap anywhere outside it closes it.
  $("workspace").addEventListener("click", (e) => { const ws = $("workspace"); if (ws.classList.contains("nav-open") && !e.target.closest("#folderPane")) { ws.classList.remove("nav-open"); e.preventDefault(); e.stopPropagation(); } }, true);
  qsa("[data-module]").forEach((b) => (b.onclick = () => shell.setModule(b.dataset.module)));
  $("btnNewMail").onclick = () => compose.open({ mode: "new" });
  $("btnNewMailPane").onclick = () => { $("workspace").classList.remove("nav-open"); compose.open({ mode: "new" }); };
  $("fabNewMail").onclick = () => compose.open({ mode: "new" });
  $("btnRefresh").onclick = () => { list.fetch(); folders.load(); mailplus.loadOutbox(); };
  $("btnTheme").onclick = () => { const cur = document.documentElement.dataset.theme; prefs.set("theme", cur === "dark" ? "light" : "dark"); applyTheme(); };
  $("btnSettings").onclick = () => settingsUI.open(state.module === "calendar" ? "calendar" : "general");
  $("btnShortcuts").onclick = () => $("shortcutsDialog").showModal();
  $("accountAvatar").onclick = (e) => mailplus.accountMenu(e);
  $("btnMyDay").onclick = () => mailplus.toggleMyDay();
  qsa("#stNav button").forEach((b) => (b.onclick = () => { settingsUI.tab = b.dataset.tab; settingsUI.render(); }));
  qsa("[data-close]").forEach((b) => (b.onclick = () => $(b.dataset.close).close()));

  const search = $("searchInput");
  search.addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    const q = search.value.trim();
    if (state.module === "calendar") return calendarUI.search(q);
    if (state.module === "people") return peopleUI.search(q);
    if (state.module === "todo") return todoUI.search(q);
    if (q) list.search(q); else list.clearSearch();
  });
  search.addEventListener("input", () => {
    $("searchClear").classList.toggle("hidden", !search.value);
    if (state.module === "people") peopleUI.search(search.value.trim());
    if (state.module === "todo") todoUI.search(search.value.trim());
    if (!search.value && state.search && state.module === "mail") list.clearSearch();
  });
  $("searchClear").onclick = () => { search.value = ""; $("searchClear").classList.add("hidden"); if (state.module === "mail") list.clearSearch(); else if (state.module === "people") peopleUI.search(""); else if (state.module === "todo") todoUI.search(""); else calendarUI.search(""); };
  $("searchOptions").onclick = () => mailplus.searchDialog();

  $("cmdSelectAll").onclick = (e) => { e.stopPropagation(); list.selectAll($("cmdSelectAll").checked); };
  qsa("#focusTabs button").forEach((b) => (b.onclick = () => { state.focus = b.dataset.focus; state.selected.clear(); list.render(); }));
  $("listFilter").onclick = (e) => mailplus.filterMenu(e.currentTarget);
  $("listSort").onclick = (e) => mailplus.sortMenu(e.currentTarget);
  $("btnLoadMore").onclick = () => list.fetch({ more: true });
  mailplus.wireRibbon();

  // reading pane
  const d = () => state.openDetail;
  $("rdBack").onclick = () => reading.close();
  for (const [id, mode] of [["rdReply", "reply"], ["rdReplyAll", "replyAll"], ["rdForward", "forward"], ["rdQuickReplyBtn", "reply"], ["rdQuickReplyAllBtn", "replyAll"], ["rdQuickForwardBtn", "forward"]]) $(id).onclick = () => d() && compose.open({ mode, detail: d() });
  $("rdDelete").onclick = () => d() && actions.delete([d()]);
  $("rdArchive").onclick = () => d() && actions.archive([d()]);
  $("rdMove").onclick = () => d() && actions.pickFolder([d()]);
  $("rdFlag").onclick = () => d() && actions.flag([d()], !d().flagged);
  $("rdUnread").onclick = () => { if (d()) { clearTimeout(reading.markTimer); actions.read([d()], false); } };
  $("rdJunk").onclick = () => d() && actions.junk([d()], true);
  $("rdNotJunk").onclick = () => d() && actions.junk([d()], false);
  $("rdAnalyze").onclick = () => d() && analysis.open(d());
  $("rdShowImages").onclick = () => { state.imagesOnce = true; reading.render(d()); };
  $("rdAlwaysImages").onclick = async () => { const a = (d().fromAddress || "").toLowerCase(); await settingsSave({ ...state.settings, imageSenders: [...new Set([...(state.settings.imageSenders || []), a])] }); reading.render(d()); };
  $("rdMore").onclick = (e) => { e.stopPropagation(); if (d()) menu.openFor(e.currentTarget, mailplus.messageMenu([d()], d(), { fromReading: true })); };

  // compose
  const closeCompose = () => { if (!state.compose) return; if (!state.compose.dirty && !$("cEditor").innerText.trim() && !$("cTo").value) { compose.close(); return; } menu.openFor($("cClose"), [{ label: "Save draft and close", icon: "drafts", onClick: async () => { await compose.saveDraft(); compose.close(); } }, { label: "Discard", icon: "trash", danger: true, onClick: () => compose.close() }, { label: "Keep writing", icon: "edit", onClick: () => {} }]); };
  $("cClose").onclick = closeCompose;
  $("cMinimize").onclick = () => compose.minimize();
  $("cPopOut").onclick = () => compose.popOut();
  $("cDiscard").onclick = () => { if (confirm("Discard this message?")) compose.close(); };
  $("cSend").onclick = () => compose.send();
  $("cSendMenu").onclick = (e) => mailplus.scheduleMenu(e.currentTarget);
  $("cSaveDraft").onclick = () => compose.saveDraft();
  $("cShowCc").onclick = () => { $("cCcRow").classList.remove("hidden"); $("cCc").focus(); };
  $("cShowBcc").onclick = () => { $("cBccRow").classList.remove("hidden"); $("cBcc").focus(); };
  qsa(".compose-tabs button").forEach((b) => (b.onclick = () => { qsa(".compose-tabs button").forEach((x) => x.classList.toggle("active", x === b)); qsa("[data-cbar]").forEach((bar) => bar.classList.toggle("hidden", bar.dataset.cbar !== b.dataset.ctab)); }));
  qsa(".fmt-bar [data-cmd]").forEach((b) => (b.onmousedown = (e) => { e.preventDefault(); document.execCommand(b.dataset.cmd, false, b.dataset.arg || null); $("cEditor").focus(); if (state.compose) state.compose.dirty = true; }));
  $("cFontFamily").onchange = () => { if ($("cFontFamily").value) document.execCommand("fontName", false, $("cFontFamily").value); $("cFontFamily").value = ""; $("cEditor").focus(); };
  $("cFontSize").onchange = () => { if ($("cFontSize").value) document.execCommand("fontSize", false, $("cFontSize").value); $("cFontSize").value = ""; $("cEditor").focus(); };
  $("cColor").oninput = () => { document.execCommand("foreColor", false, $("cColor").value); };
  $("cHighlight").oninput = () => { document.execCommand("hiliteColor", false, $("cHighlight").value); };
  for (const id of ["cLink", "cLink2"]) $(id).onmousedown = (e) => { e.preventDefault(); mailplus.insertLink(); };
  for (const id of ["cAttach", "cAttach2"]) $(id).onclick = () => $("cAttachInput").click();
  $("cAttachInput").onchange = () => { compose.addFiles(Array.from($("cAttachInput").files)); $("cAttachInput").value = ""; };
  for (const id of ["cSignature", "cSignature2"]) $(id).onclick = (e) => mailplus.signatureMenu(e.currentTarget);
  $("cTemplates").onclick = (e) => mailplus.templateMenu(e.currentTarget);
  $("cPicture").onclick = async () => { const [f] = await pickFiles({ accept: "image/*" }); if (f) { $("cEditor").focus(); document.execCommand("insertHTML", false, `<img src="${await readFileDataUrl(f)}" alt="${esc(f.name)}" style="max-width:100%">`); state.compose.dirty = true; } };
  $("cTable").onclick = () => mailplus.insertTable();
  $("cEmoji").onclick = (e) => mailplus.emojiMenu(e.currentTarget);
  $("cHr").onmousedown = (e) => { e.preventDefault(); document.execCommand("insertHTML", false, "<hr>"); };
  $("cMeeting").onclick = () => { const to = compose.recipients("cTo"), subject = $("cSubject").value; calendarUI.newEvent({ title: subject, attendees: to.map((a) => ({ email: emailOf(a), name: "" })) }); };
  $("cImportanceHigh").onclick = () => compose.setImportance("high");
  $("cImportanceLow").onclick = () => compose.setImportance("low");
  $("cImportance").onchange = () => { if (state.compose) { state.compose.importance = $("cImportance").value; compose.renderImportance(); } };
  $("cCheckNames").onclick = () => mailplus.checkNames();
  $("cSaveTemplate").onclick = () => mailplus.saveAsTemplate();
  $("cPlainText").onclick = () => { if (!state.compose) return; if (!state.compose.plain && !confirm("Switch to plain text? Formatting, pictures and tables are removed.")) return; state.compose.plain = !state.compose.plain; if (state.compose.plain) { const t = htmlToPlain($("cEditor")); $("cEditor").innerHTML = esc(t).replace(/\n/g, "<br>"); } $("cPlainText").textContent = state.compose.plain ? "HTML" : "Plain text"; };
  $("cEditor").addEventListener("input", () => { if (state.compose) state.compose.dirty = true; });
  for (const id of ["cTo", "cCc", "cBcc", "cSubject"]) $(id).addEventListener("input", () => { if (state.compose) state.compose.dirty = true; });
  $("cEditor").addEventListener("paste", (e) => {
    const files = Array.from(e.clipboardData?.files || []);
    if (!files.length) return;
    e.preventDefault();
    const imgs = files.filter((f) => f.type.startsWith("image/")), other = files.filter((f) => !f.type.startsWith("image/"));
    imgs.forEach(async (f) => document.execCommand("insertHTML", false, `<img src="${await readFileDataUrl(f)}" style="max-width:100%">`));
    if (other.length) compose.addFiles(other);
  });
  $("cEditor").addEventListener("drop", (e) => { const files = Array.from(e.dataTransfer?.files || []); if (files.length) { e.preventDefault(); compose.addFiles(files); } });
  $("composeDialog").addEventListener("cancel", (e) => { e.preventDefault(); closeCompose(); });
  mailplus.wireAutocomplete();

  window.addEventListener("resize", debounce(() => { reading.fitFrame(); calendarUI.onResize && calendarUI.onResize(); }, 150));
  document.addEventListener("visibilitychange", () => { if (!document.hidden && state.token) list.fetch({ silent: true }); });
}

// ============================================================================ native hooks
/** Android back button (and the iOS shell): returns true when something was closed. */
window.quillpostBack = function () {
  if (menu.isOpen()) { menu.close(); return true; }
  const dlg = qs("dialog[open]");
  if (dlg) { if (dlg.id === "composeDialog") $("cClose").onclick(); else dlg.close(); return true; }
  const ws = $("workspace");
  if (ws.classList.contains("nav-open")) { ws.classList.remove("nav-open"); return true; }
  for (const id of Object.values(shell.modules)) if ($(id).classList.contains("nav-open")) { $(id).classList.remove("nav-open"); return true; }
  if (state.token && state.module !== "mail") {
    if (state.module === "people" && peopleUI.back && peopleUI.back()) return true;
    if (state.module === "todo" && todoUI.back && todoUI.back()) return true;
    shell.setModule("mail"); return true;
  }
  if (state.token && ws.classList.contains("show-reading") && innerWidth <= 1000) { reading.close(); return true; }
  if (state.token && state.search) { $("searchClear").onclick(); return true; }
  if (!state.token && !$("setupStep2").classList.contains("hidden")) { setup.step(1); return true; }
  if (!state.token && !$("setupStep1").classList.contains("hidden")) { setup.step(0); return true; }
  return false;
};
/** mailto: links handed over by the shells (and the Android SENDTO intent). */
window.quillpostMailto = function (url) {
  if (!state.token) { toast("Sign in first, then the message will open."); return false; }
  let to = "", params = new URLSearchParams();
  try { const s = String(url).replace(/^mailto:/i, ""); const q = s.indexOf("?"); to = decodeURIComponent(q >= 0 ? s.slice(0, q) : s); if (q >= 0) params = new URLSearchParams(s.slice(q + 1)); } catch {}
  compose.open({ mode: "new", to: [to, params.get("to") || ""].filter(Boolean).join(", "), cc: params.get("cc") || "", subject: params.get("subject") || "", html: params.get("body") ? esc(params.get("body")).replace(/\n/g, "<br>") : "" });
  if (params.get("bcc")) { $("cBccRow").classList.remove("hidden"); $("cBcc").value = params.get("bcc"); }
  return true;
};
if (NATIVE) document.documentElement.dataset.native = NATIVE;

// ============================================================================ init
(async function init() {
  const shownAt = Date.now();
  applyTheme();
  wire();
  const resumed = await session.resume().catch(() => false);
  if (!resumed) setup.show();
  // Keep the splash up for a moment so it does not flash, then fade it out.
  setTimeout(() => { const sp = $("splash"); if (!sp) return; sp.classList.add("fade"); setTimeout(() => sp.remove(), 400); }, Math.max(0, 700 - (Date.now() - shownAt)));
})();
