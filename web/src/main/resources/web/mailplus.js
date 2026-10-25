/* Anne's Post — menus, dialogs and the mail features layered on the core client: ribbon,
 * folder management, categories, follow-up, snooze, quick steps, sweep, search options,
 * invitations, attachments, immersive reader, translation, My Day, notifications, swipe,
 * recipient autocomplete and the extra settings pages. Loaded before app.js.
 */
"use strict";
const QB_VERSION = "1.5.0";
const QB_REPO = "https://github.com/socrtwo/QuillPost";

// ============================================================================ menus
/**
 * Drop-down / context menus. Items: { label, icon, onClick, danger, checked, color, hint,
 * disabled, sep, sub: [items] }. Sub-menus replace the menu content with a Back row, which
 * works the same with a mouse and on a touch screen.
 */
const menu = {
  stack: [],
  /** The menu element; dialogs that rebuild their content can take it with them, so recreate it if needed. */
  node() {
    let m = document.getElementById("ctxMenu");
    if (!m) { m = el("div", { id: "ctxMenu", class: "ctx-menu hidden" }); document.body.appendChild(m); }
    return m;
  },
  isOpen() { return !this.node().classList.contains("hidden"); },
  close() {
    const m = this.node(); m.classList.add("hidden"); this.stack = [];
    if (m.parentNode !== document.body) document.body.appendChild(m);
  },
  openFor(anchor, items) {
    const r = anchor.getBoundingClientRect();
    this.openAt(r.left, r.bottom + 4, items, r);
  },
  openAt(x, y, items, anchorRect = null) {
    this.stack = [items];
    // A modal dialog sits in the top layer, above anything outside it: open the menu inside it.
    const m0 = this.node(), modal = typeof topModal === "function" ? topModal() : qsa("dialog[open]").filter((d) => d.matches(":modal")).pop();
    const host = modal || document.body;
    if (m0.parentNode !== host) host.appendChild(m0);
    // The click that opened the menu is still bubbling; don't let the document handler close it.
    this.justOpened = true; setTimeout(() => (this.justOpened = false), 0);
    this.render();
    const m = this.node();
    m.classList.remove("hidden");
    const w = m.offsetWidth, h = m.offsetHeight;
    let left = Math.min(x, innerWidth - w - 8), top = y;
    if (top + h > innerHeight - 8) top = anchorRect ? Math.max(8, anchorRect.top - h - 4) : Math.max(8, innerHeight - h - 8);
    m.style.left = Math.max(8, left) + "px"; m.style.top = top + "px";
    this.x = m.style.left; this.y = m.style.top;
  },
  render() {
    const m = this.node(); m.innerHTML = "";
    // Drop empty entries and leading, trailing or doubled separators.
    const items = this.stack[this.stack.length - 1].filter(Boolean).filter((it, i, arr) => !(it.sep && (i === 0 || i === arr.length - 1 || arr[i - 1].sep)));
    if (this.stack.length > 1) m.appendChild(el("button", { class: "back", onclick: (e) => { e.stopPropagation(); this.stack.pop(); this.render(); } }, icon("chev-left"), "Back"));
    for (const it of items) {
      if (it.sep) { m.appendChild(el("div", { class: "sep" })); continue; }
      if (it.header) { m.appendChild(el("div", { class: "menu-head", text: it.header })); continue; }
      if (it.node) { m.appendChild(it.node); continue; }
      const b = el("button", { class: (it.danger ? "danger " : "") + (it.checked ? "checked" : ""), disabled: it.disabled || null, title: it.title || null });
      if (it.color) b.appendChild(el("span", { class: "cat-dot", style: { background: it.color } }));
      else if (it.icon) b.appendChild(icon(it.icon));
      else b.appendChild(el("span", { class: "ico-space" }));
      b.appendChild(el("span", { class: "lbl", text: it.label }));
      if (it.hint) b.appendChild(el("span", { class: "hint", text: it.hint }));
      if (it.checked) b.appendChild(icon("check", 14));
      if (it.sub) b.appendChild(icon("chev-right", 14));
      b.onclick = (e) => {
        e.stopPropagation();
        if (it.disabled) return;
        if (it.sub) { this.stack.push(typeof it.sub === "function" ? it.sub() : it.sub); this.render(); return; }
        if (!it.keepOpen) this.close();
        it.onClick && it.onClick();
      };
      m.appendChild(b);
    }
  },
};
document.addEventListener("click", (e) => { if (!menu.justOpened && !e.target.closest("#ctxMenu")) menu.close(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && menu.isOpen()) { menu.close(); e.stopPropagation(); } }, true);
window.addEventListener("resize", () => menu.close());

// ============================================================================ form dialog
/**
 * A small modal form. fields: [{ key, label, type, value, options, placeholder, hint, required }]
 * Types: text, email, url, number, date, time, datetime-local, select, textarea, checkbox, html,
 * color, info. Resolves with the values, or null when cancelled.
 */
const formDialog = {
  open({ title, fields, ok = "Save", danger = false, wide = false, extra = null }) {
    return new Promise((resolve) => {
      const dlg = $("formDialog");
      dlg.classList.toggle("narrow", !wide); dlg.classList.toggle("wide", wide);
      $("fmTitle").textContent = title;
      const body = $("fmBody"), foot = $("fmFoot");
      body.innerHTML = ""; foot.innerHTML = "";
      const inputs = {};
      for (const f of fields) {
        let input;
        if (f.type === "info") { body.appendChild(el("p", { class: "small muted", html: f.html || esc(f.text || "") })); continue; }
        if (f.type === "select") input = select(f.options, f.value ?? f.options[0]?.[0] ?? "");
        else if (f.type === "textarea") { input = el("textarea", { rows: f.rows || 4, placeholder: f.placeholder || "" }); input.value = f.value || ""; }
        else if (f.type === "html") { input = el("div", { class: "html-editor", contenteditable: "true", html: f.value || "" }); }
        else if (f.type === "checkbox") { input = el("input", { type: "checkbox" }); input.checked = !!f.value; }
        else { input = el("input", { type: f.type || "text", placeholder: f.placeholder || "" }); input.value = f.value ?? ""; if (f.min != null) input.min = f.min; if (f.max != null) input.max = f.max; }
        inputs[f.key] = input;
        if (f.type === "checkbox") body.appendChild(el("label", { class: "check" }, input, el("span", {}, f.label, f.hint ? el("span", { class: "desc", text: " " + f.hint }) : null)));
        else body.appendChild(el("div", { class: "field" }, el("label", { text: f.label }), input, f.hint ? el("span", { class: "hint", text: f.hint }) : null));
      }
      if (extra) body.appendChild(extra);
      let done = false;
      const finish = (v) => { if (done) return; done = true; dlg.close(); resolve(v); };
      const read = () => Object.fromEntries(Object.entries(inputs).map(([k, i]) => [k, i.type === "checkbox" ? i.checked : i.classList.contains("html-editor") ? i.innerHTML : i.value]));
      const okBtn = el("button", { class: "btn " + (danger ? "danger" : "primary") }, ok);
      okBtn.onclick = () => {
        const v = read();
        const missing = fields.find((f) => f.required && !String(v[f.key] ?? "").trim());
        if (missing) { toast(`${missing.label} is required.`, { danger: true }); inputs[missing.key].focus(); return; }
        finish(v);
      };
      foot.append(el("button", { class: "btn", onclick: () => finish(null) }, "Cancel"), okBtn);
      dlg.onclose = () => finish(null);
      body.onkeydown = (e) => { if (e.key === "Enter" && e.target.tagName === "INPUT") { e.preventDefault(); okBtn.click(); } };
      dlg.showModal();
      const first = Object.values(inputs)[0];
      if (first) setTimeout(() => first.focus(), 40);
    });
  },
};

// ============================================================================ mail features
const SNOOZE_HOUR = 8;
function at(date, h, m = 0) { const d = new Date(date); d.setHours(h, m, 0, 0); return d; }
function nextWeekday(dow) { const d = new Date(); d.setDate(d.getDate() + ((dow - d.getDay() + 7) % 7 || 7)); return d; }

