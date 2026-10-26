/* Anne's Post — the mail command set, part 1: the desktop mail client's own commands (File/backstage,
 * Home, Send / Receive, Folder, View, Help, the message window's Message, Insert, Options, Format
 * Text, Review and Draw tabs, and the context menus), plus Undo/Redo for mail actions. Part 2
 * (mailextra.js) adds commands from other mail programs and new ones. Registration is pure data;
 * nothing here touches the page until a command runs, so the Node tests can load this file too.
 */
"use strict";

/** Helpers shared by both command files. */
const MC = (() => {
  class UserError extends Error { constructor(m) { super(m); this.user = true; } }
  const g = (name) => (typeof globalThis[name] !== "undefined" ? globalThis[name] : undefined);
  /** The messages a command acts on: the context menu's items, else the selection or open message. */
  const T = (ctx) => (ctx && ctx.items && ctx.items.length ? ctx.items : (typeof list !== "undefined" && state.token ? list.targets() : []));
  const has = (ctx) => T(ctx).length > 0;
  const one = (ctx) => T(ctx)[0] || null;
  const need = (ctx) => { const t = T(ctx); if (!t.length) throw new UserError("Select a message first."); return t; };
  const needOne = (ctx) => { const t = need(ctx); return t[0]; };
  async function detail(m) {
    if (state.openDetail && mkey(state.openDetail) === mkey(m)) return state.openDetail;
    const d = await api("/api/message", { query: { folder: m.folder || state.folder, uid: m.uid, tz: VIEW_TZ } });
    d.folder = m.folder || state.folder;
    return d;
  }
  const openDetail = async (ctx) => detail(needOne(ctx));
  const composing = () => !!(typeof state !== "undefined" && state.compose && $("composeDialog") && $("composeDialog").open);
  const needCompose = () => { if (!composing()) throw new UserError("Open a message you are writing first."); };
  const editor = () => $("cEditor");
  /** Runs a document.execCommand in the compose editor, keeping its selection. */
  function exec(cmd, arg = null) {
    needCompose();
    const ed = editor();
    if (!ed.contains(document.activeElement) && document.activeElement !== ed) { ed.focus(); restoreSelection(); }
    document.execCommand(cmd, false, arg);
    if (state.compose) state.compose.dirty = true;
  }
  let savedRange = null;
  function rememberSelection() {
    const sel = getSelection();
    if (sel && sel.rangeCount && editor() && editor().contains(sel.getRangeAt(0).startContainer)) savedRange = sel.getRangeAt(0).cloneRange();
  }
  function restoreSelection() {
    if (!savedRange) return;
    const sel = getSelection(); sel.removeAllRanges(); sel.addRange(savedRange);
  }
  function insertHtml(html) { needCompose(); editor().focus(); restoreSelection(); document.execCommand("insertHTML", false, html); state.compose.dirty = true; }
  function selectedText() { const s = getSelection(); return s ? String(s) : ""; }
  /** Opens a menu at the command's anchor (ribbon / QAT button) or in the middle of the screen. */
  function showMenu(items, ctx = {}) {
    if (ctx.anchor && ctx.anchor.getBoundingClientRect && ctx.anchor.isConnected) menu.openFor(ctx.anchor, items);
    else menu.openAt(Math.max(8, innerWidth / 2 - 130), Math.max(60, innerHeight / 4), items);
  }
  /** Shows a node or text in the pop-up dialog (never a new browser window). */
  function panel(title, node) { $("popupTitle").textContent = title; const b = $("popupBody"); b.innerHTML = ""; b.appendChild(node); if (!$("popupDialog").open) $("popupDialog").showModal(); return b; }
  function saveBlob(name, text, type = "text/plain;charset=utf-8") {
    const blob = new Blob([text], { type });
    const url = URL.createObjectURL(blob);
    const a = el("a", { href: url, download: name }); document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }
  const safeName = (s) => String(s || "message").replace(/[\\/:*?"<>|\r\n]+/g, "_").trim().slice(0, 80) || "message";
  const folderName = (p) => (folders.byPath(p) || {}).name || p;
  const me = () => list.myAddresses();
  const refs = (items) => items.map((m) => ({ folder: m.folder || state.folder, uid: m.uid }));
  /** A small table-ish list of messages with an Open button for each. */
  function messageRows(msgs, { extra = null, empty = "Nothing found." } = {}) {
    const box = el("div", { class: "qb-msglist" });
    if (!msgs.length) box.appendChild(el("p", { class: "muted", text: empty }));
    for (const m of msgs) {
      box.appendChild(el("div", { class: "qb-msgrow" },
        el("div", { class: "grow" }, el("b", { text: m.subject || "(no subject)" }), el("div", { class: "small muted", text: `${m.fromName || m.fromAddress || (m.to || []).join(", ")} · ${fmtDate(m.date)}${m.folder && m.folder !== state.folder ? " · " + folderName(m.folder) : ""}${m.size ? " · " + fmtSize(m.size) : ""}` })),
        extra ? extra(m) : null,
        el("button", { class: "btn sm", onclick: () => { $("popupDialog").close(); openMessage(m); } }, "Open")));
    }
    return box;
  }
  /** Opens a message from any folder: loads the folder when needed, then the message. */
  async function openMessage(m) {
    shell.setModule("mail", { silent: true });
    const f = m.folder || state.folder;
    if (f !== state.folder || state.search) await list.load(f, { keepOpen: true });
    reading.open(m);
  }
  async function searchAll(opts) {
    const query = { folder: state.folder === SCHEDULED ? "INBOX" : state.folder, scope: "all", limit: 200, text: "", ...opts };
    return (await api("/api/search/advanced", { query })).messages;
  }
  /** Persistent per-account state on the server (screener, VIPs, saved searches, snippets…). */
  const store = {
    cache: {},
    async get(name, def) {
      const key = (state.account?.email || "") + ":" + name;
      if (key in this.cache) return this.cache[key];
      try { const v = await api(`/api/x/state/${name}`); this.cache[key] = v ?? def; } catch { this.cache[key] = def; }
      return this.cache[key];
    },
    peek(name, def) { const key = (state.account?.email || "") + ":" + name; return key in this.cache ? this.cache[key] : def; },
    async set(name, value) {
      this.cache[(state.account?.email || "") + ":" + name] = value;
      await api(`/api/x/state/${name}`, { method: "PUT", body: value });
      return value;
    },
  };
  return { UserError, g, T, has, one, need, needOne, detail, openDetail, composing, needCompose, editor, exec, insertHtml, rememberSelection, restoreSelection, selectedText, showMenu, panel, saveBlob, safeName, folderName, me, refs, messageRows, openMessage, searchAll, store };
})();

// ============================================================================ undo / redo for mail actions
/**
 * Records reversible mail actions (move, delete to Deleted Items, archive, junk, read, flag, pin,
 * categorize) so Undo and Redo work like the desktop client's. Wraps the core action functions.
 */
const mailUndo = {
  undoStack: [], redoStack: [], busy: false,
  push(entry) { if (this.busy) return; this.undoStack.push(entry); if (this.undoStack.length > 50) this.undoStack.shift(); this.redoStack = []; if (typeof qatUI !== "undefined") qatUI.refresh(); },
  async undo() {
    const e = this.undoStack.pop();
    if (!e) { toast("Nothing to undo."); return; }
    this.busy = true;
    try { await e.undo(); this.redoStack.push(e); toast(`Undone: ${e.label}`); } catch (err) { toast("Could not undo: " + err.message, { danger: true }); } finally { this.busy = false; }
    list.fetch({ silent: true }); folders.load();
  },
  async redo() {
    const e = this.redoStack.pop();
    if (!e) { toast("Nothing to redo."); return; }
    this.busy = true;
    try { await e.redo(); this.undoStack.push(e); toast(`Redone: ${e.label}`); } catch (err) { toast("Could not redo: " + err.message, { danger: true }); } finally { this.busy = false; }
    list.fetch({ silent: true }); folders.load();
  },
  install() {
    if (this.installed || typeof actions === "undefined") return;
    this.installed = true;
    const self = this;
    const wrapMove = (name, label) => {
      const orig = actions[name].bind(actions);
      actions[name] = async function (items, ...rest) {
        const from = items[0] ? items[0].folder || state.folder : state.folder;
        const r = await orig(items, ...rest);
        if (r && r.moved && r.moved.length && !self.busy) {
          let here = r.moved, back = null;
          const dest = name === "move" ? rest[0] : r.moved[0].folder;
          self.push({
            label: typeof label === "function" ? label(items, dest) : label,
            undo: async () => { const x = await (name === "junk" ? orig(here, !rest[0]) : actions.move(here, from, { quiet: true })); back = x && x.moved && x.moved.length ? x.moved : null; },
            redo: async () => { if (!back) throw new Error("the messages were not found"); const x = await (name === "junk" ? orig(back, rest[0]) : actions.move(back, dest, { quiet: true })); here = x && x.moved && x.moved.length ? x.moved : here; },
          });
        }
        return r;
      };
    };
    wrapMove("move", (items, to) => `Move ${plural(items.length, "message")} to ${MC.folderName(to)}`);
    wrapMove("delete", (items) => `Delete ${plural(items.length, "message")}`);
    wrapMove("junk", (items) => `Junk ${plural(items.length, "message")}`);
    const wrapState = (name, field, label) => {
      const orig = actions[name].bind(actions);
      actions[name] = async function (items, value, ...rest) {
        const before = items.map((m) => ({ ref: { ...m }, v: !!m[field] }));
        const r = await orig(items, value, ...rest);
        self.push({
          label: label(items, value),
          undo: async () => { for (const v of [true, false]) { const grp = before.filter((b) => b.v === v).map((b) => b.ref); if (grp.length) await orig(grp, v); } },
          redo: async () => { await orig(before.map((b) => b.ref), value, ...rest); },
        });
        return r;
      };
    };
    wrapState("read", "read", (items, v) => `Mark ${plural(items.length, "message")} as ${v ? "read" : "unread"}`);
    wrapState("flag", "flagged", (items, v) => `${v ? "Flag" : "Clear flag on"} ${plural(items.length, "message")}`);
    wrapState("pin", "pinned", (items, v) => `${v ? "Pin" : "Unpin"} ${plural(items.length, "message")}`);
    const cat = actions.categorize.bind(actions);
    actions.categorize = async function (items, name, on) {
      const r = await cat(items, name, on);
      self.push({ label: `${on ? "Categorize" : "Remove category"} ${name}`, undo: () => cat(items, name, !on), redo: () => cat(items, name, on) });
      return r;
    };
  },
};

// ============================================================================ command definitions
(function defineOutlookCommands() {
  const { T, has, need, needOne, detail, openDetail, composing, needCompose, exec, insertHtml, saveBlob, safeName, UserError } = MC;
  const sel = (ctx) => has(ctx);
  const opened = () => !!(typeof state !== "undefined" && state.openDetail);
  const imap = () => !!(typeof state !== "undefined" && state.capabilities && state.capabilities.folders);
  const curFolder = () => (state.folder === SCHEDULED ? null : folders.byPath(state.folder));
  const P = (k, d) => prefs.get(k, d);
  const toggle = (k, d) => { prefs.set(k, !prefs.get(k, d)); applyTheme(); };
  const C = [];
  const add = (tab, group, list) => { for (const c of list) C.push({ tab, group, ...c }); };
  const mailOnly = { scope: "mail" };

  // ------------------------------------------------------------------ File (backstage)
  add("File", "Info", [
    { id: "file.info", label: "Account Information", icon: "info", run: () => settingsUI.open("account"), popular: true, tip: "Account settings, aliases and storage" },
    { id: "file.addAccount", label: "Add Account", icon: "person-add", run: () => setup.show({ adding: true }) },
    { id: "file.accountSettings", label: "Account Settings", icon: "settings", run: () => settingsUI.open("account") },
    { id: "file.switchAccount", label: "Switch Account", icon: "user", kind: "menu", menu: () => accounts.all().map((x) => ({ label: x.account.email, icon: "user", checked: x.account.email === state.account?.email, onClick: () => x.account.email !== state.account?.email && session.switchTo(x.account.email) })) },
    { id: "file.autoReplies", label: "Automatic Replies (Out of Office)", icon: "send-later", run: () => settingsUI.open("autoreply"), keywords: "vacation away" },
    { id: "file.mailboxCleanup", label: "Mailbox Cleanup…", icon: "sweep", run: () => mailboxCleanup(), tip: "Mailbox size, large messages, old items and Deleted Items" },
    { id: "file.manageRules", label: "Manage Rules & Alerts", icon: "rules", run: () => settingsUI.open("rules"), keywords: "filters" },
  ]);
  add("File", "Open & Export", [
    { id: "file.importExport", label: "Import/Export", icon: "download", run: () => settingsUI.open("importexport") },
    { id: "file.importMessages", label: "Import Messages (.eml / .mbox)…", icon: "upload", isEnabled: imap, run: () => mailplus.importInto(curFolder() || folders.byRole("inbox")) },
    { id: "file.exportFolder", label: "Export Folder (.mbox)", icon: "download", run: () => download(apiUrl("/api/export/mbox", { folder: state.folder === SCHEDULED ? "INBOX" : state.folder })) },
    { id: "file.openCalendar", label: "Open Calendar File (.ics)…", icon: "calendar", run: () => { shell.setModule("calendar"); calendarUI.importFile && calendarUI.importFile("calendar"); } },
  ]);
  add("File", "Save", [
    { id: "file.save", label: "Save", icon: "drafts", keys: ["Ctrl+S"], scope: "global", popular: true, tip: "Saves the message you are writing as a draft, or the open message as an .eml file",
      isEnabled: () => composing() || opened(), run: async () => { if (composing()) return compose.saveDraft(); const d = state.openDetail; download(apiUrl("/api/raw", { folder: d.folder, uid: d.uid, download: "true" })); } },
    { id: "file.saveAs", label: "Save As (.eml)", icon: "download", keys: ["F12"], isEnabled: sel, run: async (ctx) => { for (const m of need(ctx).slice(0, 20)) download(apiUrl("/api/raw", { folder: m.folder || state.folder, uid: m.uid, download: "true" })); } },
    { id: "file.saveAsText", label: "Save As Text (.txt)", icon: "note", isEnabled: sel, keywords: "export extract pine pegasus", run: async (ctx) => { const d = await openDetail(ctx); saveBlob(safeName(d.subject) + ".txt", `From: ${d.fromName} <${d.fromAddress}>\nDate: ${fmtDate(d.date, { long: true })}\nTo: ${(d.to || []).join(", ")}\n${d.cc?.length ? "Cc: " + d.cc.join(", ") + "\n" : ""}Subject: ${d.subject}\n\n${mailplus.bodyText(d)}\n`); } },
    { id: "file.saveAsHtml", label: "Save As Web Page (.html)", icon: "globe", isEnabled: sel, run: async (ctx) => { const d = await openDetail(ctx); saveBlob(safeName(d.subject) + ".html", "<!doctype html><meta charset=utf-8>" + mailplus.printable(d, false), "text/html;charset=utf-8"); } },
    { id: "file.saveAttachments", label: "Save All Attachments", icon: "attach", isEnabled: sel, run: async (ctx) => { const d = await openDetail(ctx); const n = (d.attachments || []).filter((a) => !a.inline).length; if (!n) throw new UserError("This message has no attachments."); download(apiUrl("/api/attachments/zip", { folder: d.folder, uid: d.uid })); } },
  ]);
  add("File", "Print", [
    { id: "file.print", label: "Print", icon: "print", keys: ["Ctrl+P"], popular: true, isEnabled: () => opened() || sel({}), run: async (ctx) => mailplus.print(await openDetail(ctx)) },
    { id: "file.printPreview", label: "Print Preview", icon: "eye", isEnabled: sel, run: async (ctx) => { const d = await openDetail(ctx); popup(d.subject, { html: mailplus.printable(d, state.imagesOnce) }); } },
    { id: "file.quickPrint", label: "Quick Print (Selected Messages)", icon: "print", isEnabled: sel, tip: "Prints every selected message in one document",
      run: async (ctx) => {
        const ds = []; for (const m of need(ctx).slice(0, 30)) ds.push(await detail(m));
        const html = ds.map((d) => mailplus.printable(d, false)).join('<div style="page-break-after:always"></div>');
        if (nativePrint("Messages")) return;
        const w = window.open("", "_blank"); if (!w) return popup("Print", { html });
        w.document.write(html); w.document.close(); setTimeout(() => w.print(), 400);
      } },
  ]);
  add("File", "Options", [
    { id: "file.options", label: "Options", icon: "settings", run: () => settingsUI.open(state.module === "calendar" ? "calendar" : "general"), popular: true, keywords: "settings preferences" },
    { id: "file.signOut", label: "Exit (Sign Out)", icon: "signout", run: () => { if (confirm(`Sign out of ${state.account?.email}?`)) signOut(false); } },
  ]);

  // ------------------------------------------------------------------ Home: New
  add("Home", "New", [
    { id: "mail.newMail", label: "New Email", icon: "compose", keys: ["N", "Ctrl+N"], scope: "mail", popular: true, run: () => compose.open({ mode: "new" }) },
    { id: "mail.newItems", label: "New Items", icon: "plus", kind: "menu", menu: () => [
      { label: "Email Message", icon: "mail", onClick: () => compose.open({ mode: "new" }) },
      { label: "Appointment", icon: "calendar", onClick: () => calendarUI.newEvent({}) },
      { label: "Meeting", icon: "people", onClick: () => calendarUI.newEvent({ attendees: [] }) },
      { label: "Contact", icon: "person-add", onClick: () => peopleUI.edit(null) },
      { label: "Contact Group", icon: "people", onClick: () => peopleUI.editList(null) },
      { label: "Task", icon: "todo", onClick: () => todoUI.quickAdd() },
      { sep: true },
      { label: "Email Using Template", icon: "note", sub: () => templateItems() },
    ] },
    { id: "mail.newFromTemplate", label: "New Email from Template", icon: "note", kind: "menu", keywords: "stationery thunderbird templates", menu: () => templateItems() },
    { id: "mail.newEvent", label: "New Appointment", icon: "calendar", keys: ["Ctrl+Shift+A"], scope: "global", run: () => calendarUI.newEvent({}) },
    { id: "mail.newMeeting", label: "New Meeting", icon: "people", keys: ["Ctrl+Shift+Q"], scope: "global", run: () => calendarUI.newEvent({ attendees: [] }) },
    { id: "mail.newContact", label: "New Contact", icon: "person-add", keys: ["Ctrl+Shift+C"], scope: "global", run: () => peopleUI.edit(null) },
    { id: "mail.newContactGroup", label: "New Contact Group", icon: "people", keys: ["Ctrl+Shift+L"], scope: "global", run: () => peopleUI.editList(null) },
    { id: "mail.newTask", label: "New Task", icon: "todo", keys: ["Ctrl+Shift+K"], scope: "global", run: () => todoUI.quickAdd() },
  ]);
  function templateItems() {
    const tpls = state.settings?.templates || [];
    return [...tpls.map((t) => ({ label: t.name, icon: "note", onClick: () => compose.open({ mode: "new", subject: t.subject || "", html: t.html }) })),
      tpls.length ? { sep: true } : null, { label: "Manage Templates", icon: "settings", onClick: () => settingsUI.open("templates") }];
  }

  // ------------------------------------------------------------------ Home: Delete
  add("Home", "Delete", [
    { id: "mail.delete", label: "Delete", icon: "trash", keys: ["Delete", "Backspace"], ...mailOnly, popular: true, isEnabled: sel, run: (ctx) => actions.delete(need(ctx)) },
    { id: "mail.deletePermanent", label: "Delete Permanently", icon: "trash", keys: ["Shift+Delete"], ...mailOnly, isEnabled: sel, run: (ctx) => actions.delete(need(ctx), true) },
    { id: "mail.deleteMenu", label: "Delete Options", icon: "trash", kind: "menu", internal: true, menu: (ctx) => { const t = T(ctx); return [
      { label: "Delete", icon: "trash", disabled: !t.length, onClick: () => actions.delete(t) },
      { label: "Delete permanently", icon: "trash", danger: true, disabled: !t.length, onClick: () => actions.delete(t, true) },
      { label: "Ignore conversation", icon: "mail-x", disabled: t.length !== 1, onClick: () => Commands.run("mail.ignore", { items: t }) },
      { label: "Clean Up Conversation", icon: "sweep", disabled: t.length !== 1, onClick: () => Commands.run("mail.cleanUpConversation", { items: t }) },
      { label: "Clean Up Folder", icon: "sweep", onClick: () => Commands.run("folder.cleanUp") },
      { sep: true },
      { label: "Empty folder", icon: "trash", danger: true, onClick: () => mailplus.emptyFolder(curFolder()) },
    ]; } },
    { id: "mail.ignore", label: "Ignore Conversation", icon: "mail-x", isEnabled: (ctx) => T(ctx).length >= 1, keywords: "mute kill thread", run: (ctx) => actions.ignore(mailplus.conversationOf(needOne(ctx))) },
    { id: "mail.cleanUpConversation", label: "Clean Up Conversation", icon: "sweep", isEnabled: sel, tip: "Deletes messages whose text is fully quoted in a later reply", run: (ctx) => cleanUp(mailplus.conversationOf(needOne(ctx))) },
    { id: "mail.archive", label: "Archive", icon: "archive", keys: ["E"], ...mailOnly, popular: true, isEnabled: sel, run: (ctx) => actions.archive(need(ctx)) },
    { id: "mail.junk", label: "Report Junk", icon: "junk", keys: ["J"], ...mailOnly, popular: true, isEnabled: sel, run: (ctx) => actions.junk(need(ctx), state.folderRole !== "junk") },
    { id: "mail.notJunk", label: "Not Junk", icon: "shield-ok", keys: ["Shift+J"], ...mailOnly, isEnabled: sel, run: (ctx) => actions.junk(need(ctx), false) },
    { id: "mail.reportMenu", label: "Junk Options", icon: "junk", kind: "menu", menu: (ctx) => { const t = T(ctx); return [
      { label: "Report junk", icon: "junk", disabled: !t.length, onClick: () => actions.junk(t, true) },
      { label: "Report phishing", icon: "phish", disabled: !t.length, onClick: () => actions.phish(t) },
      { label: "Not junk", icon: "shield-ok", disabled: !t.length, onClick: () => actions.junk(t, false) },
      { label: "Block sender", icon: "block", danger: true, disabled: t.length !== 1, onClick: () => actions.blockSender(t[0]) },
      { label: "Never block sender", icon: "shield-ok", disabled: t.length !== 1, onClick: () => Commands.run("mail.neverBlockSender", { items: t }) },
      { label: "Never block sender's domain", icon: "shield-ok", disabled: t.length !== 1, onClick: () => Commands.run("mail.neverBlockDomain", { items: t }) },
      { label: "Never block this group or mailing list", icon: "shield-ok", disabled: t.length !== 1, onClick: () => Commands.run("mail.neverBlockList", { items: t }) },
      { sep: true },
      { label: "Junk email options", icon: "settings", onClick: () => settingsUI.open("junk") },
    ]; } },
    { id: "mail.phish", label: "Report Phishing", icon: "phish", isEnabled: sel, run: (ctx) => actions.phish(need(ctx)) },
    { id: "mail.blockSender", label: "Block Sender", icon: "block", isEnabled: sel, run: (ctx) => actions.blockSender(needOne(ctx)) },
    { id: "mail.neverBlockSender", label: "Never Block Sender", icon: "shield-ok", isEnabled: sel, run: (ctx) => safeAdd(needOne(ctx).fromAddress) },
    { id: "mail.neverBlockDomain", label: "Never Block Sender's Domain", icon: "shield-ok", isEnabled: sel, run: (ctx) => safeAdd(domainOf(needOne(ctx).fromAddress)) },
    { id: "mail.neverBlockList", label: "Never Block This Group or Mailing List", icon: "shield-ok", isEnabled: sel, run: async (ctx) => { const d = await openDetail(ctx); const listAddr = (d.to || []).find((a) => !MC.me().has(a.toLowerCase())) || d.fromAddress; safeAdd(listAddr); } },
    { id: "mail.junkOptions", label: "Junk Email Options", icon: "settings", run: () => settingsUI.open("junk") },
    { id: "mail.sweep", label: "Sweep", icon: "sweep", isEnabled: sel, run: (ctx) => mailplus.sweep(needOne(ctx)) },
  ]);
  async function safeAdd(entry) {
    entry = String(entry || "").toLowerCase(); if (!entry) return;
    const s = state.settings;
    await settingsSave({ ...s, spam: { ...s.spam, safeSenders: [...new Set([...s.spam.safeSenders, entry])], blockedSenders: s.spam.blockedSenders.filter((x) => x !== entry) } });
    toast(`${entry} will never be treated as junk.`, { action: "Safe senders", onAction: () => settingsUI.open("senders") });
  }
  /** Mailbox Cleanup: size and quota, large items, old items, Deleted Items and Compact in one place. */
  async function mailboxCleanup() {
    const node = el("div", {}, el("p", { class: "muted", text: "Checking your mailbox…" }));
    MC.panel("Mailbox Cleanup", node);
    const [q, stats] = await Promise.all([api("/api/quota").catch(() => null), api("/api/x/folder-stats", { query: { folder: state.folder === SCHEDULED ? "INBOX" : state.folder } }).catch(() => null)]);
    const trash = folders.byRole("trash");
    node.innerHTML = "";
    node.append(
      el("dl", { class: "kv" },
        el("dt", { text: "Mailbox size" }), el("dd", { text: q && q.limitKb > 0 ? `${fmtSize(q.usedKb * 1024)} of ${fmtSize(q.limitKb * 1024)} (${Math.round((q.usedKb / q.limitKb) * 100)}%)` : "Your server does not report a quota" }),
        stats ? el("dt", { text: `This folder (${MC.folderName(stats.path)})` }) : null, stats ? el("dd", { text: `${plural(stats.total, "message")}, ${fmtSize(stats.sizeBytes)}${stats.deleted ? `, ${stats.deleted} marked deleted` : ""}` }) : null),
      el("div", { class: "qb-msglist" },
        ...[["new.bigAttachments", "Find items larger than 1 MB in every folder"], ["folder.archiveOld", "Move old items in this folder to Archive"], ["folder.compact", "Remove messages marked deleted from this folder"], ["folder.emptyTrash", trash ? `Empty ${trash.name}` : "Empty Deleted Items"], ["folder.properties", "Folder size and dates"]]
          .map(([id, text]) => el("div", { class: "qb-msgrow" }, icon(Commands.get(id).icon), el("div", { class: "grow", text }), el("button", { class: "btn sm", onclick: () => { $("popupDialog").close(); Commands.run(id); } }, Commands.get(id).label.replace(/…$/, ""))))));
  }
  /** Deletes the messages of [msgs] whose own text is fully quoted by a later one (needs the bodies). */
  async function cleanUp(msgs) {
    const full = [];
    for (const m of msgs.slice(0, 40)) { try { const d = await detail(m); full.push({ key: mkey(m), date: d.date, text: mailplus.bodyText(d), m }); } catch {} }
    const redundant = new Set(MailTools.redundantMessages(full));
    const victims = full.filter((x) => redundant.has(x.key)).map((x) => x.m);
    if (!victims.length) { toast("Nothing to clean up: every message has text of its own."); return 0; }
    await actions.delete(victims);
    return victims.length;
  }

  // ------------------------------------------------------------------ Home: Respond
  const respond = (mode) => async (ctx) => compose.open({ mode, detail: await openDetail(ctx) });
  add("Home", "Respond", [
    { id: "mail.reply", label: "Reply", icon: "reply", keys: ["R", "Ctrl+R"], ...mailOnly, popular: true, isEnabled: sel, run: respond("reply") },
    { id: "mail.replyAll", label: "Reply All", icon: "reply-all", keys: ["A", "Ctrl+Shift+R"], ...mailOnly, popular: true, isEnabled: sel, run: respond("replyAll") },
    { id: "mail.forward", label: "Forward", icon: "forward", keys: ["F", "Ctrl+Shift+F"], ...mailOnly, popular: true, isEnabled: sel, run: respond("forward") },
    { id: "mail.forwardAttach", label: "Forward as Attachment", icon: "attach", keys: ["Ctrl+Alt+F"], ...mailOnly, isEnabled: sel, run: respond("forwardAttach") },
    { id: "mail.forwardMenu", label: "Forward Options", icon: "forward", kind: "menu", internal: true, menu: (ctx) => [
      { label: "Forward", icon: "forward", onClick: () => Commands.run("mail.forward", ctx) },
      { label: "Forward as attachment", icon: "attach", onClick: () => Commands.run("mail.forwardAttach", ctx) },
      { label: "Redirect…", icon: "send", onClick: () => Commands.run("eudora.redirect", ctx) },
      { label: "Bounce…", icon: "send", onClick: () => Commands.run("pine.bounce", ctx) },
    ] },
    { id: "mail.replyMeeting", label: "Reply with Meeting", icon: "calendar", keys: ["Ctrl+Alt+R"], ...mailOnly, isEnabled: sel,
      run: async (ctx) => { const d = await openDetail(ctx); const ppl = [d.fromAddress, ...(d.to || []), ...(d.cc || [])].filter((a) => a && !MC.me().has(a.toLowerCase())); calendarUI.newEvent({ title: d.subject.replace(/^\s*(re|fw|fwd)\s*:\s*/i, ""), attendees: [...new Set(ppl)].map((email) => ({ email, name: "" })) }); } },
    { id: "mail.replySender", label: "Reply to Sender Only", icon: "reply", isEnabled: sel, keywords: "thunderbird", run: async (ctx) => { const d = await openDetail(ctx); await compose.open({ mode: "reply", detail: d }); $("cTo").value = d.fromAddress; } },
    { id: "mail.editAsNew", label: "Edit As New Message", icon: "edit", keys: ["Ctrl+Alt+E"], ...mailOnly, isEnabled: sel, origin: "Thunderbird", keywords: "resend this message mutt resend-message",
      run: async (ctx) => compose.open({ mode: "editAsNew", detail: await openDetail(ctx) }) },
    { id: "mail.respondMore", label: "More Respond Actions", icon: "more", kind: "menu", menu: (ctx) => [
      { label: "Forward as attachment", icon: "attach", onClick: () => Commands.run("mail.forwardAttach", ctx) },
      { label: "Reply with meeting", icon: "calendar", onClick: () => Commands.run("mail.replyMeeting", ctx) },
      { label: "Reply to sender only", icon: "reply", onClick: () => Commands.run("mail.replySender", ctx) },
      { label: "Reply to list", icon: "people", onClick: () => Commands.run("mutt.listReply", ctx) },
      { label: "Edit as new message", icon: "edit", onClick: () => Commands.run("mail.editAsNew", ctx) },
      { label: "Redirect…", icon: "send", onClick: () => Commands.run("eudora.redirect", ctx) },
      { label: "Bounce…", icon: "send", onClick: () => Commands.run("pine.bounce", ctx) },
      { label: "Reply with time slots", icon: "clock", onClick: () => Commands.run("new.replyTimeSlots", ctx) },
    ] },
  ]);

  // ------------------------------------------------------------------ Home: Quick Steps, Move, Rules
  add("Home", "Quick Steps", [
    { id: "mail.quickSteps", label: "Quick Steps", icon: "bolt", kind: "menu", popular: true, menu: (ctx) => mailplus.quickStepsMenu(T(ctx)) },
    { id: "mail.newQuickStep", label: "New Quick Step…", icon: "plus", run: () => { settingsUI.open("quicksteps"); mailplus.settings.editQuickStep(null); } },
    { id: "mail.manageQuickSteps", label: "Manage Quick Steps", icon: "settings", run: () => settingsUI.open("quicksteps") },
  ]);
  add("Home", "Move", [
    { id: "mail.moveTo", label: "Move To", icon: "move", kind: "menu", keys: ["Ctrl+Shift+V"], ...mailOnly, popular: true, isEnabled: sel, menu: (ctx) => { const t = need(ctx); return [
      ...mailplus.recentFolders().filter((f) => f.path !== state.folder).map((f) => ({ label: f.name, icon: FOLDER_ICONS[f.role] || "folder", onClick: () => (f.role === "junk" ? actions.junk(t, true) : actions.move(t, f.path)) })),
      mailplus.recentFolders().length ? { sep: true } : null,
      { label: "Other folder…", icon: "move", onClick: () => actions.pickFolder(t) },
      { label: "Copy to folder…", icon: "copy", onClick: () => actions.pickFolder(t, { copy: true }) },
      { label: "Archive", icon: "archive", onClick: () => actions.archive(t) },
      { label: "Always move messages in this conversation…", icon: "repeat", onClick: () => Commands.run("mail.alwaysMoveConversation", { items: t }) },
    ].filter(Boolean); } },
    { id: "mail.moveOther", label: "Move to Other Folder…", icon: "move", isEnabled: sel, run: (ctx) => actions.pickFolder(need(ctx)) },
    { id: "mail.copyTo", label: "Copy to Folder…", icon: "copy", isEnabled: sel, run: (ctx) => actions.pickFolder(need(ctx), { copy: true }) },
    { id: "mail.alwaysMoveConversation", label: "Always Move Messages in This Conversation…", icon: "repeat", isEnabled: (ctx) => sel(ctx) && imap(),
      run: (ctx) => { const t = mailplus.conversationOf(needOne(ctx)); pickFolderThen("Always move this conversation to", async (path) => { const r = await api("/api/x/routes", { method: "POST", body: { items: MC.refs(t), kind: "move", dest: path } }); list.removeLocal(t); toast(`Conversation moved (${plural(r.ok, "message")}); later replies go to ${MC.folderName(path)} too.`); folders.load(); }); } },
    { id: "mail.rulesMenu", label: "Rules", icon: "rules", kind: "menu", menu: (ctx) => { const t = T(ctx); return [
      { label: "Always move messages from this sender…", icon: "move", disabled: t.length !== 1, onClick: () => Commands.run("mail.alwaysMoveFromSender", { items: t }) },
      { label: "Create rule from this message…", icon: "plus", disabled: t.length !== 1, onClick: () => mailplus.createRuleFrom(t[0]) },
      { label: "Analyse & make junk rule", icon: "sparkle", disabled: t.length !== 1, onClick: () => Commands.run("mail.analyze", { items: t }) },
      { label: "Run rules now", icon: "bolt", onClick: () => Commands.run("folder.runRules") },
      { label: "Manage rules & alerts", icon: "settings", onClick: () => settingsUI.open("rules") },
    ]; } },
    { id: "mail.createRule", label: "Create Rule…", icon: "rules", isEnabled: sel, run: (ctx) => mailplus.createRuleFrom(needOne(ctx)) },
    { id: "mail.alwaysMoveFromSender", label: "Always Move Messages From Sender…", icon: "move", isEnabled: sel,
      run: (ctx) => { const m = needOne(ctx); pickFolderThen(`Always move mail from ${m.fromAddress} to`, async (path) => {
        const f = folders.byPath(path);
        const rule = { id: "rule-" + Math.random().toString(36).slice(2, 10), name: `Always move: ${m.fromAddress}`, enabled: true, logic: "AND", criteria: [{ field: "SENDER", operator: "EQUALS", value: m.fromAddress }], action: "MOVE_TO_FOLDER", targetFolder: path, priority: 60, createdBy: "user", createdAt: Date.now(), note: "", hitCount: 0 };
        await settingsSave({ ...state.settings, rules: [...state.settings.rules, rule] });
        const r = await api("/api/rules/apply", { method: "POST", body: { rule, folder: state.folder === SCHEDULED ? "INBOX" : state.folder, limit: 300 } });
        toast(`Rule created · ${plural(r.ok, "message")} moved to ${f ? f.name : path}`); list.fetch({ silent: true }); folders.load();
      }); } },
    { id: "mail.viewInBrowser", label: "View in Browser", icon: "external", isEnabled: sel, run: async (ctx) => mailplus.openInWindow(await openDetail(ctx)) },
    { id: "mail.openInWindow", label: "Open in New Window", icon: "external", keys: ["Ctrl+O"], ...mailOnly, isEnabled: sel, run: async (ctx) => mailplus.openInWindow(await openDetail(ctx)) },
  ]);
  function pickFolderThen(title, fn) {
    const ul = $("mvList"), search = $("mvSearch");
    $("mvTitle").textContent = title;
    const render = () => {
      ul.innerHTML = "";
      const q = search.value.trim().toLowerCase();
      for (const f of state.folders) {
        if (q && !f.name.toLowerCase().includes(q) && !f.path.toLowerCase().includes(q)) continue;
        const li = el("li", { class: f.role, style: { "--depth": q ? 0 : f.depth } }, icon(FOLDER_ICONS[f.role] || "folder", 18), el("span", { class: "name", text: f.name }));
        li.onclick = () => { $("moveDialog").close(); Promise.resolve(fn(f.path)).catch((e) => toast(e.message, { danger: true })); };
        ul.appendChild(li);
      }
    };
    search.value = ""; search.oninput = render; render();
    $("moveDialog").showModal(); setTimeout(() => search.focus(), 30);
  }
  MC.pickFolderThen = pickFolderThen;

  // ------------------------------------------------------------------ Home: Tags
  const followDays = (n) => { const d = new Date(); if (n === "week") return ymd(addDays(d, (5 - d.getDay() + 7) % 7)); if (n === "next") return ymd(nextWeekday(1)); return ymd(addDays(d, n)); };
  add("Home", "Tags", [
    { id: "mail.toggleRead", label: "Unread/Read", icon: "read", isEnabled: sel, run: (ctx) => { const t = need(ctx); return actions.read(t, !t.every((m) => m.read)); } },
    { id: "mail.markRead", label: "Mark as Read", icon: "read", keys: ["Ctrl+Q"], ...mailOnly, popular: true, isEnabled: sel, run: (ctx) => actions.read(need(ctx), true) },
    { id: "mail.markUnread", label: "Mark as Unread", icon: "unread", keys: ["Ctrl+U", "U"], ...mailOnly, isEnabled: sel, run: (ctx) => { clearTimeout(reading.markTimer); return actions.read(need(ctx), false); } },
    { id: "mail.categorize", label: "Categorize", icon: "tag", kind: "menu", popular: true, isEnabled: sel, menu: (ctx) => mailplus.categorizeMenu(need(ctx)) },
    { id: "mail.clearCategories", label: "Clear All Categories", icon: "close", isEnabled: sel, run: (ctx) => actions.clearCategories(need(ctx)) },
    { id: "mail.manageCategories", label: "Manage Categories", icon: "settings", run: () => settingsUI.open("categories") },
    { id: "mail.flag", label: "Flag / Unflag", icon: "flag", keys: ["Insert", "S"], ...mailOnly, popular: true, kind: "toggle", isEnabled: sel, isActive: (ctx) => { const t = T(ctx); return t.length > 0 && t.every((m) => m.flagged); }, run: (ctx) => { const t = need(ctx); return actions.flag(t, !t.every((m) => m.flagged)); } },
    { id: "mail.followUp", label: "Follow Up", icon: "flag", kind: "menu", isEnabled: sel, menu: (ctx) => [...mailplus.flagMenu(need(ctx)), { label: "Add reminder…", icon: "bell", onClick: () => Commands.run("mail.addReminder", ctx) }] },
    { id: "mail.flagToday", label: "Follow Up: Today", icon: "flag", isEnabled: sel, run: (ctx) => actions.flag(need(ctx), true, followDays(0)) },
    { id: "mail.flagTomorrow", label: "Follow Up: Tomorrow", icon: "flag", isEnabled: sel, run: (ctx) => actions.flag(need(ctx), true, followDays(1)) },
    { id: "mail.flagThisWeek", label: "Follow Up: This Week", icon: "flag", isEnabled: sel, run: (ctx) => actions.flag(need(ctx), true, followDays("week")) },
    { id: "mail.flagNextWeek", label: "Follow Up: Next Week", icon: "flag", isEnabled: sel, run: (ctx) => actions.flag(need(ctx), true, followDays("next")) },
    { id: "mail.flagNoDate", label: "Follow Up: No Date", icon: "flag", isEnabled: sel, run: (ctx) => actions.flag(need(ctx), true, "") },
    { id: "mail.flagCustom", label: "Follow Up: Custom…", icon: "calendar", isEnabled: sel, run: async (ctx) => { const t = need(ctx); const v = await formDialog.open({ title: "Custom follow up", fields: [{ key: "start", label: "Start date", type: "date", value: ymd(new Date()) }, { key: "due", label: "Due date", type: "date", value: followDays(1), required: true }] }); if (!v) return; await actions.flag(t, true, v.due); if (v.start) await actions.meta(t, { flagStart: v.start }); } },
    { id: "mail.addReminder", label: "Add Reminder…", icon: "bell", isEnabled: sel, run: async (ctx) => { const t = need(ctx); const v = await formDialog.open({ title: "Reminder", fields: [{ key: "when", label: "Remind me at", type: "datetime-local", value: localIso(new Date(Date.now() + 3600e3)), required: true }] }); if (!v) return; await actions.flag(t, true); await actions.meta(t, { reminder: new Date(v.when).getTime() }); toast(`Reminder set for ${fmtDate(new Date(v.when).getTime(), { long: true })}`); } },
    { id: "mail.markComplete", label: "Mark Complete", icon: "check-circle", isEnabled: sel, run: (ctx) => actions.completeFlag(need(ctx)) },
    { id: "mail.clearFlag", label: "Clear Flag", icon: "close", isEnabled: sel, run: (ctx) => actions.flag(need(ctx), false) },
    { id: "mail.pin", label: "Pin / Unpin", icon: "pin", kind: "toggle", isEnabled: sel, isActive: (ctx) => { const t = T(ctx); return t.length > 0 && t.every((m) => m.pinned); }, run: (ctx) => { const t = need(ctx); return actions.pin(t, !t.every((m) => m.pinned)); } },
    { id: "mail.snooze", label: "Snooze", icon: "clock", kind: "menu", isEnabled: (ctx) => sel(ctx) && imap(), keywords: "defer later gmail", menu: (ctx) => mailplus.snoozeMenu(need(ctx)) },
  ]);

  // ------------------------------------------------------------------ Home: Find, Speech, Language, Junk tools
  add("Home", "Find", [
    { id: "mail.focusSearch", label: "Search Mail", icon: "search", keys: ["/", "Ctrl+E", "F3"], scope: "global", run: () => { shell.setModule("mail", { silent: true }); $("searchInput").focus(); $("searchInput").select(); } },
    { id: "mail.advancedSearch", label: "Advanced Find…", icon: "filter", keys: ["Ctrl+Alt+A"], scope: "global", run: () => mailplus.searchDialog() },
    { id: "mail.searchPeople", label: "Search People", icon: "people", run: () => { shell.setModule("people"); setTimeout(() => $("searchInput").focus(), 50); } },
    { id: "mail.addressBook", label: "Address Book", icon: "contact", keys: ["Ctrl+Shift+B"], scope: "global", run: () => shell.setModule("people") },
    { id: "mail.filterEmail", label: "Filter Email", icon: "filter", kind: "menu", popular: true, menu: () => [
      ...FILTERS.map(([v, l]) => ({ label: l, checked: state.filter === v, onClick: () => { state.filter = v; state.selected.clear(); list.render(); } })),
      { label: "This week", checked: state.filter === "x:week", onClick: () => { state.filter = "x:week"; list.render(); } },
      { label: "Important", checked: state.filter === "x:important", onClick: () => { state.filter = "x:important"; list.render(); } },
      { label: "Categorized", checked: state.filter === "x:categorized", onClick: () => { state.filter = "x:categorized"; list.render(); } },
      { label: "Sent to: me only", checked: state.filter === "x:toMeOnly", onClick: () => { state.filter = "x:toMeOnly"; list.render(); } },
      { label: "Tagged", checked: state.filter === "x:tagged", onClick: () => { state.filter = "x:tagged"; list.render(); } },
    ] },
    { id: "mail.clearFilter", label: "Clear Filter", icon: "close", run: () => { state.filter = "all"; list.render(); } },
    { id: "mail.findRelatedConversation", label: "Find Related: Messages in This Conversation", icon: "reply-all", isEnabled: sel, run: async (ctx) => { const d = await openDetail(ctx); const s = normSubject(d.subject); $("searchInput").value = s; list.search(s, { opts: { scope: "all", subject: s } }); } },
    { id: "mail.findRelatedSender", label: "Find Related: Messages from Sender", icon: "user", isEnabled: sel, run: (ctx) => { const m = needOne(ctx); $("searchInput").value = m.fromAddress; list.search("", { opts: { scope: "all", from: m.fromAddress } }); } },
    { id: "mail.analyze", label: "Analyse & Make Rule", icon: "sparkle", isEnabled: sel, run: async (ctx) => analysis.open(await openDetail(ctx)) },
    { id: "mail.rescan", label: "Re-scan Folder for Junk", icon: "junk", run: async () => { toast("Re-scanning this folder…"); await list.fetch({ rescan: true }); } },
  ]);
  add("Home", "Speech", [
    { id: "mail.readAloud", label: "Read Aloud", icon: "volume", keys: ["Ctrl+Alt+Space"], ...mailOnly, isEnabled: sel, run: async (ctx) => readAloud(await openDetail(ctx)) },
    { id: "mail.translate", label: "Translate Message", icon: "globe", isEnabled: sel, run: async (ctx) => mailplus.translate(await openDetail(ctx)) },
  ]);
  function readAloud(d) {
    if (!("speechSynthesis" in window)) throw new UserError("Read aloud is not available in this browser.");
    if (speechSynthesis.speaking) { speechSynthesis.cancel(); return; }
    const u = new SpeechSynthesisUtterance(`${d.subject}. From ${d.fromName || d.fromAddress}. ${MailTools.freshText(mailplus.bodyText(d))}`);
    speechSynthesis.speak(u);
    toast("Reading aloud — run Read Aloud again to stop.", { action: "Stop", onAction: () => speechSynthesis.cancel() });
  }
  MC.readAloud = readAloud;

  // ------------------------------------------------------------------ Home: selection and navigation
  add("Home", "Select", [
    { id: "mail.selectAll", label: "Select All", icon: "select", keys: ["Ctrl+A"], ...mailOnly, run: () => list.selectAll(true) },
    { id: "mail.toggleSelect", label: "Select / Deselect Message", icon: "check", keys: ["X"], ...mailOnly, isEnabled: () => !!state.open, run: () => list.toggleSelect(mkey(state.open), false) },
    { id: "mail.escape", label: "Clear Selection / Close Message", icon: "close", keys: ["Escape"], ...mailOnly, internal: true, run: () => { if (state.selected.size) list.selectAll(false); else reading.close(); } },
    { id: "mail.nextItem", label: "Next Item", icon: "chev-down", keys: ["ArrowDown", "Ctrl+."], ...mailOnly, run: () => list.moveFocus(1) },
    { id: "mail.prevItem", label: "Previous Item", icon: "chev-left", keys: ["ArrowUp", "Ctrl+,"], ...mailOnly, run: () => list.moveFocus(-1) },
    { id: "mail.copyText", label: "Copy Message Text", icon: "copy", isEnabled: sel, run: async (ctx) => { const d = await openDetail(ctx); await navigator.clipboard.writeText(mailplus.bodyText(d)); toast("Message text copied"); } },
    { id: "mail.copySender", label: "Copy Sender Address", icon: "copy", isEnabled: sel, keywords: "copy email address thunderbird", run: async (ctx) => { await navigator.clipboard.writeText(needOne(ctx).fromAddress); toast("Address copied"); } },
    { id: "mail.addSenderToContacts", label: "Add Sender to Contacts", icon: "person-add", isEnabled: sel, run: (ctx) => { const m = needOne(ctx); peopleUI.edit(null, { name: m.fromName, email: m.fromAddress }); } },
    { id: "mail.viewSource", label: "View Message Source", icon: "note", isEnabled: sel, run: async (ctx) => { const m = needOne(ctx); const r = await api("/api/raw", { query: { folder: m.folder || state.folder, uid: m.uid }, raw: true }); popup("Message source", { text: await r.text() }); } },
    { id: "mail.editMessage", label: "Edit Message (Subject)…", icon: "edit", isEnabled: (ctx) => sel(ctx) && imap(), tip: "Changes the subject of a stored message, as Actions → Edit Message does",
      run: async (ctx) => { const m = needOne(ctx); const v = await formDialog.open({ title: "Edit message subject", fields: [{ key: "subject", label: "Subject", value: m.subject, required: true }] }); if (!v || v.subject === m.subject) return; const r = await api("/api/x/rewrite", { method: "POST", body: { folder: m.folder || state.folder, uid: m.uid, subject: v.subject } }); toast("Subject changed"); if (r.uid && state.lastInboxUids) state.lastInboxUids.add(r.uid); await list.fetch({ silent: true }); if (r.uid) { const nm = state.messages.find((x) => x.uid === r.uid && (x.folder || state.folder) === r.folder); if (nm) reading.open(nm, { force: true }); } } },
  ]);

  // ------------------------------------------------------------------ Send / Receive
  add("Send / Receive", "Send & Receive", [
    { id: "mail.sendReceiveAll", label: "Send/Receive All Folders", icon: "refresh", keys: ["F9"], scope: "global", popular: true, run: async () => { await Promise.all([list.fetch(), folders.load(), mailplus.loadOutbox()]); mailplus.refreshOtherAccounts(); qatUI.lastSync = Date.now(); } },
    { id: "mail.updateFolder", label: "Update Folder", icon: "refresh", keys: ["Shift+F9"], scope: "global", run: () => list.fetch() },
    { id: "mail.sendAll", label: "Send All", icon: "send", tip: "Sends every message waiting in the outbox now", run: async () => { const r = await api("/api/x/outbox/flush", { method: "POST" }); toast(r.ok ? `Sent ${plural(r.ok, "message")}` : "The outbox is empty."); if (r.errors?.length) toast(r.errors[0], { danger: true }); mailplus.loadOutbox(); folders.load(); } },
    { id: "mail.showProgress", label: "Show Progress", icon: "list", run: () => showProgress() },
    { id: "mail.sendReceiveSettings", label: "Send/Receive Settings", icon: "settings", kind: "menu", keywords: "schedule interval groups define claris emailer", menu: () => [["30", "Every 30 seconds"], ["60", "Every minute"], ["300", "Every 5 minutes"], ["900", "Every 15 minutes"], ["0", "Manual only"]].map(([v, l]) => ({ label: l, checked: String(P("autoRefresh", 60)) === v, onClick: () => { prefs.set("autoRefresh", Number(v)); list.schedulePoll(); toast(`Checking for new mail: ${l.toLowerCase()}`); } })) },
    { id: "mail.workOffline", label: "Work Offline", icon: "bell-off", kind: "toggle", tip: "Stops checking for mail; messages you send wait in the outbox until you go online", isActive: () => !!P("workOffline", false),
      run: () => { const on = !P("workOffline", false); prefs.set("workOffline", on); if (on) { clearTimeout(state.pollTimer); toast("Working offline: no mail is checked and sent messages wait in the outbox."); } else { list.schedulePoll(); toast("Back online.", state.outbox.length ? { action: "Send All", onAction: () => Commands.run("mail.sendAll") } : {}); } qatUI.refresh(); } },
    { id: "mail.viewOutbox", label: "Outbox (Scheduled)", icon: "send-later", run: () => list.load(SCHEDULED) },
  ]);
  function showProgress() {
    const node = el("div", {},
      el("p", {}, el("b", { text: "Junk analysis: " }), state.pending > 0 ? `${state.pending} message(s) still being checked` : "idle"),
      el("p", {}, el("b", { text: "Outbox: " }), state.outbox.length ? `${plural(state.outbox.length, "message")} waiting` : "empty"),
      el("p", {}, el("b", { text: "Automatic check: " }), P("workOffline", false) ? "off (working offline)" : P("autoRefresh", 60) ? `every ${P("autoRefresh", 60)} s` : "manual"),
      el("p", {}, el("b", { text: "Last full send/receive: " }), qatUI.lastSync ? fmtDate(qatUI.lastSync, { long: true }) : "this session's first load"));
    MC.panel("Send/Receive progress", node);
  }

  // ------------------------------------------------------------------ Folder
  add("Folder", "New", [
    { id: "folder.new", label: "New Folder", icon: "folder-plus", keys: ["Ctrl+Shift+E"], scope: "global", isEnabled: imap, run: () => mailplus.createFolder(null) },
    { id: "folder.newSub", label: "New Subfolder", icon: "folder-plus", isEnabled: imap, run: () => mailplus.createFolder(state.folder === SCHEDULED ? null : state.folder) },
  ]);
  add("Folder", "Actions", [
    { id: "folder.rename", label: "Rename Folder", icon: "edit", isEnabled: () => curFolder()?.role === "other", run: () => mailplus.renameFolder(curFolder()) },
    { id: "folder.copy", label: "Copy Folder…", icon: "copy", isEnabled: () => imap() && !!curFolder(), run: () => pickFolderThen(`Copy "${curFolder().name}" into`, async (parent) => { const r = await api("/api/x/folder-copy", { method: "POST", body: { path: state.folder, parent } }); toast(`Copied ${plural(r.copied, "message")} to ${r.path}`); folders.load(); }) },
    { id: "folder.move", label: "Move Folder…", icon: "move", isEnabled: () => curFolder()?.role === "other", run: () => mailplus.moveFolder(curFolder()) },
    { id: "folder.delete", label: "Delete Folder", icon: "trash", isEnabled: () => curFolder()?.role === "other", run: () => mailplus.deleteFolder(curFolder()) },
    { id: "folder.goTo", label: "Go to Folder…", icon: "folder", keys: ["Ctrl+Y"], scope: "global", keywords: "change folder mutt", run: () => pickFolderThen("Go to folder", (p) => { shell.setModule("mail", { silent: true }); list.load(p); }) },
    { id: "folder.favorite", label: "Show in Favorites", icon: "star", kind: "toggle", isEnabled: () => !!curFolder(), isActive: () => (state.settings?.favorites || []).includes(state.folder), run: () => mailplus.toggleFavorite(curFolder()) },
    { id: "folder.properties", label: "Folder Properties", icon: "info", isEnabled: () => !!curFolder(), run: async () => { const s = await api("/api/x/folder-stats", { query: { folder: state.folder } }); const kv = el("dl", { class: "kv" }); const add2 = (k, v) => { kv.append(el("dt", { text: k }), el("dd", { text: v })); }; add2("Folder", s.path); add2("Messages", String(s.total)); add2("Unread", String(s.unread)); add2("Flagged", String(s.flagged)); add2("Marked deleted", String(s.deleted)); add2("Size", fmtSize(s.sizeBytes)); add2("Oldest", s.oldest ? fmtDate(s.oldest, { long: true }) : "—"); add2("Newest", s.newest ? fmtDate(s.newest, { long: true }) : "—"); MC.panel(`Properties: ${MC.folderName(state.folder)}`, kv); } },
  ]);
  add("Folder", "Clean Up", [
    { id: "folder.markAllRead", label: "Mark All as Read", icon: "check", keys: ["Ctrl+Alt+Q"], scope: "mail", popular: true, run: () => mailplus.markAllRead(curFolder()) },
    { id: "folder.markReadByDate", label: "Mark as Read by Date…", icon: "calendar", origin: "Thunderbird", isEnabled: () => !!curFolder(),
      run: async () => { const v = await formDialog.open({ title: "Mark as read by date", fields: [{ key: "since", label: "From", type: "date", value: ymd(addDays(new Date(), -30)) }, { key: "before", label: "To (inclusive)", type: "date", value: ymd(new Date()) }] }); if (!v) return; const r = await api("/api/x/markread-range", { method: "POST", body: { path: state.folder, since: v.since ? parseYmd(v.since).getTime() : null, before: v.before ? addDays(parseYmd(v.before), 1).getTime() : null } }); toast(`${plural(r.ok, "message")} marked as read`); list.fetch({ silent: true }); folders.load(); } },
    { id: "folder.runRules", label: "Run Rules Now", icon: "bolt", keywords: "run filters on folder thunderbird apply", run: async () => { const rules = (state.settings.rules || []).filter((r) => r.enabled); if (!rules.length) throw new UserError("No rules are turned on."); let n = 0; for (const r of rules) { try { n += (await api("/api/rules/apply", { method: "POST", body: { rule: r, folder: state.folder === SCHEDULED ? "INBOX" : state.folder, limit: 300 } })).ok; } catch {} } toast(`Ran ${plural(rules.length, "rule")} · ${plural(n, "message")} acted on`); list.fetch({ silent: true }); folders.load(); } },
    { id: "folder.cleanUp", label: "Clean Up Folder", icon: "sweep", tip: "Deletes messages whose text is fully quoted in a later reply, conversation by conversation",
      run: async () => { const groups = list.threads(state.messages).filter((g) => g.length > 1); if (!groups.length) throw new UserError("No conversations with several messages here."); let n = 0; for (const g of groups.slice(0, 20)) n += (await cleanUp(g)) || 0; toast(`Clean Up removed ${plural(n, "redundant message")}`); } },
    { id: "folder.deleteAll", label: "Delete All", icon: "trash", run: () => mailplus.emptyFolder(curFolder()) },
    { id: "folder.emptyTrash", label: "Empty Deleted Items Folder", icon: "trash", isEnabled: () => !!folders.byRole("trash"), run: () => mailplus.emptyFolder(folders.byRole("trash")) },
    { id: "folder.recoverDeleted", label: "Recover Deleted Items", icon: "undo", isEnabled: () => imap() && !!curFolder(), tip: "Lists messages marked deleted but not yet removed from the server, and restores them",
      run: async () => { const r = await api("/api/x/deleted", { query: { folder: state.folder } }); const box = MC.messageRows(r.messages, { empty: "No messages in this folder are marked deleted. Messages deleted to Deleted Items can be moved back from there.", extra: (m) => el("button", { class: "btn sm", onclick: async () => { await api("/api/x/flags", { method: "POST", body: { items: [{ folder: m.folder, uid: m.uid }], remove: ["\\Deleted"] } }); toast("Recovered"); list.fetch({ silent: true }); } }, "Recover") }); MC.panel("Recover deleted items", box); } },
    { id: "folder.compact", label: "Compact Folder", icon: "minimize", origin: "Thunderbird", keywords: "expunge pine purge", isEnabled: () => imap() && !!curFolder(), run: async () => { const r = await api("/api/x/compact", { method: "POST", body: { path: state.folder } }); toast(r.ok ? `Removed ${plural(r.ok, "message")} marked deleted` : "Folder compacted — nothing was marked deleted."); list.fetch({ silent: true }); folders.load(); } },
    { id: "folder.archiveOld", label: "Archive Old Items…", icon: "archive", keywords: "autoarchive clean up old items get me to zero", isEnabled: () => imap() && !!curFolder(),
      run: async () => { const v = await formDialog.open({ title: `Archive old items in ${MC.folderName(state.folder)}`, ok: "Archive", fields: [{ key: "days", label: "Archive items older than (days)", type: "number", value: 180, min: 1 }, { key: "gran", label: "Archive folders", type: "select", value: "year", options: [["none", "Archive"], ["year", "Archive/<year>"], ["month", "Archive/<year>/<year-month>"]] }] }); if (!v) return; const r = await api("/api/x/archive", { method: "POST", body: { path: state.folder, olderThanDays: Number(v.days) || 180, granularity: v.gran } }); toast(`Archived ${plural(r.ok, "message")}`); list.fetch({ silent: true }); folders.load(); } },
  ]);
  add("Folder", "View", [
    { id: "folder.showAllAZ", label: "Show All Folders A to Z", icon: "sort", kind: "toggle", isActive: () => !!P("foldersAZ", false), run: () => { prefs.set("foldersAZ", !P("foldersAZ", false)); folders.render(); } },
  ]);

  // ------------------------------------------------------------------ View
  add("View", "Current View", [
    { id: "view.settings", label: "View Settings", icon: "settings", run: () => settingsUI.open("layout") },
    { id: "view.changeView", label: "Change View", icon: "pane", kind: "menu", menu: () => [
      { label: "Compact", checked: P("density", "comfortable") === "compact" && !P("showPreview", true), onClick: () => { prefs.set("density", "compact"); prefs.set("showPreview", false); applyTheme(); } },
      { label: "Single", checked: P("density", "comfortable") === "compact" && P("showPreview", true), onClick: () => { prefs.set("density", "compact"); prefs.set("showPreview", true); applyTheme(); } },
      { label: "Preview", checked: P("density", "comfortable") === "comfortable", onClick: () => { prefs.set("density", "comfortable"); prefs.set("showPreview", true); applyTheme(); } },
    ] },
    { id: "view.reset", label: "Reset View", icon: "undo", run: () => { for (const k of ["sort", "density", "showPreview", "pane", "conversations", "previewLines"]) localStorage.removeItem("qb." + k); state.filter = "all"; applyTheme(); list.render(); toast("View reset"); } },
  ]);
  add("View", "Messages", [
    { id: "view.conversations", label: "Show as Conversations", icon: "reply-all", kind: "toggle", isActive: () => P("conversations", true), run: () => { prefs.set("conversations", !P("conversations", true)); list.render(); } },
    { id: "view.conversationSettings", label: "Conversation Settings", icon: "reply-all", kind: "menu", menu: () => [
      { label: "Show messages as conversations", checked: P("conversations", true), onClick: () => { prefs.set("conversations", true); list.render(); } },
      { label: "Show individual messages", checked: !P("conversations", true), onClick: () => { prefs.set("conversations", false); list.render(); } },
      { sep: true },
      { label: "Newest message on top", checked: P("newestOnTop", true), onClick: () => { prefs.set("newestOnTop", true); reading.renderThread(); } },
      { label: "Newest message at the bottom", checked: !P("newestOnTop", true), onClick: () => { prefs.set("newestOnTop", false); reading.renderThread(); } },
      { label: "Always expand selected conversation", checked: P("expandSelected", false), onClick: () => prefs.set("expandSelected", !P("expandSelected", false)) },
    ] },
    { id: "view.expandAll", label: "Expand All Conversations", icon: "chev-down", run: () => { for (const g of list.threads(state.messages)) if (g.length > 1) state.expanded.add(list.threadKey(g)); list.render(); } },
    { id: "view.collapseAll", label: "Collapse All Conversations", icon: "minus", keywords: "collapse threads mutt", run: () => { state.expanded.clear(); list.render(); } },
    { id: "view.preview", label: "Message Preview", icon: "list", kind: "menu", menu: () => [["0", "Off"], ["1", "1 Line"], ["2", "2 Lines"], ["3", "3 Lines"]].map(([v, l]) => ({ label: l, checked: String(P("previewLines", P("showPreview", true) ? 1 : 0)) === v, onClick: () => { prefs.set("previewLines", Number(v)); prefs.set("showPreview", v !== "0"); applyTheme(); qatUI.applyViewPrefs(); } })) },
    { id: "view.arrangeBy", label: "Arrange By", icon: "sort", kind: "menu", menu: () => { const s = P("sort", { by: "date", dir: "desc" }); return [...[...SORTS, ["to", "To"], ["attachments", "Attachments"]].map(([v, l]) => ({ label: l, checked: s.by === v, onClick: () => { prefs.set("sort", { by: v, dir: ["date", "size", "importance", "flag", "attachments"].includes(v) ? "desc" : "asc" }); list.render(); } })), { sep: true }, { label: "Reverse sort", icon: "repeat", onClick: () => Commands.run("view.reverseSort") }]; } },
    { id: "view.reverseSort", label: "Reverse Sort", icon: "repeat", run: () => { const s = P("sort", { by: "date", dir: "desc" }); prefs.set("sort", { ...s, dir: s.dir === "asc" ? "desc" : "asc" }); list.render(); } },
    { id: "view.focused", label: "Focused Inbox", icon: "inbox", kind: "toggle", isActive: () => P("focusedInbox", true),
      run: async () => { const on = !list.focusedActive() || state.folderRole !== "inbox" ? !P("focusedInbox", true) : false; prefs.set("focusedInbox", on); if (on && state.settings.focused?.enabled === false) await settingsSave({ ...state.settings, focused: { ...state.settings.focused, enabled: true } }); toast(on ? "Focused Inbox on" : "Focused Inbox off"); list.render(); } },
  ]);
  add("View", "Layout", [
    { id: "view.folderPane", label: "Folder Pane", icon: "menu", kind: "toggle", keys: ["Alt+F1"], scope: "global", isActive: () => !P("navCollapsed", false), run: () => { prefs.set("navCollapsed", !P("navCollapsed", false)); applyTheme(); } },
    { id: "view.readingPane", label: "Reading Pane", icon: "pane", kind: "menu", menu: () => { const cur = P("pane", "right"); return [["right", "Right"], ["bottom", "Bottom"], ["off", "Off"]].map(([v, l]) => ({ label: l, checked: cur === v, onClick: () => { prefs.set("pane", v); applyTheme(); } })); } },
    { id: "view.density", label: "Density", icon: "list", kind: "menu", menu: () => { const cur = P("density", "comfortable"); return [...[["comfortable", "Comfortable"], ["compact", "Compact"]].map(([v, l]) => ({ label: l, checked: cur === v, onClick: () => { prefs.set("density", v); applyTheme(); } })), { sep: true }, { label: "Show preview text", checked: P("showPreview", true), onClick: () => toggle("showPreview", true) }, { label: "Show sender pictures", checked: P("showAvatars", true), onClick: () => toggle("showAvatars", true) }]; } },
    { id: "view.todoBar", label: "To-Do Bar (My Day)", icon: "sun", kind: "toggle", isActive: () => P("myDay", false), run: () => mailplus.toggleMyDay() },
    { id: "view.reminders", label: "Reminders Window", icon: "bell", run: () => { reminders.render(reminders.due()); $("reminderDialog").showModal(); } },
    { id: "view.closeAll", label: "Close All Items", icon: "close", run: () => { for (const d of qsa("dialog[open]")) if (d.id !== "composeDialog") d.close(); reading.close(); } },
    { id: "view.ribbon", label: "Collapse the Ribbon", icon: "minimize", kind: "toggle", keys: ["Ctrl+F1"], scope: "global", isActive: () => !!P("ribbonCollapsed", false), run: () => { prefs.set("ribbonCollapsed", !P("ribbonCollapsed", false)); qatUI.applyViewPrefs(); } },
    { id: "view.fullScreen", label: "Full Screen", icon: "maximize", kind: "toggle", isActive: () => !!document.fullscreenElement, run: () => (document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen && document.documentElement.requestFullscreen()) },
  ]);
  add("View", "Zoom", [
    { id: "view.zoomIn", label: "Zoom In (Reading Pane)", icon: "zoom-in", run: () => zoom(10) },
    { id: "view.zoomOut", label: "Zoom Out (Reading Pane)", icon: "zoom-out", run: () => zoom(-10) },
    { id: "view.zoomReset", label: "Zoom 100%", icon: "zoom-in", run: () => { prefs.set("readZoom", 100); applyTheme(); } },
    { id: "view.immersive", label: "Immersive Reader", icon: "reader", isEnabled: sel, run: async (ctx) => mailplus.reader(await openDetail(ctx)) },
  ]);
  function zoom(d) { prefs.set("readZoom", Math.min(200, Math.max(60, P("readZoom", 100) + d))); applyTheme(); }
  add("View", "Theme", [
    { id: "view.dark", label: "Dark Mode", icon: "moon", kind: "toggle", isActive: () => document.documentElement.dataset.theme === "dark", run: () => { prefs.set("theme", document.documentElement.dataset.theme === "dark" ? "light" : "dark"); applyTheme(); qatUI.refresh(); } },
    { id: "view.theme", label: "Colour Theme", icon: "sparkle", kind: "menu", menu: () => { const cur = P("accent", "blue"); return [["blue", "#0f6cbd", "Blue"], ["navy", "#0c3b5e", "Navy"], ["teal", "#038387", "Teal"], ["green", "#107c10", "Green"], ["purple", "#8764b8", "Purple"], ["pink", "#c239b3", "Pink"], ["red", "#c4314b", "Red"], ["orange", "#ca5010", "Orange"], ["graphite", "#4f4f4f", "Graphite"]].map(([id, c, l]) => ({ label: l, color: c, checked: cur === id, onClick: () => { prefs.set("accent", id); applyTheme(); } })); } },
  ]);
  add("View", "Quick Access Toolbar", [
    { id: "view.touchMode", label: "Touch/Mouse Mode", icon: "c-touch", kind: "toggle", popular: true, tip: "More space between commands, for fingers", isActive: () => qatUI.prefs().touch, run: () => qatUI.update({ touch: !qatUI.prefs().touch }) },
    { id: "view.showQat", label: "Show Quick Access Toolbar", icon: "c-qat", kind: "toggle", isActive: () => !qatUI.prefs().hidden, run: () => qatUI.update({ hidden: !qatUI.prefs().hidden }) },
    { id: "view.qatPosition", label: "Quick Access Toolbar Position", icon: "c-qat", kind: "menu", menu: () => [["top", "Show Above the Ribbon"], ["below", "Show Below the Ribbon"], ["bottom", "Show at the Bottom (near the keyboard)"]].map(([v, l]) => ({ label: l, checked: qatUI.prefs().position === v, onClick: () => qatUI.update({ position: v, hidden: false }) })) },
    { id: "view.qatLabels", label: "Show Command Labels", icon: "list", kind: "toggle", isActive: () => qatUI.prefs().labels, run: () => qatUI.update({ labels: !qatUI.prefs().labels }) },
  ]);

  // ------------------------------------------------------------------ Help
  add("Help", "Help", [
    { id: "help.help", label: "Help", icon: "help", keys: ["F1"], scope: "global", run: () => openExternal(QB_REPO + "#readme") },
    { id: "help.shortcuts", label: "Keyboard Shortcuts", icon: "keyboard", keys: ["?"], scope: "global", run: () => qatUI.shortcuts() },
    { id: "help.searchCommands", label: "Search Commands (Tell Me)", icon: "search", keys: ["Alt+Q", "Ctrl+K"], scope: "global", popular: true, keywords: "command palette superhuman tell me what you want", run: () => qatUI.palette() },
    { id: "help.commandReference", label: "Command Reference", icon: "list", run: () => qatUI.reference() },
    { id: "help.customizeQat", label: "Customize Quick Access Toolbar…", icon: "c-qat", run: () => qatUI.chooser() },
    { id: "help.customizeKeyboard", label: "Customize Keyboard Shortcuts…", icon: "keyboard", run: () => qatUI.keyboard() },
    { id: "help.keySchemes", label: "Keyboard Scheme", icon: "keyboard", kind: "menu", keywords: "gmail mutt vim j k", menu: () => [["default", "Desktop mail (default)"], ["gmail", "Gmail-style (j/k, e, #, x, s, !)"], ["mutt", "Mutt/Pine-style (j/k, d, |, b, T, l, $)"]].map(([v, l]) => ({ label: l, checked: Commands.scheme === v, onClick: () => { Commands.scheme = v; prefs.set("keyScheme", v); toast(`Keyboard scheme: ${l}`); } })) },
    { id: "help.whatsNew", label: "What's New", icon: "sparkle", run: () => mailplus.whatsNew() },
    { id: "help.feedback", label: "Report a Problem", icon: "warning", run: () => openExternal(QB_REPO + "/issues/new") },
    { id: "help.about", label: "About Anne\'s Post", icon: "info", run: () => settingsUI.open("about") },
  ]);

  // ------------------------------------------------------------------ Edit (undo / redo)
  add("Home", "Undo", [
    { id: "edit.undo", label: "Undo", icon: "undo", keys: ["Ctrl+Z"], scope: "mail", popular: true, tip: "Undoes the last move, delete, junk, read, flag, pin or category change",
      run: () => { if (MC.composing() && (MC.editor().contains(document.activeElement) || document.activeElement === MC.editor())) { document.execCommand("undo"); return; } return mailUndo.undo(); } },
    { id: "edit.redo", label: "Redo", icon: "c-redo", keys: ["Ctrl+Shift+Z"], scope: "mail", popular: true, run: () => { if (MC.composing() && document.activeElement === MC.editor()) { document.execCommand("redo"); return; } return mailUndo.redo(); } },
  ]);

  // ------------------------------------------------------------------ Message window: Message tab (writing)
  const cmp = { scope: "compose", isEnabled: composing };
  add("Message", "Send", [
    { id: "compose.send", label: "Send", icon: "send", keys: ["Ctrl+Enter", "Alt+S"], ...cmp, popular: true, run: () => compose.send() },
    { id: "compose.saveDraft", label: "Save Draft", icon: "drafts", ...cmp, run: () => compose.saveDraft() },
    { id: "compose.discard", label: "Discard", icon: "trash", ...cmp, run: () => { if (confirm("Discard this message?")) compose.close(); } },
    { id: "compose.popOut", label: "Pop Out / Dock", icon: "maximize", ...cmp, run: () => compose.popOut() },
  ]);
  add("Message", "Clipboard", [
    { id: "compose.cut", label: "Cut", icon: "c-cut", ...cmp, run: () => exec("cut") },
    { id: "compose.copy", label: "Copy", icon: "copy", ...cmp, run: () => exec("copy") },
    { id: "compose.paste", label: "Paste", icon: "c-paste", ...cmp, run: async () => { try { const t = await navigator.clipboard.readText(); insertHtml(esc(t).replace(/\n/g, "<br>")); } catch { toast("Your browser only allows pasting with Ctrl+V."); } } },
    { id: "compose.pastePlain", label: "Paste as Plain Text", icon: "c-paste", ...cmp, run: async () => { try { const t = await navigator.clipboard.readText(); exec("insertText", t); } catch { toast("Your browser only allows pasting with Ctrl+V."); } } },
    { id: "compose.formatPainter", label: "Format Painter", icon: "c-brush", ...cmp, tip: "Copies the formatting of the selection; select other text and run it again to apply", run: () => formatPainter() },
  ]);
  let painted = null;
  function formatPainter() {
    needCompose();
    const s = getSelection();
    if (!painted) {
      const node = s.anchorNode && (s.anchorNode.nodeType === 1 ? s.anchorNode : s.anchorNode.parentElement);
      if (!node || !MC.editor().contains(node)) throw new UserError("Select some formatted text in the message first.");
      const cs = getComputedStyle(node);
      painted = { bold: Number(cs.fontWeight) >= 600, italic: cs.fontStyle === "italic", underline: cs.textDecorationLine.includes("underline"), color: cs.color, font: cs.fontFamily, size: cs.fontSize };
      toast("Formatting copied — select the text to format and run Format Painter again.");
      return;
    }
    const p = painted; painted = null;
    const text = String(s);
    if (!text) throw new UserError("Select the text to format.");
    insertHtml(`<span style="font-weight:${p.bold ? 700 : 400};font-style:${p.italic ? "italic" : "normal"};text-decoration:${p.underline ? "underline" : "none"};color:${p.color};font-family:${p.font.replace(/"/g, "'")};font-size:${p.size}">${esc(text)}</span>`);
  }
  add("Message", "Basic Text", [
    { id: "compose.bold", label: "Bold", icon: "bold", ...cmp, run: () => exec("bold") },
    { id: "compose.italic", label: "Italic", icon: "italic", ...cmp, run: () => exec("italic") },
    { id: "compose.underline", label: "Underline", icon: "underline", ...cmp, run: () => exec("underline") },
    { id: "compose.strike", label: "Strikethrough", icon: "strike", ...cmp, run: () => exec("strikeThrough") },
    { id: "compose.growFont", label: "Grow Font", icon: "zoom-in", keys: ["Ctrl+Shift+>", "Ctrl+]"], ...cmp, run: () => fontStep(1) },
    { id: "compose.shrinkFont", label: "Shrink Font", icon: "zoom-out", keys: ["Ctrl+Shift+<", "Ctrl+["], ...cmp, run: () => fontStep(-1) },
    { id: "compose.highlight", label: "Text Highlight Colour", icon: "highlight", kind: "menu", ...cmp, menu: () => colorItems("hiliteColor", ["#fff100", "#00ff00", "#00ffff", "#ff00ff", "#ffb3b3", "#d0d0d0", "transparent"]) },
    { id: "compose.fontColor", label: "Font Colour", icon: "font", kind: "menu", ...cmp, menu: () => colorItems("foreColor", ["#000000", "#c50f1f", "#ca5010", "#107c10", "#0f6cbd", "#8764b8", "#616161"]) },
    { id: "compose.bullets", label: "Bullets", icon: "ul", ...cmp, run: () => exec("insertUnorderedList") },
    { id: "compose.numbering", label: "Numbering", icon: "ol", ...cmp, run: () => exec("insertOrderedList") },
    { id: "compose.indent", label: "Increase Indent", icon: "indent", ...cmp, run: () => exec("indent") },
    { id: "compose.outdent", label: "Decrease Indent", icon: "outdent", ...cmp, run: () => exec("outdent") },
    { id: "compose.clearFormatting", label: "Clear All Formatting", icon: "clear-fmt", keys: ["Ctrl+Space"], ...cmp, run: () => exec("removeFormat") },
  ]);
  function fontStep(d) {
    needCompose();
    const s = getSelection(); const node = s.anchorNode && (s.anchorNode.nodeType === 1 ? s.anchorNode : s.anchorNode.parentElement);
    const px = node ? parseFloat(getComputedStyle(node).fontSize) : 14;
    const steps = [8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48, 72];
    const i = steps.findIndex((x) => x >= Math.round(px));
    const next = steps[Math.min(steps.length - 1, Math.max(0, (i < 0 ? steps.length - 1 : i) + d))];
    const text = String(s);
    if (!text) throw new UserError("Select the text to resize.");
    insertHtml(`<span style="font-size:${next}px">${esc(text)}</span>`);
  }
  function colorItems(cmd, colors) {
    const grid = el("div", { class: "qb-swatches" }, ...colors.map((c) => el("button", { class: "qb-swatch", title: c, style: { background: c === "transparent" ? "repeating-linear-gradient(45deg,#fff,#fff 4px,#ccc 4px,#ccc 8px)" : c }, onmousedown: (e) => e.preventDefault(), onclick: (e) => { e.stopPropagation(); menu.close(); exec(cmd, c); } })));
    return [{ node: grid }];
  }
  add("Message", "Include", [
    { id: "compose.attachFile", label: "Attach File", icon: "attach", ...cmp, popular: true, run: () => $("cAttachInput").click() },
    { id: "compose.attachItem", label: "Attach Item (Message)", icon: "mail", ...cmp, tip: "Attaches messages from the current folder as .eml files", run: () => attachItem() },
    { id: "compose.businessCard", label: "Business Card (vCard)", icon: "contact", kind: "menu", ...cmp, menu: () => businessCards() },
    { id: "compose.insertCalendar", label: "Calendar (Free Times)", icon: "calendar", ...cmp, run: () => Commands.run("new.insertTimeSlots") },
    { id: "compose.signature", label: "Signature", icon: "edit", kind: "menu", ...cmp, menu: () => { const sigs = state.settings?.signatures || []; return [...sigs.map((s) => ({ label: s.name, icon: "edit", onClick: () => insertHtml(`<div class="qb-signature">${s.html}</div>`) })), sigs.length ? { sep: true } : null, { label: "Signatures…", icon: "settings", onClick: () => settingsUI.open("signatures") }]; } },
    { id: "compose.link", label: "Link", icon: "link", keys: ["Ctrl+K"], ...cmp, run: () => mailplus.insertLink() },
    { id: "compose.addressBook", label: "Address Book", icon: "contact", ...cmp, run: () => addressBook() },
    { id: "compose.checkNames", label: "Check Names", icon: "people", keys: ["Alt+K"], ...cmp, run: () => mailplus.checkNames() },
  ]);
  async function attachItem() {
    needCompose();
    const msgs = state.messages.slice(0, 60);
    const box = el("div", { class: "qb-msglist" });
    const picked = new Set();
    for (const m of msgs) {
      const cb = el("input", { type: "checkbox" }); cb.onchange = () => (cb.checked ? picked.add(m) : picked.delete(m));
      box.appendChild(el("label", { class: "qb-msgrow check" }, cb, el("div", { class: "grow" }, el("b", { text: m.subject || "(no subject)" }), el("div", { class: "small muted", text: `${m.fromName || m.fromAddress} · ${fmtDate(m.date)}` }))));
    }
    const v = await formDialog.open({ title: `Attach messages from ${MC.folderName(state.folder)}`, ok: "Attach", wide: true, fields: [], extra: box });
    if (!v || !picked.size) return;
    state.compose.attachMessages.push(...MC.refs([...picked])); compose.renderAtts(); state.compose.dirty = true;
  }
  function businessCards() {
    const contacts = pim.data.contacts.filter((c) => c.emails?.length).slice().sort((a, b) => pim.contactName(a).localeCompare(pim.contactName(b))).slice(0, 40);
    const items = contacts.map((c) => ({ label: pim.contactName(c), icon: "contact", onClick: () => attachVcard(c) }));
    return items.length ? items : [{ label: "No contacts with an e-mail address", disabled: true }];
  }
  function attachVcard(c) {
    needCompose();
    const text = MailTools.vcardOf(c);
    state.compose.attachments.push({ fileName: MC.safeName(pim.contactName(c)) + ".vcf", mimeType: "text/vcard", base64: btoa(unescape(encodeURIComponent(text))) });
    compose.renderAtts(); state.compose.dirty = true;
  }
  MC.attachVcard = attachVcard;
  async function addressBook() {
    needCompose();
    const box = el("div", { class: "qb-msglist" });
    const choices = [...pim.data.contactLists.map((l) => ({ name: l.name, value: l.name, desc: `Contact group · ${plural(l.members.length, "member")}` })),
      ...pim.data.contacts.flatMap((c) => (c.emails || []).map((e) => ({ name: pim.contactName(c), value: `${pim.contactName(c)} <${e.value}>`, desc: e.value })))].sort((a, b) => a.name.localeCompare(b.name));
    const picks = new Map();
    for (const ch of choices.slice(0, 400)) {
      const field = select([["", "—"], ["cTo", "To"], ["cCc", "Cc"], ["cBcc", "Bcc"]], "", { style: { width: "80px" } });
      field.onchange = () => (field.value ? picks.set(ch, field.value) : picks.delete(ch));
      box.appendChild(el("div", { class: "qb-msgrow" }, el("div", { class: "grow" }, el("b", { text: ch.name }), el("div", { class: "small muted", text: ch.desc })), field));
    }
    if (!choices.length) box.appendChild(el("p", { class: "muted", text: "Your address book is empty. Add contacts under People." }));
    const v = await formDialog.open({ title: "Select names: Address Book", ok: "OK", wide: true, fields: [], extra: box });
    if (!v) return;
    for (const [ch, fieldId] of picks) {
      if (fieldId !== "cTo") $(fieldId + "Row") && $(fieldId + "Row").classList.remove("hidden");
      const inp = $(fieldId); inp.value = [...splitAddresses(inp.value), ch.value].join(", ");
    }
    state.compose.dirty = true;
  }
  add("Message", "Tags", [
    { id: "compose.importanceHigh", label: "High Importance", icon: "c-high", kind: "toggle", ...cmp, isActive: () => state.compose?.importance === "high", run: () => compose.setImportance("high") },
    { id: "compose.importanceLow", label: "Low Importance", icon: "c-low", kind: "toggle", ...cmp, isActive: () => state.compose?.importance === "low", run: () => compose.setImportance("low") },
    { id: "compose.sensitivity", label: "Sensitivity", icon: "lock", kind: "menu", ...cmp, menu: () => [["", "Normal"], ["Personal", "Personal"], ["Private", "Private"], ["Company-Confidential", "Confidential"]].map(([v, l]) => ({ label: l, checked: (MC.headers().Sensitivity || "") === v, onClick: () => MC.setHeader("Sensitivity", v) })) },
    { id: "compose.followUpRecipients", label: "Follow Up for Recipients…", icon: "flag", ...cmp, tip: "Asks recipients' mail programs to flag the message, with a reply-by date",
      run: async () => { const v = await formDialog.open({ title: "Flag for recipients", fields: [{ key: "flag", label: "Flag text", type: "select", value: "Follow up", options: ["Follow up", "For your information", "Reply", "Reply to all", "Review", "Call", "Do not forward", "Read"].map((x) => [x, x]) }, { key: "by", label: "Reply by", type: "date", value: ymd(addDays(new Date(), 2)) }] }); if (!v) return; MC.setHeader("X-Message-Flag", v.flag); if (v.by) MC.setHeader("Reply-By", new Date(parseYmd(v.by).getTime() + 17 * 3600e3).toUTCString().replace("GMT", "+0000")); toast(`Recipients will see "${v.flag}"${v.by ? " by " + v.by : ""}.`); } },
    { id: "compose.templates", label: "My Templates", icon: "note", kind: "menu", ...cmp, keywords: "stationery", menu: () => [...(state.settings?.templates || []).map((t) => ({ label: t.name, icon: "note", onClick: () => { if (t.subject && !$("cSubject").value) $("cSubject").value = t.subject; insertHtml(t.html); } })), { sep: true }, { label: "Save as template", icon: "plus", onClick: () => mailplus.saveAsTemplate() }, { label: "Manage templates", icon: "settings", onClick: () => settingsUI.open("templates") }] },
  ]);
  // Extra headers for the message being written; sent through the existing send path.
  MC.headers = () => (state.compose ? (state.compose.extraHeaders || (state.compose.extraHeaders = {})) : {});
  MC.setHeader = (k, v) => { needCompose(); const h = MC.headers(); if (v) h[k] = v; else delete h[k]; state.compose.dirty = true; };

  // ------------------------------------------------------------------ Insert tab
  add("Insert", "Insert", [
    { id: "compose.table", label: "Table", icon: "table", ...cmp, run: () => mailplus.insertTable() },
    { id: "compose.picture", label: "Pictures", icon: "image", ...cmp, run: () => $("cPicture").click() },
    { id: "compose.screenshot", label: "Screenshot", icon: "c-screenshot", ...cmp, tip: "Captures a window or screen you choose and inserts it", run: () => screenshot() },
    { id: "compose.draw", label: "Drawing Canvas", icon: "c-pen", ...cmp, run: () => drawPad() },
    { id: "compose.hr", label: "Horizontal Line", icon: "minus", ...cmp, run: () => insertHtml("<hr>") },
    { id: "compose.emoji", label: "Emoji", icon: "smile", ...cmp, run: (ctx) => mailplus.emojiMenu(ctx.anchor && ctx.anchor.isConnected ? ctx.anchor : $("cEmoji")) },
    { id: "compose.symbol", label: "Symbol", icon: "c-omega", kind: "menu", ...cmp, menu: () => { const grid = el("div", { class: "emoji-grid" }, ..."© ® ™ § ¶ † ‡ • … – — ‘ ’ “ ” « » ‹ › ° ± × ÷ ≠ ≈ ≤ ≥ ∞ √ ∑ π µ Ω α β γ δ λ € £ ¥ ¢ ₹ ½ ⅓ ¼ ¾ ← → ↑ ↓ ⇒ ✓ ✗ ★ ☆ ♥ ☎ ✉ ✂ ⌘".split(" ").map((ch) => el("button", { text: ch, onmousedown: (e) => e.preventDefault(), onclick: (e) => { e.stopPropagation(); menu.close(); exec("insertText", ch); } }))); return [{ node: grid }]; } },
    { id: "compose.dateTime", label: "Date & Time", icon: "clock", kind: "menu", ...cmp, menu: () => { const d = new Date(); return [d.toLocaleDateString(), d.toLocaleDateString(undefined, { weekday: "long", year: "numeric", month: "long", day: "numeric" }), ymd(d), d.toLocaleString(), d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })].map((s) => ({ label: s, onClick: () => exec("insertText", s) })); } },
    { id: "compose.textBox", label: "Text Box", icon: "note", ...cmp, run: () => insertHtml('<div style="border:1px solid #8a8a8a;padding:8px 12px;margin:8px 0;border-radius:4px">Text box</div><div><br></div>') },
    { id: "compose.bookmark", label: "Bookmark…", icon: "pin", ...cmp, run: async () => { const v = await formDialog.open({ title: "Bookmark", fields: [{ key: "name", label: "Bookmark name", required: true }] }); if (v) insertHtml(`<a id="${esc(v.name.replace(/\s+/g, "_"))}" name="${esc(v.name.replace(/\s+/g, "_"))}"></a>`); } },
    { id: "compose.dropCap", label: "Drop Cap", icon: "font", ...cmp, run: () => { const t = MC.selectedText(); if (!t) throw new UserError("Select the paragraph's first letter or word."); insertHtml(`<span style="float:left;font-size:3.2em;line-height:.9;margin:2px 6px 0 0;font-weight:600">${esc(t[0])}</span>${esc(t.slice(1))}`); } },
    { id: "compose.meeting", label: "Meeting", icon: "calendar", ...cmp, run: () => $("cMeeting").click() },
  ]);
  async function screenshot() {
    needCompose();
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) throw new UserError("Screen capture is not available here.");
    MC.rememberSelection();
    const stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
    try {
      const v = document.createElement("video"); v.srcObject = stream; v.muted = true; await v.play();
      await new Promise((r) => setTimeout(r, 300));
      const c = document.createElement("canvas"); c.width = v.videoWidth; c.height = v.videoHeight; c.getContext("2d").drawImage(v, 0, 0);
      insertHtml(`<img src="${c.toDataURL("image/png")}" alt="Screenshot" style="max-width:100%">`);
    } finally { stream.getTracks().forEach((t) => t.stop()); }
  }
  /** A small drawing pad (pen, highlighter, eraser); the drawing is inserted as a picture. */
  function drawPad(tool = "pen") {
    needCompose();
    MC.rememberSelection();
    const canvas = el("canvas", { width: 640, height: 320, class: "qb-drawpad" });
    const ctx2 = canvas.getContext("2d"); ctx2.fillStyle = "#fff"; ctx2.fillRect(0, 0, 640, 320);
    let color = "#0f6cbd", width = 3, mode = tool, drawing = false, last = null;
    const pos = (e) => { const r = canvas.getBoundingClientRect(); return { x: (e.clientX - r.left) * (640 / r.width), y: (e.clientY - r.top) * (320 / r.height) }; };
    canvas.onpointerdown = (e) => { drawing = true; last = pos(e); canvas.setPointerCapture(e.pointerId); };
    canvas.onpointermove = (e) => { if (!drawing) return; const p = pos(e); ctx2.globalAlpha = mode === "highlighter" ? 0.35 : 1; ctx2.strokeStyle = mode === "eraser" ? "#fff" : color; ctx2.lineWidth = mode === "highlighter" ? 14 : mode === "eraser" ? 18 : width; ctx2.lineCap = "round"; ctx2.beginPath(); ctx2.moveTo(last.x, last.y); ctx2.lineTo(p.x, p.y); ctx2.stroke(); last = p; };
    canvas.onpointerup = () => { drawing = false; };
    const tools = el("div", { class: "btn-row", style: { marginBottom: "8px" } },
      ...[["pen", "Pen"], ["highlighter", "Highlighter"], ["eraser", "Eraser"]].map(([m, l]) => el("button", { class: "btn sm", onclick: () => { mode = m; } }, l)),
      ...["#000000", "#0f6cbd", "#c50f1f", "#107c10", "#ca5010", "#fff100"].map((c) => el("button", { class: "qb-swatch", style: { background: c }, title: c, onclick: () => { color = c; if (mode === "eraser") mode = "pen"; } })),
      el("button", { class: "btn sm", onclick: () => { ctx2.globalAlpha = 1; ctx2.fillStyle = "#fff"; ctx2.fillRect(0, 0, 640, 320); } }, "Clear"));
    formDialog.open({ title: "Draw", ok: "Insert drawing", wide: true, fields: [], extra: el("div", {}, tools, canvas) }).then((v) => { if (v) insertHtml(`<img src="${canvas.toDataURL("image/png")}" alt="Drawing" style="max-width:100%">`); });
  }
  add("Draw", "Tools", [
    { id: "compose.drawPen", label: "Draw with Pen", icon: "c-pen", ...cmp, run: () => drawPad("pen") },
    { id: "compose.drawHighlighter", label: "Highlighter (Sketch)", icon: "highlight", ...cmp, run: () => drawPad("highlighter") },
  ]);

  // ------------------------------------------------------------------ Options tab
  add("Options", "Show Fields", [
    { id: "compose.showBcc", label: "Bcc", icon: "people", kind: "toggle", ...cmp, isActive: () => !$("cBccRow").classList.contains("hidden"), run: () => { $("cBccRow").classList.toggle("hidden"); if (!$("cBccRow").classList.contains("hidden")) $("cBcc").focus(); } },
    { id: "compose.showCc", label: "Cc", icon: "people", kind: "toggle", ...cmp, isActive: () => !$("cCcRow").classList.contains("hidden"), run: () => { $("cCcRow").classList.toggle("hidden"); if (!$("cCcRow").classList.contains("hidden")) $("cCc").focus(); } },
    { id: "compose.showFrom", label: "From", icon: "user", kind: "toggle", ...cmp, isActive: () => !$("cFromRow").classList.contains("hidden"), run: () => $("cFromRow").classList.toggle("hidden") },
  ]);
  add("Options", "Tracking", [
    { id: "compose.readReceipt", label: "Request a Read Receipt", icon: "eye", kind: "toggle", ...cmp, keywords: "return receipt mdn", isActive: () => $("cReadReceipt").checked, run: () => { $("cReadReceipt").checked = !$("cReadReceipt").checked; toast($("cReadReceipt").checked ? "A read receipt will be requested." : "No read receipt."); } },
    { id: "compose.deliveryReceipt", label: "Request a Delivery Receipt", icon: "check-circle", kind: "toggle", ...cmp, keywords: "dsn", isActive: () => $("cDeliveryReceipt").checked, run: () => { $("cDeliveryReceipt").checked = !$("cDeliveryReceipt").checked; toast($("cDeliveryReceipt").checked ? "A delivery receipt will be requested." : "No delivery receipt."); } },
  ]);
  add("Options", "More Options", [
    { id: "compose.delayDelivery", label: "Delay Delivery…", icon: "send-later", ...cmp, popular: true, keywords: "schedule send later", run: async () => { const v = await formDialog.open({ title: "Delay delivery", ok: "Schedule", fields: [{ key: "when", label: "Do not deliver before", type: "datetime-local", value: localIso(new Date(Date.now() + 3600e3)), required: true }] }); if (!v) return; const t = new Date(v.when).getTime(); if (t < Date.now() + 60000) throw new UserError("Choose a time at least a minute from now."); compose.send({ sendAt: t }); } },
    { id: "compose.sendLater", label: "Send Later", icon: "send-later", kind: "menu", ...cmp, keywords: "spark schedule", origin: "Spark / Gmail", run: (ctx) => mailplus.scheduleMenu(ctx.anchor && ctx.anchor.isConnected ? ctx.anchor : $("cSendMenu")) },
    { id: "compose.directRepliesTo", label: "Direct Replies To…", icon: "reply", ...cmp, run: async () => { const v = await formDialog.open({ title: "Have replies sent to", fields: [{ key: "a", label: "Address", type: "email", value: $("cReplyToAddr").value }] }); if (v) { $("cReplyToAddr").value = v.a.trim(); state.compose.dirty = true; } } },
    { id: "compose.plainText", label: "Format: Plain Text", icon: "note", kind: "toggle", ...cmp, isActive: () => !!state.compose?.plain, run: () => $("cPlainText").click() },
    { id: "compose.pageColor", label: "Page Colour", icon: "c-brush", kind: "menu", ...cmp, menu: () => [["", "No colour"], ["#fff8e6", "Cream"], ["#eaf3fb", "Light blue"], ["#f1faf1", "Mint"], ["#f5f0ff", "Lavender"], ["#f3f3f3", "Grey"]].map(([c, l]) => ({ label: l, color: c || "#ffffff", onClick: () => { needCompose(); const ed = MC.editor(); const wrap = ed.querySelector(":scope > .qb-page"); if (!c) { if (wrap) wrap.replaceWith(...wrap.childNodes); return; } if (wrap) wrap.style.background = c; else { const w = el("div", { class: "qb-page", style: { background: c, padding: "12px" } }); while (ed.firstChild) w.appendChild(ed.firstChild); ed.appendChild(w); } state.compose.dirty = true; } })) },
  ]);

  // ------------------------------------------------------------------ Format Text tab
  add("Format Text", "Paragraph", [
    { id: "compose.alignLeft", label: "Align Left", icon: "align-left", ...cmp, run: () => exec("justifyLeft") },
    { id: "compose.alignCenter", label: "Center", icon: "align-center", ...cmp, run: () => exec("justifyCenter") },
    { id: "compose.alignRight", label: "Align Right", icon: "align-right", ...cmp, run: () => exec("justifyRight") },
    { id: "compose.justify", label: "Justify", icon: "c-justify", ...cmp, run: () => exec("justifyFull") },
    { id: "compose.lineSpacing", label: "Line Spacing", icon: "c-spacing", kind: "menu", ...cmp, menu: () => ["1.0", "1.15", "1.5", "2.0", "2.5", "3.0"].map((v) => ({ label: v, onClick: () => blockStyle("lineHeight", v) })) },
    { id: "compose.shading", label: "Shading", icon: "c-brush", kind: "menu", ...cmp, menu: () => [["transparent", "No colour"], ["#fff100", "Yellow"], ["#cfe4fa", "Blue"], ["#d4f0d4", "Green"], ["#f3f3f3", "Grey"]].map(([c, l]) => ({ label: l, color: c === "transparent" ? "#ffffff" : c, onClick: () => blockStyle("background", c) })) },
    { id: "compose.borders", label: "Borders", icon: "table", kind: "menu", ...cmp, menu: () => [["1px solid #8a8a8a", "Box border"], ["0", "No border"]].map(([b, l]) => ({ label: l, onClick: () => { blockStyle("border", b); blockStyle("padding", b === "0" ? "0" : "6px 10px"); } })) },
    { id: "compose.sortLines", label: "Sort Lines", icon: "sort", ...cmp, run: () => { const t = MC.selectedText(); if (!t.includes("\n")) throw new UserError("Select several lines to sort."); insertHtml(t.split("\n").filter((x) => x.trim()).sort((a, b) => a.localeCompare(b)).map(esc).join("<br>")); } },
    { id: "compose.showMarks", label: "Show/Hide ¶", icon: "c-pilcrow", kind: "toggle", ...cmp, isActive: () => MC.editor().classList.contains("qb-marks"), run: () => MC.editor().classList.toggle("qb-marks") },
  ]);
  function blockStyle(prop, val) {
    needCompose();
    const s = getSelection(); let n = s.anchorNode && (s.anchorNode.nodeType === 1 ? s.anchorNode : s.anchorNode.parentElement);
    const ed = MC.editor();
    while (n && n !== ed && !/^(P|DIV|LI|H1|H2|H3|H4|BLOCKQUOTE|PRE|TD)$/.test(n.tagName)) n = n.parentElement;
    if (!n || n === ed) { document.execCommand("formatBlock", false, "div"); n = getSelection().anchorNode; n = n && (n.nodeType === 1 ? n : n.parentElement); }
    if (n && n !== ed) { n.style[prop] = val; state.compose.dirty = true; }
  }
  add("Format Text", "Styles", [
    { id: "compose.styleNormal", label: "Style: Normal", icon: "font", ...cmp, run: () => exec("formatBlock", "p") },
    { id: "compose.styleH1", label: "Style: Heading 1", icon: "font", ...cmp, run: () => exec("formatBlock", "h1") },
    { id: "compose.styleH2", label: "Style: Heading 2", icon: "font", ...cmp, run: () => exec("formatBlock", "h2") },
    { id: "compose.styleH3", label: "Style: Heading 3", icon: "font", ...cmp, run: () => exec("formatBlock", "h3") },
    { id: "compose.styleQuote", label: "Style: Quote", icon: "c-quote", ...cmp, run: () => exec("formatBlock", "blockquote") },
    { id: "compose.styleCode", label: "Style: Code", icon: "note", ...cmp, run: () => exec("formatBlock", "pre") },
  ]);
  add("Format Text", "Font", [
    { id: "compose.superscript", label: "Superscript", icon: "c-sup", keys: ["Ctrl+Shift++"], ...cmp, run: () => exec("superscript") },
    { id: "compose.subscript", label: "Subscript", icon: "c-sub", keys: ["Ctrl+="], ...cmp, run: () => exec("subscript") },
    { id: "compose.changeCase", label: "Change Case", icon: "c-case", kind: "menu", ...cmp, menu: () => [["sentence", "Sentence case."], ["lower", "lowercase"], ["upper", "UPPERCASE"], ["title", "Capitalize Each Word"], ["toggle", "tOGGLE cASE"]].map(([m, l]) => ({ label: l, onClick: () => { const t = MC.selectedText(); if (!t) return toast("Select some text first."); exec("insertText", MailTools.changeCase(t, m)); } })) },
    { id: "compose.font", label: "Font", icon: "font", kind: "menu", ...cmp, menu: () => [["Segoe UI, system-ui, sans-serif", "Sans serif"], ["Georgia, 'Times New Roman', serif", "Serif"], ["Consolas, 'Courier New', monospace", "Fixed width"], ["Arial, Helvetica, sans-serif", "Arial"], ["Verdana, sans-serif", "Verdana"], ["'Comic Sans MS', 'Comic Neue', cursive", "Comic"]].map(([f, l]) => ({ label: l, onClick: () => exec("fontName", f) })) },
    { id: "compose.zoom", label: "Zoom (Message Body)", icon: "zoom-in", kind: "menu", ...cmp, menu: () => ["75%", "100%", "125%", "150%", "200%"].map((z) => ({ label: z, onClick: () => { MC.editor().style.zoom = z; } })) },
  ]);

  // ------------------------------------------------------------------ Review tab
  add("Review", "Proofing", [
    { id: "compose.spelling", label: "Spelling & Grammar", icon: "check", keys: ["F7"], ...cmp, tip: "Uses your browser's spelling checker; misspelled words are underlined", run: () => { const ed = MC.editor(); ed.spellcheck = true; ed.setAttribute("spellcheck", "true"); ed.focus(); const t = ed.innerHTML; ed.innerHTML = t; toast("Spelling is checked as you type — right-click an underlined word for suggestions."); } },
    { id: "compose.wordCount", label: "Word Count", icon: "list", keys: ["Ctrl+Shift+G"], ...cmp, run: () => { const sel = MC.selectedText(); const s = MailTools.wordStats(sel || htmlToPlain(MC.editor())); MC.panel(sel ? "Word count (selection)" : "Word count", el("dl", { class: "kv" }, el("dt", { text: "Words" }), el("dd", { text: String(s.words) }), el("dt", { text: "Characters (no spaces)" }), el("dd", { text: String(s.charsNoSpaces) }), el("dt", { text: "Characters (with spaces)" }), el("dd", { text: String(s.chars) }), el("dt", { text: "Sentences" }), el("dd", { text: String(s.sentences) }), el("dt", { text: "Paragraphs" }), el("dd", { text: String(s.paragraphs) }))); } },
    { id: "compose.accessibility", label: "Check Accessibility", icon: "eye", ...cmp, run: () => accessibility() },
    { id: "compose.language", label: "Proofing Language", icon: "globe", kind: "menu", ...cmp, menu: () => [["", "Browser default"], ["en-US", "English (US)"], ["en-GB", "English (UK)"], ["fr", "French"], ["de", "German"], ["es", "Spanish"], ["it", "Italian"], ["pt", "Portuguese"], ["nl", "Dutch"]].map(([v, l]) => ({ label: l, checked: (MC.editor().lang || "") === v, onClick: () => { MC.editor().lang = v; } })) },
    { id: "compose.readAloud", label: "Read Aloud (Draft)", icon: "volume", ...cmp, run: () => { if (speechSynthesis.speaking) return speechSynthesis.cancel(); speechSynthesis.speak(new SpeechSynthesisUtterance(htmlToPlain(MC.editor()))); } },
  ]);
  function accessibility() {
    needCompose();
    const ed = MC.editor(); const issues = [];
    ed.querySelectorAll("img").forEach((i) => { if (!(i.getAttribute("alt") || "").trim() || /^(image|screenshot|drawing)$/i.test(i.alt)) issues.push(["Missing alternative text", "A picture has no description for people using screen readers.", i]); });
    ed.querySelectorAll("a").forEach((a) => { if (/^(click here|here|link|this|more)$/i.test(a.textContent.trim())) issues.push(["Unclear link text", `"${a.textContent.trim()}" does not say where the link goes.`, a]); });
    ed.querySelectorAll("table").forEach((t) => { if (!t.querySelector("th")) issues.push(["Table without header row", "Mark the first row as headers so it can be read in order.", t]); });
    ed.querySelectorAll("[style*='font-size']").forEach((n) => { if (parseFloat(n.style.fontSize) < 10) issues.push(["Very small text", "Text smaller than 10 px is hard to read.", n]); });
    ed.querySelectorAll("[style*='color']").forEach((n) => { if (/(#ff0|#fff100|yellow|#ffff00)/i.test(n.style.color)) issues.push(["Hard-to-read colour", "Light text colours on white are hard to read.", n]); });
    const caps = (htmlToPlain(ed).match(/\b[A-Z]{6,}\b/g) || []).length; if (caps > 3) issues.push(["Lots of capitals", "Text in capitals is harder to read and can sound like shouting.", null]);
    const box = el("div", {}, issues.length ? el("ul", {}, ...issues.map(([t, d, n]) => el("li", {}, el("b", { text: t + ": " }), d, n ? el("button", { class: "btn sm", style: { marginLeft: "6px" }, onclick: () => { $("popupDialog").close(); n.scrollIntoView({ block: "center" }); n.style.outline = "2px solid var(--danger)"; setTimeout(() => (n.style.outline = ""), 3000); } }, "Show") : null))) : el("p", { text: "No accessibility issues found." }));
    MC.panel("Accessibility checker", box);
  }

  Commands.def(C);
})();

// ============================================================================ ribbon, compose and reading-pane bindings
/** Existing buttons → commands. Clicking them now runs the command (the ribbon, QAT and keys share it). */
const RIBBON_BINDINGS = {
  btnNewMail: "mail.newMail", btnNewMenu: "mail.newItems", btnNewMailPane: "mail.newMail", fabNewMail: "mail.newMail",
  cmdDelete: "mail.delete", cmdDeleteMenu: "mail.deleteMenu", cmdArchive: "mail.archive", cmdJunk: "mail.junk", cmdReportMenu: "mail.reportMenu", cmdNotJunk: "mail.notJunk",
  cmdSweep: "mail.sweep", cmdMove: "mail.moveTo", cmdReply: "mail.reply", cmdReplyAll: "mail.replyAll", cmdForward: "mail.forward", cmdForwardMenu: "mail.forwardMenu",
  cmdRead: "mail.toggleRead", cmdCategorize: "mail.categorize", cmdFlag: "mail.flag", cmdFlagMenu: "mail.followUp", cmdPin: "mail.pin", cmdSnooze: "mail.snooze",
  cmdQuickSteps: "mail.quickSteps", cmdRules: "mail.rulesMenu", cmdMarkAllRead: "folder.markAllRead", cmdPrint: "file.print", cmdAnalyze: "mail.analyze", cmdRescan: "mail.rescan",
  vwSettings: "view.settings", vwLayout: "view.readingPane", vwDensity: "view.density", vwConversations: "view.conversationSettings", vwSort: "view.arrangeBy",
  vwFolderPane: "view.folderPane", vwFocused: "view.focused", vwZoomOut: "view.zoomOut", vwZoomIn: "view.zoomIn", vwReader: "view.immersive", vwDark: "view.dark", vwTheme: "view.theme",
  hlpShortcuts: "help.shortcuts", hlpHelp: "help.help", hlpWhatsNew: "help.whatsNew", hlpReport: "help.feedback", hlpAbout: "help.about",
  btnRefresh: "mail.sendReceiveAll", btnTheme: "view.dark", btnShortcuts: "help.shortcuts", btnSettings: "file.options", btnMyDay: "view.todoBar",
};
/** Reading-pane buttons act on the open message. */
const READING_BINDINGS = {
  rdReply: "mail.reply", rdReplyAll: "mail.replyAll", rdForward: "mail.forward", rdQuickReplyBtn: "mail.reply", rdQuickReplyAllBtn: "mail.replyAll", rdQuickForwardBtn: "mail.forward",
  rdDelete: "mail.delete", rdArchive: "mail.archive", rdMove: "mail.moveOther", rdFlag: "mail.flag", rdUnread: "mail.markUnread", rdJunk: "mail.junk", rdNotJunk: "mail.notJunk", rdAnalyze: "mail.analyze",
};
/** Compose buttons: only tagged (so they can be added to the QAT from a right-click); their own handlers stay. */
const COMPOSE_TAGS = {
  cSend: "compose.send", cDiscard: "compose.discard", cSaveDraft: "compose.saveDraft", cAttach: "compose.attachFile", cAttach2: "compose.attachFile", cLink: "compose.link", cLink2: "compose.link",
  cSignature: "compose.signature", cSignature2: "compose.signature", cTemplates: "compose.templates", cPicture: "compose.picture", cTable: "compose.table", cEmoji: "compose.emoji", cHr: "compose.hr",
  cMeeting: "compose.meeting", cImportanceHigh: "compose.importanceHigh", cImportanceLow: "compose.importanceLow", cCheckNames: "compose.checkNames", cPlainText: "compose.plainText",
};
/** Ribbon tabs added next to the existing Home / View / Help tabs. */
const EXTRA_RIBBON_TABS = [
  { id: "sendreceive", label: "Send / Receive", after: "home", items: ["mail.sendReceiveAll", "mail.updateFolder", "mail.sendAll", "|", "mail.viewOutbox", "mail.showProgress", "mail.sendReceiveSettings", "mail.workOffline", "|", "eudora.sendQueued"] },
  { id: "folder", label: "Folder", after: "sendreceive", items: ["folder.new", "folder.newSub", "folder.newSearchFolder", "|", "folder.rename", "folder.copy", "folder.move", "folder.delete", "|", "folder.markAllRead", "folder.markReadByDate", "folder.runRules", "|", "folder.cleanUp", "folder.deleteAll", "folder.recoverDeleted", "folder.compact", "folder.archiveOld", "|", "folder.showAllAZ", "folder.favorite", "folder.properties"] },
  { id: "tools", label: "Tools", after: "view", items: ["new.triage", "new.threadDigest", "new.attachmentShelf", "new.senderTimeline", "new.waiting", "new.nudges", "|", "new.unsubscribeEverywhere", "new.bigAttachments", "new.bulkSender", "new.headerInspector", "|", "hey.screener", "split.toggle", "hey.paperTrail", "apple.vipMail", "|", "tb.quickFilter", "pine.limit", "pine.tagPattern", "pine.pipe", "|", "help.searchCommands", "help.commandReference"] },
];
/** Extra commands placed on the compose tabs, plus new Review and Draw tabs. */
const COMPOSE_EXTRA = {
  message: ["compose.formatPainter", "compose.addressBook", "compose.attachItem", "compose.businessCard", "compose.sensitivity", "compose.followUpRecipients", "snip.insert", "eudora.personalities"],
  insert: ["compose.screenshot", "compose.symbol", "compose.dateTime", "compose.textBox", "compose.businessCard", "compose.insertCalendar", "snip.insert"],
  format: ["compose.growFont", "compose.shrinkFont", "compose.changeCase", "compose.justify", "compose.lineSpacing", "compose.shading", "compose.showMarks"],
  options: ["compose.showBcc", "compose.showFrom", "compose.delayDelivery", "compose.pageColor", "eudora.priority", "notes.moodStamp", "pegasus.copyToSelf", "sh.remindNoReply", "new.cleanLinksToggle"],
  review: { label: "Review", items: ["compose.spelling", "compose.wordCount", "compose.accessibility", "compose.language", "compose.readAloud", "eudora.moodWatch", "new.presend", "new.plainPreview"] },
  draw: { label: "Draw", items: ["compose.drawPen", "compose.drawHighlighter", "compose.screenshot"] },
};