const mailplus = {
  otherUnread: {},

  // ---------------------------------------------------------------- categories
  categoryColor(name) { return (state.settings?.categories || []).find((c) => c.name === name)?.color || "#8a8a8a"; },
  categorizeMenu(items) {
    const cats = state.settings?.categories || [];
    const has = (c) => items.length && items.every((m) => (m.categories || []).includes(c));
    return [
      ...cats.map((c) => ({ label: c.name, color: c.color, checked: has(c.name), onClick: () => actions.categorize(items, c.name, !has(c.name)) })),
      { sep: true },
      { label: "New category…", icon: "plus", onClick: async () => { const v = await formDialog.open({ title: "New category", fields: [{ key: "name", label: "Name", required: true }, { key: "color", label: "Colour", type: "color", value: "#0f6cbd" }] }); if (!v) return; await settingsSave({ ...state.settings, categories: [...(state.settings.categories || []).filter((c) => c.name !== v.name.trim()), { name: v.name.trim(), color: v.color }] }); actions.categorize(items, v.name.trim(), true); } },
      { label: "Clear categories", icon: "close", onClick: () => actions.clearCategories(items) },
      { label: "Manage categories", icon: "settings", onClick: () => settingsUI.open("categories") },
    ];
  },

  // ---------------------------------------------------------------- follow-up flags
  flagMenu(items) {
    const today = new Date();
    const friday = addDays(today, (5 - today.getDay() + 7) % 7);
    const monday = nextWeekday(1);
    return [
      { label: "Today", icon: "flag", hint: fmtDay(today), onClick: () => actions.flag(items, true, ymd(today)) },
      { label: "Tomorrow", icon: "flag", hint: fmtDay(addDays(today, 1)), onClick: () => actions.flag(items, true, ymd(addDays(today, 1))) },
      { label: "This week", icon: "flag", hint: fmtDay(friday), onClick: () => actions.flag(items, true, ymd(friday)) },
      { label: "Next week", icon: "flag", hint: fmtDay(monday), onClick: () => actions.flag(items, true, ymd(monday)) },
      { label: "No date", icon: "flag", onClick: () => actions.flag(items, true, "") },
      { label: "Custom…", icon: "calendar", onClick: async () => { const v = await formDialog.open({ title: "Follow up", fields: [{ key: "due", label: "Due date", type: "date", value: ymd(addDays(today, 1)), required: true }] }); if (v) actions.flag(items, true, v.due); } },
      { sep: true },
      { label: "Mark complete", icon: "check-circle", onClick: () => actions.completeFlag(items) },
      { label: "Clear flag", icon: "close", onClick: () => actions.flag(items, false) },
    ];
  },

  // ---------------------------------------------------------------- snooze
  snoozeMenu(items) {
    const now = new Date();
    const later = new Date(Math.ceil((now.getTime() + 3 * 3600e3) / 3600e3) * 3600e3);
    const tomorrow = at(addDays(now, 1), SNOOZE_HOUR);
    const weekend = at(now.getDay() === 6 ? addDays(now, 7) : nextWeekday(6), SNOOZE_HOUR);
    const nextWeek = at(nextWeekday(1), SNOOZE_HOUR);
    const opt = (label, d) => ({ label, icon: "clock", hint: `${fmtDay(d)} ${fmtTime(d)}`, onClick: () => actions.snooze(items, d.getTime()) });
    return [
      later.getDate() === now.getDate() ? opt("Later today", later) : null,
      opt("Tomorrow", tomorrow), opt("This weekend", weekend), opt("Next week", nextWeek),
      { label: "Choose a date…", icon: "calendar", onClick: async () => {
        const v = await formDialog.open({ title: "Snooze until", fields: [{ key: "when", label: "Date and time", type: "datetime-local", value: localIso(tomorrow), required: true }] });
        if (v) { const t = new Date(v.when).getTime(); if (t <= Date.now()) return toast("Choose a time in the future.", { danger: true }); actions.snooze(items, t); }
      } },
      state.folderRole === "snoozed" ? { sep: true } : null,
      state.folderRole === "snoozed" ? { label: "Unsnooze (return now)", icon: "inbox", onClick: () => actions.snooze(items, null) } : null,
    ];
  },

  // ---------------------------------------------------------------- quick steps
  quickStepsMenu(items) {
    const steps = state.settings?.quickSteps || [];
    return [
      ...steps.map((q) => ({ label: q.name, icon: "bolt", hint: q.actions.map((a) => a.type.toLowerCase().replace(/_/g, " ")).join(", "), onClick: () => this.runQuickStep(q, items) })),
      steps.length ? { sep: true } : null,
      { label: "New quick step…", icon: "plus", onClick: () => { settingsUI.open("quicksteps"); this.settings.editQuickStep(null); } },
      { label: "Manage quick steps", icon: "settings", onClick: () => settingsUI.open("quicksteps") },
    ];
  },
  async runQuickStep(q, items) {
    if (!items.length) return toast("Select a message first.", { danger: true });
    // Metadata and flags first; actions that move the message (and change its UID) last.
    const order = { CATEGORIZE: 0, PIN: 0, FLAG: 1, UNFLAG: 1, MARK_READ: 1, MARK_UNREAD: 1, COPY: 2, REPLY: 3, FORWARD: 3, MOVE: 4, ARCHIVE: 4, DELETE: 4 };
    const steps = [...q.actions].sort((a, b) => (order[a.type] ?? 2) - (order[b.type] ?? 2));
    for (const a of steps) {
      try {
        switch (a.type) {
          case "CATEGORIZE": if (a.category) await actions.categorize(items, a.category, true); break;
          case "PIN": await actions.pin(items, true); break;
          case "FLAG": await actions.flag(items, true); break;
          case "UNFLAG": await actions.flag(items, false); break;
          case "MARK_READ": await actions.read(items, true); break;
          case "MARK_UNREAD": await actions.read(items, false); break;
          case "COPY": if (a.folder) await actions.copy(items, a.folder); break;
          case "REPLY": case "FORWARD": {
            const d = await api("/api/message", { query: { folder: items[0].folder || state.folder, uid: items[0].uid, tz: VIEW_TZ } }); d.folder = items[0].folder || state.folder;
            await compose.open({ mode: a.type === "REPLY" ? "reply" : "forward", detail: d });
            if (a.to) $("cTo").value = a.to;
            const t = (state.settings.templates || []).find((x) => x.id === a.templateId);
            if (t) { $("cEditor").focus(); placeCaretAtStart($("cEditor")); document.execCommand("insertHTML", false, t.html); }
            break;
          }
          case "MOVE": if (a.folder) await actions.move(items, a.folder, { quiet: true }); break;
          case "ARCHIVE": await actions.archive(items); break;
          case "DELETE": await actions.delete(items); break;
        }
      } catch (e) { toast(`${q.name}: ${e.message}`, { danger: true }); return; }
    }
    toast(`Quick step "${q.name}" done`);
  },

  // ---------------------------------------------------------------- sweep
  async sweep(m) {
    if (!m) return toast("Select a message from the sender to clean up.", { danger: true });
    const sender = m.fromAddress;
    const folderName = folders.byPath(m.folder || state.folder)?.name || state.folder;
    const dests = [["__trash", "Deleted Items"], ["__archive", "Archive"], ...state.folders.filter((f) => !["inbox", "trash", "junk", "drafts", "sent"].includes(f.role)).map((f) => [f.path, f.name])];
    const v = await formDialog.open({
      title: "Sweep", ok: "Sweep",
      fields: [
        { type: "info", html: `Clean up messages from <b>${esc(sender)}</b> in <b>${esc(folderName)}</b>.` },
        { key: "mode", label: "What to do", type: "select", value: "all", options: [["all", "Move all messages from this sender"], ["future", "Move all messages and any future messages from this sender"], ["latest", "Always keep the latest message and move the rest"], ["older", "Move messages older than 10 days"]] },
        { key: "dest", label: "Move to", type: "select", value: "__trash", options: dests },
      ],
    });
    if (!v) return;
    try {
      const folder = m.folder || state.folder;
      const r = await api("/api/search/advanced", { query: { folder, scope: "folder", from: sender, limit: 500 } });
      let victims = r.messages.filter((x) => x.fromAddress.toLowerCase() === sender.toLowerCase()).sort((a, b) => b.date - a.date);
      if (v.mode === "latest") victims = victims.slice(1);
      if (v.mode === "older") victims = victims.filter((x) => x.date < Date.now() - 10 * 86400000);
      if (victims.length) {
        if (v.dest === "__trash") await actions.delete(victims);
        else if (v.dest === "__archive") await actions.archive(victims);
        else await actions.move(victims, v.dest);
      } else toast("Nothing to sweep.");
      if (v.mode === "future") {
        const rule = { id: "rule-" + Math.random().toString(36).slice(2, 10), name: `Sweep: ${sender}`, enabled: true, logic: "AND", criteria: [{ field: "SENDER", operator: "EQUALS", value: sender }],
          action: v.dest === "__trash" ? "DELETE" : "MOVE_TO_FOLDER", targetFolder: v.dest === "__trash" ? null : v.dest === "__archive" ? (folders.byRole("archive")?.path || "Archive") : v.dest, priority: 50, createdBy: "user", createdAt: Date.now(), note: "Created by Sweep", hitCount: 0 };
        await settingsSave({ ...state.settings, rules: [...state.settings.rules, rule] });
        toast(`Future messages from ${sender} will be swept automatically.`, { action: "Rules", onAction: () => settingsUI.open("rules") });
      }
      list.fetch({ silent: true });
    } catch (e) { toast("Sweep failed: " + e.message, { danger: true }); }
  },

  // ---------------------------------------------------------------- menus for messages
  messageMenu(items, m, { fromReading = false } = {}) {
    const one = items.length === 1 ? items[0] : null;
    const role = folders.byPath(m.folder || state.folder)?.role || state.folderRole;
    const getDetail = async () => { if (state.openDetail && mkey(state.openDetail) === mkey(m)) return state.openDetail; const d = await api("/api/message", { query: { folder: m.folder || state.folder, uid: m.uid, tz: VIEW_TZ } }); d.folder = m.folder || state.folder; return d; };
    const withDetail = (fn) => async () => { try { fn(await getDetail()); } catch (e) { toast(e.message, { danger: true }); } };
    const unread = items.some((x) => !x.read);
    return [
      !fromReading ? { label: "Open", icon: "mail", onClick: () => reading.open(m) } : null,
      one ? { label: "Reply", icon: "reply", onClick: withDetail((d) => compose.open({ mode: "reply", detail: d })) } : null,
      one ? { label: "Reply all", icon: "reply-all", onClick: withDetail((d) => compose.open({ mode: "replyAll", detail: d })) } : null,
      one ? { label: "Forward", icon: "forward", sub: [
        { label: "Forward", icon: "forward", onClick: withDetail((d) => compose.open({ mode: "forward", detail: d })) },
        { label: "Forward as attachment", icon: "attach", onClick: withDetail((d) => compose.open({ mode: "forwardAttach", detail: d })) },
      ] } : null,
      one && role === "sent" ? { label: "Edit as new message / resend", icon: "edit", onClick: withDetail((d) => compose.open({ mode: "editAsNew", detail: d })) } : null,
      { sep: true },
      { label: unread ? "Mark as read" : "Mark as unread", icon: unread ? "read" : "unread", onClick: () => actions.read(items, unread) },
      { label: "Flag", icon: "flag", sub: () => this.flagMenu(items) },
      { label: "Categorize", icon: "tag", sub: () => this.categorizeMenu(items) },
      { label: items.every((x) => x.pinned) ? "Unpin" : "Pin", icon: "pin", onClick: () => actions.pin(items, !items.every((x) => x.pinned)) },
      { label: "Snooze", icon: "clock", sub: () => this.snoozeMenu(items) },
      { sep: true },
      { label: "Move to…", icon: "move", onClick: () => actions.pickFolder(items) },
      { label: "Copy to…", icon: "copy", onClick: () => actions.pickFolder(items, { copy: true }) },
      { label: "Archive", icon: "archive", onClick: () => actions.archive(items) },
      { label: "Quick steps", icon: "bolt", sub: () => this.quickStepsMenu(items) },
      { sep: true },
      role === "junk" ? { label: "Not junk", icon: "shield-ok", onClick: () => actions.junk(items, false) } : { label: "Report junk", icon: "junk", onClick: () => actions.junk(items, true) },
      { label: "Report phishing", icon: "phish", onClick: () => actions.phish(items) },
      one ? { label: "Block sender", icon: "block", danger: true, onClick: () => actions.blockSender(one) } : null,
      one ? { label: "Trust sender (safe list)", icon: "shield-ok", onClick: () => actions.trustSender(one) } : null,
      one ? { label: "Sweep…", icon: "sweep", onClick: () => this.sweep(one) } : null,
      one ? { label: "Create rule…", icon: "rules", onClick: () => this.createRuleFrom(one) } : null,
      one ? { label: "Analyse & make junk rule", icon: "sparkle", onClick: withDetail((d) => analysis.open(d)) } : null,
      one && role === "inbox" ? { label: m.focused === false ? "Always move to Focused" : "Always move to Other", icon: "inbox", onClick: () => this.focusOverride(one, m.focused === false) } : null,
      one ? { label: "Ignore conversation", icon: "mail-x", onClick: () => actions.ignore(this.conversationOf(one)) } : null,
      one ? { label: "Add sender to contacts", icon: "person-add", onClick: () => peopleUI.edit(null, { name: one.fromName, email: one.fromAddress }) } : null,
      { sep: true },
      one ? { label: "View", icon: "eye", sub: [
        { label: "Immersive reader", icon: "reader", onClick: withDetail((d) => this.reader(d)) },
        { label: "Translate", icon: "globe", onClick: withDetail((d) => this.translate(d)) },
        { label: "Open in new window", icon: "external", onClick: withDetail((d) => this.openInWindow(d)) },
        { label: "View message headers", icon: "list", onClick: withDetail((d) => popup("Message headers", { text: Object.entries(d.headers || {}).map(([k, v]) => `${k}: ${v}`).join("\n\n") || "(no headers captured)" })) },
        { label: "View message source", icon: "note", onClick: async () => { try { const r = await api("/api/raw", { query: { folder: m.folder || state.folder, uid: m.uid }, raw: true }); popup("Message source", { text: await r.text() }); } catch (e) { toast(e.message, { danger: true }); } } },
      ] } : null,
      one ? { label: "Print", icon: "print", onClick: withDetail((d) => this.print(d)) } : null,
      one ? { label: "Download (.eml)", icon: "download", onClick: () => download(apiUrl("/api/raw", { folder: m.folder || state.folder, uid: m.uid, download: "true" })) } : null,
      fromReading && state.openDetail?.listUnsubscribe ? { label: "Unsubscribe", icon: "block", onClick: () => this.unsubscribe(state.openDetail) } : null,
      { sep: true },
      { label: "Delete", icon: "trash", danger: true, onClick: () => actions.delete(items) },
      { label: "Delete permanently", icon: "trash", danger: true, onClick: () => actions.delete(items, true) },
    ];
  },
  conversationOf(m) {
    const t = list.threads(state.messages).find((g) => g.some((x) => mkey(x) === mkey(m)));
    return t || [m];
  },
  async focusOverride(m, toFocused) {
    const f = state.settings.focused || { enabled: true, alwaysFocused: [], alwaysOther: [] };
    const addr = m.fromAddress.toLowerCase();
    const next = { ...f, alwaysFocused: (f.alwaysFocused || []).filter((x) => x !== addr), alwaysOther: (f.alwaysOther || []).filter((x) => x !== addr) };
    (toFocused ? next.alwaysFocused : next.alwaysOther).push(addr);
    await settingsSave({ ...state.settings, focused: next });
    toast(`Messages from ${addr} will always go to ${toFocused ? "Focused" : "Other"}.`);
    list.fetch({ silent: true });
  },
  unsubscribe(d) {
    const m = d.listUnsubscribe.match(/<(https?:[^>]+)>/); const mail = d.listUnsubscribe.match(/<mailto:([^>]+)>/);
    if (m) openExternal(m[1]);
    else if (mail) { const [to, q] = mail[1].split("?"); const p = new URLSearchParams(q || ""); compose.open({ mode: "new", to, subject: p.get("subject") || "unsubscribe" }); }
  },
  createRuleFrom(m) {
    settingsUI.editRule(null, { name: `From ${m.fromName || m.fromAddress}`, logic: "AND", criteria: [{ field: "SENDER", operator: "EQUALS", value: m.fromAddress }], action: "MOVE_TO_FOLDER", targetFolder: folders.byRole("archive")?.path || "Archive" });
  },
  printable(d, allowImages = true) {
    return `<title>${esc(d.subject)}</title><style>body{font-family:Segoe UI,system-ui,sans-serif;margin:24px}h2{margin:0 0 6px}table.h td{padding:1px 10px 1px 0;font-size:13px;vertical-align:top}hr{border:0;border-top:1px solid #ccc;margin:12px 0}</style><h2>${esc(d.subject)}</h2><table class="h"><tr><td><b>From</b></td><td>${esc(d.fromName)} &lt;${esc(d.fromAddress)}&gt;</td></tr><tr><td><b>Sent</b></td><td>${esc(fmtDate(d.date, { long: true }))}</td></tr><tr><td><b>To</b></td><td>${esc((d.to || []).join("; "))}</td></tr>${d.cc?.length ? `<tr><td><b>Cc</b></td><td>${esc(d.cc.join("; "))}</td></tr>` : ""}${(d.attachments || []).filter((a) => !a.inline).length ? `<tr><td><b>Attachments</b></td><td>${esc(d.attachments.filter((a) => !a.inline).map((a) => a.fileName).join("; "))}</td></tr>` : ""}</table><hr>${d.bodyHtml ? sanitizeHtml(d.bodyHtml, { allowImages, folder: d.folder, uid: d.uid }).html : `<pre style="white-space:pre-wrap;font-family:inherit">${esc(d.bodyText)}</pre>`}`;
  },
  print(d) {
    if (nativePrint(d.subject)) return;
    const w = window.open("", "_blank");
    if (!w) { window.print(); return; }
    w.document.write(this.printable(d)); w.document.close(); setTimeout(() => w.print(), 400);
  },
  async openInWindow(m) {
    try {
      const d = m.bodyText !== undefined ? m : await api("/api/message", { query: { folder: m.folder || state.folder, uid: m.uid, tz: VIEW_TZ } });
      d.folder = d.folder || m.folder || state.folder;
      popup(d.subject, { html: this.printable(d, state.imagesOnce) });
    } catch (e) { toast(e.message, { danger: true }); }
  },

  // ---------------------------------------------------------------- ribbon
  recentFolders() { return prefs.get("recentFolders", []).map((p) => folders.byPath(p)).filter(Boolean); },
  pushRecentFolder(p) { prefs.set("recentFolders", [p, ...prefs.get("recentFolders", []).filter((x) => x !== p)].slice(0, 6)); },
  wireRibbon() {
    qsa(".ribbon-tabs button").forEach((b) => (b.onclick = () => { qsa(".ribbon-tabs button").forEach((x) => x.classList.toggle("active", x === b)); qsa("[data-rbar]").forEach((bar) => bar.classList.toggle("hidden", bar.dataset.rbar !== b.dataset.rtab)); }));
    const T = () => list.targets();
    const need = (fn) => () => { const t = T(); if (!t.length) return toast("Select a message first."); fn(t); };
    const needMenu = (id, fn) => { $(id).onclick = (e) => { e.stopPropagation(); const t = T(); if (!t.length) return toast("Select a message first."); menu.openFor(e.currentTarget, fn(t)); }; };
    $("btnNewMenu").onclick = (e) => { e.stopPropagation(); menu.openFor(e.currentTarget, [
      { label: "Mail", icon: "mail", onClick: () => compose.open({ mode: "new" }) },
      { label: "Event", icon: "calendar", onClick: () => calendarUI.newEvent({}) },
      { label: "Contact", icon: "person-add", onClick: () => peopleUI.edit(null) },
      { label: "Contact list", icon: "people", onClick: () => peopleUI.editList(null) },
      { label: "Task", icon: "todo", onClick: () => todoUI.quickAdd() },
    ]); };
    $("cmdDelete").onclick = need((t) => actions.delete(t));
    $("cmdDeleteMenu").onclick = (e) => { e.stopPropagation(); const t = T(); menu.openFor(e.currentTarget, [
      { label: "Delete", icon: "trash", disabled: !t.length, onClick: () => actions.delete(t) },
      { label: "Delete permanently", icon: "trash", danger: true, disabled: !t.length, onClick: () => actions.delete(t, true) },
      { label: "Ignore conversation", icon: "mail-x", disabled: t.length !== 1, onClick: () => actions.ignore(this.conversationOf(t[0])) },
      { sep: true },
      { label: "Empty folder", icon: "trash", danger: true, onClick: () => this.emptyFolder(folders.byPath(state.folder)) },
    ]); };
    $("cmdArchive").onclick = need((t) => actions.archive(t));
    $("cmdJunk").onclick = need((t) => actions.junk(t, true));
    $("cmdNotJunk").onclick = need((t) => actions.junk(t, false));
    $("cmdReportMenu").onclick = (e) => { e.stopPropagation(); const t = T(); menu.openFor(e.currentTarget, [
      { label: "Report junk", icon: "junk", disabled: !t.length, onClick: () => actions.junk(t, true) },
      { label: "Report phishing", icon: "phish", disabled: !t.length, onClick: () => actions.phish(t) },
      { label: "Not junk", icon: "shield-ok", disabled: !t.length, onClick: () => actions.junk(t, false) },
      { label: "Block sender", icon: "block", danger: true, disabled: t.length !== 1, onClick: () => actions.blockSender(t[0]) },
      { sep: true },
      { label: "Junk email settings", icon: "settings", onClick: () => settingsUI.open("junk") },
    ]); };
    $("cmdSweep").onclick = need((t) => this.sweep(t[0]));
    $("cmdMove").onclick = (e) => { e.stopPropagation(); const t = T(); if (!t.length) return toast("Select a message first."); menu.openFor(e.currentTarget, [
      ...this.recentFolders().filter((f) => f.path !== state.folder).map((f) => ({ label: f.name, icon: FOLDER_ICONS[f.role] || "folder", onClick: () => (f.role === "junk" ? actions.junk(t, true) : actions.move(t, f.path)) })),
      this.recentFolders().length ? { sep: true } : null,
      { label: "Other folder…", icon: "move", onClick: () => actions.pickFolder(t) },
      { label: "Copy to folder…", icon: "copy", onClick: () => actions.pickFolder(t, { copy: true }) },
      { label: "Archive", icon: "archive", onClick: () => actions.archive(t) },
    ].filter(Boolean)); };
    const withOpen = (mode) => async () => { const t = T(); if (!t.length) return toast("Select a message first."); const m = t[0]; const d = state.openDetail && mkey(state.openDetail) === mkey(m) ? state.openDetail : await api("/api/message", { query: { folder: m.folder || state.folder, uid: m.uid, tz: VIEW_TZ } }); d.folder = d.folder || m.folder || state.folder; compose.open({ mode, detail: d }); };
    $("cmdReply").onclick = withOpen("reply");
    $("cmdReplyAll").onclick = withOpen("replyAll");
    $("cmdForward").onclick = withOpen("forward");
    $("cmdForwardMenu").onclick = (e) => { e.stopPropagation(); menu.openFor(e.currentTarget, [{ label: "Forward", icon: "forward", onClick: withOpen("forward") }, { label: "Forward as attachment", icon: "attach", onClick: withOpen("forwardAttach") }]); };
    $("cmdRead").onclick = need((t) => actions.read(t, !t.every((m) => m.read)));
    needMenu("cmdCategorize", (t) => this.categorizeMenu(t));
    $("cmdFlag").onclick = need((t) => actions.flag(t, !t.every((m) => m.flagged)));
    needMenu("cmdFlagMenu", (t) => this.flagMenu(t));
    $("cmdPin").onclick = need((t) => actions.pin(t, !t.every((m) => m.pinned)));
    needMenu("cmdSnooze", (t) => this.snoozeMenu(t));
    $("cmdQuickSteps").onclick = (e) => { e.stopPropagation(); menu.openFor(e.currentTarget, this.quickStepsMenu(T())); };
    $("cmdRules").onclick = (e) => { e.stopPropagation(); const t = T(); menu.openFor(e.currentTarget, [
      { label: "Create rule from this message…", icon: "plus", disabled: t.length !== 1, onClick: () => this.createRuleFrom(t[0]) },
      { label: "Analyse & make junk rule", icon: "sparkle", disabled: t.length !== 1, onClick: async () => { const d = await api("/api/message", { query: { folder: t[0].folder || state.folder, uid: t[0].uid } }); d.folder = t[0].folder || state.folder; analysis.open(d); } },
      { label: "Manage rules", icon: "settings", onClick: () => settingsUI.open("rules") },
    ]); };
    $("cmdMarkAllRead").onclick = () => this.markAllRead(folders.byPath(state.folder));
    $("cmdPrint").onclick = () => { if (state.openDetail) this.print(state.openDetail); else toast("Open a message to print it."); };
    $("cmdAnalyze").onclick = () => { if (state.openDetail) analysis.open(state.openDetail); else toast("Open a message to analyse it."); };
    $("cmdRescan").onclick = async () => { toast("Re-scanning this folder…"); await list.fetch({ rescan: true }); };
    // View tab
    $("vwSettings").onclick = () => settingsUI.open("layout");
    $("vwLayout").onclick = (e) => { e.stopPropagation(); const cur = prefs.get("pane", "right"); menu.openFor(e.currentTarget, [["right", "Show reading pane on the right"], ["bottom", "Show reading pane at the bottom"], ["off", "Hide reading pane"]].map(([v, l]) => ({ label: l, checked: cur === v, onClick: () => { prefs.set("pane", v); applyTheme(); } }))); };
    $("vwDensity").onclick = (e) => { e.stopPropagation(); const cur = prefs.get("density", "comfortable"); menu.openFor(e.currentTarget, [
      ...[["comfortable", "Comfortable"], ["compact", "Compact"]].map(([v, l]) => ({ label: l, checked: cur === v, onClick: () => { prefs.set("density", v); applyTheme(); } })),
      { sep: true },
      { label: "Show preview text", checked: prefs.get("showPreview", true), onClick: () => { prefs.set("showPreview", !prefs.get("showPreview", true)); applyTheme(); } },
      { label: "Show sender pictures", checked: prefs.get("showAvatars", true), onClick: () => { prefs.set("showAvatars", !prefs.get("showAvatars", true)); applyTheme(); } },
    ]); };
    $("vwConversations").onclick = (e) => { e.stopPropagation(); menu.openFor(e.currentTarget, [
      { label: "Show messages as conversations", checked: prefs.get("conversations", true), onClick: () => { prefs.set("conversations", true); list.render(); } },
      { label: "Show individual messages", checked: !prefs.get("conversations", true), onClick: () => { prefs.set("conversations", false); list.render(); } },
      { sep: true },
      { label: "Newest message on top", checked: prefs.get("newestOnTop", true), onClick: () => { prefs.set("newestOnTop", true); reading.renderThread(); } },
      { label: "Newest message at the bottom", checked: !prefs.get("newestOnTop", true), onClick: () => { prefs.set("newestOnTop", false); reading.renderThread(); } },
    ]); };
    $("vwFocused").onclick = async () => { const on = !list.focusedActive() || state.folderRole !== "inbox" ? !prefs.get("focusedInbox", true) : false; prefs.set("focusedInbox", on); if (on && state.settings.focused?.enabled === false) await settingsSave({ ...state.settings, focused: { ...state.settings.focused, enabled: true } }); toast(on ? "Focused Inbox on" : "Focused Inbox off"); list.render(); };
    $("vwSort").onclick = (e) => this.sortMenu(e.currentTarget);
    $("vwFolderPane").onclick = () => { prefs.set("navCollapsed", !prefs.get("navCollapsed", false)); applyTheme(); };
    const zoom = (d) => { const z = Math.min(200, Math.max(60, prefs.get("readZoom", 100) + d)); prefs.set("readZoom", z); applyTheme(); };
    $("vwZoomIn").onclick = () => zoom(10); $("vwZoomOut").onclick = () => zoom(-10);
    $("vwReader").onclick = () => { if (state.openDetail) this.reader(state.openDetail); else toast("Open a message first."); };
    $("vwDark").onclick = () => $("btnTheme").onclick();
    $("vwTheme").onclick = (e) => { e.stopPropagation(); const cur = prefs.get("accent", "blue"); menu.openFor(e.currentTarget, [["blue", "#0f6cbd", "Blue"], ["navy", "#0c3b5e", "Navy"], ["teal", "#038387", "Teal"], ["green", "#107c10", "Green"], ["purple", "#8764b8", "Purple"], ["pink", "#c239b3", "Pink"], ["red", "#c4314b", "Red"], ["orange", "#ca5010", "Orange"], ["graphite", "#4f4f4f", "Graphite"]].map(([id, c, l]) => ({ label: l, color: c, checked: cur === id, onClick: () => { prefs.set("accent", id); applyTheme(); } }))); };
    // Help tab
    $("hlpShortcuts").onclick = () => $("shortcutsDialog").showModal();
    $("hlpHelp").onclick = () => openExternal(QB_REPO + "#readme");
    $("hlpWhatsNew").onclick = () => this.whatsNew();
    $("hlpReport").onclick = () => openExternal(QB_REPO + "/issues/new");
    $("hlpAbout").onclick = () => settingsUI.open("about");
  },
  filterMenu(anchor) {
    const cur = state.filter;
    menu.openFor(anchor, [
      ...FILTERS.map(([v, l]) => ({ label: l, checked: cur === v, onClick: () => { state.filter = v; state.selected.clear(); list.render(); } })),
      (state.settings?.categories || []).length ? { header: "Categories" } : null,
      ...(state.settings?.categories || []).map((c) => ({ label: c.name, color: c.color, checked: cur === "cat:" + c.name, onClick: () => { state.filter = "cat:" + c.name; list.render(); } })),
    ]);
  },
  sortMenu(anchor) {
    const s = prefs.get("sort", { by: "date", dir: "desc" });
    menu.openFor(anchor, [
      { header: "Sort by" },
      ...SORTS.map(([v, l]) => ({ label: l, checked: s.by === v, onClick: () => { prefs.set("sort", { by: v, dir: v === "date" || v === "size" || v === "importance" || v === "flag" ? "desc" : "asc" }); list.render(); } })),
      { sep: true },
      { label: s.by === "date" ? "Newest on top" : "Descending", checked: s.dir === "desc", onClick: () => { prefs.set("sort", { ...s, dir: "desc" }); list.render(); } },
      { label: s.by === "date" ? "Oldest on top" : "Ascending", checked: s.dir === "asc", onClick: () => { prefs.set("sort", { ...s, dir: "asc" }); list.render(); } },
    ]);
  },
  whatsNew() {
    const node = el("div", { class: "whats-new" }, el("h4", { text: `Anne's Post ${QB_VERSION}` }), el("ul", {}, ...[
      "Calendar with day, work week, week and month views, recurring events, reminders, meeting invitations and replies, .ics import/export, internet calendar subscriptions, holiday and birthday calendars.",
      "People: contacts and contact lists with vCard and CSV import/export.",
      "To Do: task lists, My Day, Important, Planned, steps, due dates, reminders and repeats, plus flagged email.",
      "Mail: Focused Inbox, conversations, categories, pins, follow-up flags with due dates, snooze, schedule send, undo send, quick steps, sweep, ignore conversation, signatures, templates, automatic replies, read and delivery receipts, importance, aliases, folder management, advanced search and more.",
      "Several accounts at once, colour themes, immersive reader, translation with a local AI model, and swipe actions on phones.",
    ].map((t) => el("li", { text: t }))));
    popup("What's new", { node });
  },

  // ---------------------------------------------------------------- folders
  async createFolder(parent) {
    const v = await formDialog.open({ title: parent ? `New folder in ${folders.byPath(parent)?.name || parent}` : "New folder", ok: "Create", fields: [{ key: "name", label: "Folder name", required: true }] });
    if (!v) return null;
    try { const r = await api("/api/folders/create", { method: "POST", body: { name: v.name.trim(), parent } }); await folders.load(); toast(`Folder "${v.name.trim()}" created`); return r.path; }
    catch (e) { toast("Could not create the folder: " + e.message, { danger: true }); return null; }
  },
  async renameFolder(f) {
    const v = await formDialog.open({ title: "Rename folder", ok: "Rename", fields: [{ key: "name", label: "New name", value: f.name, required: true }] });
    if (!v || v.name.trim() === f.name) return;
    try { const r = await api("/api/folders/rename", { method: "POST", body: { path: f.path, name: v.name.trim() } }); if (state.folder === f.path) state.folder = r.path; const s = await api("/api/settings"); state.settings = s; await folders.load(); }
    catch (e) { toast("Could not rename: " + e.message, { danger: true }); }
  },
  async moveFolder(f) {
    const opts = [["", "(top level)"], ...state.folders.filter((x) => x.path !== f.path && !x.path.startsWith(f.path + f.delimiter) && x.role !== "trash").map((x) => [x.path, "  ".repeat(x.depth) + x.name])];
    const v = await formDialog.open({ title: `Move "${f.name}"`, ok: "Move", fields: [{ key: "parent", label: "Into", type: "select", options: opts, value: "" }] });
    if (!v) return;
    try { const r = await api("/api/folders/move", { method: "POST", body: { path: f.path, parent: v.parent || null } }); if (state.folder === f.path) state.folder = r.path; await folders.load(); }
    catch (e) { toast("Could not move the folder: " + e.message, { danger: true }); }
  },
  async deleteFolder(f) {
    if (!confirm(`Delete the folder "${f.name}"? Its messages and subfolders move to Deleted Items.`)) return;
    try { const r = await api("/api/folders/delete", { method: "POST", body: { path: f.path } }); toast(`Folder deleted · ${plural(r.ok, "message")} moved to Deleted Items`); if (state.folder === f.path) await list.load("INBOX"); const s = await api("/api/settings"); state.settings = s; await folders.load(); }
    catch (e) { toast("Could not delete: " + e.message, { danger: true }); }
  },
  async emptyFolder(f) {
    if (!f) return;
    const permanent = f.role === "trash" || f.role === "junk";
    if (!confirm(permanent ? `Permanently delete everything in ${f.name}? This cannot be undone.` : `Move everything in ${f.name} to Deleted Items?`)) return;
    try { const r = await api("/api/folders/empty", { method: "POST", body: { path: f.path } }); toast(`${plural(r.ok, "message")} ${permanent ? "deleted" : "moved to Deleted Items"}`); if (state.folder === f.path) list.fetch(); folders.load(); }
    catch (e) { toast("Could not empty the folder: " + e.message, { danger: true }); }
  },
  async markAllRead(f) {
    if (!f || f.path === SCHEDULED) return;
    try { const r = await api("/api/folders/markread", { method: "POST", body: { path: f.path } }); if (state.folder === f.path) list.patchLocal(state.messages, { read: true }); toast(`${plural(r.ok, "message")} marked as read`); folders.load(); }
    catch (e) { toast("Could not update: " + e.message, { danger: true }); }
  },
  async toggleFavorite(f) {
    const cur = state.settings.favorites?.length ? state.settings.favorites : folders.favorites().map((x) => x.path);
    const next = cur.includes(f.path) ? cur.filter((p) => p !== f.path) : [...cur, f.path];
    await settingsSave({ ...state.settings, favorites: next });
    folders.render();
  },
  async importInto(f) {
    const files = await pickFiles({ accept: ".eml,.mbox,message/rfc822,application/mbox", multiple: true });
    if (!files.length) return;
    let n = 0;
    for (const file of files) {
      try { const r = await api("/api/import/messages", { method: "POST", query: { folder: f.path }, rawBody: file }); n += r.imported; }
      catch (e) { toast(`${file.name}: ${e.message}`, { danger: true }); }
    }
    toast(`Imported ${plural(n, "message")} into ${f.name}`);
    folders.load(); if (state.folder === f.path) list.fetch();
  },
  folderMenu(e, f) {
    const isFav = (state.settings.favorites?.length ? state.settings.favorites : folders.favorites().map((x) => x.path)).includes(f.path);
    const special = f.role !== "other";
    menu.openAt(e.clientX, e.clientY, [
      { label: "Create new subfolder", icon: "folder-plus", disabled: !state.capabilities.folders, onClick: () => this.createFolder(f.path) },
      { label: "Rename folder", icon: "edit", disabled: special, onClick: () => this.renameFolder(f) },
      { label: "Move folder…", icon: "move", disabled: special, onClick: () => this.moveFolder(f) },
      { label: "Delete folder", icon: "trash", danger: true, disabled: special, onClick: () => this.deleteFolder(f) },
      { sep: true },
      { label: isFav ? "Remove from Favorites" : "Add to Favorites", icon: "star", onClick: () => this.toggleFavorite(f) },
      { label: "Mark all as read", icon: "check", onClick: () => this.markAllRead(f) },
      { label: "Empty folder", icon: "trash", danger: true, onClick: () => this.emptyFolder(f) },
      { sep: true },
      { label: "Import messages (.eml / .mbox)…", icon: "upload", disabled: !state.capabilities.folders, onClick: () => this.importInto(f) },
      { label: "Export folder (.mbox)", icon: "download", onClick: () => download(apiUrl("/api/export/mbox", { folder: f.path })) },
    ]);
  },
  accountMenu(e) {
    const all = accounts.all();
    const me = state.account?.email;
    const items = [
      { header: "Accounts" },
      ...all.map((x) => ({ label: x.account.email, icon: "user", checked: x.account.email === me, onClick: () => x.account.email !== me && session.switchTo(x.account.email) })),
      { label: "Add account", icon: "person-add", onClick: () => setup.show({ adding: true }) },
      { sep: true },
      { label: "Account settings", icon: "settings", onClick: () => settingsUI.open("account") },
      { label: `Sign out of ${me}`, icon: "signout", danger: true, onClick: () => signOut(false) },
    ];
    if (e.currentTarget && e.currentTarget.getBoundingClientRect) menu.openFor(e.currentTarget, items); else menu.openAt(e.clientX, e.clientY, items);
  },
  async refreshOtherAccounts() {
    for (const x of accounts.all()) {
      if (x.account.email === state.account?.email) continue;
      const tk = accounts.token(x.account.email);
      if (!tk) continue;
      try { const fs = await api("/api/folders", { token: tk }); const inbox = fs.find((f) => f.role === "inbox"); this.otherUnread[x.account.email.toLowerCase()] = inbox?.unread || 0; }
      catch { accounts.setToken(x.account.email, null); }
    }
    folders.render();
  },
  async loadQuota() {
    try {
      const q = await api("/api/quota");
      const line = $("quotaLine");
      if (!q || q.limitKb <= 0) { line.classList.add("hidden"); return; }
      const pct = Math.min(100, Math.round((q.usedKb / q.limitKb) * 100));
      $("quotaFill").style.width = pct + "%";
      line.classList.toggle("full", pct >= 90);
      $("quotaText").textContent = `${fmtSize(q.usedKb * 1024)} of ${fmtSize(q.limitKb * 1024)} used (${pct}%)`;
      line.classList.remove("hidden");
      state.quota = q;
    } catch { $("quotaLine").classList.add("hidden"); }
  },

  // ---------------------------------------------------------------- scheduled send
  async loadOutbox() {
    try { state.outbox = await api("/api/outbox"); } catch { state.outbox = []; }
    folders.render();
    if (state.folder === SCHEDULED && !state.search) { state.messages = this.outboxRows(); list.render(); }
  },
  outboxRows() { return state.outbox.map((o) => ({ scheduled: true, uid: o.id, id: o.id, folder: SCHEDULED, subject: o.subject, to: o.to, fromName: "To: " + o.to.join(", "), fromAddress: "", date: o.sendAt, read: true, error: o.error })); },
  outboxRow(m) {
    const div = el("div", { class: "msg", dataset: { key: mkey(m) } },
      el("span", { class: "cb" }), el("div", { class: "avatar", style: { background: "var(--accent)" } }, icon("send-later", 18)),
      el("div", { class: "msg-main" },
        el("div", { class: "msg-line" }, el("span", { class: "msg-from", text: m.fromName }), m.error ? el("span", { class: "badge danger", title: m.error }, "Retrying") : null, el("span", { class: "msg-date", text: fmtDate(m.date, { long: true }) })),
        el("div", { class: "msg-line" }, el("span", { class: "msg-subject", text: m.subject || "(no subject)" })),
        el("div", { class: "msg-line msg-preview-line" }, el("span", { class: "msg-preview", text: m.error ? "Last attempt failed: " + m.error : "Waiting to be sent" }))));
    div.onclick = (e) => this.openScheduled(m, e);
    div.oncontextmenu = (e) => { e.preventDefault(); this.openScheduled(m, e); };
    return div;
  },
  openScheduled(m, e) {
    const items = [
      { header: `Sends ${fmtDate(m.date, { long: true })}` },
      { label: "Edit (move to Drafts)", icon: "edit", onClick: async () => { try { const r = await api(`/api/outbox/${encodeURIComponent(m.id)}`, { method: "DELETE", query: { drafts: "true" } }); await this.loadOutbox(); const drafts = folders.byRole("drafts"); if (drafts && r.uid > 0) { const d = await api("/api/message", { query: { folder: drafts.path, uid: r.uid, tz: VIEW_TZ } }); compose.open({ mode: "draft", draft: { ...d, folder: drafts.path } }); } else toast("Moved to Drafts"); } catch (er) { toast(er.message, { danger: true }); } } },
      { label: "Cancel sending", icon: "close", danger: true, onClick: async () => { if (!confirm("Cancel this scheduled message? It will be discarded.")) return; try { await api(`/api/outbox/${encodeURIComponent(m.id)}`, { method: "DELETE" }); toast("Scheduled message cancelled"); this.loadOutbox(); } catch (er) { toast(er.message, { danger: true }); } } },
    ];
    if (e) menu.openAt(e.clientX, e.clientY, items); else menu.openAt(innerWidth / 2 - 100, innerHeight / 3, items);
  },
  scheduleMenu(anchor) {
    const now = new Date();
    const tm = at(addDays(now, 1), 8), ta = at(addDays(now, 1), 13), mon = at(nextWeekday(1), 8);
    const opt = (label, d) => ({ label, icon: "clock", hint: `${fmtDay(d)} ${fmtTime(d)}`, onClick: () => compose.send({ sendAt: d.getTime() }) });
    menu.openFor(anchor, [
      { header: "Schedule send" },
      opt("Tomorrow morning", tm), opt("Tomorrow afternoon", ta), opt("Monday morning", mon),
      { label: "Custom time…", icon: "calendar", onClick: async () => { const v = await formDialog.open({ title: "Schedule send", ok: "Schedule", fields: [{ key: "when", label: "Send at", type: "datetime-local", value: localIso(tm), required: true }, { type: "info", text: "Anne's Post sends the message at that time while it is running (always the case for the server version; keep the desktop or phone app open)." }] }); if (!v) return; const t = new Date(v.when).getTime(); if (t < Date.now() + 60000) return toast("Choose a time at least a minute from now.", { danger: true }); compose.send({ sendAt: t }); } },
    ]);
  },

  // ---------------------------------------------------------------- compose helpers
  signatureMenu(anchor) {
    const sigs = state.settings?.signatures || [];
    menu.openFor(anchor, [
      ...sigs.map((s) => ({ label: s.name, icon: "edit", onClick: () => { $("cEditor").focus(); document.execCommand("insertHTML", false, `<div class="qb-signature">${s.html}</div>`); } })),
      sigs.length ? { sep: true } : null,
      { label: "Signatures…", icon: "settings", onClick: () => settingsUI.open("signatures") },
    ]);
  },
  templateMenu(anchor) {
    const tpls = state.settings?.templates || [];
    menu.openFor(anchor, [
      ...tpls.map((t) => ({ label: t.name, icon: "note", onClick: () => { if (t.subject && !$("cSubject").value) $("cSubject").value = t.subject; $("cEditor").focus(); document.execCommand("insertHTML", false, t.html); if (state.compose) state.compose.dirty = true; } })),
      tpls.length ? { sep: true } : null,
      { label: "Save this message as a template", icon: "plus", onClick: () => this.saveAsTemplate() },
      { label: "Manage templates", icon: "settings", onClick: () => settingsUI.open("templates") },
    ]);
  },
  async saveAsTemplate() {
    const v = await formDialog.open({ title: "Save as template", fields: [{ key: "name", label: "Template name", value: $("cSubject").value, required: true }] });
    if (!v) return;
    const t = { id: "tpl-" + Math.random().toString(36).slice(2, 10), name: v.name.trim(), subject: $("cSubject").value, html: $("cEditor").innerHTML };
    await settingsSave({ ...state.settings, templates: [...(state.settings.templates || []), t] });
    toast(`Template "${t.name}" saved`);
  },
  emojiMenu(anchor) {
    const set = "😀 😃 😄 😁 😆 😅 😂 🙂 😉 😊 😇 🥰 😍 😘 😋 😎 🤓 🤔 🤗 🙄 😐 😬 😴 😷 🤒 🥳 😢 😭 😤 😡 👍 👎 👏 🙌 🙏 💪 👋 🤝 ✌️ 👌 ❤️ 💙 💚 💛 🧡 💜 🖤 💔 ⭐ 🌟 ✨ 🔥 🎉 🎂 🎁 ☕ 🍰 🌹 🌻 ☀️ 🌧️ ❄️ ⚡ ✅ ❌ ⚠️ ❓ ❗ 📌 📎 📅 📞 📧 ✉️ 📝 📖 💡 🔒 🏠 🚗 ✈️ 🕐 ⏰".split(" ");
    const grid = el("div", { class: "emoji-grid" }, ...set.map((e) => el("button", { text: e, onclick: (ev) => { ev.stopPropagation(); menu.close(); $("cEditor").focus(); document.execCommand("insertText", false, e); } })));
    menu.openFor(anchor, [{ node: grid }]);
  },
  async insertTable() {
    const sel = getSelection(); const range = sel.rangeCount ? sel.getRangeAt(0) : null;
    const v = await formDialog.open({ title: "Insert table", ok: "Insert", fields: [{ key: "rows", label: "Rows", type: "number", value: 3, min: 1, max: 50 }, { key: "cols", label: "Columns", type: "number", value: 3, min: 1, max: 12 }, { key: "header", label: "First row is a header", type: "checkbox", value: true }] });
    if (!v) return;
    const r = Math.min(50, Math.max(1, Number(v.rows) || 3)), c = Math.min(12, Math.max(1, Number(v.cols) || 3));
    let html = '<table style="border-collapse:collapse;min-width:50%">';
    for (let i = 0; i < r; i++) { html += "<tr>"; for (let j = 0; j < c; j++) html += (i === 0 && v.header ? '<th style="border:1px solid #bbb;padding:4px 8px;background:#f3f3f3">' : '<td style="border:1px solid #bbb;padding:4px 8px">') + "&nbsp;" + (i === 0 && v.header ? "</th>" : "</td>"); html += "</tr>"; }
    html += "</table><br>";
    $("cEditor").focus();
    if (range && $("cEditor").contains(range.startContainer)) { sel.removeAllRanges(); sel.addRange(range); }
    document.execCommand("insertHTML", false, html);
  },
  async insertLink() {
    const sel = getSelection(); const range = sel.rangeCount ? sel.getRangeAt(0) : null;
    const text = range ? range.toString() : "";
    const v = await formDialog.open({ title: "Insert link", ok: "Insert", fields: [{ key: "url", label: "Web address", type: "url", value: "https://", required: true }, { key: "text", label: "Display as", value: text }] });
    if (!v) return;
    $("cEditor").focus();
    if (range && $("cEditor").contains(range.startContainer)) { sel.removeAllRanges(); sel.addRange(range); }
    const url = v.url.trim();
    if (!/^(https?:|mailto:|tel:)/i.test(url)) return toast("Links must start with http://, https://, mailto: or tel:", { danger: true });
    document.execCommand("insertHTML", false, `<a href="${esc(url)}">${esc(v.text || url)}</a>`);
  },
  /** Contact names and contact lists in a recipient field → real addresses. */
  expandRecipients(list) {
    const out = [];
    for (const raw of list) {
      const t = raw.trim(); if (!t) continue;
      if (isEmail(t)) { out.push(t); continue; }
      const cl = pim.data.contactLists.find((l) => l.name.toLowerCase() === t.toLowerCase());
      if (cl) { cl.members.forEach((m) => out.push(m.name ? `${m.name} <${m.email}>` : m.email)); continue; }
      const c = pim.data.contacts.find((x) => pim.contactName(x).toLowerCase() === t.toLowerCase() && x.emails?.length);
      if (c) { out.push(`${pim.contactName(c)} <${c.emails[0].value}>`); continue; }
      out.push(t);
    }
    return out;
  },
  checkNames() {
    const unresolved = [];
    for (const id of ["cTo", "cCc", "cBcc"]) {
      const v = this.expandRecipients(splitAddresses($(id).value));
      unresolved.push(...v.filter((a) => !isEmail(a)));
      if (v.length) $(id).value = v.join(", ");
    }
    toast(unresolved.length ? `Not found in your contacts: ${unresolved.join(", ")}` : "All names resolved.", { danger: !!unresolved.length });
  },
  rememberRecipients(list) {
    const recent = prefs.get("recentRecipients", []);
    for (const a of list) {
      const email = emailOf(a).toLowerCase(); if (!isEmail(email)) continue;
      const name = (a.match(/^\s*"?([^"<]+?)"?\s*</) || [])[1] || "";
      const i = recent.findIndex((r) => r.email === email);
      if (i >= 0) { recent[i].n++; recent[i].last = Date.now(); if (name) recent[i].name = name; } else recent.push({ email, name, n: 1, last: Date.now() });
    }
    prefs.set("recentRecipients", recent.sort((a, b) => b.last - a.last).slice(0, 300));
  },
  suggestions(q) {
    q = q.trim().toLowerCase(); if (!q) return [];
    const out = [], seen = new Set();
    const add = (name, email, kind, extra) => { const k = (email || name).toLowerCase(); if (seen.has(k)) return; seen.add(k); out.push({ name, email, kind, ...extra }); };
    for (const l of pim.data.contactLists) if (l.name.toLowerCase().includes(q)) add(l.name, "", "list", { members: l.members });
    for (const c of pim.data.contacts) {
      const n = pim.contactName(c);
      for (const e of c.emails || []) if (n.toLowerCase().includes(q) || e.value.toLowerCase().includes(q) || (c.nickname || "").toLowerCase().includes(q)) add(n, e.value, "contact");
    }
    for (const r of prefs.get("recentRecipients", [])) if (r.email.includes(q) || (r.name || "").toLowerCase().includes(q)) add(r.name, r.email, "recent");
    // People who have written to you, from the mail already loaded (never from junk).
    if (state.folderRole !== "junk") for (const m of state.messages) {
      const email = (m.fromAddress || "").toLowerCase();
      if (!isEmail(email) || m.verdict?.level === "SPAM" || m.verdict?.level === "SUSPICIOUS") continue;
      if (email.includes(q) || (m.fromName || "").toLowerCase().includes(q)) add(m.fromName || "", email, "recent");
    }
    return out.slice(0, 8);
  },
  wireAutocomplete() {
    const pop = $("acPopup");
    let field = null, items = [], idx = 0, mention = null;
    const hide = () => { pop.classList.add("hidden"); field = null; mention = null; };
    const show = (anchorRect) => {
      pop.innerHTML = "";
      items.forEach((s, i) => {
        const b = el("button", { class: i === idx ? "active" : "", onmousedown: (e) => { e.preventDefault(); choose(i); } },
          s.kind === "list" ? el("div", { class: "avatar", style: { background: "var(--accent)" } }, icon("people", 14)) : el("div", { class: "avatar", text: initials(s.name, s.email), style: { background: avatarColor(s.email) } }),
          el("div", {}, el("div", { class: "n", text: s.name || s.email }), el("div", { class: "e", text: s.kind === "list" ? `Contact list · ${plural(s.members.length, "member")}` : s.email })));
        pop.appendChild(b);
      });
      const dr = $("composeDialog").getBoundingClientRect();
      pop.style.left = Math.max(8, anchorRect.left - dr.left) + "px"; pop.style.top = (anchorRect.bottom - dr.top + 2) + "px";
      pop.classList.toggle("hidden", !items.length);
    };
    const choose = (i) => {
      const s = items[i]; if (!s) return;
      if (mention) {
        const { node, start, end } = mention;
        const range = document.createRange(); range.setStart(node, start); range.setEnd(node, end);
        const sel = getSelection(); sel.removeAllRanges(); sel.addRange(range);
        document.execCommand("insertHTML", false, `<a class="mention" href="mailto:${esc(s.email)}">@${esc(s.name || s.email)}</a>&nbsp;`);
        if (s.email && !splitAddresses($("cTo").value).some((a) => emailOf(a).toLowerCase() === s.email.toLowerCase())) $("cTo").value = [...splitAddresses($("cTo").value), s.name ? `${s.name} <${s.email}>` : s.email].join(", ");
        hide(); return;
      }
      const parts = splitAddresses(field.value); parts.pop();
      const add = s.kind === "list" ? s.members.map((m) => (m.name ? `${m.name} <${m.email}>` : m.email)) : [s.name ? `${s.name} <${s.email}>` : s.email];
      field.value = [...parts, ...add].join(", ") + ", ";
      if (state.compose) state.compose.dirty = true;
      field.focus(); hide();
    };
    for (const id of ["cTo", "cCc", "cBcc"]) {
      const inp = $(id);
      inp.addEventListener("input", () => {
        field = inp; mention = null;
        const cur = inp.value.split(/[,;]/).pop();
        items = this.suggestions(cur); idx = 0;
        show(inp.getBoundingClientRect());
      });
      inp.addEventListener("keydown", (e) => {
        if (pop.classList.contains("hidden") || field !== inp) return;
        if (e.key === "ArrowDown") { e.preventDefault(); idx = (idx + 1) % items.length; show(inp.getBoundingClientRect()); }
        else if (e.key === "ArrowUp") { e.preventDefault(); idx = (idx - 1 + items.length) % items.length; show(inp.getBoundingClientRect()); }
        else if (e.key === "Enter" || e.key === "Tab") { if (items.length) { e.preventDefault(); choose(idx); } }
        else if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); hide(); }
      });
      inp.addEventListener("blur", () => setTimeout(() => { if (field === inp) hide(); }, 150));
    }
    // @mentions in the message body
    const ed = $("cEditor");
    ed.addEventListener("keyup", (e) => {
      if (["ArrowDown", "ArrowUp", "Enter", "Tab", "Escape"].includes(e.key) && mention) return;
      const sel = getSelection(); if (!sel.rangeCount) return;
      const r = sel.getRangeAt(0); const node = r.startContainer;
      if (node.nodeType !== 3) { if (mention) hide(); return; }
      const before = node.textContent.slice(0, r.startOffset);
      const m = before.match(/(^|\s)@([\p{L}\p{N}._-]{1,30})$/u);
      if (!m) { if (mention) hide(); return; }
      items = this.suggestions(m[2]).filter((s) => s.kind !== "list"); idx = 0;
      mention = { node, start: r.startOffset - m[2].length - 1, end: r.startOffset }; field = null;
      const rr = r.getBoundingClientRect();
      show({ left: rr.left, bottom: rr.bottom });
    });
    ed.addEventListener("keydown", (e) => {
      if (!mention || pop.classList.contains("hidden")) return;
      if (e.key === "ArrowDown") { e.preventDefault(); idx = (idx + 1) % items.length; show(pop.getBoundingClientRect()); }
      else if (e.key === "ArrowUp") { e.preventDefault(); idx = (idx - 1 + items.length) % items.length; show(pop.getBoundingClientRect()); }
      else if (e.key === "Enter" || e.key === "Tab") { if (items.length) { e.preventDefault(); choose(idx); } }
      else if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); hide(); }
    });
    ed.addEventListener("blur", () => setTimeout(() => { if (mention) hide(); }, 150));
  },

  // ---------------------------------------------------------------- reading pane extras
  renderAttachments(d) {
    const host = $("rdAttachments"); host.innerHTML = "";
    const files = (d.attachments || []).filter((x) => !x.inline);
    for (const a of files) {
      const isImg = /^image\//.test(a.mimeType);
      const chip = el("button", { class: "att", title: `${a.fileName} (${a.mimeType})` },
        isImg ? el("img", { class: "thumb", src: attachmentUrl(d.folder, d.uid, { index: a.index, inline: true }), alt: "" }) : icon(/pdf/.test(a.mimeType) ? "note" : /calendar|ics/.test(a.mimeType) ? "calendar" : "attach"),
        el("span", { text: a.fileName }), el("span", { class: "size", text: fmtSize(a.size) }));
      chip.onclick = () => this.preview(d, a);
      chip.oncontextmenu = (e) => { e.preventDefault(); menu.openAt(e.clientX, e.clientY, [{ label: "Preview", icon: "eye", onClick: () => this.preview(d, a) }, { label: "Download", icon: "download", onClick: () => download(attachmentUrl(d.folder, d.uid, { index: a.index })) }, /\.(ics)$/i.test(a.fileName) ? { label: "Import into calendar", icon: "calendar", onClick: () => calendarUI.importFromUrl(attachmentUrl(d.folder, d.uid, { index: a.index })) } : null, /\.(vcf)$/i.test(a.fileName) ? { label: "Import into contacts", icon: "people", onClick: () => peopleUI.importFromUrl(attachmentUrl(d.folder, d.uid, { index: a.index })) } : null]); };
      host.appendChild(chip);
    }
    if (files.length > 1) host.appendChild(el("button", { class: "att-all", onclick: () => download(apiUrl("/api/attachments/zip", { folder: d.folder, uid: d.uid })) }, icon("download", 14), `Download all (${files.length})`));
  },
  async preview(d, a) {
    const url = attachmentUrl(d.folder, d.uid, { index: a.index, inline: true });
    const dl = el("div", { class: "btn-row", style: { marginBottom: "10px" } },
      el("button", { class: "btn primary", onclick: () => download(attachmentUrl(d.folder, d.uid, { index: a.index })) }, icon("download"), "Download"),
      /\.(ics)$/i.test(a.fileName) || /calendar/.test(a.mimeType) ? el("button", { class: "btn", onclick: () => { $("popupDialog").close(); calendarUI.importFromUrl(url); } }, icon("calendar"), "Add to calendar") : null,
      /\.(vcf)$/i.test(a.fileName) || /vcard/.test(a.mimeType) ? el("button", { class: "btn", onclick: () => { $("popupDialog").close(); peopleUI.importFromUrl(url); } }, icon("people"), "Add to contacts") : null,
      el("span", { class: "small muted", text: `${a.mimeType} · ${fmtSize(a.size)}` }));
    let view;
    if (/^image\//.test(a.mimeType)) view = el("img", { class: "preview-img", src: url, alt: a.fileName });
    else if (/pdf/.test(a.mimeType) && !NATIVE) view = el("iframe", { class: "preview-pdf", src: url, title: a.fileName });
    else if (/^text\/(plain|csv)|json|xml|calendar|vcard/.test(a.mimeType) && a.size < 2e6) {
      view = el("pre", { class: "body-text mono", text: "Loading…" });
      fetch(url).then((r) => r.text()).then((t) => (view.textContent = t)).catch((e) => (view.textContent = e.message));
    } else view = el("p", { class: "muted", text: "No preview is available for this type of file. Download it to open it with another program." });
    $("popupTitle").textContent = a.fileName;
    $("popupBody").innerHTML = ""; $("popupBody").append(dl, view);
    $("popupDialog").showModal();
  },
  /** Meeting request, update, reply or cancellation in the open message. */
  renderInvite(d) {
    const host = $("rdInvite"); host.innerHTML = "";
    const inv = d.invite; if (!inv) return;
    const e = inv.event;
    const start = e.allDay ? parseYmd(e.start) : zonedToDate(e.start, e.tz);
    const end = e.allDay ? parseYmd(e.end) : zonedToDate(e.end, e.tz);
    const when = e.allDay ? `${fmtDay(start, { weekday: "long", month: "long", day: "numeric", year: "numeric" })} (all day)` : `${fmtDay(start, { weekday: "long", month: "long", day: "numeric", year: "numeric" })} ${fmtTime(start)} – ${sameDay(start, end) ? fmtTime(end) : fmtDay(end) + " " + fmtTime(end)}`;
    const row = (ic, text) => text ? el("div", { class: "row" }, icon(ic, 14), el("span", { text })) : null;
    const status = { ACCEPTED: "You accepted", TENTATIVE: "You tentatively accepted", DECLINED: "You declined" }[inv.existingStatus || e.myStatus] || "";
    const box = el("div", { class: "invite" },
      el("div", { class: "cal-ico" }, el("b", { text: start.toLocaleDateString(undefined, { month: "short" }) }), el("span", { text: String(start.getDate()) })),
      el("div", {},
        el("h4", { text: (inv.method === "CANCEL" ? "Cancelled: " : "") + e.title }),
        row("clock", when + (e.recurrence ? " · " + describeRecurrence(e.recurrence) : "")),
        row("map-pin", e.location), row("video", e.onlineUrl),
        row("user", e.organizer ? `Organizer: ${e.organizer.name || e.organizer.email}` : ""),
        row("people", e.attendees?.length ? `${plural(e.attendees.length, "attendee")}` : ""),
        status ? el("div", { class: "status", text: status }) : null));
    const right = box.lastChild;
    const respond = (response, mode) => this.respondInvite(d, response, mode);
    const respMenu = (label, response, cls) => {
      const b = el("button", { class: "btn " + cls }, label, icon("chev-down", 12));
      b.onclick = (ev) => { ev.stopPropagation(); menu.openFor(b, [
        { label: "Send the response now", onClick: () => respond(response, "send") },
        { label: "Edit the response before sending", onClick: () => respond(response, "edit") },
        { label: "Don't send a response", onClick: () => respond(response, "none") },
      ]); };
      return b;
    };
    const btns = el("div", { class: "btn-row" });
    if (inv.method === "REQUEST" && !inv.isOrganizer) btns.append(respMenu("Accept", "ACCEPTED", "primary"), respMenu("Tentative", "TENTATIVE", ""), respMenu("Decline", "DECLINED", ""));
    if (inv.method === "PUBLISH") btns.append(el("button", { class: "btn primary", onclick: () => respond("ACCEPTED", "none") }, icon("plus"), inv.existingEventId ? "Update in calendar" : "Add to calendar"));
    if (inv.method === "CANCEL") btns.append(el("button", { class: "btn", onclick: () => respond("REMOVE", "none") }, icon("trash"), "Remove from calendar"));
    if (inv.method === "REPLY") right.appendChild(el("div", { class: "status", text: `${inv.replyFrom || "An attendee"} ${({ ACCEPTED: "accepted", TENTATIVE: "tentatively accepted", DECLINED: "declined" })[inv.replyStatus] || "replied to"} this meeting.` + (inv.existingEventId ? " Your calendar has been updated." : "") }));
    if (inv.isOrganizer && inv.method === "REQUEST") right.appendChild(el("div", { class: "status", text: "You are the organizer of this meeting." }));
    btns.appendChild(el("button", { class: "btn subtle", onclick: () => { shell.setModule("calendar"); calendarUI.goTo(start); } }, icon("calendar"), "View in calendar"));
    right.appendChild(btns);
    host.appendChild(box);
  },
  async respondInvite(d, response, mode) {
    let comment = "";
    if (mode === "edit") { const v = await formDialog.open({ title: "Respond to the meeting", ok: "Send", fields: [{ key: "comment", label: "Message to the organizer", type: "textarea" }] }); if (!v) return; comment = v.comment; }
    try {
      await api("/api/invite/respond", { method: "POST", body: { folder: d.folder, uid: d.uid, response, comment, sendResponse: mode !== "none", tz: VIEW_TZ } });
      toast({ ACCEPTED: "Accepted", TENTATIVE: "Tentatively accepted", DECLINED: "Declined", REMOVE: "Removed from your calendar" }[response] + (mode !== "none" && response !== "REMOVE" ? " — response sent" : ""));
      await pim.load();
      if (folders.byRole("trash")?.path !== d.folder) list.removeLocal([d]);
      folders.load();
    } catch (e) { toast("Could not respond: " + e.message, { danger: true }); }
  },

  // ---------------------------------------------------------------- immersive reader & translation
  bodyText(d) {
    if (d.bodyText && d.bodyText.trim()) return d.bodyText;
    const doc = new DOMParser().parseFromString(d.bodyHtml || "", "text/html");
    doc.querySelectorAll("style,script").forEach((n) => n.remove());
    doc.querySelectorAll("br").forEach((b) => b.replaceWith("\n"));
    doc.querySelectorAll("p,div,li,tr,h1,h2,h3").forEach((b) => b.append("\n"));
    return (doc.body?.textContent || "").replace(/\n{3,}/g, "\n\n").trim();
  },
  reader(d) {
    const page = $("rdrPage"); page.innerHTML = "";
    const apply = () => {
      page.className = "reader-page " + prefs.get("rdrTheme", "light") + " " + prefs.get("rdrFont", "serif");
      page.style.setProperty("--rdr-size", prefs.get("rdrSize", 22) + "px");
      page.style.setProperty("--rdr-spacing", prefs.get("rdrSpacing", "1.5"));
      $("rdrTheme").value = prefs.get("rdrTheme", "light"); $("rdrFont").value = prefs.get("rdrFont", "serif"); $("rdrSpacing").value = String(prefs.get("rdrSpacing", "1.5"));
    };
    page.appendChild(el("h1", { text: d.subject }));
    page.appendChild(el("div", { class: "meta", text: `${d.fromName || d.fromAddress} · ${fmtDate(d.date, { long: true })}` }));
    const paras = this.bodyText(d).split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
    paras.forEach((p) => page.appendChild(el("p", { text: p })));
    apply();
    $("rdrSmaller").onclick = () => { prefs.set("rdrSize", Math.max(14, prefs.get("rdrSize", 22) - 2)); apply(); };
    $("rdrLarger").onclick = () => { prefs.set("rdrSize", Math.min(48, prefs.get("rdrSize", 22) + 2)); apply(); };
    $("rdrSpacing").onchange = () => { prefs.set("rdrSpacing", $("rdrSpacing").value); apply(); };
    $("rdrTheme").onchange = () => { prefs.set("rdrTheme", $("rdrTheme").value); apply(); };
    $("rdrFont").onchange = () => { prefs.set("rdrFont", $("rdrFont").value); apply(); };
    $("rdrSpeak").onclick = () => this.speak(page);
    $("readerDialog").onclose = () => { if ("speechSynthesis" in window) speechSynthesis.cancel(); };
    $("readerDialog").showModal();
  },
  speak(page) {
    if (!("speechSynthesis" in window)) return toast("Read aloud is not available in this browser.", { danger: true });
    if (speechSynthesis.speaking) { speechSynthesis.cancel(); qsa(".speaking", page).forEach((n) => n.classList.remove("speaking")); qs("#rdrSpeak use").setAttribute("href", "#i-volume"); return; }
    const nodes = qsa("h1, p", page);
    let i = 0;
    qs("#rdrSpeak use").setAttribute("href", "#i-stop");
    const next = () => {
      qsa(".speaking", page).forEach((n) => n.classList.remove("speaking"));
      if (i >= nodes.length) { qs("#rdrSpeak use").setAttribute("href", "#i-volume"); return; }
      const n = nodes[i++]; n.classList.add("speaking"); n.scrollIntoView({ block: "center", behavior: "smooth" });
      const u = new SpeechSynthesisUtterance(n.textContent);
      u.onend = next; u.onerror = next;
      speechSynthesis.speak(u);
    };
    next();
  },
  async translate(d) {
    const langs = ["English", "Spanish", "French", "German", "Italian", "Portuguese", "Dutch", "Russian", "Ukrainian", "Polish", "Chinese (Simplified)", "Chinese (Traditional)", "Japanese", "Korean", "Hindi", "Arabic", "Hebrew", "Greek", "Turkish", "Vietnamese", "Latin"];
    const v = await formDialog.open({ title: "Translate message", ok: "Translate", fields: [{ key: "language", label: "Translate into", type: "select", value: prefs.get("translateTo", "English"), options: langs.map((l) => [l, l]) }, { type: "info", text: state.settings.ollama?.enabled ? `Uses your local ${state.settings.ollama.model} model; the text never leaves your machine.` : "Translation uses a local AI model. Turn one on under Settings → AI models." }] });
    if (!v) return;
    prefs.set("translateTo", v.language);
    const host = $("rdTranslation"); host.classList.remove("hidden");
    host.innerHTML = ""; host.append(el("div", { class: "t-head" }, icon("globe", 14), `Translating into ${v.language}…`), el("span", { class: "spinner" }));
    try {
      const r = await api("/api/translate", { method: "POST", body: { folder: d.folder, uid: d.uid, language: v.language } });
      host.innerHTML = ""; host.append(el("div", { class: "t-head" }, icon("globe", 14), `Translated into ${r.language} by your local model`, el("button", { class: "btn sm", style: { marginLeft: "auto" }, onclick: () => host.classList.add("hidden") }, "Show original only")), el("div", { text: r.text }));
    } catch (e) { host.innerHTML = ""; host.append(el("div", { class: "t-head" }, icon("globe", 14), "Translation unavailable"), el("div", { class: "small", text: e.message })); }
  },

  // ---------------------------------------------------------------- search
  async searchDialog() {
    const o = state.searchOpts || {};
    const v = await formDialog.open({
      title: "Search options", ok: "Search",
      fields: [
        { key: "scope", label: "Folders", type: "select", value: o.scope || prefs.get("searchScope", "all"), options: [["all", "All folders"], ["folder", `Current folder (${folders.byPath(state.folder)?.name || state.folder})`]] },
        { key: "from", label: "From", value: o.from || "" },
        { key: "to", label: "To", value: o.to || "" },
        { key: "subject", label: "Subject", value: o.subject || "" },
        { key: "text", label: "Keywords", value: $("searchInput").value || "" },
        { key: "since", label: "Received from", type: "date", value: o.sinceStr || "" },
        { key: "before", label: "Received to", type: "date", value: o.beforeStr || "" },
        { key: "hasAttachments", label: "Has attachments", type: "checkbox", value: !!o.hasAttachments },
        { key: "unread", label: "Unread only", type: "checkbox", value: !!o.unread },
        { key: "flagged", label: "Flagged only", type: "checkbox", value: !!o.flagged },
      ],
    });
    if (!v) return;
    const opts = { scope: v.scope, from: v.from.trim(), to: v.to.trim(), subject: v.subject.trim(), hasAttachments: v.hasAttachments, unread: v.unread, flagged: v.flagged,
      sinceStr: v.since, beforeStr: v.before, since: v.since ? parseYmd(v.since).getTime() : "", before: v.before ? addDays(parseYmd(v.before), 1).getTime() : "" };
    if (!v.text.trim() && !opts.from && !opts.to && !opts.subject && !opts.hasAttachments && !opts.unread && !opts.flagged && !opts.since && !opts.before) return toast("Enter at least one search condition.", { danger: true });
    $("searchInput").value = v.text.trim(); $("searchClear").classList.remove("hidden");
    shell.setModule("mail", { silent: true });
    list.search(v.text.trim(), { opts });
  },

  // ---------------------------------------------------------------- My Day
  toggleMyDay() {
    const on = !prefs.get("myDay", false);
    prefs.set("myDay", on);
    this.renderMyDay();
  },
  renderMyDay() {
    const on = prefs.get("myDay", false);
    const host = $("myDay");
    $("workspace").classList.toggle("with-myday", on);
    host.classList.toggle("hidden", !on);
    $("btnMyDay").classList.toggle("active", on);
    if (!on) return;
    const tab = prefs.get("myDayTab", "calendar");
    host.innerHTML = "";
    host.append(el("div", { class: "md-head" }, el("h3", { text: "My Day" }), el("span", { class: "small muted", text: fmtDay(new Date(), { weekday: "long", month: "long", day: "numeric" }) }), el("button", { class: "icon-btn", title: "Close", onclick: () => this.toggleMyDay() }, icon("close"))),
      el("div", { class: "md-tabs" }, ...[["calendar", "Calendar"], ["todo", "To Do"]].map(([id, l]) => el("button", { class: tab === id ? "active" : "", onclick: () => { prefs.set("myDayTab", id); this.renderMyDay(); } }, l))));
    const body = el("div", { class: "md-body" });
    host.appendChild(body);
    if (tab === "calendar") {
      body.appendChild(el("button", { class: "btn sm", onclick: () => calendarUI.newEvent({}) }, icon("plus"), "New event"));
      const start = startOfDay(new Date());
      for (let i = 0; i < 3; i++) {
        const day = addDays(start, i);
        const occ = pim.occurrences(day, addDays(day, 1));
        body.appendChild(el("div", { class: "agenda-day", text: i === 0 ? "Today" : i === 1 ? "Tomorrow" : fmtDay(day, { weekday: "long", month: "short", day: "numeric" }) }));
        if (!occ.length) body.appendChild(el("div", { class: "small muted", text: "Nothing planned" }));
        for (const o of occ) body.appendChild(calendarUI.agendaItem(o));
      }
    } else {
      body.appendChild(todoUI.miniList());
    }
  },

  // ---------------------------------------------------------------- notifications
  askNotifications() {
    if (!("Notification" in window) || Notification.permission !== "default" || prefs.get("notifAsked", false) || NATIVE) return;
    prefs.set("notifAsked", true);
    toast("Get desktop notifications for new mail and reminders?", { action: "Turn on", timeout: 15000, onAction: () => Notification.requestPermission().then((p) => { if (p === "granted") toast("Notifications on"); }) });
  },
  newMailCheck(messages) {
    const uids = new Set(messages.map((m) => m.uid));
    if (!state.lastInboxUids || state.lastInboxFolder !== state.account.email) { state.lastInboxUids = uids; state.lastInboxFolder = state.account.email; return; }
    const fresh = messages.filter((m) => !state.lastInboxUids.has(m.uid) && !m.read && (!list.focusedActive() || m.focused !== false));
    state.lastInboxUids = uids;
    if (!fresh.length || !prefs.get("notifyMail", true)) return;
    if (prefs.get("notifySound", false)) this.chime();
    const m = fresh[0];
    notify(fresh.length === 1 ? (m.fromName || m.fromAddress) : `${fresh.length} new messages`, fresh.length === 1 ? m.subject : fresh.map((x) => x.subject).slice(0, 3).join("\n"), () => { shell.setModule("mail"); reading.open(m); });
  },
  chime() {
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      [660, 880].forEach((f, i) => { const o = ctx.createOscillator(), g = ctx.createGain(); o.frequency.value = f; g.gain.setValueAtTime(0.0001, ctx.currentTime + i * 0.12); g.gain.exponentialRampToValueAtTime(0.15, ctx.currentTime + i * 0.12 + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + i * 0.12 + 0.25); o.connect(g).connect(ctx.destination); o.start(ctx.currentTime + i * 0.12); o.stop(ctx.currentTime + i * 0.12 + 0.3); });
    } catch {}
  },

  // ---------------------------------------------------------------- swipe actions (touch)
  swipeActions: {
    delete: ["Delete", "trash", "#c50f1f", (t) => actions.delete(t)],
    archive: ["Archive", "archive", "#107c10", (t) => actions.archive(t)],
    read: ["Read / unread", "read", "#0f6cbd", (t) => actions.read(t, !t.every((m) => m.read))],
    flag: ["Flag", "flag", "#ca5010", (t) => actions.flag(t, !t.every((m) => m.flagged))],
    pin: ["Pin", "pin", "#8764b8", (t) => actions.pin(t, !t.every((m) => m.pinned))],
    snooze: ["Snooze", "clock", "#038387", (t) => menu.openAt(innerWidth / 2 - 110, innerHeight / 3, mailplus.snoozeMenu(t))],
    move: ["Move", "move", "#4f6bed", (t) => actions.pickFolder(t)],
    junk: ["Junk", "junk", "#9a5b00", (t) => actions.junk(t, true)],
  },
  attachSwipe(div, targets) {
    if (!("ontouchstart" in window)) return;
    let sx = 0, sy = 0, dx = 0, tracking = false, decided = false;
    div.addEventListener("touchstart", (e) => { if (e.touches.length !== 1) return; sx = e.touches[0].clientX; sy = e.touches[0].clientY; dx = 0; tracking = true; decided = false; }, { passive: true });
    div.addEventListener("touchmove", (e) => {
      if (!tracking) return;
      const x = e.touches[0].clientX, y = e.touches[0].clientY;
      if (!decided) { if (Math.abs(x - sx) < 12 && Math.abs(y - sy) < 12) return; decided = true; if (Math.abs(y - sy) > Math.abs(x - sx)) { tracking = false; return; } }
      dx = x - sx;
      const a = this.swipeActions[prefs.get(dx > 0 ? "swipeRight" : "swipeLeft", dx > 0 ? "archive" : "delete")];
      if (!a) return;
      div.classList.add("swiping");
      div.style.transform = `translateX(${dx}px)`;
      div.style.boxShadow = `${dx > 0 ? -1 : 1}px 0 0 ${Math.abs(dx)}px ${a[2]}`;
      div.dataset.swipe = a[0];
    }, { passive: true });
    const end = () => {
      if (!tracking) return; tracking = false;
      const w = div.offsetWidth;
      const key = prefs.get(dx > 0 ? "swipeRight" : "swipeLeft", dx > 0 ? "archive" : "delete");
      div.style.transition = "transform .18s"; div.style.transform = ""; div.style.boxShadow = "";
      setTimeout(() => { div.style.transition = ""; div.classList.remove("swiping"); }, 200);
      if (Math.abs(dx) > w * 0.33 && this.swipeActions[key]) this.swipeActions[key][3](targets());
    };
    div.addEventListener("touchend", end); div.addEventListener("touchcancel", end);
  },

  // ---------------------------------------------------------------- settings pages
  settings: {
    render_account(host) {
      const a = state.account;
      host.append(el("h3", { text: "Your accounts" }), el("p", { text: "All the accounts signed in on this device. Switch between them from the folder pane or the account picture." }));
      for (const x of accounts.all()) {
        const e = x.account.email, active = e === a.email;
        host.appendChild(el("div", { class: "acct-row" },
          el("div", { class: "avatar", text: initials(x.account.displayName, e), style: { background: avatarColor(e) } }),
          el("div", { class: "grow" }, el("b", { text: e }), el("div", { class: "small muted", text: `${x.account.protocol || "IMAP"} ${x.account.incomingHost || ""}${x.remember ? " · remembered on this device" : ""}${active ? " · active" : ""}` })),
          !active ? el("button", { class: "btn sm", onclick: () => { $("settingsDialog").close(); session.switchTo(e); } }, "Switch") : el("span", { class: "badge info", text: "Active" }),
          x.remember ? el("button", { class: "btn sm", onclick: () => { accounts.forgetPassword(e); toast("This device will ask for the password next time."); settingsUI.render(); } }, "Forget password") : null,
          el("button", { class: "btn sm danger", onclick: () => { if (!confirm(`Sign out of ${e} on this device?`)) return; if (active) { $("settingsDialog").close(); signOut(false); } else { accounts.remove(e); settingsUI.render(); folders.render(); } } }, "Sign out")));
      }
      host.appendChild(el("button", { class: "btn", onclick: () => { $("settingsDialog").close(); setup.show({ adding: true }); } }, icon("person-add"), "Add account"));
      host.appendChild(el("h4", { text: "This account" }));
      const kv = el("dl", { class: "kv" });
      const add = (k, v) => { kv.appendChild(el("dt", { text: k })); kv.appendChild(el("dd", { text: v || "—" })); };
      add("Name", a.displayName); add("Incoming", a.incomingHost ? `${a.protocol} ${a.incomingHost}:${a.incomingPort} (${a.incomingSecurity})` : a.protocol); add("Outgoing", a.smtpHost ? `SMTP ${a.smtpHost}:${a.smtpPort} (${a.smtpSecurity})` : ""); add("User name", a.username);
      if (state.quota) add("Storage", `${fmtSize(state.quota.usedKb * 1024)} of ${fmtSize(state.quota.limitKb * 1024)} used`);
      if (NATIVE === "ios") add("Anne's Post server", location.origin);
      if (NATIVE === "android") add("Mail engine", "Running inside the Anne's Post app on this phone (127.0.0.1)");
      host.appendChild(kv);
      host.appendChild(el("h4", { text: "Send-as addresses (aliases)" }));
      host.appendChild(el("p", { class: "small muted", text: "Other addresses your provider lets this account send from. They appear in the From box when you write." }));
      const box = el("div", { class: "list-editor" });
      const aliases = state.settings.aliases || [];
      if (!aliases.length) box.appendChild(el("div", { class: "small muted", text: "None" }));
      aliases.forEach((v) => box.appendChild(el("div", { class: "item" }, el("span", { text: v }), el("button", { class: "icon-btn", onclick: async () => { await settingsSave({ ...state.settings, aliases: aliases.filter((x) => x !== v) }); settingsUI.render(); } }, icon("close")))));
      const input = el("input", { type: "email", placeholder: "alias@example.com" });
      const addAlias = async () => { const v = input.value.trim().toLowerCase(); if (!isEmail(v)) return toast("Enter a valid address.", { danger: true }); await settingsSave({ ...state.settings, aliases: [...new Set([...aliases, v])] }); settingsUI.render(); };
      input.onkeydown = (e) => { if (e.key === "Enter") addAlias(); };
      box.appendChild(el("div", { class: "add" }, input, el("button", { class: "btn sm", onclick: addAlias }, icon("plus"), "Add")));
      host.appendChild(box);
      if (NATIVE === "ios" && IOS_BRIDGE) host.appendChild(el("div", { class: "btn-row", style: { marginTop: "16px" } }, el("button", { class: "btn", onclick: () => IOS_BRIDGE.postMessage({ type: "changeServer" }) }, icon("settings"), "Change server…")));
    },
    render_signatures(host) {
      const s = state.settings;
      host.append(el("h3", { text: "Signatures" }), el("p", { text: "Create several signatures and choose which one is added automatically." }));
      const sigs = s.signatures || [];
      for (const sig of sigs) host.appendChild(el("div", { class: "sig-row" }, icon("edit"), el("div", { class: "grow" }, el("b", { text: sig.name }), el("div", { class: "small muted", text: new DOMParser().parseFromString(sig.html, "text/html").body.textContent.slice(0, 90) })),
        el("button", { class: "btn sm", onclick: () => this.editSignature(sig) }, "Edit"),
        el("button", { class: "icon-btn", title: "Delete", onclick: async () => { if (!confirm(`Delete "${sig.name}"?`)) return; await settingsSave({ ...state.settings, signatures: sigs.filter((x) => x.id !== sig.id), defaultSignatureNew: s.defaultSignatureNew === sig.id ? null : s.defaultSignatureNew, defaultSignatureReply: s.defaultSignatureReply === sig.id ? null : s.defaultSignatureReply }); settingsUI.render(); } }, icon("trash"))));
      host.appendChild(el("button", { class: "btn primary", onclick: () => this.editSignature(null) }, icon("plus"), "New signature"));
      host.appendChild(el("h4", { text: "Default signatures" }));
      const opts = [["", "(none)"], ...sigs.map((x) => [x.id, x.name])];
      const dn = select(opts, s.defaultSignatureNew || "", { style: { width: "240px" } }), dr = select(opts, s.defaultSignatureReply || "", { style: { width: "240px" } });
      dn.onchange = () => settingsSave({ ...state.settings, defaultSignatureNew: dn.value || null });
      dr.onchange = () => settingsSave({ ...state.settings, defaultSignatureReply: dr.value || null });
      host.append(settingsUI.row("For new messages", "", dn), settingsUI.row("For replies and forwards", "", dr));
    },
    async editSignature(sig) {
      const v = await formDialog.open({ title: sig ? "Edit signature" : "New signature", wide: true, fields: [{ key: "name", label: "Name", value: sig?.name || "", required: true }, { key: "html", label: "Signature (formatting from the compose window can be pasted here)", type: "html", value: sig?.html || (state.account.displayName ? esc(state.account.displayName) : "") }] });
      if (!v) return;
      const s = state.settings;
      const item = { id: sig?.id || "sig-" + Math.random().toString(36).slice(2, 10), name: v.name.trim(), html: v.html };
      const signatures = sig ? (s.signatures || []).map((x) => (x.id === sig.id ? item : x)) : [...(s.signatures || []), item];
      const first = !(s.signatures || []).length;
      await settingsSave({ ...s, signatures, defaultSignatureNew: first ? item.id : s.defaultSignatureNew, defaultSignatureReply: first ? item.id : s.defaultSignatureReply });
      settingsUI.render();
    },
    render_autoreply(host) {
      const ar = state.settings.autoReply || {};
      host.append(el("h3", { text: "Automatic replies" }), el("p", { text: "Send an automatic reply (an out-of-office message) to people who write to you. Each sender gets it at most once in the chosen interval; mailing lists, newsletters and automated senders are never answered." }));
      host.appendChild(el("div", { class: "info-bar warn" }, icon("warning"), el("span", { class: "grow", text: "IMAP has no server-side automatic replies, so Anne's Post answers new mail while it is running — always for the server version, and while the desktop or phone app is open. If your provider offers a vacation responder in its webmail, that also works when Anne's Post is closed." })));
      const on = el("input", { type: "checkbox" }); on.checked = !!ar.enabled;
      const period = el("input", { type: "checkbox" }); period.checked = !!(ar.start || ar.end);
      const start = el("input", { type: "datetime-local", value: ar.start ? localIso(new Date(ar.start)) : localIso(new Date()) });
      const end = el("input", { type: "datetime-local", value: ar.end ? localIso(new Date(ar.end)) : localIso(addDays(new Date(), 7)) });
      const subject = el("input", { type: "text", value: ar.subject || "", placeholder: "Automatic reply: (original subject)" });
      const body = el("div", { class: "html-editor", contenteditable: "true", html: ar.html || "Thank you for your message. I am away and will reply when I return." });
      const contacts = el("input", { type: "checkbox" }); contacts.checked = !!ar.contactsOnly;
      const days = select([["1", "Once a day"], ["4", "Once every 4 days"], ["7", "Once a week"], ["30", "Once a month"]], String(ar.oncePerDays || 4), { style: { width: "220px" } });
      host.append(el("label", { class: "check" }, on, el("span", { text: "Turn on automatic replies" })),
        el("label", { class: "check" }, period, el("span", { text: "Send replies only during a time period" })),
        el("div", { class: "grid-2" }, el("div", { class: "field" }, el("label", { text: "Start" }), start), el("div", { class: "field" }, el("label", { text: "End" }), end)),
        el("div", { class: "field" }, el("label", { text: "Subject" }), subject),
        el("div", { class: "field" }, el("label", { text: "Message" }), body),
        el("label", { class: "check" }, contacts, el("span", { text: "Only reply to people in my contacts" })),
        settingsUI.row("Reply to each sender", "", days),
        el("div", { class: "btn-row", style: { marginTop: "12px" } }, el("button", { class: "btn primary", onclick: async () => {
          await settingsSave({ ...state.settings, autoReply: { enabled: on.checked, start: period.checked ? new Date(start.value).getTime() : null, end: period.checked ? new Date(end.value).getTime() : null, subject: subject.value.trim(), html: body.innerHTML, contactsOnly: contacts.checked, oncePerDays: Number(days.value) } });
          toast(on.checked ? "Automatic replies are on" : "Automatic replies are off");
        } }, "Save")));
    },
    render_notifications(host) {
      host.append(el("h3", { text: "Notifications" }), el("p", { text: "Desktop notifications appear when Anne's Post is in the background. Stored in this browser." }));
      const perm = "Notification" in window ? Notification.permission : "unsupported";
      host.appendChild(settingsUI.row("Desktop notifications", perm === "granted" ? "Allowed by the browser." : perm === "denied" ? "Blocked in the browser's site settings." : perm === "unsupported" ? "Not supported here." : "Not yet allowed.", perm === "default" ? el("button", { class: "btn sm", onclick: () => Notification.requestPermission().then(() => settingsUI.render()) }, "Allow") : settingsUI.toggle(prefs.get("notifyDesktop", true), (v) => prefs.set("notifyDesktop", v))));
      host.appendChild(settingsUI.row("New mail", "Notify about new messages in Focused (or the whole Inbox when Focused Inbox is off).", settingsUI.toggle(prefs.get("notifyMail", true), (v) => prefs.set("notifyMail", v))));
      host.appendChild(settingsUI.row("Play a sound for new mail", "", settingsUI.toggle(prefs.get("notifySound", false), (v) => { prefs.set("notifySound", v); if (v) mailplus.chime(); })));
      host.appendChild(settingsUI.row("Calendar and task reminders", "", settingsUI.toggle(prefs.get("remindersOn", true), (v) => prefs.set("remindersOn", v))));
    },
    render_templates(host) {
      const tpls = state.settings.templates || [];
      host.append(el("h3", { text: "Templates" }), el("p", { text: "Reusable messages. Insert them from Insert → Templates while writing." }));
      for (const t of tpls) host.appendChild(el("div", { class: "tpl-row" }, icon("note"), el("div", { class: "grow" }, el("b", { text: t.name }), el("div", { class: "small muted", text: t.subject || new DOMParser().parseFromString(t.html, "text/html").body.textContent.slice(0, 80) })),
        el("button", { class: "btn sm", onclick: () => this.editTemplate(t) }, "Edit"),
        el("button", { class: "icon-btn", title: "Delete", onclick: async () => { if (!confirm(`Delete "${t.name}"?`)) return; await settingsSave({ ...state.settings, templates: tpls.filter((x) => x.id !== t.id) }); settingsUI.render(); } }, icon("trash"))));
      host.appendChild(el("button", { class: "btn primary", onclick: () => this.editTemplate(null) }, icon("plus"), "New template"));
    },
    async editTemplate(t) {
      const v = await formDialog.open({ title: t ? "Edit template" : "New template", wide: true, fields: [{ key: "name", label: "Name", value: t?.name || "", required: true }, { key: "subject", label: "Subject (optional)", value: t?.subject || "" }, { key: "html", label: "Text", type: "html", value: t?.html || "" }] });
      if (!v) return;
      const item = { id: t?.id || "tpl-" + Math.random().toString(36).slice(2, 10), name: v.name.trim(), subject: v.subject, html: v.html };
      const tpls = state.settings.templates || [];
      await settingsSave({ ...state.settings, templates: t ? tpls.map((x) => (x.id === t.id ? item : x)) : [...tpls, item] });
      settingsUI.render();
    },
    render_quicksteps(host) {
      const qsList = state.settings.quickSteps || [];
      host.append(el("h3", { text: "Quick steps" }), el("p", { text: "Run several actions on the selected messages with one click (ribbon → Quick steps)." }));
      for (const q of qsList) host.appendChild(el("div", { class: "qs-row" }, icon("bolt"), el("div", { class: "grow" }, el("b", { text: q.name }), el("div", { class: "small muted", text: q.actions.map((a) => this.describeQuick(a)).join(" → ") })),
        el("button", { class: "btn sm", onclick: () => this.editQuickStep(q) }, "Edit"),
        el("button", { class: "icon-btn", title: "Delete", onclick: async () => { if (!confirm(`Delete "${q.name}"?`)) return; await settingsSave({ ...state.settings, quickSteps: qsList.filter((x) => x.id !== q.id) }); settingsUI.render(); } }, icon("trash"))));
      host.appendChild(el("button", { class: "btn primary", onclick: () => this.editQuickStep(null) }, icon("plus"), "New quick step"));
    },
    quickTypes: [["MOVE", "Move to folder"], ["COPY", "Copy to folder"], ["ARCHIVE", "Archive"], ["DELETE", "Delete"], ["MARK_READ", "Mark as read"], ["MARK_UNREAD", "Mark as unread"], ["FLAG", "Flag"], ["UNFLAG", "Clear flag"], ["PIN", "Pin"], ["CATEGORIZE", "Categorize"], ["REPLY", "Reply (with template)"], ["FORWARD", "Forward to"]],
    describeQuick(a) {
      const base = (this.quickTypes.find((t) => t[0] === a.type) || [0, a.type])[1];
      return a.folder ? `${base}: ${folders.byPath(a.folder)?.name || a.folder}` : a.category ? `${base}: ${a.category}` : a.to ? `${base} ${a.to}` : base;
    },
    async editQuickStep(q) {
      const steps = (q?.actions || [{ type: "MARK_READ" }]).map((a) => ({ ...a }));
      const box = el("div", {});
      const renderSteps = () => {
        box.innerHTML = "";
        steps.forEach((a, i) => {
          const type = select(this.quickTypes, a.type, { style: { width: "200px" } });
          const extra = el("span", {});
          const sync = () => {
            extra.innerHTML = ""; a.type = type.value;
            if (a.type === "MOVE" || a.type === "COPY") { const f = select(state.folders.map((x) => [x.path, x.name]), a.folder || state.folders[0]?.path); a.folder = f.value; f.onchange = () => (a.folder = f.value); extra.appendChild(f); }
            if (a.type === "CATEGORIZE") { const c = select((state.settings.categories || []).map((x) => [x.name, x.name]), a.category || state.settings.categories?.[0]?.name || ""); a.category = c.value; c.onchange = () => (a.category = c.value); extra.appendChild(c); }
            if (a.type === "FORWARD") { const t = el("input", { type: "email", value: a.to || "", placeholder: "name@example.com" }); t.oninput = () => (a.to = t.value.trim()); extra.appendChild(t); }
            if (a.type === "REPLY") { const t = select([["", "(no template)"], ...(state.settings.templates || []).map((x) => [x.id, x.name])], a.templateId || ""); t.onchange = () => (a.templateId = t.value); extra.appendChild(t); }
          };
          type.onchange = sync; sync();
          box.appendChild(el("div", { class: "crit", style: { gridTemplateColumns: "200px 1fr 28px", marginBottom: "6px" } }, type, extra, el("button", { class: "icon-btn", onclick: () => { steps.splice(i, 1); renderSteps(); } }, icon("close"))));
        });
        box.appendChild(el("button", { class: "btn sm", onclick: () => { steps.push({ type: "MARK_READ" }); renderSteps(); } }, icon("plus"), "Add action"));
      };
      renderSteps();
      const v = await formDialog.open({ title: q ? "Edit quick step" : "New quick step", wide: true, fields: [{ key: "name", label: "Name", value: q?.name || "", required: true }], extra: box });
      if (!v || !steps.length) return;
      const item = { id: q?.id || "qs-" + Math.random().toString(36).slice(2, 10), name: v.name.trim(), actions: steps.map((a) => ({ type: a.type, folder: a.folder || null, category: a.category || null, to: a.to || null, templateId: a.templateId || null })) };
      const all = state.settings.quickSteps || [];
      await settingsSave({ ...state.settings, quickSteps: q ? all.map((x) => (x.id === q.id ? item : x)) : [...all, item] });
      if ($("settingsDialog").open) settingsUI.render();
    },
    render_categories(host) {
      const cats = state.settings.categories || [];
      host.append(el("h3", { text: "Categories" }), el("p", { text: "Colour-coded labels for messages, events, contacts and tasks." }));
      for (const c of cats) {
        const color = el("input", { type: "color", value: c.color, style: { width: "40px", height: "28px", padding: 0, border: 0 } });
        color.onchange = async () => { await settingsSave({ ...state.settings, categories: cats.map((x) => (x.name === c.name ? { ...x, color: color.value } : x)) }); list.render(); };
        host.appendChild(el("div", { class: "cat-row" }, color, el("div", { class: "grow" }, el("span", { class: "cat-chip", style: { background: c.color } }, c.name)),
          el("button", { class: "btn sm", onclick: async () => { const v = await formDialog.open({ title: "Rename category", fields: [{ key: "name", label: "Name", value: c.name, required: true }] }); if (!v) return; await settingsSave({ ...state.settings, categories: cats.map((x) => (x.name === c.name ? { ...x, name: v.name.trim() } : x)) }); settingsUI.render(); toast("Renamed. Messages already labelled keep the old name until you relabel them."); } }, "Rename"),
          el("button", { class: "icon-btn", title: "Delete", onclick: async () => { if (!confirm(`Delete the category "${c.name}"?`)) return; await settingsSave({ ...state.settings, categories: cats.filter((x) => x.name !== c.name) }); settingsUI.render(); } }, icon("trash"))));
      }
      host.appendChild(el("button", { class: "btn primary", onclick: async () => { const v = await formDialog.open({ title: "New category", fields: [{ key: "name", label: "Name", required: true }, { key: "color", label: "Colour", type: "color", value: "#038387" }] }); if (!v) return; await settingsSave({ ...state.settings, categories: [...cats.filter((x) => x.name !== v.name.trim()), { name: v.name.trim(), color: v.color }] }); settingsUI.render(); } }, icon("plus"), "New category"));
    },
    render_swipe(host) {
      host.append(el("h3", { text: "Swipe actions" }), el("p", { text: "On touch screens, swipe a message left or right in the list to act on it." }));
      const opts = [["none", "No action"], ...Object.entries(mailplus.swipeActions).map(([k, v]) => [k, v[0]])];
      host.appendChild(settingsUI.row("Swipe right", "", settingsUI.pref("swipeRight", "archive", opts)));
      host.appendChild(settingsUI.row("Swipe left", "", settingsUI.pref("swipeLeft", "delete", opts)));
    },
    render_importexport(host) {
      host.append(el("h3", { text: "Import and export" }), el("p", { text: "Move mail, calendars and contacts in and out of Anne's Post using standard files." }));
      host.appendChild(el("h4", { text: "Mail" }));
      const fsel = select(state.folders.map((f) => [f.path, f.name]), state.folder !== SCHEDULED ? state.folder : "INBOX", { style: { width: "240px" } });
      host.append(settingsUI.row("Folder", "", fsel),
        el("div", { class: "btn-row" },
          el("button", { class: "btn", onclick: () => download(apiUrl("/api/export/mbox", { folder: fsel.value })) }, icon("download"), "Export folder (.mbox)"),
          el("button", { class: "btn", disabled: !state.capabilities.folders || null, onclick: () => mailplus.importInto(folders.byPath(fsel.value)) }, icon("upload"), "Import .eml / .mbox into folder")),
        el("p", { class: "small muted", text: "Mailboxes from other programs — Outlook .pst/.ost, Thunderbird, Apple Mail, Eudora, Outlook Express, Gmail Takeout and more — can be imported with all their folders:" }),
        el("div", { class: "btn-row" }, el("button", { class: "btn", onclick: () => { $("settingsDialog")?.close?.(); Commands.run("mail.importMailbox"); } }, icon("upload"), "Import mailbox from another program…")));
      host.appendChild(el("h4", { text: "Calendar" }));
      const csel = select(pim.data.calendars.filter((c) => !c.readOnly).map((c) => [c.id, c.name]), "calendar", { style: { width: "240px" } });
      host.append(settingsUI.row("Calendar", "", csel), el("div", { class: "btn-row" },
        el("button", { class: "btn", onclick: () => download(apiUrl("/api/pim/export", { kind: "ics", calendarId: csel.value })) }, icon("download"), "Export (.ics)"),
        el("button", { class: "btn", onclick: () => calendarUI.importFile(csel.value) }, icon("upload"), "Import .ics"),
        el("button", { class: "btn", onclick: () => calendarUI.subscribe() }, icon("globe"), "Subscribe to an internet calendar")));
      host.appendChild(el("h4", { text: "People" }));
      host.append(el("div", { class: "btn-row" },
        el("button", { class: "btn", onclick: () => download(apiUrl("/api/pim/export", { kind: "vcf" })) }, icon("download"), "Export vCard (.vcf)"),
        el("button", { class: "btn", onclick: () => download(apiUrl("/api/pim/export", { kind: "csv" })) }, icon("download"), "Export CSV"),
        el("button", { class: "btn", onclick: () => peopleUI.importFile() }, icon("upload"), "Import .vcf / .csv")));
    },
    render_calendar(host) {
      host.append(el("h3", { text: "Calendar" }), el("p", { text: "How the calendar looks and the defaults for new events. Stored in this browser." }));
      const days = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
      host.appendChild(settingsUI.row("First day of the week", "", settingsUI.pref("weekStart", 0, days.map((d, i) => [String(i), d]), () => calendarUI.render && calendarUI.render())));
      const ww = el("div", { class: "swatches" });
      const work = prefs.get("workDays", [1, 2, 3, 4, 5]);
      days.forEach((d, i) => { const cb = el("input", { type: "checkbox" }); cb.checked = work.includes(i); cb.onchange = () => { const cur = new Set(prefs.get("workDays", [1, 2, 3, 4, 5])); if (cb.checked) cur.add(i); else cur.delete(i); prefs.set("workDays", [...cur].sort()); }; ww.appendChild(el("label", { class: "check", style: { margin: "0 8px 0 0" } }, cb, el("span", { text: d.slice(0, 3) }))); });
      host.appendChild(settingsUI.row("Work week", "", ww));
      const hours = Array.from({ length: 24 }, (_, h) => [String(h), `${String(h).padStart(2, "0")}:00`]);
      host.appendChild(settingsUI.row("Working hours start", "", settingsUI.pref("workStart", 8, hours)));
      host.appendChild(settingsUI.row("Working hours end", "", settingsUI.pref("workEnd", 17, hours)));
      host.appendChild(settingsUI.row("Time scale", "", settingsUI.pref("timeScale", 30, [["60", "60 minutes"], ["30", "30 minutes"], ["15", "15 minutes"]])));
      host.appendChild(settingsUI.row("Default reminder", "", settingsUI.pref("defaultReminder", 15, [["-1", "None"], ["0", "At start time"], ["5", "5 minutes before"], ["15", "15 minutes before"], ["30", "30 minutes before"], ["60", "1 hour before"], ["1440", "1 day before"]])));
      host.appendChild(settingsUI.row("Default event length", "", settingsUI.pref("defaultDuration", 30, [["15", "15 minutes"], ["30", "30 minutes"], ["45", "45 minutes"], ["60", "1 hour"], ["90", "1.5 hours"], ["120", "2 hours"]])));
      host.appendChild(settingsUI.row("Show week numbers", "", settingsUI.toggle(prefs.get("weekNumbers", false), (v) => prefs.set("weekNumbers", v))));
      host.appendChild(el("h4", { text: "Holiday and birthday calendars" }));
      for (const hc of HOLIDAY_CALENDARS) host.appendChild(settingsUI.row(hc.name, "", settingsUI.toggle(prefs.get("cal." + hc.id, hc.id === "holidays-us"), (v) => { prefs.set("cal." + hc.id, v); pim.emit(); })));
      host.appendChild(settingsUI.row("Birthdays of my contacts", "", settingsUI.toggle(prefs.get("cal.birthdays", true), (v) => { prefs.set("cal.birthdays", v); pim.emit(); })));
    },
    render_about(host) {
      host.append(el("h3", { text: "About Anne's Post" }), el("p", { text: `Version ${QB_VERSION}` }));
      host.appendChild(el("p", { text: "Anne's Post is an open-source mail, calendar, contacts and to-do client for any IMAP or POP3 account, with built-in junk and phishing protection. It runs on Windows, macOS, Linux, Raspberry Pi, Android, ChromeOS and iOS, and as a self-hosted web app." }));
      host.appendChild(el("p", { class: "small muted", text: "Anne's Post is an independent project. Its layout follows common desktop mail-client conventions; it is not affiliated with, endorsed by or connected to Microsoft or any other mail software vendor. Product names mentioned in the documentation belong to their owners." }));
      host.appendChild(el("p", { class: "small muted", text: "Privacy: messages stay between this Anne's Post server and your mail provider. Calendars, contacts, tasks and settings are stored in the Anne's Post data folder on the machine that runs it. Junk checks make only DNS queries; AI features use a local model you install yourself." }));
      host.appendChild(el("div", { class: "btn-row" }, el("button", { class: "btn", onclick: () => openExternal(QB_REPO) }, icon("external"), "Project page"), el("button", { class: "btn", onclick: () => mailplus.whatsNew() }, icon("sparkle"), "What's new")));
    },
  },
};
