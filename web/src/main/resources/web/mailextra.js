/* Anne's Post — the mail command set, part 2: commands from other mail programs (Eudora, Pine/Alpine,
 * mutt, Pegasus Mail, Lotus Notes, Netscape/Thunderbird, Outlook Express, Gmail, Apple Mail, Spark,
 * Superhuman, Hey, Claris Emailer, Opera M2) and new commands original to this client, plus the
 * hooks that give them their behaviour in the list and reading pane (tags, screener, split inbox,
 * quick filter, tracker blocking, watched threads, message reminders…). Registration is pure data;
 * mailExtras.install() wires the hooks once the page is up.
 */
"use strict";
/** Origin label for commands that no earlier mail program had. */
const ORIGINAL = "Anne\'s Post";

/** Thunderbird's five standard tags (IMAP keywords $label1…$label5); custom tags are kept per account. */
const STANDARD_TAGS = [
  { key: "$label1", name: "Important", color: "#d13438" }, { key: "$label2", name: "Work", color: "#ca5010" }, { key: "$label3", name: "Personal", color: "#107c10" },
  { key: "$label4", name: "To Do", color: "#0f6cbd" }, { key: "$label5", name: "Later", color: "#8764b8" },
];
const SPECIAL_KEYWORDS = { $Important: "Important", $SetAside: "Set aside", $ReplyLater: "Reply later", $Redirected: "Redirected", $Bounced: "Bounced", "\\Deleted": "Marked deleted" };

const mailExtras = {
  qfilter: null, history: [], historyPos: -1, navigating: false, tagged: null,
  tags() { return [...STANDARD_TAGS, ...(MC.store.peek("tags", []) || [])]; },
  tag(key) { return this.tags().find((t) => t.key.toLowerCase() === String(key).toLowerCase()); },
  vips() { return new Set((MC.store.peek("vip", []) || []).map((x) => x.toLowerCase())); },
  screener() { return MC.store.peek("screener", { enabled: false, approved: [], denied: [] }) || { enabled: false, approved: [], denied: [] }; },
  watched() { return MC.store.peek("watch", []) || []; },
  savedSearches() { return MC.store.peek("searches", []) || []; },
  snippets() { return MC.store.peek("snippets", []) || []; },
};

(function defineExtraCommands() {
  const { T, has, one, need, needOne, detail, openDetail, composing, needCompose, insertHtml, showMenu, saveBlob, safeName, UserError, store } = MC;
  const sel = (ctx) => has(ctx);
  const imap = () => !!(typeof state !== "undefined" && state.capabilities && state.capabilities.folders);
  const P = (k, d) => prefs.get(k, d);
  const cmp = { scope: "compose", isEnabled: composing };
  const C = [];
  const add = (tab, group, origin, list) => { for (const c of list) C.push({ tab, group, origin, ...c }); };
  const E = mailExtras;

  // ------------------------------------------------------------------ shared helpers
  async function editFlags(items, addK = [], remK = []) {
    await api("/api/x/flags", { method: "POST", body: { items: MC.refs(items), add: addK, remove: remK } });
    const ids = new Set(items.map(mkey));
    const norm = (k) => k.toLowerCase();
    const patch = (m) => {
      if (!ids.has(mkey(m))) return m;
      let kw = (m.keywords || []).filter((k) => !remK.map(norm).includes(norm(k)));
      for (const k of addK) if (!kw.map(norm).includes(norm(k))) kw.push(k);
      const out = { ...m, keywords: kw };
      for (const [k, f] of [["\\Seen", "read"], ["\\Answered", "answered"], ["\\Flagged", "flagged"]]) { if (addK.includes(k)) out[f] = true; if (remK.includes(k)) out[f] = false; }
      if (addK.includes("$Forwarded")) out.forwarded = true; if (remK.includes("$Forwarded")) out.forwarded = false;
      return out;
    };
    state.messages = state.messages.map(patch);
    if (state.openDetail && ids.has(mkey(state.openDetail))) Object.assign(state.openDetail, patch(state.openDetail));
    list.render();
  }
  E.editFlags = editFlags;
  const hasKw = (m, k) => (m.keywords || []).some((x) => x.toLowerCase() === k.toLowerCase());
  async function toggleKw(ctx, k, label) {
    const t = need(ctx);
    const on = !t.every((m) => hasKw(m, k));
    await editFlags(t, on ? [k] : [], on ? [] : [k]);
    toast(`${on ? "Added" : "Removed"} ${label}`);
  }
  async function thread(m) {
    const d = await detail(m);
    if (state.openDetail && mkey(state.openDetail) === mkey(m) && state.thread.length) return state.thread;
    try { const r = await api("/api/thread", { query: { folder: d.folder, uid: d.uid } }); return r.messages.length ? r.messages : [m]; } catch { return [m]; }
  }
  async function bodies(msgs, max = 25) {
    const out = [];
    for (const m of msgs.slice(-max)) { try { const d = await detail(m); out.push({ ...m, d, text: mailplus.bodyText(d), from: d.fromAddress, fromName: d.fromName, date: d.date }); } catch {} }
    return out;
  }
  const addrOf = (a) => emailOf(a).toLowerCase();
  const knownSenders = () => {
    const s = new Set([...(E.screener().approved || []), ...E.vips(), ...(state.settings?.spam?.safeSenders || [])].map((x) => x.toLowerCase()));
    for (const c of pim.data.contacts) for (const e of c.emails || []) s.add(e.value.toLowerCase());
    for (const r of prefs.get("recentRecipients", [])) s.add(r.email);
    for (const a of list.myAddresses()) s.add(a);
    return s;
  };
  E.knownSenders = knownSenders;
  function senderKnown(addr, known) {
    addr = String(addr || "").toLowerCase();
    if (!addr) return true;
    const dom = domainOf(addr);
    return known.has(addr) || known.has(dom) || (state.settings?.spam?.safeSenders || []).some((x) => x === dom);
  }
  E.unscreened = () => {
    const sc = E.screener(); if (!sc.enabled) return [];
    if (E._unscr && E._unscr.msgs === state.messages && E._unscr.sc === sc && E._unscr.n === pim.data.contacts.length) return E._unscr.list;
    const known = knownSenders(); const denied = new Set((sc.denied || []).map((x) => x.toLowerCase()));
    const out = state.messages.filter((m) => !senderKnown(m.fromAddress, known) && !denied.has((m.fromAddress || "").toLowerCase()) && !(m.verdict && m.verdict.level === "SPAM"));
    E._unscr = { msgs: state.messages, sc, n: pim.data.contacts.length, list: out, keys: new Set(out.map(mkey)) };
    return out;
  };
  E.isUnscreened = (m) => { E.unscreened(); return !!(E._unscr && E._unscr.keys.has(mkey(m))); };
  async function recipientsDialog(title, ok, hint) {
    const v = await formDialog.open({ title, ok, fields: [{ key: "to", label: "To", placeholder: "name@example.com, …", required: true }, { type: "info", text: hint }] });
    if (!v) return null;
    const to = mailplus.expandRecipients(splitAddresses(v.to));
    const bad = to.find((a) => !isEmail(a)); if (bad) throw new UserError(`"${bad}" is not a valid address.`);
    return to;
  }

  // ================================================================== Eudora
  add("Tools", "Eudora", "Eudora", [
    { id: "eudora.redirect", label: "Redirect…", icon: "send", isEnabled: (ctx) => sel(ctx) && imap(), tip: "Sends the message on unchanged, from the original sender \"by way of\" you; replies go to the original sender",
      run: async (ctx) => { const m = needOne(ctx); const to = await recipientsDialog("Redirect", "Redirect", "The message is sent as it is, with Resent-From / Resent-To headers and \"(by way of you)\" after the author's name. Some receivers may treat a redirected message as suspicious because the original sender did not send it from your server."); if (!to) return; await api("/api/x/resend", { method: "POST", body: { folder: m.folder || state.folder, uid: m.uid, to, mode: "redirect" } }); toast(`Redirected to ${to.join(", ")}`); } },
    { id: "eudora.sendAgain", label: "Send Again", icon: "repeat", isEnabled: sel, tip: "Re-sends a message that bounced (or any message) as a new message",
      run: async (ctx) => { const m = needOne(ctx); const o = await api("/api/x/original", { query: { folder: m.folder || state.folder, uid: m.uid } }).catch(() => ({ found: false })); if (o.found) { await compose.open({ mode: "new", to: o.to.join(", "), cc: o.cc.join(", "), subject: o.subject, html: o.html ? sanitizeHtml(o.html, { allowImages: false, folder: m.folder, uid: m.uid }).html.replace(/^[\s\S]*<body>|<\/body>[\s\S]*$/g, "") : esc(o.text).replace(/\n/g, "<br>") }); toast("The undelivered message is open again — check the address and send."); } else compose.open({ mode: "editAsNew", detail: await detail(m) }); } },
    { id: "eudora.filterMessages", label: "Filter Messages", icon: "rules", isEnabled: sel, keywords: "apply rules to selected", tip: "Runs your rules on the selected messages only", run: (ctx) => filterSelected(need(ctx)) },
    { id: "eudora.makeNickname", label: "Make Nickname…", icon: "person-add", isEnabled: sel, keywords: "address book entry alias",
      run: async (ctx) => { const m = needOne(ctx); const v = await formDialog.open({ title: "Make nickname", fields: [{ key: "nick", label: "Nickname", value: (m.fromName || m.fromAddress.split("@")[0]).split(/\s+/)[0].toLowerCase(), required: true }, { key: "name", label: "Full name", value: m.fromName }, { key: "email", label: "Address", type: "email", value: m.fromAddress, required: true }, { type: "info", text: "Type the nickname in To, Cc or Bcc and it expands to the address." }] }); if (!v) return; const [first, ...rest] = (v.name || "").trim().split(/\s+/); await pim.save("contacts", { firstName: first || "", lastName: rest.join(" "), displayName: v.name || "", nickname: v.nick.trim(), emails: [{ label: "", value: v.email.trim() }] }); toast(`Nickname "${v.nick}" saved`); } },
    { id: "eudora.nicknames", label: "Nicknames", icon: "contact", run: () => { const box = el("div", { class: "qb-msglist" }); const withNick = pim.data.contacts.filter((c) => c.nickname); if (!withNick.length) box.appendChild(el("p", { class: "muted", text: "No nicknames yet. Use Make Nickname on a message." })); for (const c of withNick) box.appendChild(el("div", { class: "qb-msgrow" }, el("b", { text: c.nickname }), el("span", { class: "grow muted", text: ` → ${pim.contactName(c)} <${c.emails?.[0]?.value || ""}>` }))); MC.panel("Nicknames", box); } },
    { id: "eudora.moodWatch", label: "MoodWatch", icon: "c-pepper", keywords: "tone flame chili peppers", tip: "Rates how heated a message reads (0–3 chili peppers)",
      run: async (ctx) => { const text = composing() ? htmlToPlain(MC.editor()) : mailplus.bodyText(await openDetail(ctx)); const s = MailTools.moodScore(text); MC.panel("MoodWatch", el("div", {}, el("p", { class: "qb-peppers", text: s.peppers ? "🌶".repeat(s.peppers) : "🧊" }), el("p", { text: ["Calm — nothing likely to offend.", "Mildly heated: one chili pepper.", "Heated: two chili peppers — the reader may be offended.", "On fire: three chili peppers — consider rewriting before sending."][s.peppers] }), s.hits.length ? el("p", { class: "small muted", text: "Because of: " + s.hits.join(", ") }) : null)); } },
    { id: "eudora.moodWatchIncoming", label: "MoodWatch: Rate Incoming Mail", icon: "c-pepper", kind: "toggle", isActive: () => P("moodIncoming", true), run: () => { prefs.set("moodIncoming", !P("moodIncoming", true)); if (state.openDetail) reading.render(state.openDetail); } },
    { id: "eudora.changeStatus", label: "Change Status", icon: "check", kind: "menu", isEnabled: sel, menu: (ctx) => { const t = need(ctx); return [
      { label: "Unread", onClick: () => editFlags(t, [], ["\\Seen"]) }, { label: "Read", onClick: () => editFlags(t, ["\\Seen"], []) },
      { label: "Replied", onClick: () => editFlags(t, ["\\Answered"], []) }, { label: "Not replied", onClick: () => editFlags(t, [], ["\\Answered"]) },
      { label: "Forwarded", onClick: () => editFlags(t, ["$Forwarded"], []) }, { label: "Redirected", onClick: () => editFlags(t, ["$Redirected"], []) },
      { label: "Clear forwarded / redirected", onClick: () => editFlags(t, [], ["$Forwarded", "$Redirected", "$Bounced"]) },
    ]; } },
    { id: "eudora.personalities", label: "Personalities", icon: "user", kind: "menu", ...cmp, keywords: "identity from alias", menu: () => { const aliases = [state.account.email, ...(state.settings?.aliases || [])]; return [...aliases.map((a) => ({ label: a, checked: $("cFrom").value === a, onClick: () => { $("cFrom").value = a; $("cFromRow").classList.toggle("hidden", aliases.length < 2); const sig = (state.settings.signatures || []).find((s) => s.name.toLowerCase() === a.toLowerCase() || s.name.toLowerCase() === a.split("@")[0]); if (sig) insertHtml(`<div class="qb-signature">${sig.html}</div>`); toast(`Writing as ${a}`); } })), { sep: true }, { label: "Manage personalities (aliases)…", icon: "settings", onClick: () => settingsUI.open("account") }]; } },
    { id: "eudora.priority", label: "Priority", icon: "c-high", kind: "menu", ...cmp, menu: () => [["1 (Highest)", "Highest", "high"], ["2 (High)", "High", "high"], ["3 (Normal)", "Normal", "normal"], ["4 (Low)", "Low", "low"], ["5 (Lowest)", "Lowest", "low"]].map(([h, l, imp]) => ({ label: l, checked: (MC.headers()["X-Priority"] || "3 (Normal)") === h, onClick: () => { MC.setHeader("X-Priority", h === "3 (Normal)" ? "" : h); state.compose.importance = imp; compose.renderImportance(); } })) },
    { id: "eudora.queue", label: "Queue for Delivery", icon: "send-later", ...cmp, tip: "Puts the message in the outbox; Send Queued Messages sends everything queued", run: () => compose.send({ sendAt: Date.now() + 365 * 86400e3 }) },
    { id: "eudora.sendQueued", label: "Send Queued Messages", icon: "send", run: () => Commands.run("mail.sendAll") },
    { id: "eudora.pasteQuotation", label: "Paste As Quotation", icon: "c-quote", ...cmp, run: async () => { let t = ""; try { t = await navigator.clipboard.readText(); } catch { throw new UserError("Your browser does not allow reading the clipboard here."); } insertHtml(`<blockquote style="border-left:3px solid #ccc;margin:6px 0;padding-left:10px;color:#555">${esc(t).replace(/\n/g, "<br>")}</blockquote><div><br></div>`); } },
  ]);
  async function filterSelected(items) {
    const rules = (state.settings.rules || []).filter((r) => r.enabled);
    if (!rules.length) throw new UserError("No rules are turned on.");
    const left = new Map(items.map((m) => [mkey(m), m]));
    let acted = 0;
    for (const r of rules.sort((a, b) => a.priority - b.priority)) {
      if (!left.size) break;
      const folder = items[0].folder || state.folder;
      const pv = await api("/api/rules/preview", { method: "POST", body: { rule: r, folder, limit: 300 } });
      const hit = pv.matches.map((x) => left.get(mkey(x))).filter(Boolean);
      if (!hit.length) continue;
      hit.forEach((m) => left.delete(mkey(m)));
      acted += hit.length;
      switch (r.action) {
        case "MOVE_TO_FOLDER": if (/^junk$/i.test(r.targetFolder || "Junk")) await actions.junk(hit, true); else { const f = state.folders.find((x) => x.path === r.targetFolder || x.name === r.targetFolder); if (f) await actions.move(hit, f.path); } break;
        case "COPY_TO_FOLDER": await actions.copy(hit, r.targetFolder); break;
        case "DELETE": await actions.delete(hit); break;
        case "MARK_READ": await actions.read(hit, true); break;
        case "FLAG": await actions.flag(hit, true); break;
        case "PIN": await actions.pin(hit, true); break;
        case "CATEGORIZE": for (const c of r.categories || []) await actions.categorize(hit, c, true); break;
        default: break;
      }
    }
    toast(acted ? `Rules acted on ${plural(acted, "message")}` : "No rule matched the selected messages.");
  }

  // ================================================================== Pine / Alpine and mutt
  add("Tools", "Pine & mutt", "Pine / mutt", [
    { id: "pine.tagPattern", label: "Tag Messages Matching Pattern…", icon: "select", keywords: "select pattern ; T mutt", tip: "Selects every message in the list matching a mutt-style pattern (~f, ~s, ~d, ~z …)", run: () => patternDialog("Tag messages matching", "Tag", (fn) => { let n = 0; for (const m of list.visible()) if (fn(m)) { state.selected.add(mkey(m)); n++; } list.render(); toast(`${plural(n, "message")} tagged`); }) },
    { id: "pine.untagPattern", label: "Untag Messages Matching Pattern…", icon: "close", run: () => patternDialog("Untag messages matching", "Untag", (fn) => { let n = 0; for (const m of list.visible()) if (fn(m) && state.selected.delete(mkey(m))) n++; list.render(); toast(`${plural(n, "message")} untagged`); }) },
    { id: "pine.applyToTagged", label: "Apply Command to Tagged…", icon: "bolt", isEnabled: () => state.selected.size > 0, run: (ctx) => { const t = list.targets(); showMenu(mailplus.messageMenu(t, t[0]), ctx); } },
    { id: "pine.limit", label: "Limit View to Pattern… (Zoom)", icon: "filter", keywords: "mutt l limit pine zoom", run: () => patternDialog("Show only messages matching", "Limit", (fn, src) => { state.filter = "pattern:" + src; state.selected.clear(); list.render(); }) },
    { id: "pine.unlimit", label: "Show All Messages (Unzoom)", icon: "close", isEnabled: () => String(state.filter).startsWith("pattern:"), run: () => { state.filter = "all"; list.render(); } },
    { id: "pine.pipe", label: "Pipe Message…", icon: "c-pipe", isEnabled: sel, keywords: "| unix filter grep", tip: "Runs the message through built-in filters (grep, sort, uniq, wc, sed, cut, tr …)", run: async (ctx) => pipeDialog(await openDetail(ctx)) },
    { id: "pine.bounce", label: "Bounce…", icon: "send", isEnabled: (ctx) => sel(ctx) && imap(), keywords: "remail resend", tip: "Re-sends the message byte for byte with Resent-* headers",
      run: async (ctx) => { const t = need(ctx); const to = await recipientsDialog(t.length > 1 ? `Bounce ${t.length} messages` : "Bounce message", "Bounce", "The message is delivered exactly as you received it; only Resent-From/To/Date headers are added. The recipient sees the original sender, and replies go there."); if (!to) return; for (const m of t) await api("/api/x/resend", { method: "POST", body: { folder: m.folder || state.folder, uid: m.uid, to, mode: "bounce" } }); toast(`Bounced ${plural(t.length, "message")} to ${to.join(", ")}`); } },
    { id: "pine.takeAddr", label: "Take Address…", icon: "person-add", isEnabled: sel, keywords: "address book add", run: async (ctx) => takeAddress(await openDetail(ctx)) },
    { id: "pine.markDeleted", label: "Mark for Deletion", icon: "trash", isEnabled: (ctx) => sel(ctx) && imap(), tip: "Marks messages deleted without moving them; Compact (Expunge) removes them", run: (ctx) => editFlags(need(ctx), ["\\Deleted"], []) },
    { id: "pine.undelete", label: "Undelete", icon: "undo", isEnabled: (ctx) => sel(ctx) && imap(), run: (ctx) => editFlags(need(ctx), [], ["\\Deleted"]) },
    { id: "pine.jump", label: "Jump to Message Number…", icon: "list", run: async () => { const vis = list.visible(); const v = await formDialog.open({ title: "Jump to message", fields: [{ key: "n", label: `Message number (1–${vis.length})`, type: "number", min: 1, max: vis.length, value: 1, required: true }] }); if (!v) return; const m = vis[Math.min(vis.length, Math.max(1, Number(v.n))) - 1]; if (m) { reading.open(m); qs(`.msg[data-key="${CSS.escape(mkey(m))}"]`)?.scrollIntoView({ block: "center" }); } } },
    { id: "pine.headerMode", label: "Full Headers", icon: "list", kind: "toggle", keywords: "header mode h toggle weed blah blah blah show all headers", isActive: () => P("fullHeaders", false), run: () => { prefs.set("fullHeaders", !P("fullHeaders", false)); if (state.openDetail) reading.render(state.openDetail); } },
    { id: "mutt.nextUnread", label: "Next Unread Message", icon: "chev-down", run: () => jumpTo((m) => !m.read, 1) },
    { id: "mutt.prevUnread", label: "Previous Unread Message", icon: "chev-left", run: () => jumpTo((m) => !m.read, -1) },
    { id: "mutt.nextFlagged", label: "Next Flagged Message", icon: "flag", origin: "Thunderbird", run: () => jumpTo((m) => m.flagged, 1) },
    { id: "mutt.nextUnreadThread", label: "Next Unread Conversation", icon: "reply-all", origin: "Thunderbird", run: () => { const cur = state.open ? mailplus.conversationOf(state.open).map(mkey) : []; jumpTo((m) => !m.read && !cur.includes(mkey(m)), 1); } },
    { id: "mutt.listReply", label: "Reply to List", icon: "people", isEnabled: sel, run: async (ctx) => { const d = await openDetail(ctx); const listAddr = (d.to || []).concat(d.cc || []).find((a) => !MC.me().has(a.toLowerCase()) && a.toLowerCase() !== (d.fromAddress || "").toLowerCase()); if (!listAddr) throw new UserError("No list address found in To or Cc."); await compose.open({ mode: "reply", detail: d }); $("cTo").value = listAddr; } },
    { id: "mutt.readThread", label: "Mark Conversation as Read", icon: "read", isEnabled: sel, origin: "mutt / Thunderbird", run: (ctx) => actions.read(mailplus.conversationOf(needOne(ctx)), true) },
    { id: "mutt.deleteThread", label: "Delete Conversation", icon: "trash", isEnabled: sel, run: (ctx) => actions.delete(mailplus.conversationOf(needOne(ctx))) },
    { id: "mutt.toggleNew", label: "Toggle New (Read/Unread)", icon: "unread", isEnabled: sel, run: (ctx) => { const t = need(ctx); return actions.read(t, !t.every((m) => m.read)); } },
    { id: "mutt.sortThreads", label: "Sort by Thread", icon: "reply-all", kind: "toggle", isActive: () => P("conversations", true) && P("sort", { by: "date" }).by === "date", run: () => { prefs.set("conversations", true); prefs.set("sort", { by: "date", dir: "desc" }); list.render(); } },
  ]);
  function patternDialog(title, ok, fn) {
    const help = el("div", { class: "small muted qb-pattern-help" }, ...Object.entries(MailTools.PATTERN_OPS).map(([k, v]) => el("div", {}, el("code", { text: "~" + k }), " " + v)), el("div", { style: { marginTop: "6px" }, text: "Combine with spaces (and), | (or), ! (not) and parentheses; = for exact match. Example: ~f jane ~d <2w | ~z >1M" }));
    return formDialog.open({ title, ok, wide: true, fields: [{ key: "p", label: "Pattern", value: P("lastPattern", "~U"), required: true }], extra: help }).then((v) => {
      if (!v) return;
      let compiled;
      try { compiled = MailTools.compilePattern(v.p); } catch (e) { throw new UserError("Pattern: " + e.message); }
      prefs.set("lastPattern", v.p);
      const env = { me: list.myAddresses(), tagged: state.selected, key: mkey };
      return fn((m) => compiled(m, env), v.p);
    });
  }
  function jumpTo(pred, dir) {
    const vis = list.visible();
    let i = state.open ? vis.findIndex((m) => mkey(m) === mkey(state.open)) : -1;
    for (let k = 0; k < vis.length; k++) { i += dir; if (i < 0 || i >= vis.length) break; if (pred(vis[i])) { reading.open(vis[i]); qs(`.msg[data-key="${CSS.escape(mkey(vis[i]))}"]`)?.scrollIntoView({ block: "nearest" }); return; } }
    toast("No more matching messages in this list.");
  }
  async function pipeDialog(d) {
    const src = select([["text", "Message text"], ["fresh", "New text only (quotes removed)"], ["raw", "Full source"]], P("pipeSource", "text"));
    const cmd = el("input", { type: "text", value: P("pipeCmd", "grep -i http | urls"), placeholder: "grep -i invoice | sort | uniq -c" });
    const out = el("pre", { class: "body-text mono qb-pipe-out", text: "" });
    const presets = el("div", { class: "btn-row" }, ...[["Links", "urls"], ["Addresses", "emails"], ["Word count", "wc"], ["Questions", "grep \\?"], ["Numbered", "nl"], ["ROT13", "rot13"], ["Unique lines", "sort | uniq -c | sort -r -n"]].map(([l, c]) => el("button", { class: "btn sm", onclick: () => { cmd.value = c; run(); } }, l)));
    let raw = null;
    async function input() {
      if (src.value === "raw") { if (raw === null) raw = await (await api("/api/raw", { query: { folder: d.folder, uid: d.uid }, raw: true })).text(); return raw; }
      const t = mailplus.bodyText(d); return src.value === "fresh" ? MailTools.freshText(t) : t;
    }
    async function run() { try { prefs.set("pipeCmd", cmd.value); prefs.set("pipeSource", src.value); out.textContent = MailTools.runPipeline(await input(), cmd.value) || "(no output)"; } catch (e) { out.textContent = e.message; } }
    cmd.onkeydown = (e) => { if (e.key === "Enter") { e.preventDefault(); run(); } };
    const node = el("div", {}, el("div", { class: "field" }, el("label", { text: "Input" }), src), el("div", { class: "field" }, el("label", { text: "| Command" }), cmd), presets,
      el("p", { class: "small muted", text: "Built-in filters only — nothing runs on the server's shell: " + MailTools.PIPE_HELP }),
      el("div", { class: "btn-row" }, el("button", { class: "btn primary", onclick: run }, "Run"), el("button", { class: "btn", onclick: () => navigator.clipboard.writeText(out.textContent).then(() => toast("Copied")) }, "Copy output"), el("button", { class: "btn", onclick: () => saveBlob(safeName(d.subject) + "-pipe.txt", out.textContent) }, "Save output")), out);
    MC.panel(`Pipe: ${d.subject}`, node);
    run();
  }
  async function takeAddress(d) {
    const people = [[d.fromName, d.fromAddress], ...(d.to || []).map((a) => ["", a]), ...(d.cc || []).map((a) => ["", a])].filter(([, a]) => a && !MC.me().has(a.toLowerCase()) && !pim.contactByEmail(a));
    if (!people.length) throw new UserError("Everyone on this message is already in your address book.");
    const box = el("div", {}); const picks = new Set();
    for (const p of people) { const cb = el("input", { type: "checkbox", checked: p === people[0] }); if (p === people[0]) picks.add(p); cb.onchange = () => (cb.checked ? picks.add(p) : picks.delete(p)); box.appendChild(el("label", { class: "check" }, cb, el("span", { text: p[0] ? `${p[0]} <${p[1]}>` : p[1] }))); }
    const v = await formDialog.open({ title: "Take address", ok: "Add to contacts", fields: [], extra: box });
    if (!v) return;
    for (const [name, email] of picks) { const [first, ...rest] = (name || "").split(/\s+/); await pim.save("contacts", { firstName: first || "", lastName: rest.join(" "), displayName: name || "", emails: [{ label: "", value: email }] }); }
    toast(`Added ${plural(picks.size, "contact")}`);
  }

  // ================================================================== Pegasus Mail
  add("Tools", "Pegasus Mail", "Pegasus Mail", [
    { id: "pegasus.glossary", label: "Expand Glossary Entry", icon: "note", keys: ["F3"], ...cmp, keywords: "abbreviation autotext quick parts expand", tip: "Type a snippet's abbreviation and press F3 to expand it", run: () => expandGlossary() },
    { id: "pegasus.mailMerge", label: "Mail Merge…", icon: "people", tip: "Sends a personalised copy to every member of a contact group", run: () => mailMerge() },
    { id: "pegasus.copyToSelf", label: "Copy to Self", icon: "copy", kind: "toggle", ...cmp, isActive: () => !!state.compose?.copySelf, run: () => { state.compose.copySelf = !state.compose.copySelf; toast(state.compose.copySelf ? "A copy will be sent to you (Bcc)." : "No copy to self."); } },
  ]);
  async function expandGlossary() {
    needCompose();
    await store.get("snippets", []);
    const s = getSelection(); if (!s.rangeCount) return;
    const r = s.getRangeAt(0); const node = r.startContainer;
    if (node.nodeType !== 3) throw new UserError("Type an abbreviation, then press F3.");
    const before = node.textContent.slice(0, r.startOffset);
    const word = (before.match(/(\S+)$/) || [])[1] || "";
    const snip = E.snippets().find((x) => (x.abbr || "").toLowerCase() === word.toLowerCase() || x.name.toLowerCase() === word.toLowerCase());
    if (!snip) throw new UserError(word ? `No snippet or glossary entry called "${word}".` : "Type an abbreviation, then press F3.");
    const range = document.createRange(); range.setStart(node, r.startOffset - word.length); range.setEnd(node, r.startOffset);
    s.removeAllRanges(); s.addRange(range);
    document.execCommand("insertHTML", false, fillSnippet(snip.html));
    state.compose.dirty = true;
  }
  function fillSnippet(html) {
    const to = splitAddresses($("cTo").value)[0] || "";
    const name = (to.match(/^\s*"?([^"<]+?)"?\s*</) || [])[1] || (pim.contactByEmail(emailOf(to)) ? pim.contactName(pim.contactByEmail(emailOf(to))) : emailOf(to).split("@")[0]);
    return MailTools.expandSnippet(html, { name, email: emailOf(to), my_name: state.account.displayName || "", subject: $("cSubject").value }).replace("{cursor}", "");
  }
  async function mailMerge() {
    const lists = pim.data.contactLists;
    if (!lists.length) throw new UserError("Create a contact group under People first.");
    const v = await formDialog.open({ title: "Mail merge", ok: "Send to everyone", wide: true, fields: [
      { key: "list", label: "Send to each member of", type: "select", options: lists.map((l) => [l.id, `${l.name} (${l.members.length})`]) },
      { key: "subject", label: "Subject", required: true },
      { key: "body", label: "Message — {first_name}, {name}, {email} and {my_name} are filled in for each person", type: "html", value: "Dear {first_name},<br><br><br>{my_name}" },
    ] });
    if (!v) return;
    const l = lists.find((x) => x.id === v.list);
    if (!confirm(`Send ${plural(l.members.length, "personalised message")}?`)) return;
    let ok = 0;
    for (const m of l.members) {
      const c = pim.contactByEmail(m.email);
      const name = m.name || (c ? pim.contactName(c) : m.email.split("@")[0]);
      const vars = { name, first_name: c?.firstName || name.split(/\s+/)[0], email: m.email, my_name: state.account.displayName || "" };
      const html = MailTools.expandSnippet(v.body, vars);
      const tmp = el("div", { html });
      try { await api("/api/send", { method: "POST", body: { to: [name ? `${name} <${m.email}>` : m.email], cc: [], bcc: [], subject: MailTools.expandSnippet(v.subject, vars), body: htmlToPlain(tmp), html: `<!doctype html><html><body>${html}</body></html>`, attachments: [] } }); ok++; }
      catch (e) { toast(`${m.email}: ${e.message}`, { danger: true }); }
    }
    toast(`Mail merge sent ${plural(ok, "message")}`); folders.load();
  }

  // ================================================================== Lotus Notes
  add("Tools", "Lotus Notes", "Lotus Notes", [
    { id: "notes.followUp", label: "Follow Up (with Priority)…", icon: "flag", isEnabled: sel,
      run: async (ctx) => { const t = need(ctx); const v = await formDialog.open({ title: "Follow up", fields: [{ key: "prio", label: "Priority", type: "select", value: "Normal", options: [["High", "High"], ["Normal", "Normal"], ["Low", "Low"]] }, { key: "due", label: "Date", type: "date", value: ymd(addDays(new Date(), 1)) }, { key: "remind", label: "Remind me on that day", type: "checkbox", value: true }] }); if (!v) return; await actions.flag(t, true, v.due || ""); const cat = `Follow up: ${v.prio}`; if (!(state.settings.categories || []).some((c) => c.name === cat)) await settingsSave({ ...state.settings, categories: [...(state.settings.categories || []), { name: cat, color: { High: "#d13438", Normal: "#ca5010", Low: "#8a8a8a" }[v.prio] }] }); await actions.categorize(t, cat, true); if (v.remind && v.due) await actions.meta(t, { reminder: parseYmd(v.due).getTime() + 9 * 3600e3 }); toast(`Follow up (${v.prio}) set`); } },
    { id: "notes.copyToCalendar", label: "Copy Into New Calendar Entry", icon: "calendar", isEnabled: sel, run: async (ctx) => { const d = await openDetail(ctx); calendarUI.newEvent({ title: d.subject.replace(/^\s*(re|fw|fwd)\s*:\s*/i, ""), attendees: [{ email: d.fromAddress, name: d.fromName }] }); } },
    { id: "notes.copyToTask", label: "Copy Into New To Do", icon: "todo", isEnabled: sel, keywords: "add to tasks gmail", run: async (ctx) => { const d = await openDetail(ctx); await pim.save("tasks", { listId: "tasks", title: d.subject || "(no subject)", notes: `From ${d.fromName || ""} <${d.fromAddress}>, ${fmtDate(d.date, { long: true })}\n\n${MailTools.freshText(mailplus.bodyText(d)).slice(0, 2000)}`, important: d.importance === "high" }); toast("Task created", { action: "To Do", onAction: () => shell.setModule("todo") }); } },
    { id: "notes.moodStamp", label: "Mood Stamp", icon: "smile", kind: "menu", ...cmp, menu: () => [["", "None"], ["Personal", "👤 Personal"], ["Confidential", "🔒 Confidential"], ["Flame", "🔥 Flame"], ["Good Job!", "👍 Good Job!"], ["Joke", "😄 Joke"], ["Thank You!", "🙏 Thank You!"], ["Question", "❓ Question"], ["Reminder", "⏰ Reminder"], ["FYI", "ℹ FYI"]].map(([v, l]) => ({ label: l, checked: (MC.headers()["X-Mood-Stamp"] || "") === v, onClick: () => { MC.setHeader("X-Mood-Stamp", v); const ed = MC.editor(); ed.querySelector(".qb-mood")?.remove(); if (v) ed.insertBefore(el("div", { class: "qb-mood", style: { fontWeight: "600", color: "#8764b8", marginBottom: "6px" }, text: l }), ed.firstChild); } })) },
    { id: "notes.whoIsIn", label: "Who Is In This Conversation", icon: "people", isEnabled: sel, run: async (ctx) => {
      const msgs = await thread(needOne(ctx)); const ppl = new Map();
      for (const m of msgs) { const k = (m.fromAddress || "").toLowerCase(); const p = ppl.get(k) || { name: m.fromName || m.fromAddress, addr: k, n: 0, last: 0, cc: false }; p.n++; p.last = Math.max(p.last, m.date); ppl.set(k, p); for (const a of [...(m.to || []), ...(m.cc || [])]) { const ak = a.toLowerCase(); if (!ppl.has(ak)) ppl.set(ak, { name: a, addr: ak, n: 0, last: 0 }); } }
      const box = el("div", { class: "qb-msglist" }, ...[...ppl.values()].sort((a, b) => b.n - a.n).map((p) => el("div", { class: "qb-msgrow" }, el("div", { class: "avatar", text: initials(p.name, p.addr), style: { background: avatarColor(p.addr) } }), el("div", { class: "grow" }, el("b", { text: p.name + (MC.me().has(p.addr) ? " (you)" : "") }), el("div", { class: "small muted", text: p.n ? `${plural(p.n, "message")} · last ${fmtDate(p.last)}` : "Copied, has not written in this conversation" })), el("button", { class: "btn sm", onclick: () => Commands.run("new.senderTimeline", { arg: p.addr }) }, "Timeline"))));
      MC.panel(`Who is in "${msgs[0]?.subject || ""}"`, box); } },
  ]);

  // ================================================================== Netscape / Thunderbird / Outlook Express
  add("Tools", "Thunderbird", "Netscape Messenger / Thunderbird", [
    { id: "tb.tag", label: "Tag", icon: "tag", kind: "menu", isEnabled: (ctx) => sel(ctx) && imap(), keywords: "keyword label imap", menu: (ctx) => { const t = need(ctx); return [
      ...E.tags().map((tg, i) => ({ label: tg.name, color: tg.color, hint: i < 9 ? String(i + 1) : "", checked: t.every((m) => hasKw(m, tg.key)), onClick: () => toggleKw({ items: t }, tg.key, tg.name) })),
      { sep: true },
      { label: "New tag…", icon: "plus", onClick: () => newTag(t) },
      { label: "Remove all tags", icon: "close", hint: "0", onClick: () => editFlags(t, [], E.tags().map((x) => x.key)) },
    ]; } },
    ...[1, 2, 3, 4, 5].map((n) => ({ id: `tb.tag${n}`, label: `Tag: ${STANDARD_TAGS[n - 1].name}`, icon: "tag", keys: [String(n)], scope: "mail", isEnabled: (ctx) => sel(ctx) && imap(), run: (ctx) => toggleKw(ctx, STANDARD_TAGS[n - 1].key, STANDARD_TAGS[n - 1].name) })),
    { id: "tb.removeTags", label: "Remove All Tags", icon: "close", keys: ["0"], scope: "mail", isEnabled: (ctx) => sel(ctx) && imap(), run: (ctx) => editFlags(need(ctx), [], E.tags().map((x) => x.key)) },
    { id: "tb.quickFilter", label: "Quick Filter Bar", icon: "filter", kind: "toggle", keys: ["Ctrl+Alt+K"], scope: "global", isActive: () => !!E.qfilter, run: () => { E.qfilter = E.qfilter ? null : { unread: false, flagged: false, contact: false, tags: false, attach: false, text: "" }; list.render(); setTimeout(() => qs("#qbQuickText")?.focus(), 30); } },
    { id: "tb.watchThread", label: "Watch Conversation", icon: "eye", kind: "toggle", keys: ["W"], scope: "mail", origin: "Outlook Express / Thunderbird", isEnabled: sel, isActive: (ctx) => { const m = one(ctx); return !!m && E.isWatched(m); }, run: (ctx) => watch(needOne(ctx)) },
    { id: "tb.archiveYear", label: "Archive by Year", icon: "archive", isEnabled: (ctx) => sel(ctx) && imap(), run: (ctx) => archiveBy(need(ctx), "year") },
    { id: "tb.archiveMonth", label: "Archive by Month", icon: "archive", isEnabled: (ctx) => sel(ctx) && imap(), run: (ctx) => archiveBy(need(ctx), "month") },
    { id: "tb.stripAttachments", label: "Delete Attachments (Keep Message)", icon: "attach", isEnabled: (ctx) => sel(ctx) && imap(), keywords: "detach remove attachments shrink", tip: "Removes the attached files from the stored message and leaves a note where each was",
      run: async (ctx) => { const t = need(ctx).filter((m) => m.hasAttachments); if (!t.length) throw new UserError("The selected messages have no attachments."); if (!confirm(`Remove the attachments from ${plural(t.length, "message")}? The files are deleted from the server; the message text stays.`)) return; let files = 0; for (const m of t) { const r = await api("/api/x/rewrite", { method: "POST", body: { folder: m.folder || state.folder, uid: m.uid, stripAttachments: true } }); files += r.removed.length; if (r.uid && state.lastInboxUids) state.lastInboxUids.add(r.uid); } toast(`Removed ${plural(files, "attachment")}`); reading.close(); list.fetch({ silent: true }); folders.load(); } },
    { id: "tb.bodyAs", label: "Message Body As", icon: "note", kind: "menu", menu: () => [["html", "Original HTML"], ["simple", "Simple HTML"], ["plain", "Plain Text"]].map(([v, l]) => ({ label: l, checked: P("bodyAs", "html") === v, onClick: () => { prefs.set("bodyAs", v); if (state.openDetail) reading.render(state.openDetail); } })) },
    { id: "tb.deleteJunkInFolder", label: "Delete Mail Marked as Junk in Folder", icon: "junk", run: async () => { const junk = state.messages.filter((m) => m.verdict && m.verdict.level === "SPAM"); if (!junk.length) throw new UserError("Nothing in this folder is marked as junk."); if (confirm(`Delete ${plural(junk.length, "junk message")}?`)) await actions.delete(junk); } },
    { id: "tb.goBack", label: "Go Back", icon: "chev-left", keys: ["Alt+ArrowLeft"], scope: "mail", isEnabled: () => E.historyPos > 0, run: () => E.go(-1) },
    { id: "tb.goForward", label: "Go Forward", icon: "chev-right", keys: ["Alt+ArrowRight"], scope: "mail", isEnabled: () => E.historyPos < E.history.length - 1, run: () => E.go(1) },
  ]);
  async function newTag(items) {
    const v = await formDialog.open({ title: "New tag", fields: [{ key: "name", label: "Tag name", required: true }, { key: "color", label: "Colour", type: "color", value: "#038387" }] });
    if (!v) return;
    const key = v.name.trim().replace(/\s+/g, "_").replace(/[^A-Za-z0-9$_.-]/g, "");
    if (!key) throw new UserError("Use letters or digits in the tag name.");
    const tags = [...(await store.get("tags", [])).filter((x) => x.key !== key), { key, name: v.name.trim(), color: v.color }];
    await store.set("tags", tags);
    if (items && items.length) await editFlags(items, [key], []);
  }
  async function archiveBy(items, granularity) {
    const r = await api("/api/x/archive", { method: "POST", body: { items: MC.refs(items), granularity } });
    list.removeLocal(items); toast(`Archived ${plural(r.ok, "message")} by ${granularity}`); folders.load();
  }
  E.isWatched = (m) => { const ids = new Set(E.watched().flatMap((w) => w.ids)); return [m.messageId, m.inReplyTo, ...(m.references || [])].some((x) => x && ids.has(x)); };
  async function watch(m) {
    const ids = [m.messageId, ...(m.references || []), m.inReplyTo].filter(Boolean);
    if (!ids.length) throw new UserError("This message has no Message-ID, so its conversation cannot be watched.");
    const cur = await store.get("watch", []);
    const on = !E.isWatched(m);
    const next = on ? [...cur, { ids, subject: m.subject, at: Date.now() }] : cur.filter((w) => !w.ids.some((x) => ids.includes(x)));
    await store.set("watch", next.slice(-200));
    list.render();
    toast(on ? "Watching this conversation — you will be told about new replies." : "No longer watching this conversation.");
  }

  // ================================================================== Gmail
  add("Tools", "Gmail", "Gmail", [
    { id: "gmail.mute", label: "Mute Conversation", icon: "bell-off", isEnabled: (ctx) => sel(ctx) && imap(), tip: "Archives the conversation; later replies skip the Inbox too",
      run: async (ctx) => { const t = mailplus.conversationOf(needOne(ctx)); const r = await api("/api/x/routes", { method: "POST", body: { items: MC.refs(t), kind: "mute" } }); list.removeLocal(t); toast(`Conversation muted (${plural(r.ok, "message")} archived)`); folders.load(); } },
    { id: "gmail.unmuteAll", label: "Muted and Routed Conversations", icon: "bell", run: async () => { const routes = await api("/api/x/routes"); const box = el("div", { class: "qb-msglist" }); if (!routes.length) box.appendChild(el("p", { class: "muted", text: "No muted or routed conversations." })); for (const r of routes) box.appendChild(el("div", { class: "qb-msgrow" }, el("div", { class: "grow" }, el("b", { text: r.subject || "(no subject)" }), el("div", { class: "small muted", text: r.kind === "mute" ? "Muted (archived)" : `Always moved to ${MC.folderName(r.dest)}` })), el("button", { class: "btn sm", onclick: async () => { const keep = (await api("/api/x/routes")).filter((x) => x.created !== r.created); await api("/api/x/state/routes", { method: "PUT", body: keep }); toast("Stopped"); Commands.run("gmail.unmuteAll"); } }, "Stop"))); MC.panel("Muted and routed conversations", box); } },
    { id: "gmail.labels", label: "Label As", icon: "tag", kind: "menu", isEnabled: (ctx) => sel(ctx) && imap(), keywords: "copy to folder labels", tip: "Adds a label (copies into that folder, which is how labels appear over IMAP)",
      menu: (ctx) => { const t = need(ctx); return [...state.folders.filter((f) => f.role === "other").map((f) => ({ label: f.name, icon: "tag", onClick: () => actions.copy(t, f.path) })), { sep: true }, { label: "New label…", icon: "plus", onClick: async () => { const p = await mailplus.createFolder(null); if (p) actions.copy(t, p); } }]; } },
    { id: "gmail.important", label: "Mark as Important", icon: "c-important", kind: "toggle", isEnabled: (ctx) => sel(ctx) && imap(), isActive: (ctx) => { const t = T(ctx); return t.length > 0 && t.every((m) => hasKw(m, "$Important")); }, run: (ctx) => toggleKw(ctx, "$Important", "importance marker") },
    { id: "gmail.filterLike", label: "Filter Messages Like These", icon: "rules", isEnabled: sel, run: (ctx) => { const m = needOne(ctx); settingsUI.editRule(null, { name: `Like: ${m.fromAddress}`, logic: "AND", criteria: [{ field: "SENDER_DOMAIN", operator: "ENDS_WITH", value: domainOf(m.fromAddress) }, { field: "SUBJECT", operator: "CONTAINS", value: normSubject(m.subject).split(/\s+/).slice(0, 3).join(" ") }], action: "MOVE_TO_FOLDER", targetFolder: folders.byRole("archive")?.path || "Archive" }); } },
    { id: "gmail.printAll", label: "Print All (Conversation)", icon: "print", isEnabled: sel, run: async (ctx) => { const ms = await bodies(await thread(needOne(ctx)), 30); const html = ms.map((x) => mailplus.printable(x.d, false)).join("<hr style='margin:24px 0'>"); if (nativePrint("Conversation")) return; const w = window.open("", "_blank"); if (!w) return popup("Conversation", { html }); w.document.write(html); w.document.close(); setTimeout(() => w.print(), 400); } },
    { id: "gmail.forwardAll", label: "Forward All (Conversation)", icon: "forward", isEnabled: sel, run: async (ctx) => { const ms = await bodies(await thread(needOne(ctx)), 30); const first = ms[ms.length - 1].d; await compose.open({ mode: "forward", detail: first }); const extra = ms.slice(0, -1).reverse().map((x) => `<div class="qb-quote">${compose.quoteHeader(x.d)}${esc(x.text).replace(/\n/g, "<br>")}</div>`).join(""); MC.editor().insertAdjacentHTML("beforeend", extra); } },
    { id: "gmail.createEvent", label: "Create Event", icon: "calendar", isEnabled: sel, run: (ctx) => Commands.run("notes.copyToCalendar", ctx) },
    { id: "gmail.undoSend", label: "Undo Send", icon: "undo", tip: "Takes back the message you just sent while it is still waiting (undo-send delay or scheduled) and opens it again",
      run: async () => { await mailplus.loadOutbox(); const soon = state.outbox.filter((o) => o.sendAt - Date.now() < 10 * 60e3).sort((a, b) => b.created - a.created)[0]; if (!soon) throw new UserError("Nothing is waiting to be sent — it is too late to undo."); const r = await api(`/api/outbox/${encodeURIComponent(soon.id)}`, { method: "DELETE", query: { drafts: "true" } }); await mailplus.loadOutbox(); const drafts = folders.byRole("drafts"); if (drafts && r && r.uid > 0) { const d = await api("/api/message", { query: { folder: drafts.path, uid: r.uid, tz: VIEW_TZ } }); compose.open({ mode: "draft", draft: { ...d, folder: drafts.path } }); toast("Sending undone — the message is open again."); } else toast("Sending undone — the message is in Drafts."); } },
  ]);

  // ================================================================== Apple Mail
  add("Tools", "Apple Mail", "Apple Mail", [
    { id: "apple.vip", label: "Add Sender to VIPs", icon: "star", kind: "toggle", isEnabled: sel, isActive: (ctx) => { const m = one(ctx); return !!m && E.vips().has((m.fromAddress || "").toLowerCase()); },
      run: async (ctx) => { const a = (needOne(ctx).fromAddress || "").toLowerCase(); const cur = await store.get("vip", []); const on = !cur.includes(a); await store.set("vip", on ? [...cur, a] : cur.filter((x) => x !== a)); list.render(); toast(on ? `${a} is a VIP` : `${a} removed from VIPs`); } },
    { id: "apple.vipMail", label: "VIP Mail", icon: "star", kind: "toggle", isActive: () => state.filter === "x:vip", run: () => { state.filter = state.filter === "x:vip" ? "all" : "x:vip"; list.render(); } },
    { id: "apple.vipNotify", label: "Notify Only for VIPs", icon: "bell", kind: "toggle", isActive: () => P("vipNotifyOnly", false), run: () => { prefs.set("vipNotifyOnly", !P("vipNotifyOnly", false)); toast(P("vipNotifyOnly", false) ? "New-mail notifications only for VIPs" : "Notifications for all new mail"); } },
    { id: "apple.remindMe", label: "Remind Me", icon: "bell", kind: "menu", isEnabled: sel, tip: "Brings the message back to the top of the Inbox, unread, at the time you choose", menu: (ctx) => { const t = need(ctx); const now = new Date(); const at = (d, h) => { const x = new Date(d); x.setHours(h, 0, 0, 0); return x; }; const opt = (l, d) => ({ label: l, icon: "clock", hint: `${fmtDay(d)} ${fmtTime(d)}`, onClick: () => remindMe(t, d.getTime()) }); return [opt("In 1 hour", new Date(Date.now() + 3600e3)), now.getHours() < 20 ? opt("Tonight", at(now, 21)) : null, opt("Tomorrow", at(addDays(now, 1), 8)), { label: "Remind me later…", icon: "calendar", onClick: async () => { const v = await formDialog.open({ title: "Remind me", fields: [{ key: "when", label: "Date and time", type: "datetime-local", value: localIso(at(addDays(now, 1), 8)), required: true }] }); if (v) remindMe(t, new Date(v.when).getTime()); } }]; } },
    { id: "apple.blockTrackers", label: "Protect Mail Activity (Block Trackers)", icon: "shield-ok", kind: "toggle", keywords: "tracking pixel privacy read status hey spark", isActive: () => P("blockTrackers", true), run: () => { prefs.set("blockTrackers", !P("blockTrackers", true)); if (state.openDetail) reading.render(state.openDetail); } },
    { id: "mail.unsubscribe", label: "Unsubscribe", icon: "block", isEnabled: sel, origin: "Apple Mail / Gmail / Outlook", keywords: "list-unsubscribe one-click rfc 8058", run: async (ctx) => unsubscribe([needOne(ctx)]) },
  ]);
  async function remindMe(items, when) {
    await actions.meta(items, { reminder: when });
    toast(`I'll remind you ${fmtDate(when, { long: true })}`);
  }
  async function unsubscribe(items) {
    const results = [];
    for (const m of items) {
      try { const r = await api("/api/x/unsubscribe", { method: "POST", body: { folder: m.folder || state.folder, uid: m.uid } }); results.push({ m, r }); }
      catch (e) { results.push({ m, r: { method: "error", status: "failed", detail: e.message } }); }
    }
    if (results.length === 1) {
      const { r } = results[0];
      if (r.status === "open" && r.url) { openExternal(r.url); toast("The list's unsubscribe page was opened."); }
      else toast(r.status === "done" ? `Unsubscribed (${r.method === "one-click" ? "one-click" : "by e-mail"}). ${r.detail}` : r.detail || "Could not unsubscribe.", { danger: r.status !== "done" });
      return results;
    }
    return results;
  }

  // ================================================================== Spark, Superhuman, Hey
  add("Tools", "Spark, Superhuman & Hey", "Superhuman / Spark / Hey", [
    { id: "hey.screener", label: "The Screener", icon: "c-screener", origin: "Hey", tip: "First-time senders wait here until you say yes or no", run: () => screenerDialog() },
    { id: "hey.screenerToggle", label: "Screen First-Time Senders", icon: "c-screener", kind: "toggle", origin: "Hey", isActive: () => !!E.screener().enabled, run: async () => { const sc = await store.get("screener", { enabled: false, approved: [], denied: [] }); await store.set("screener", { ...sc, enabled: !sc.enabled }); list.render(); toast(!sc.enabled ? "Screener on: mail from people you have never dealt with waits in the Screener." : "Screener off."); } },
    { id: "hey.paperTrail", label: "Paper Trail (Receipts)", icon: "note", origin: "Hey", keywords: "receipts invoices orders", run: () => paperTrail() },
    { id: "hey.moveToPaperTrail", label: "Move to Paper Trail", icon: "move", origin: "Hey", isEnabled: (ctx) => sel(ctx) && imap(), run: async (ctx) => { const t = need(ctx); let f = state.folders.find((x) => x.name === "Paper Trail"); if (!f) { const r = await api("/api/folders/create", { method: "POST", body: { name: "Paper Trail" } }); await folders.load(); f = folders.byPath(r.path); } await actions.move(t, f.path); } },
    { id: "hey.setAside", label: "Set Aside", icon: "pin", kind: "toggle", origin: "Hey", isEnabled: (ctx) => sel(ctx) && imap(), isActive: (ctx) => { const t = T(ctx); return t.length > 0 && t.every((m) => hasKw(m, "$SetAside")); }, run: (ctx) => toggleKw(ctx, "$SetAside", "Set Aside") },
    { id: "hey.showSetAside", label: "Show Set Aside", icon: "pin", origin: "Hey", run: () => { state.filter = "x:kw:$SetAside"; list.render(); } },
    { id: "hey.replyLater", label: "Reply Later", icon: "reply", kind: "toggle", origin: "Hey", isEnabled: (ctx) => sel(ctx) && imap(), isActive: (ctx) => { const t = T(ctx); return t.length > 0 && t.every((m) => hasKw(m, "$ReplyLater")); }, run: (ctx) => toggleKw(ctx, "$ReplyLater", "Reply Later") },
    { id: "hey.focusReply", label: "Focus & Reply", icon: "reply-all", origin: "Hey", tip: "Opens the Reply Later messages one after another with a reply ready", run: () => focusReply() },
    { id: "hey.bundle", label: "Bundle Newsletters", icon: "c-bundle", kind: "toggle", origin: "Hey / Inbox by Gmail", isActive: () => P("bundleNews", false), run: () => { prefs.set("bundleNews", !P("bundleNews", false)); list.render(); } },
    { id: "split.toggle", label: "Split Inbox", icon: "c-split", kind: "toggle", origin: "Superhuman / Spark", isActive: () => P("splitInbox", false), run: () => { prefs.set("splitInbox", !P("splitInbox", false)); if (!P("splitInbox", false) && String(state.filter).startsWith("split:")) state.filter = "all"; list.render(); } },
    { id: "snip.insert", label: "Insert Snippet (Quick Parts)", icon: "note", kind: "menu", ...cmp, origin: "Outlook Quick Parts / Superhuman Snippets", keywords: "autotext glossary", menu: async () => { const s = await store.get("snippets", []); return [...s.map((x) => ({ label: x.name, hint: x.abbr || "", icon: "note", onClick: () => insertHtml(fillSnippet(x.html)) })), s.length ? { sep: true } : null, { label: "Save selection as snippet…", icon: "plus", onClick: () => saveSnippet() }, { label: "Manage snippets", icon: "settings", onClick: () => manageSnippets() }]; } },
    { id: "snip.manage", label: "Manage Snippets", icon: "settings", run: () => manageSnippets() },
    { id: "sh.instantIntro", label: "Instant Intro", icon: "people", isEnabled: sel, origin: "Superhuman", tip: "Thanks the person who introduced you and moves them to Bcc", run: async (ctx) => { const d = await openDetail(ctx); await compose.open({ mode: "replyAll", detail: d }); const intro = d.fromAddress; const keep = [...splitAddresses($("cTo").value), ...splitAddresses($("cCc").value)].filter((a) => addrOf(a) !== intro.toLowerCase()); $("cTo").value = keep.join(", "); $("cCc").value = ""; $("cBccRow").classList.remove("hidden"); $("cBcc").value = intro; const first = (d.fromName || intro).split(/[\s@]/)[0]; MC.editor().insertAdjacentHTML("afterbegin", `<div>Thanks ${esc(first)} (moved to Bcc)!</div><div><br></div>`); } },
    { id: "sh.getMeToZero", label: "Get Me to Zero…", icon: "check-circle", origin: "Superhuman", isEnabled: imap, run: async () => { const v = await formDialog.open({ title: "Get me to zero", ok: "Archive", fields: [{ key: "d", label: "Archive Inbox messages older than", type: "select", value: "7", options: [["1", "1 day"], ["7", "1 week"], ["30", "1 month"], ["90", "3 months"]] }] }); if (!v) return; const inbox = folders.byRole("inbox"); const r = await api("/api/x/archive", { method: "POST", body: { path: inbox.path, olderThanDays: Number(v.d), granularity: "none" } }); toast(`Archived ${plural(r.ok, "message")}`); list.fetch({ silent: true }); folders.load(); } },
    { id: "sh.remindNoReply", label: "Remind Me If No Reply", icon: "bell", kind: "toggle", ...cmp, origin: "Superhuman / Spark", isActive: () => !!state.compose?.remindNoReply, run: async () => { if (state.compose.remindNoReply) { state.compose.remindNoReply = null; return toast("No reminder."); } const v = await formDialog.open({ title: "Remind me if nobody replies", fields: [{ key: "d", label: "After", type: "select", value: "3", options: [["1", "1 day"], ["2", "2 days"], ["3", "3 days"], ["7", "1 week"]] }] }); if (v) { state.compose.remindNoReply = Number(v.d); toast(`If nobody replies within ${plural(Number(v.d), "day")}, you will be reminded.`); } } },
  ]);
  async function screenerDialog() {
    await store.get("screener", { enabled: false, approved: [], denied: [] });
    const sc = E.screener();
    const waiting = E.unscreened();
    const bySender = new Map();
    for (const m of (sc.enabled ? waiting : state.messages.filter((m) => !senderKnown(m.fromAddress, knownSenders())))) { const k = (m.fromAddress || "").toLowerCase(); if (!bySender.has(k)) bySender.set(k, []); bySender.get(k).push(m); }
    const box = el("div", { class: "qb-msglist" },
      el("p", { class: "small muted", text: sc.enabled ? "These people have never written to you before. Say yes to let their mail in, or no to file it (and anything else from them) in Screened Out." : "The Screener is off. Turn it on to hold mail from first-time senders here. People below are not yet in your contacts or approved." }),
      el("label", { class: "check" }, Object.assign(el("input", { type: "checkbox", checked: sc.enabled }), { onchange: () => Commands.run("hey.screenerToggle").then(() => screenerDialog()) }), el("span", { text: "Screen first-time senders" })));
    if (!bySender.size) box.appendChild(el("p", { class: "muted", text: "Nobody is waiting." }));
    for (const [addr, msgs] of bySender) {
      box.appendChild(el("div", { class: "qb-msgrow" }, el("div", { class: "avatar", text: initials(msgs[0].fromName, addr), style: { background: avatarColor(addr) } }),
        el("div", { class: "grow" }, el("b", { text: msgs[0].fromName || addr }), el("div", { class: "small muted", text: `${addr} · ${plural(msgs.length, "message")} · "${msgs[0].subject}"` })),
        el("button", { class: "btn sm primary", onclick: async () => { const s = await store.get("screener", sc); await store.set("screener", { ...s, approved: [...new Set([...(s.approved || []), addr])] }); list.render(); screenerDialog(); } }, "Yes"),
        el("button", { class: "btn sm", onclick: async () => { await screenOut(addr, msgs); screenerDialog(); } }, "No")));
    }
    MC.panel("The Screener", box);
  }
  async function screenOut(addr, msgs) {
    const s = await store.get("screener", { enabled: true, approved: [], denied: [] });
    await store.set("screener", { ...s, denied: [...new Set([...(s.denied || []), addr])], approved: (s.approved || []).filter((x) => x !== addr) });
    const rules = state.settings.rules || [];
    let rule = rules.find((r) => r.id === "rule-screened-out");
    const crit = { field: "SENDER", operator: "EQUALS", value: addr };
    rule = rule ? { ...rule, criteria: [...rule.criteria.filter((c) => c.value !== addr), crit] } : { id: "rule-screened-out", name: "Screened out", enabled: true, logic: "OR", criteria: [crit], action: "MOVE_TO_FOLDER", targetFolder: "Screened Out", priority: 40, createdBy: "user", createdAt: Date.now(), note: "Created by the Screener", hitCount: 0 };
    await settingsSave({ ...state.settings, rules: [...rules.filter((r) => r.id !== rule.id), rule] });
    if (imap()) {
      let f = state.folders.find((x) => x.name === "Screened Out");
      if (!f) { const r = await api("/api/folders/create", { method: "POST", body: { name: "Screened Out" } }); await folders.load(); f = folders.byPath(r.path); }
      if (f) await actions.move(msgs, f.path, { quiet: true });
    }
    toast(`${addr} screened out`);
  }
  async function paperTrail() {
    const words = ["receipt", "invoice", "order", "shipped", "payment", "booking", "confirmation"];
    const seen = new Map();
    for (const w of words) { try { for (const m of await MC.searchAll({ subject: w, limit: 60 })) if (MailTools.isReceipt(m)) seen.set(mkey(m), m); } catch {} }
    const msgs = [...seen.values()].sort((a, b) => b.date - a.date);
    MC.panel(`Paper Trail (${msgs.length})`, MC.messageRows(msgs, { empty: "No receipts, invoices or order confirmations found." }));
  }
  async function focusReply() {
    const queue = state.messages.filter((m) => hasKw(m, "$ReplyLater"));
    if (!queue.length) throw new UserError("Nothing is marked Reply Later in this folder.");
    const m = queue[0];
    reading.open(m);
    const d = await detail(m);
    await compose.open({ mode: "reply", detail: d });
    E.afterSend = async () => { await editFlags([m], [], ["$ReplyLater"]); if (queue.length > 1) toast(`${queue.length - 1} more to reply to`, { action: "Next", onAction: () => focusReply() }); else toast("Reply Later is clear."); };
  }
  async function saveSnippet() {
    const html = MC.composing() ? (getSelection().rangeCount && MC.editor().contains(getSelection().anchorNode) ? (() => { const d = el("div"); d.appendChild(getSelection().getRangeAt(0).cloneContents()); return d.innerHTML; })() : "") : "";
    const v = await formDialog.open({ title: "New snippet", wide: true, fields: [{ key: "name", label: "Name", required: true }, { key: "abbr", label: "Abbreviation (type it and press F3)" }, { key: "html", label: "Text — {first_name}, {name}, {email}, {my_name}, {date} are filled in", type: "html", value: html }] });
    if (!v) return;
    const s = await store.get("snippets", []);
    await store.set("snippets", [...s.filter((x) => x.name !== v.name.trim()), { id: "sn-" + Date.now().toString(36), name: v.name.trim(), abbr: v.abbr.trim(), html: v.html }]);
    toast(`Snippet "${v.name.trim()}" saved`);
  }
  async function manageSnippets() {
    const s = await store.get("snippets", []);
    const box = el("div", { class: "qb-msglist" }, el("button", { class: "btn primary", onclick: async () => { $("popupDialog").close(); await saveSnippet(); manageSnippets(); } }, icon("plus"), "New snippet"));
    for (const x of s) box.appendChild(el("div", { class: "qb-msgrow" }, el("div", { class: "grow" }, el("b", { text: x.name }), x.abbr ? el("code", { text: " " + x.abbr }) : null, el("div", { class: "small muted", text: el("div", { html: x.html }).textContent.slice(0, 90) })), el("button", { class: "icon-btn", title: "Delete", onclick: async () => { await store.set("snippets", s.filter((y) => y !== x)); manageSnippets(); } }, icon("trash"))));
    MC.panel("Snippets", box);
  }

  // ================================================================== Claris Emailer, Opera M2
  add("Tools", "Claris Emailer & Opera", "Claris Emailer / Opera M2", [
    { id: "emailer.unifiedInbox", label: "Unified Inbox", icon: "inbox", origin: "Claris Emailer / Apple Mail", tip: "Recent mail from every signed-in account in one list", run: () => unifiedInbox() },
    { id: "opera.mailingLists", label: "Mailing Lists", icon: "people", origin: "Opera M2", tip: "This folder's mail grouped by mailing list", run: () => mailingLists() },
    { id: "opera.attachments", label: "Attachments by Type", icon: "attach", origin: "Opera M2", tip: "Every attachment in this folder's recent mail, grouped by kind", run: async () => { const msgs = state.messages.filter((m) => m.hasAttachments).slice(0, 60); if (!msgs.length) throw new UserError("No messages with attachments in this list."); shelfPanel("Attachments in " + MC.folderName(state.folder), await api("/api/x/shelf", { method: "POST", body: { items: MC.refs(msgs) } })); } },
    { id: "opera.lowBandwidth", label: "Low Bandwidth Mode", icon: "c-low", kind: "toggle", origin: "Opera M2", tip: "Smaller pages, no remote pictures, checks every 5 minutes", isActive: () => P("lowBandwidth", false), run: () => { const on = !P("lowBandwidth", false); prefs.set("lowBandwidth", on); state.pageSize = on ? 25 : 50; if (on) prefs.set("autoRefresh", 300); list.schedulePoll(); toast(on ? "Low bandwidth mode on" : "Low bandwidth mode off"); } },
  ]);
  async function unifiedInbox() {
    const rows = [];
    for (const x of accounts.all()) {
      const tk = x.account.email === state.account.email ? state.token : accounts.token(x.account.email);
      if (!tk) continue;
      try { const r = await api("/api/messages", { query: { folder: "INBOX", limit: 40 }, token: tk }); for (const m of r.messages) rows.push({ ...m, account: x.account.email }); } catch {}
    }
    rows.sort((a, b) => b.date - a.date);
    const box = el("div", { class: "qb-msglist" });
    if (!rows.length) box.appendChild(el("p", { class: "muted", text: "No mail." }));
    for (const m of rows.slice(0, 120)) box.appendChild(el("div", { class: "qb-msgrow" + (m.read ? "" : " unread") }, el("div", { class: "grow" }, el("b", { text: m.subject || "(no subject)" }), el("div", { class: "small muted", text: `${m.fromName || m.fromAddress} · ${fmtDate(m.date)} · ${m.account}` })),
      el("button", { class: "btn sm", onclick: async () => { $("popupDialog").close(); if (m.account !== state.account.email) await session.switchTo(m.account); reading.open({ uid: m.uid, folder: "INBOX", subject: m.subject, fromAddress: m.fromAddress, fromName: m.fromName, date: m.date }); } }, "Open")));
    MC.panel(`Unified Inbox (${accounts.all().length} accounts)`, box);
  }
  async function mailingLists() {
    const groups = await api("/api/x/lists", { query: { folder: state.folder === SCHEDULED ? "INBOX" : state.folder } });
    const box = el("div", { class: "qb-msglist" });
    if (!groups.length) box.appendChild(el("p", { class: "muted", text: "No mailing-list mail in this folder's recent messages." }));
    for (const g of groups) box.appendChild(el("div", { class: "qb-msgrow" }, el("div", { class: "grow" }, el("b", { text: g.name }), el("div", { class: "small muted", text: `${g.listId} · ${plural(g.count, "message")}${g.unread ? `, ${g.unread} unread` : ""} · latest ${fmtDate(g.latest)}` })),
      el("button", { class: "btn sm", onclick: () => { $("popupDialog").close(); state.filter = "pattern:" + g.uids.map((u) => `=i "${(state.messages.find((m) => m.uid === u) || {}).messageId || "-"}"`).join(" | "); list.render(); } }, "Show"),
      el("button", { class: "btn sm", onclick: async () => { const msgs = state.messages.filter((m) => g.uids.includes(m.uid)); if (msgs.length) { await actions.read(msgs, true); toast("Marked as read"); } } }, "Mark read"),
      g.unsubscribe ? el("button", { class: "btn sm", onclick: async () => { const m = state.messages.find((x) => g.uids.includes(x.uid)); if (m) unsubscribe([m]); } }, "Unsubscribe") : null));
    MC.panel(`Mailing lists in ${MC.folderName(state.folder)}`, box);
  }

  // ================================================================== new commands original to this client
  add("Tools", "Insights", ORIGINAL, [
    { id: "new.replyTimeSlots", label: "Reply with Time Slots", icon: "clock", isEnabled: sel, tip: "Replies offering free times from your calendar", keywords: "availability meeting schedule superhuman share availability",
      run: async (ctx) => { const d = await openDetail(ctx); await compose.open({ mode: "reply", detail: d }); MC.editor().focus(); placeCaretAtStart(MC.editor()); document.execCommand("insertHTML", false, slotsHtml()); } },
    { id: "new.insertTimeSlots", label: "Insert Free Time Slots", icon: "clock", ...cmp, run: () => insertHtml(slotsHtml()) },
    { id: "new.threadDigest", label: "Thread Digest", icon: "list", isEnabled: sel, tip: "What each person added, the open questions, requests, dates and links — no AI, quoted text folded away", run: async (ctx) => threadDigest(needOne(ctx)) },
    { id: "new.attachmentShelf", label: "Attachment Shelf", icon: "attach", isEnabled: sel, tip: "Every file shared in this conversation", run: async (ctx) => { const m = needOne(ctx); const msgs = await thread(m); shelfPanel(`Attachments in "${m.subject}"`, await api("/api/x/shelf", { method: "POST", body: { items: MC.refs(msgs) } })); } },
    { id: "new.waiting", label: "Waiting for Reply", icon: "send-later", keywords: "follow-up suggestions apple nudge sent no reply", tip: "Messages you sent that nobody has answered", run: () => waitingPanel() },
    { id: "new.nudges", label: "Questions Waiting for You", icon: "c-question", origin: "Gmail (Nudges)", tip: "Mail to you, with a question, that you have not answered after two days", run: () => nudges() },
    { id: "new.unsubscribeEverywhere", label: "Unsubscribe Everywhere…", icon: "block", tip: "One-click unsubscribes (RFC 8058) from the selected senders, or from every newsletter in this list", run: (ctx) => unsubscribeEverywhere(T(ctx)) },
    { id: "new.senderTimeline", label: "Sender Timeline", icon: "user", isEnabled: (ctx) => !!ctx.arg || sel(ctx), origin: ORIGINAL + " (Opera M2 contact view)", tip: "Every message to and from this person, with first contact and reply times", run: (ctx) => senderTimeline(ctx.arg || needOne(ctx).fromAddress) },
    { id: "new.bigAttachments", label: "Big-Attachment Finder", icon: "c-big", tip: "The largest messages in all folders; open, delete or strip their attachments", run: () => bigAttachments() },
    { id: "new.quoteCheck", label: "Check Quote Integrity", icon: "c-quote", isEnabled: sel, tip: "Compares how this reply quotes you with what you actually wrote", run: (ctx) => quoteCheck(needOne(ctx)) },
    { id: "new.bulkSender", label: "Bulk Act on Sender or Domain…", icon: "people", isEnabled: sel, keywords: "mark all from sender domain delete archive", run: (ctx) => bulkSender(needOne(ctx)) },
    { id: "new.senderRules", label: "Sender Rules", icon: "rules", isEnabled: sel, tip: "Everything that applies to this sender in one place: VIP, Focused, safe/blocked, Screener, pictures, routing", run: (ctx) => senderRules(needOne(ctx)) },
    { id: "new.plainPreview", label: "Preview as Plain Text / Safe HTML", icon: "eye", ...cmp, tip: "Shows what people with plain-text or strict mail programs will see", run: () => plainPreview() },
    { id: "new.headerInspector", label: "Header Inspector (SPF/DKIM/DMARC)", icon: "shield-ok", isEnabled: sel, keywords: "authentication results received hops properties message options", run: async (ctx) => headerInspector(needOne(ctx)) },
    { id: "new.cleanLinksToggle", label: "Clean Tracking Links", icon: "link", kind: "toggle", keywords: "utm fbclid privacy", tip: "Removes utm_*, fbclid and similar tracking parameters from links you open and send", isActive: () => P("cleanLinks", true), run: () => { prefs.set("cleanLinks", !P("cleanLinks", true)); if (state.openDetail) reading.render(state.openDetail); } },
    { id: "new.presend", label: "Check Message Before Sending", icon: "check-circle", ...cmp, keywords: "pre-send check recipients attachment", run: () => presendPanel() },
    { id: "new.presendAuto", label: "Always Check Before Sending", icon: "check-circle", kind: "toggle", isActive: () => P("presendAuto", true), run: () => prefs.set("presendAuto", !P("presendAuto", true)) },
    { id: "new.triage", label: "Triage Mode", icon: "bolt", tip: "One unread message at a time with one-key decisions", run: () => triage() },
    { id: "new.exportMarkdown", label: "Export Conversation as Markdown", icon: "download", isEnabled: sel, run: async (ctx) => { const m = needOne(ctx); const ms = await bodies(await thread(m), 40); saveBlob(safeName(m.subject) + ".md", MailTools.threadMarkdown(m.subject, ms.map((x) => ({ fromName: x.fromName, from: x.from, date: x.date, text: x.text }))), "text/markdown;charset=utf-8"); } },
  ]);
  function slotsHtml() {
    const now = new Date();
    const busy = pim.occurrences(now, addDays(now, 21)).filter((o) => !o.virtual && !o.allDay && (o.ev.showAs || "busy") !== "free").map((o) => ({ start: o.start, end: o.end }));
    const slots = MailTools.freeSlots(busy, { from: new Date(Date.now() + 2 * 3600e3), workStart: Number(P("workStart", 9)) || 9, workEnd: Number(P("workEnd", 17)) || 17, workDays: P("workDays", [1, 2, 3, 4, 5]), duration: Number(P("defaultDuration", 30)) || 30, max: 6 });
    if (!slots.length) throw new UserError("No free time found in your working hours over the next weeks.");
    const tz = VIEW_TZ.replace(/_/g, " ");
    return `<div>Would any of these times work for you?</div><ul>${slots.map((s) => `<li>${esc(fmtDay(s.start, { weekday: "long", month: "long", day: "numeric" }))}, ${esc(fmtTime(s.start))}–${esc(fmtTime(s.end))}</li>`).join("")}</ul><div class="small">(${esc(tz)} time)</div><div><br></div>`;
  }
  async function threadDigest(m) {
    const ms = await bodies(await thread(m), 30);
    const dg = MailTools.threadDigest(ms.map((x) => ({ fromName: x.fromName || x.from, from: x.from, date: x.date, text: x.text })));
    const sec = (title, nodes) => (nodes.length ? el("div", { class: "qb-digest-sec" }, el("h4", { text: title }), ...nodes) : null);
    const node = el("div", { class: "qb-digest" },
      el("p", { class: "small muted", text: `${plural(dg.items.length, "message")} · ${dg.participants.length} ${dg.participants.length === 1 ? "person" : "people"} · ${dg.words} new words (quoted history removed)` }),
      sec("People", [el("div", { text: dg.participants.map((p) => `${p.name} (${p.count})`).join(" · ") })]),
      sec("Open questions", dg.questions.map((q) => el("div", { class: "qb-digest-item" }, el("b", { text: q.from + ": " }), q.q))),
      sec("Requests", dg.asks.map((a) => el("div", { class: "qb-digest-item" }, el("b", { text: a.from + ": " }), a.a))),
      sec("Links", dg.links.map((l) => el("div", {}, el("a", { href: l, target: "_blank", rel: "noopener noreferrer", text: l })))),
      sec("Message by message", dg.items.map((i) => el("details", { class: "qb-digest-item" }, el("summary", {}, el("b", { text: i.from }), ` · ${fmtDate(i.date)} · ${i.words} words${i.dates.length ? " · mentions " + i.dates.join(", ") : ""}`), el("pre", { class: "body-text", text: i.fresh || "(nothing new — only quoted text)" })))));
    MC.panel(`Digest: ${m.subject}`, node);
  }
  function shelfPanel(title, items) {
    const kind = (a) => (/^image\//.test(a.mimeType) ? "Pictures" : /pdf|word|document|text|rtf|opendocument|sheet|excel|presentation|powerpoint|csv/.test(a.mimeType) ? "Documents" : /zip|compressed|tar|rar|7z/.test(a.mimeType) ? "Archives" : /audio/.test(a.mimeType) ? "Audio" : /video/.test(a.mimeType) ? "Video" : /calendar|vcard/.test(a.mimeType) ? "Invitations & cards" : "Other");
    const groups = {};
    for (const a of items) (groups[kind(a)] = groups[kind(a)] || []).push(a);
    const node = el("div", { class: "qb-msglist" });
    if (!items.length) node.appendChild(el("p", { class: "muted", text: "No attachments." }));
    for (const [k, list2] of Object.entries(groups)) {
      node.appendChild(el("h4", { text: `${k} (${list2.length})` }));
      for (const a of list2) node.appendChild(el("div", { class: "qb-msgrow" }, icon(/^image/.test(a.mimeType) ? "image" : "attach"), el("div", { class: "grow" }, el("b", { text: a.fileName }), el("div", { class: "small muted", text: `${fmtSize(a.size)} · ${a.fromAddress} · ${fmtDate(a.date)} · ${a.subject}` })),
        el("button", { class: "btn sm", onclick: () => mailplus.preview({ folder: a.folder, uid: a.uid }, a) }, "Preview"),
        el("button", { class: "btn sm", onclick: () => download(attachmentUrl(a.folder, a.uid, { index: a.index })) }, "Download")));
    }
    if (items.length > 1) node.appendChild(el("button", { class: "btn", onclick: () => items.forEach((a, i) => setTimeout(() => download(attachmentUrl(a.folder, a.uid, { index: a.index })), i * 400)) }, icon("download"), `Download all ${items.length}`));
    MC.panel(title, node);
  }
  async function waitingPanel() {
    const box = el("div", {}, el("p", { class: "muted", text: "Looking through Sent Items…" }));
    MC.panel("Waiting for reply", box);
    const items = await api("/api/x/awaiting", { query: { days: 21 } });
    box.innerHTML = "";
    box.appendChild(el("p", { class: "small muted", text: "Messages you sent in the last three weeks that nobody has replied to (replies are looked for in the Inbox and Archive)." }));
    box.appendChild(MC.messageRows(items.map((x) => ({ ...x, folder: x.folder, fromName: "To: " + x.to.join(", ") })), { empty: "Everyone has replied.", extra: (m) => el("button", { class: "btn sm", onclick: async () => { $("popupDialog").close(); const d = await detail(m); await compose.open({ mode: "reply", detail: d }); $("cTo").value = d.to.join(", "); MC.editor().insertAdjacentHTML("afterbegin", "<div>Just following up on this — any news?</div>"); } }, "Nudge") }));
  }
  async function nudges() {
    const inbox = folders.byRole("inbox");
    const msgs = (state.folder === inbox?.path ? state.messages : (await api("/api/messages", { query: { folder: inbox.path, limit: 200 } })).messages)
      .filter((m) => !m.answered && Date.now() - m.date > 2 * 86400e3 && Date.now() - m.date < 30 * 86400e3 && !MailTools.isNewsletter(m) && !(m.verdict && m.verdict.level !== "CLEAN") && /\?/.test(m.preview || "") && (m.to || []).some((a) => MC.me().has(a.toLowerCase())));
    MC.panel("Questions waiting for you", MC.messageRows(msgs.map((m) => ({ ...m, folder: m.folder || inbox.path })), { empty: "You have answered everything that asked you something." }));
  }
  async function unsubscribeEverywhere(selected) {
    const pool = selected.length ? selected : state.messages.filter((m) => MailTools.isNewsletter(m));
    const bySender = new Map(); for (const m of pool) { const k = (m.fromAddress || "").toLowerCase(); if (!bySender.has(k)) bySender.set(k, m); }
    if (!bySender.size) throw new UserError("Select newsletters, or open a folder with newsletters in it.");
    const box = el("div", {}); const picks = new Set(bySender.keys());
    for (const [k, m] of bySender) { const cb = el("input", { type: "checkbox", checked: true }); cb.onchange = () => (cb.checked ? picks.add(k) : picks.delete(k)); box.appendChild(el("label", { class: "check" }, cb, el("span", { text: `${m.fromName || k} <${k}>` }))); }
    const archive = el("input", { type: "checkbox", checked: true });
    box.appendChild(el("label", { class: "check", style: { marginTop: "10px" } }, archive, el("span", { text: "Also archive their messages in this list" })));
    const v = await formDialog.open({ title: `Unsubscribe from ${plural(bySender.size, "sender")}`, ok: "Unsubscribe", wide: true, fields: [{ type: "info", text: "Uses each list's own unsubscribe mechanism: an automatic one-click request where offered (RFC 8058), otherwise an unsubscribe e-mail, otherwise its web page." }], extra: box });
    if (!v) return;
    const results = await unsubscribe([...picks].map((k) => bySender.get(k)));
    const out = el("div", { class: "qb-msglist" });
    for (const { m, r } of results) out.appendChild(el("div", { class: "qb-msgrow" }, el("div", { class: "grow" }, el("b", { text: m.fromName || m.fromAddress }), el("div", { class: "small " + (r.status === "done" ? "ok" : "muted"), text: r.status === "done" ? `Done (${r.method})` : r.detail })), r.status === "open" && r.url ? el("button", { class: "btn sm", onclick: () => openExternal(r.url) }, "Open page") : null));
    if (archive.checked) { const victims = state.messages.filter((m) => picks.has((m.fromAddress || "").toLowerCase())); if (victims.length) await actions.archive(victims); }
    MC.panel("Unsubscribe results", out);
  }
  async function senderTimeline(addr) {
    addr = String(addr).toLowerCase();
    const node = el("div", {}, el("p", { class: "muted", text: "Searching all folders…" }));
    MC.panel(`Timeline: ${addr}`, node);
    const [from, to] = await Promise.all([MC.searchAll({ from: addr }), MC.searchAll({ to: addr })]);
    const all = new Map(); for (const m of [...from, ...to]) all.set(mkey(m), m);
    const msgs = [...all.values()].sort((a, b) => a.date - b.date);
    const mine = MC.me();
    const inbound = msgs.filter((m) => (m.fromAddress || "").toLowerCase() === addr), outbound = msgs.filter((m) => mine.has((m.fromAddress || "").toLowerCase()));
    const replyTimes = []; let lastIn = null;
    for (const m of msgs) { if ((m.fromAddress || "").toLowerCase() === addr) lastIn = m.date; else if (lastIn && mine.has((m.fromAddress || "").toLowerCase())) { replyTimes.push(m.date - lastIn); lastIn = null; } }
    const avg = replyTimes.length ? replyTimes.reduce((a, b) => a + b, 0) / replyTimes.length : null;
    const human = (ms) => (ms < 3600e3 ? `${Math.round(ms / 6e4)} min` : ms < 86400e3 ? `${(ms / 3600e3).toFixed(1)} h` : `${(ms / 86400e3).toFixed(1)} days`);
    node.innerHTML = "";
    const c = pim.contactByEmail(addr);
    node.append(el("dl", { class: "kv" },
      el("dt", { text: "Contact" }), el("dd", { text: c ? pim.contactName(c) : "not in your contacts" }),
      el("dt", { text: "First contact" }), el("dd", { text: msgs.length ? fmtDate(msgs[0].date, { long: true }) : "—" }),
      el("dt", { text: "Latest" }), el("dd", { text: msgs.length ? fmtDate(msgs[msgs.length - 1].date, { long: true }) : "—" }),
      el("dt", { text: "From them / from you" }), el("dd", { text: `${inbound.length} / ${outbound.length}` }),
      el("dt", { text: "Your average reply time" }), el("dd", { text: avg ? human(avg) : "—" })),
      MC.messageRows([...msgs].reverse()));
  }
  async function bigAttachments() {
    const node = el("div", {}, el("p", { class: "muted", text: "Looking for messages over 1 MB in every folder…" }));
    MC.panel("Big-attachment finder", node);
    const r = await api("/api/x/large", { query: { scope: "all", minKb: 1024, limit: 60, folder: state.folder === SCHEDULED ? "INBOX" : state.folder } });
    node.innerHTML = "";
    const total = r.messages.reduce((n, m) => n + (m.size || 0), 0);
    node.append(el("p", { class: "small muted", text: `${plural(r.messages.length, "message")} over 1 MB, ${fmtSize(total)} in total.` }),
      MC.messageRows(r.messages, { empty: "No messages over 1 MB.", extra: (m) => el("span", { class: "btn-row" },
        el("button", { class: "btn sm", onclick: async (e) => { e.target.disabled = true; try { const x = await api("/api/x/rewrite", { method: "POST", body: { folder: m.folder, uid: m.uid, stripAttachments: true } }); e.target.textContent = `Removed ${x.removed.length}`; } catch (er) { toast(er.message, { danger: true }); e.target.disabled = false; } } }, "Strip attachments"),
        el("button", { class: "btn sm danger", onclick: async (e) => { await actions.delete([m]); e.target.closest(".qb-msgrow").remove(); } }, "Delete")) }));
  }
  async function quoteCheck(m) {
    const d = await detail(m);
    const quoted = MailTools.splitQuoted(d.bodyText || mailplus.bodyText(d)).quoted;
    if (!quoted) throw new UserError("This message does not quote anything.");
    const msgs = await thread(m);
    const mine = MC.me();
    const target = msgs.filter((x) => mine.has((x.fromAddress || "").toLowerCase()) && x.date < d.date && (!d.inReplyTo || x.messageId === d.inReplyTo || true)).sort((a, b) => b.date - a.date)[0];
    if (!target) throw new UserError("Your original message was not found in this conversation (Inbox, Sent Items, Archive).");
    const orig = mailplus.bodyText(await detail(target));
    const r = MailTools.compareQuote(orig, quoted);
    const node = el("div", {},
      el("p", { class: r.ok ? "ok" : "danger", text: r.ok ? (r.trimmed ? `The quote is faithful but shortened: ${plural(r.missing.length, "sentence")} of yours left out.` : "The quote matches what you wrote.") : `${plural(r.altered.length, "quoted sentence")} differ from what you wrote.` }),
      r.altered.length ? el("div", {}, el("h4", { text: "Quoted, but not what you wrote" }), ...r.altered.map((s) => el("div", { class: "qb-diff-add", text: s }))) : null,
      r.missing.length ? el("div", {}, el("h4", { text: "You wrote, but it was left out" }), ...r.missing.map((s) => el("div", { class: "qb-diff-del", text: s }))) : null,
      el("p", { class: "small muted", text: `Compared with your message of ${fmtDate(target.date, { long: true })}.` }));
    MC.panel("Quote integrity", node);
  }
  async function bulkSender(m) {
    const dom = domainOf(m.fromAddress);
    const v = await formDialog.open({ title: "Bulk act on sender or domain", ok: "Apply", fields: [
      { key: "who", label: "Messages from", type: "select", value: "sender", options: [["sender", m.fromAddress], ["domain", "anyone @" + dom]] },
      { key: "where", label: "In", type: "select", value: "folder", options: [["folder", `This folder (${MC.folderName(state.folder)})`], ["all", "All folders"]] },
      { key: "act", label: "Do", type: "select", value: "read", options: [["read", "Mark as read"], ["unread", "Mark as unread"], ["archive", "Archive"], ["delete", "Delete"], ["flag", "Flag"], ["move", "Move to folder…"], ["junk", "Report junk"]] },
    ] });
    if (!v) return;
    const q = v.who === "sender" ? m.fromAddress : "@" + dom;
    let msgs = v.where === "all" ? await MC.searchAll({ from: q }) : (await api("/api/search/advanced", { query: { folder: state.folder, scope: "folder", from: q, limit: 500 } })).messages;
    msgs = msgs.filter((x) => (v.who === "sender" ? (x.fromAddress || "").toLowerCase() === m.fromAddress.toLowerCase() : domainOf(x.fromAddress) === dom || domainOf(x.fromAddress).endsWith("." + dom)));
    if (!msgs.length) throw new UserError("No messages found.");
    if (!confirm(`${v.act[0].toUpperCase() + v.act.slice(1)}: ${plural(msgs.length, "message")}?`)) return;
    const run = { read: () => actions.read(msgs, true), unread: () => actions.read(msgs, false), archive: () => actions.archive(msgs), delete: () => actions.delete(msgs), flag: () => actions.flag(msgs, true), junk: () => actions.junk(msgs, true), move: () => actions.pickFolder(msgs) }[v.act];
    await run(); list.fetch({ silent: true });
  }
  async function senderRules(m) {
    const a = (m.fromAddress || "").toLowerCase(); const dom = domainOf(a);
    await Promise.all([store.get("vip", []), store.get("screener", { enabled: false, approved: [], denied: [] })]);
    const s = state.settings; const f = s.focused || {};
    const row = (title, desc, on, fn) => settingsUI.row(title, desc, settingsUI.toggle(on, async (v) => { try { await fn(v); toast("Saved"); } catch (e) { toast(e.message, { danger: true }); } }));
    const setList = async (key, v) => { const cur = state.settings.spam[key]; await settingsUI.patchSpam({ [key]: v ? [...new Set([...cur, a])] : cur.filter((x) => x !== a) }); };
    const node = el("div", {},
      el("p", { class: "small muted", text: `${m.fromName || a} <${a}> · domain ${dom}` }),
      row("VIP", "Starred everywhere and allowed to notify when VIP-only notifications are on.", E.vips().has(a), async (v) => { const cur = await store.get("vip", []); await store.set("vip", v ? [...new Set([...cur, a])] : cur.filter((x) => x !== a)); list.render(); }),
      row("Always Focused", "", (f.alwaysFocused || []).includes(a), async (v) => { if (v) await mailplus.focusOverride(m, true); else await settingsSave({ ...state.settings, focused: { ...f, alwaysFocused: (f.alwaysFocused || []).filter((x) => x !== a) } }); }),
      row("Always Other", "", (f.alwaysOther || []).includes(a), async (v) => { if (v) await mailplus.focusOverride(m, false); else await settingsSave({ ...state.settings, focused: { ...f, alwaysOther: (f.alwaysOther || []).filter((x) => x !== a) } }); }),
      row("Safe sender", "Never treated as junk.", s.spam.safeSenders.includes(a), (v) => setList("safeSenders", v)),
      row("Blocked sender", "Always treated as junk.", s.spam.blockedSenders.includes(a), (v) => setList("blockedSenders", v)),
      row("Approved in the Screener", "", (E.screener().approved || []).includes(a), async (v) => { const sc = E.screener(); await store.set("screener", { ...sc, approved: v ? [...new Set([...(sc.approved || []), a])] : (sc.approved || []).filter((x) => x !== a) }); list.render(); }),
      row("Show pictures automatically", "", (s.imageSenders || []).includes(a), async (v) => { await settingsSave({ ...state.settings, imageSenders: v ? [...new Set([...(state.settings.imageSenders || []), a])] : (state.settings.imageSenders || []).filter((x) => x !== a) }); }),
      el("div", { class: "btn-row", style: { marginTop: "10px" } },
        el("button", { class: "btn", onclick: () => Commands.run("mail.alwaysMoveFromSender", { items: [m] }) }, icon("move"), "Always move to…"),
        el("button", { class: "btn", onclick: () => Commands.run("new.bulkSender", { items: [m] }) }, icon("people"), "Bulk act…"),
        el("button", { class: "btn", onclick: () => senderTimeline(a) }, icon("user"), "Timeline")));
    MC.panel("Sender rules", node);
  }
  function plainPreview() {
    needCompose();
    const plain = htmlToPlain(MC.editor());
    const safe = sanitizeHtml(MC.editor().innerHTML, { allowImages: false, folder: "", uid: 0 }).html;
    const frame = el("iframe", { class: "body-frame", sandbox: "allow-same-origin", title: "Safe HTML preview", style: { height: "260px", border: "1px solid var(--border)" } });
    frame.srcdoc = safe;
    MC.panel("Preview: plain text and safe HTML", el("div", { class: "qb-preview2" }, el("div", {}, el("h4", { text: "Plain-text mail programs" }), el("pre", { class: "body-text", text: plain })), el("div", {}, el("h4", { text: "Strict HTML (remote pictures blocked)" }), frame),
      el("div", { class: "btn-row" }, el("button", { class: "btn", onclick: () => { $("popupDialog").close(); if (!state.compose.plain) $("cPlainText").click(); } }, "Send as plain text"))));
  }
  async function headerInspector(m) {
    const r = await api("/api/x/inspect", { query: { folder: m.folder || state.folder, uid: m.uid } });
    const badge = (v) => el("span", { class: "badge " + (/^pass/.test(v) ? "ok" : /^(fail|softfail|permerror)/.test(v) ? "danger" : /^(none|neutral|temperror)/.test(v) ? "warn" : "") }, String(v));
    const yes = (b) => (b === null || b === undefined ? "unknown" : b ? "yes" : "no");
    const node = el("div", { class: "qb-inspector" },
      el("dl", { class: "kv" },
        el("dt", { text: "From domain" }), el("dd", { text: r.fromDomain || "—" }),
        el("dt", { text: "Envelope sender" }), el("dd", { text: r.returnPathDomain || "—" }),
        r.replyToDomain ? el("dt", { text: "Reply-To domain" }) : null, r.replyToDomain ? el("dd", { text: r.replyToDomain }) : null,
        el("dt", { text: "DMARC" }), el("dd", {}, badge(r.dmarc)),
        el("dt", { text: "SPF aligned with From" }), el("dd", { text: yes(r.spfAligned) }),
        el("dt", { text: "DKIM aligned with From" }), el("dd", { text: yes(r.dkimAligned) + (r.dkimDomains.length ? ` (signed by ${r.dkimDomains.join(", ")})` : "") }),
        el("dt", { text: "Delivery time" }), el("dd", { text: r.totalDelaySeconds != null ? `${r.totalDelaySeconds} s over ${r.hops.length} hops` : "—" })),
      r.warnings.length ? el("div", { class: "info-bar warn" }, icon("warning"), el("div", {}, ...r.warnings.map((w) => el("div", { text: w })))) : null,
      el("h4", { text: "Authentication results" }),
      el("table", { class: "table" }, el("tr", {}, el("th", { text: "Check" }), el("th", { text: "Result" }), el("th", { text: "Details" }), el("th", { text: "Reported by" })), ...r.checks.map((c) => el("tr", {}, el("td", { text: c.method.toUpperCase() }), el("td", {}, badge(c.result)), el("td", { class: "mono small", text: c.detail }), el("td", { class: "small", text: c.source })))),
      el("h4", { text: "Route (oldest first)" }),
      el("table", { class: "table" }, el("tr", {}, el("th", { text: "From" }), el("th", { text: "By" }), el("th", { text: "IP" }), el("th", { text: "Time" }), el("th", { text: "Delay" })), ...r.hops.map((h) => el("tr", {}, el("td", { class: "small", text: h.from }), el("td", { class: "small", text: h.by }), el("td", { class: "mono small", text: h.ip || "" }), el("td", { class: "small", text: h.at ? new Date(h.at).toLocaleString() : "" }), el("td", { class: "small", text: h.delaySeconds != null ? h.delaySeconds + " s" : "" })))),
      el("details", {}, el("summary", { text: `All headers (${r.headers.length})` }), el("pre", { class: "body-text mono small", text: r.headers.map(([k, v]) => `${k}: ${v}`).join("\n") })));
    MC.panel("Header inspector", node);
  }
  function presendWarnings() {
    const p = compose.payload();
    const names = [...pim.data.contacts.flatMap((c) => (c.emails || []).map((e) => ({ name: pim.contactName(c), email: e.value })))];
    return MailTools.presendChecks({ to: p.to, cc: p.cc, bcc: p.bcc, subject: p.subject, body: p.body, attachments: p.attachments.length + p.attachMessages.length, myDomain: domainOf(state.account.email), names, mood: P("moodOutgoing", true) });
  }
  E.presendWarnings = presendWarnings;
  function presendPanel() {
    needCompose();
    const w = presendWarnings();
    MC.panel("Check message", el("div", {}, w.length ? el("ul", {}, ...w.map((x) => el("li", { class: x.level === "error" ? "danger" : "" }, x.text))) : el("p", { text: "Everything looks fine." })));
  }
  function triage() {
    const queue = list.visible().filter((m) => !m.read);
    if (!queue.length) throw new UserError("No unread messages in this list.");
    let i = 0;
    const box = el("div", { class: "qb-triage" });
    const next = () => { i++; render(); };
    const act = async (fn) => { const m = queue[i]; try { await fn(m); } catch (e) { toast(e.message, { danger: true }); } next(); };
    const btns = [["E", "Archive", (m) => actions.archive([m])], ["Del", "Delete", (m) => actions.delete([m])], ["L", "Reply later", (m) => editFlags([m], ["$ReplyLater"], [])], ["A", "Set aside", (m) => editFlags([m], ["$SetAside"], [])], ["Z", "Snooze until tomorrow", (m) => actions.snooze([m], at(addDays(new Date(), 1), 8).getTime())], ["S", "Flag", (m) => actions.flag([m], true)], ["R", "Reply", async (m) => { $("popupDialog").close(); await compose.open({ mode: "reply", detail: await detail(m) }); }], ["Space", "Keep (mark read)", (m) => actions.read([m], true)]];
    async function render() {
      box.innerHTML = "";
      if (i >= queue.length) { box.appendChild(el("p", { class: "qb-triage-done", text: "Done — everything triaged." })); return; }
      const m = queue[i];
      box.append(el("div", { class: "small muted", text: `${i + 1} of ${queue.length}` }), el("h3", { text: m.subject || "(no subject)" }), el("div", { class: "muted", text: `${m.fromName || m.fromAddress} · ${fmtDate(m.date, { long: true })}` }), el("p", { class: "qb-triage-preview", text: m.preview || "" }),
        el("div", { class: "btn-row" }, ...btns.map(([k, l, fn]) => el("button", { class: "btn", onclick: () => act(fn) }, el("kbd", { text: k }), " " + l))));
    }
    box.tabIndex = 0;
    box.onkeydown = (e) => { const k = e.key === " " ? "Space" : e.key === "Delete" ? "Del" : e.key.toUpperCase(); const b = btns.find((x) => x[0] === k); if (b) { e.preventDefault(); e.stopPropagation(); act(b[2]); } };
    render();
    MC.panel("Triage", box);
    setTimeout(() => box.focus(), 50);
  }

  Commands.def(C);

  // ------------------------------------------------------------------ keyboard schemes
  Commands.schemes.gmail = { J: "mail.nextItem", K: "mail.prevItem", E: "mail.archive", "#": "mail.delete", X: "mail.toggleSelect", S: "mail.flag", "!": "mail.junk", R: "mail.reply", A: "mail.replyAll", F: "mail.forward", C: "mail.newMail", "/": "mail.focusSearch", M: "gmail.mute", "Shift+I": "mail.markRead", "Shift+U": "mail.markUnread", "+": "gmail.important", "=": "gmail.important", B: "mail.snooze", L: "gmail.labels", V: "mail.moveTo", "Shift+T": "notes.copyToTask", Z: "edit.undo", U: "mail.escape", "Shift+N": "mutt.nextUnread", "?": "help.shortcuts" };
  Commands.schemes.mutt = { J: "mail.nextItem", K: "mail.prevItem", D: "pine.markDeleted", U: "pine.undelete", "|": "pine.pipe", B: "pine.bounce", T: "mail.toggleSelect", "Shift+T": "pine.tagPattern", ";": "pine.applyToTagged", L: "pine.limit", "Shift+L": "mutt.listReply", Z: "pine.limit", O: "view.arrangeBy", "$": "folder.compact", X: "folder.compact", Tab: "mutt.nextUnread", N: "mutt.toggleNew", R: "mail.reply", G: "mail.replyAll", F: "mail.forward", "Shift+F": "mail.flag", M: "mail.newMail", C: "folder.goTo", S: "mail.moveOther", "Shift+C": "mail.copyTo", H: "pine.headerMode", V: "new.attachmentShelf", "Shift+J": "pine.jump", "Ctrl+R": "mutt.readThread", "Ctrl+D": "mutt.deleteThread", W: "mail.focusSearch", "/": "mail.focusSearch", "?": "help.shortcuts", E: "mail.editAsNew" };
})();

// ============================================================================ behaviour hooks
mailExtras.install = function install() {
  if (this.installed) return; this.installed = true;
  const E = this;
  const P = (k, d) => prefs.get(k, d);

  // ---- list filters: patterns, split inbox, VIP, tags, screener, quick filter, bundles
  const origMatch = list.matchesFilter.bind(list);
  const split = (m, k) => {
    const nl = MailTools.isNewsletter(m), rc = MailTools.isReceipt(m);
    const notif = /^(notifications?|alerts?|no-?reply|donotreply|do-not-reply|mailer-daemon|security|account|support|info)[@.+-]/i.test(m.fromAddress || "") && !nl;
    return { vip: E.vips().has((m.fromAddress || "").toLowerCase()), people: !nl && !notif && !rc, newsletters: nl, notifications: notif, receipts: rc }[k];
  };
  list.matchesFilter = function (m, f = state.filter) {
    if (state.folderRole === "inbox" && !state.search && f !== "x:screener") {
      if (E.screener().enabled && E.isUnscreened(m)) return false;
      if (P("bundleNews", false) && f === "all" && MailTools.isNewsletter(m)) return false;
    }
    const q = E.qfilter;
    if (q) {
      if (q.unread && m.read) return false;
      if (q.flagged && !m.flagged) return false;
      if (q.attach && !m.hasAttachments) return false;
      if (q.contact && !pim.contactByEmail(m.fromAddress)) return false;
      if (q.tags && !(m.keywords || []).some((k) => E.tag(k))) return false;
      if (q.text) { const t = q.text.toLowerCase(); if (![m.fromName, m.fromAddress, ...(m.to || []), m.subject, m.preview].some((x) => String(x || "").toLowerCase().includes(t))) return false; }
    }
    if (typeof f === "string") {
      if (f.startsWith("pattern:")) { try { const fn = E._pat && E._pat.src === f ? E._pat.fn : (E._pat = { src: f, fn: MailTools.compilePattern(f.slice(8)) }).fn; return fn(m, { me: list.myAddresses(), tagged: state.selected, key: mkey }); } catch { return true; } }
      if (f.startsWith("split:")) return split(m, f.slice(6));
      if (f === "x:vip") return E.vips().has((m.fromAddress || "").toLowerCase());
      if (f === "x:screener") return E.isUnscreened(m);
      if (f === "x:week") return Date.now() - m.date < 7 * 86400e3;
      if (f === "x:important") return m.importance === "high" || (m.keywords || []).some((k) => /^\$important$/i.test(k));
      if (f === "x:categorized") return (m.categories || []).length > 0;
      if (f === "x:toMeOnly") { const me = list.myAddresses(); return (m.to || []).length === 1 && me.has(String(m.to[0]).toLowerCase()); }
      if (f === "x:tagged") return (m.keywords || []).some((k) => E.tag(k));
      if (f.startsWith("x:kw:")) return (m.keywords || []).some((k) => k.toLowerCase() === f.slice(5).toLowerCase());
    }
    return origMatch(m, f);
  };

  // ---- Split Inbox replaces Focused / Other while it is on
  const origFocused = list.focusedActive.bind(list);
  list.focusedActive = function () { return P("splitInbox", false) ? false : origFocused(); };

  // ---- extra sort keys (To, Attachments)
  const origSorted = list.sorted.bind(list);
  list.sorted = function (msgs) {
    const s = P("sort", { by: "date", dir: "desc" });
    if (s.by !== "to" && s.by !== "attachments") return origSorted(msgs);
    const k = s.by === "to" ? (m) => String((m.to || [])[0] || "").toLowerCase() : (m) => (m.hasAttachments ? 1 : 0);
    const sign = s.dir === "asc" ? 1 : -1;
    return [...msgs].sort((a, b) => { const x = k(a), y = k(b); return (x < y ? -1 : x > y ? 1 : 0) * sign || b.date - a.date; });
  };

  // ---- list rows: tags, VIP star, watched threads, marked-deleted, special keywords
  const origRow = list.row.bind(list);
  list.row = function (m, opts) {
    const div = origRow(m, opts);
    if (m.scheduled) return div;
    try {
      const line = div.querySelector(".msg-line:nth-child(2)") || div.querySelector(".msg-main");
      const chips = [];
      for (const k of m.keywords || []) {
        const t = E.tag(k);
        if (t) chips.push(el("span", { class: "qb-tag", style: { background: t.color }, title: `Tag: ${t.name}` }, t.name));
        else if (SPECIAL_KEYWORDS[k]) chips.push(el("span", { class: "qb-kw", title: SPECIAL_KEYWORDS[k] }, k === "$Important" ? "❗" : SPECIAL_KEYWORDS[k]));
      }
      if (E.vips().has((m.fromAddress || "").toLowerCase())) div.querySelector(".msg-from")?.prepend(el("span", { class: "qb-vip", title: "VIP" }, "★ "));
      if (E.watched().length && E.isWatched(m)) { div.classList.add("qb-watched"); chips.push(el("span", { class: "qb-kw", title: "Watched conversation" }, "👁")); }
      if ((m.keywords || []).includes("\\Deleted")) div.classList.add("qb-deleted");
      if (chips.length && line) line.append(...chips);
    } catch {}
    return div;
  };

  // ---- the bar above the list: split inbox tabs, quick filter, screener and bundle notices
  const origRender = list.render.bind(list);
  list.render = function () {
    origRender();
    try { E.renderBar(); } catch (e) { console.error(e); }
  };
  E.renderBar = function () {
    let bar = $("qbListBar");
    if (!bar) { bar = el("div", { id: "qbListBar", class: "qb-listbar" }); $("messageList").before(bar); }
    bar.innerHTML = "";
    const inbox = state.folderRole === "inbox" && !state.search;
    if (P("splitInbox", false) && inbox) {
      const tabs = [["all", "All"], ["split:vip", "VIP"], ["split:people", "People"], ["split:newsletters", "Newsletters"], ["split:notifications", "Notifications"], ["split:receipts", "Receipts"]];
      const all = state.messages;
      bar.appendChild(el("div", { class: "qb-split" }, ...tabs.map(([f, l]) => { const n = f === "all" ? 0 : all.filter((m) => !m.read && split(m, f.slice(6))).length; return el("button", { class: state.filter === f ? "active" : "", onclick: () => { state.filter = f; state.selected.clear(); list.render(); } }, l, n ? el("span", { class: "n", text: String(n) }) : null); })));
    }
    if (E.qfilter) {
      const q = E.qfilter;
      const t = (k, l) => el("button", { class: "qb-chip" + (q[k] ? " on" : ""), onclick: () => { q[k] = !q[k]; list.render(); } }, l);
      const input = el("input", { id: "qbQuickText", type: "search", placeholder: "Filter these messages (Ctrl+Alt+K)", value: q.text });
      input.oninput = debounce(() => { q.text = input.value; const pos = input.selectionStart; list.render(); const ni = $("qbQuickText"); if (ni) { ni.focus(); ni.setSelectionRange(pos, pos); } }, 200);
      bar.appendChild(el("div", { class: "qb-quickfilter" }, t("unread", "Unread"), t("flagged", "Starred"), t("contact", "Contact"), t("tags", "Tags"), t("attach", "Attachment"), input, el("button", { class: "icon-btn", title: "Close the quick filter", onclick: () => { E.qfilter = null; list.render(); } }, icon("close"))));
    }
    if (inbox && E.screener().enabled) { const n = new Set(E.unscreened().map((m) => m.fromAddress)).size; if (n) bar.appendChild(el("div", { class: "qb-notice" }, icon("c-screener", 14), el("span", { class: "grow", text: `${plural(n, "first-time sender")} waiting in the Screener` }), el("button", { class: "btn sm", onclick: () => Commands.run("hey.screener") }, "Screen them"))); }
    if (inbox && P("bundleNews", false) && state.filter === "all") { const n = state.messages.filter((m) => MailTools.isNewsletter(m)).length; if (n) bar.appendChild(el("div", { class: "qb-notice" }, icon("c-bundle", 14), el("span", { class: "grow", text: `${plural(n, "newsletter")} bundled` }), el("button", { class: "btn sm", onclick: () => { state.filter = "split:newsletters"; list.render(); } }, "Open bundle"), el("button", { class: "btn sm", onclick: async () => { const ms = state.messages.filter((m) => MailTools.isNewsletter(m) && !m.read); if (ms.length) await actions.read(ms, true); } }, "Mark all read"))); }
    bar.classList.toggle("hidden", !bar.childElementCount);
  };

  // ---- reading pane: trackers, MoodWatch, tags, full headers, body-as, watched notice
  const origReadRender = reading.render.bind(reading);
  reading.render = function (d) {
    const mode = P("bodyAs", "html");
    let d2 = d;
    if (mode === "plain" && d.bodyHtml) d2 = { ...d, bodyHtml: null, bodyText: d.bodyText || mailplus.bodyText(d) };
    else if (mode === "simple" && d.bodyHtml) d2 = { ...d, bodyHtml: d.bodyHtml.replace(/<style[\s\S]*?<\/style>/gi, "").replace(/\s(style|class|bgcolor|color|face|background)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, "").replace(/<\/?(font|center|table|tbody|tr|td|th)\b[^>]*>/gi, " ") };
    origReadRender(d2);
    try {
      const info = $("rdInfo");
      const trackers = d.bodyHtml && P("blockTrackers", true) ? MailTools.findTrackers(d.bodyHtml) : [];
      if (trackers.length) info.appendChild(el("div", { class: "info-bar" }, icon("shield-ok"), el("span", { class: "grow", text: `${plural(trackers.length, "tracking pixel")} blocked (${[...new Set(trackers.map((t) => t.host))].slice(0, 3).join(", ")}) — the sender cannot see that you opened this.` })));
      if (P("moodIncoming", true)) { const s = MailTools.moodScore(d.bodyText || mailplus.bodyText(d)); if (s.peppers) $("rdBadges").appendChild(el("span", { class: "badge warn", title: "MoodWatch: " + s.hits.join(", ") }, "🌶".repeat(s.peppers))); }
      const m = state.messages.find((x) => mkey(x) === mkey(d)) || d;
      for (const k of m.keywords || []) { const t = E.tag(k); if (t) $("rdCats").appendChild(el("span", { class: "cat-chip", style: { background: t.color }, title: "Tag (click to remove)", onclick: () => E.editFlags([m], [], [t.key]) }, t.name)); }
      if (E.vips().has((d.fromAddress || "").toLowerCase())) $("rdBadges").appendChild(el("span", { class: "badge info" }, "★ VIP"));
      if ((m.keywords || []).includes("\\Deleted")) info.appendChild(el("div", { class: "info-bar warn" }, icon("trash"), el("span", { class: "grow", text: "Marked for deletion — Compact Folder removes it." }), el("button", { class: "btn", onclick: () => Commands.run("pine.undelete", { items: [m] }) }, "Undelete")));
      if (mode !== "html") info.appendChild(el("div", { class: "info-bar" }, icon("note"), el("span", { class: "grow", text: `Showing the message as ${mode === "plain" ? "plain text" : "simple HTML"}.` }), el("button", { class: "btn", onclick: () => { prefs.set("bodyAs", "html"); reading.render(d); } }, "Original HTML")));
      if (P("fullHeaders", false)) {
        const det = el("details", { class: "qb-headers", open: true }, el("summary", { text: "All headers" }), el("pre", { class: "body-text mono small", text: "Loading…" }));
        info.appendChild(det);
        api("/api/x/inspect", { query: { folder: d.folder, uid: d.uid } }).then((r) => { det.querySelector("pre").textContent = r.headers.map(([k, v]) => `${k}: ${v}`).join("\n"); }).catch((e) => { det.querySelector("pre").textContent = e.message; });
      }
    } catch (e) { console.error(e); }
  };

  // ---- message history (Back / Forward)
  const origOpen = reading.open.bind(reading);
  reading.open = function (m, opts) {
    if (!E.navigating && !m.scheduled) {
      const k = mkey({ folder: m.folder || state.folder, uid: m.uid });
      if (E.history[E.historyPos] && mkey(E.history[E.historyPos]) === k) {} else { E.history = E.history.slice(0, E.historyPos + 1); E.history.push({ ...m, folder: m.folder || state.folder }); if (E.history.length > 100) E.history.shift(); E.historyPos = E.history.length - 1; }
    }
    return origOpen(m, opts);
  };
  E.go = async function (d) {
    const i = this.historyPos + d; const m = this.history[i]; if (!m) return;
    this.historyPos = i; this.navigating = true;
    try { await MC.openMessage(m); } finally { this.navigating = false; }
  };

  // ---- privacy: block tracking pixels even when pictures are allowed, clean tracking parameters, low bandwidth
  const origSanitize = window.sanitizeHtml;
  if (typeof origSanitize === "function") {
    window.sanitizeHtml = function (html, opts) {
      let h = String(html || "");
      if (opts && opts.allowImages && P("blockTrackers", true)) for (const t of MailTools.findTrackers(h)) h = h.split(t.src).join("data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
      if (P("cleanLinks", true)) h = h.replace(/(<a\b[^>]*\shref\s*=\s*)(["'])(https?:[^"']+)\2/gi, (all, pre, q, url) => pre + q + MailTools.cleanUrl(url.replace(/&amp;/g, "&")).replace(/&/g, "&amp;") + q);
      return origSanitize(h, P("lowBandwidth", false) ? { ...opts, allowImages: false } : opts);
    };
  }

  // ---- compose: extra headers, copy to self, clean links, pre-send checks, offline queue, reminders
  const origPayload = compose.payload.bind(compose);
  compose.payload = function () {
    const p = origPayload();
    const c = state.compose || {};
    if (c.extraHeaders && Object.keys(c.extraHeaders).length) p.extraHeaders = { ...c.extraHeaders };
    if (c.copySelf && !p.bcc.some((a) => emailOf(a).toLowerCase() === state.account.email.toLowerCase())) p.bcc = [...p.bcc, state.account.email];
    if (P("cleanLinks", true) && p.html) p.html = p.html.replace(/(<a\b[^>]*\shref\s*=\s*)(["'])(https?:[^"']+)\2/gi, (all, pre, q, url) => pre + q + MailTools.cleanUrl(url.replace(/&amp;/g, "&")).replace(/&/g, "&amp;") + q);
    return p;
  };
  const origSend = compose.send.bind(compose);
  compose.send = async function (opts = {}) {
    if (!state.compose) return;
    if (P("presendAuto", true) && !opts.skipChecks) {
      try {
        const w = E.presendWarnings().filter((x) => x.level !== "info" && !/no recipients|mention an attachment|subject is empty/i.test(x.text));
        if (w.length && !confirm("Before you send:\n\n• " + w.map((x) => x.text).join("\n• ") + "\n\nSend anyway?")) return;
      } catch {}
    }
    if (P("workOffline", false) && !opts.sendAt) { opts = { ...opts, sendAt: Date.now() + 365 * 86400e3 }; toast("Working offline: the message waits in the outbox until you choose Send All."); }
    const remind = state.compose.remindNoReply, subject = $("cSubject").value, to = $("cTo").value;
    const after = E.afterSend; E.afterSend = null;
    const r = await origSend(opts);
    if (!state.compose) {
      if (remind) { const list2 = await MC.store.get("noreply", []); await MC.store.set("noreply", [...list2, { subject, to, due: Date.now() + remind * 86400e3, at: Date.now() }].slice(-200)); }
      if (after) after();
    }
    return r;
  };

  // ---- new-mail notifications: VIP-only, watched threads
  const origNewMail = mailplus.newMailCheck.bind(mailplus);
  mailplus.newMailCheck = function (messages) {
    const before = state.lastInboxUids ? new Set(state.lastInboxUids) : null;
    if (P("vipNotifyOnly", false) && before) {
      const vip = E.vips();
      const fresh = messages.filter((m) => !before.has(m.uid) && !m.read);
      if (fresh.length && !fresh.some((m) => vip.has((m.fromAddress || "").toLowerCase()))) { state.lastInboxUids = new Set(messages.map((m) => m.uid)); return; }
    }
    origNewMail(messages);
    if (before && E.watched().length) for (const m of messages) if (!before.has(m.uid) && !m.read && E.isWatched(m)) notify("Reply in a watched conversation", `${m.fromName || m.fromAddress}: ${m.subject}`, () => { shell.setModule("mail"); reading.open(m); });
  };

  // ---- folder tree: A–Z order and search folders
  const origFolderRender = folders.render.bind(folders);
  folders.render = function () {
    if (P("foldersAZ", false)) {
      const saved = state.folders;
      state.folders = [...saved].sort((a, b) => (a.role === "inbox" ? -1 : b.role === "inbox" ? 1 : a.path.localeCompare(b.path, undefined, { sensitivity: "base" })));
      try { origFolderRender(); } finally { state.folders = saved; }
    } else origFolderRender();
    try {
      const searches = E.savedSearches();
      if (!searches.length) return;
      const host = $("folderTree");
      const ul = el("ul", { class: "folder-list" }, ...searches.map((s) => { const li = el("li", { class: "other" + (state.search && state.searchOpts && state.searchOpts.savedId === s.id ? " active" : ""), title: "Search folder" }, icon("filter", 18), el("span", { class: "name", text: s.name })); li.onclick = () => { shell.setModule("mail", { silent: true }); $("searchInput").value = s.opts.text || ""; list.search(s.opts.text || "", { opts: { ...s.opts, savedId: s.id } }).then(() => { $("folderTitle").textContent = s.name; }); }; li.oncontextmenu = (e) => { e.preventDefault(); menu.openAt(e.clientX, e.clientY, [{ label: "Delete search folder", icon: "trash", danger: true, onClick: async () => { await MC.store.set("searches", E.savedSearches().filter((x) => x.id !== s.id)); folders.render(); } }]); }; return li; }));
      const head = el("div", { class: "tree-head" }, icon("chev-down", 14), el("span", { class: "name", text: "Search Folders" }));
      head.querySelector("svg").classList.add("chev");
      const addBtn = host.querySelector(".add-account");
      host.insertBefore(head, addBtn); host.insertBefore(el("div", { class: "tree-sub" }, ul), addBtn);
    } catch (e) { console.error(e); }
  };

  // ---- context menu: commands from other programs and new ones
  const origMenu = mailplus.messageMenu.bind(mailplus);
  mailplus.messageMenu = function (items, m, opts = {}) {
    const base = origMenu(items, m, opts);
    const ctx = { items };
    const cmd = (id, label) => { const c = Commands.get(id); return c && Commands.enabled(id, ctx) ? { label: label || c.label, icon: c.icon, onClick: () => Commands.run(id, ctx), sub: c.kind === "menu" && c.menu ? () => c.menu(ctx) : undefined } : null; };
    const one = items.length === 1;
    return [...base, { sep: true },
      { label: "Tag", icon: "tag", sub: () => Commands.get("tb.tag").menu(ctx) },
      { label: "More actions", icon: "more", sub: () => [
        cmd("mail.markComplete"), cmd("mail.addReminder"), cmd("apple.remindMe"), cmd("hey.replyLater"), cmd("hey.setAside"), cmd("gmail.important"), cmd("apple.vip"), cmd("tb.watchThread"), one ? cmd("gmail.mute") : null, cmd("mail.alwaysMoveConversation"),
        { sep: true }, cmd("mail.replyMeeting"), cmd("mail.editAsNew"), cmd("eudora.redirect"), cmd("pine.bounce"), cmd("eudora.sendAgain"), cmd("new.replyTimeSlots"), cmd("sh.instantIntro"), cmd("mutt.listReply"),
        { sep: true }, cmd("tb.archiveYear"), cmd("tb.archiveMonth"), cmd("eudora.changeStatus"), cmd("pine.markDeleted"), cmd("pine.undelete"), cmd("tb.stripAttachments"), cmd("mail.editMessage"),
        { sep: true }, cmd("notes.copyToTask"), cmd("notes.copyToCalendar"), cmd("eudora.makeNickname"), cmd("pine.takeAddr"), cmd("eudora.filterMessages"), cmd("mail.findRelatedConversation"), cmd("mail.findRelatedSender"),
      ].filter(Boolean) },
      { label: "Insights", icon: "sparkle", sub: () => [cmd("new.threadDigest"), cmd("new.attachmentShelf"), cmd("notes.whoIsIn"), cmd("new.senderTimeline"), cmd("new.senderRules"), cmd("new.headerInspector"), cmd("new.quoteCheck"), cmd("eudora.moodWatch"), cmd("pine.pipe"), cmd("new.exportMarkdown"), cmd("new.bulkSender"), cmd("mail.unsubscribe"), cmd("file.saveAsText")].filter(Boolean) },
    ];
  };

  // ---- message reminders (Add Reminder, Remind Me, Follow Up) and "remind me if no reply"
  const fired = () => { try { return new Set(JSON.parse(localStorage.getItem("qb.firedReminders") || "[]")); } catch { return new Set(); } };
  const markFired = (k) => { const s = fired(); s.add(k); try { localStorage.setItem("qb.firedReminders", JSON.stringify([...s].slice(-500))); } catch {} };
  E.checkReminders = async function () {
    if (!state.token) return;
    try {
      const meta = await api("/api/meta");
      const now = Date.now();
      for (const [id, x] of Object.entries(meta || {})) {
        if (!x.reminder || x.reminder > now || now - x.reminder > 7 * 86400e3) continue;
        const k = `${state.account.email}|${id}|${x.reminder}`;
        if (fired().has(k)) continue;
        markFired(k);
        const m = state.messages.find((y) => y.messageId === id);
        if (m) { actions.read([m], false).catch(() => {}); actions.pin([m], true).catch(() => {}); }
        notify("Reminder", `${x.subject || "Message"}${x.from ? " — " + x.from : ""}`, () => { if (m) { shell.setModule("mail"); reading.open(m); } });
        toast(`Reminder: ${x.subject || "a message"}`, m ? { action: "Open", onAction: () => reading.open(m), timeout: 15000 } : { timeout: 15000 });
      }
      const pending = await MC.store.get("noreply", []);
      const due = pending.filter((p) => p.due <= now);
      if (due.length) {
        const waiting = await api("/api/x/awaiting", { query: { days: 30 } });
        for (const p of due) {
          const still = waiting.find((w) => w.subject === p.subject);
          if (still) toast(`No reply yet: "${p.subject}"`, { action: "Follow up", onAction: () => Commands.run("new.waiting"), timeout: 20000 });
        }
        await MC.store.set("noreply", pending.filter((p) => p.due > now));
      }
    } catch {}
  };
  setInterval(() => E.checkReminders(), 60000);
  setTimeout(() => E.checkReminders(), 8000);

  // ---- per-account state the hooks read synchronously
  E.loadState = async function () {
    MC.store.cache = {};
    await Promise.all(["tags", "vip", "screener", "watch", "searches", "snippets"].map((n) => MC.store.get(n, n === "screener" ? { enabled: false, approved: [], denied: [] } : [])));
    folders.render(); list.render();
  };
  const origStart = app.start.bind(app);
  app.start = async function () { const r = await origStart(); E.loadState(); return r; };
  if (state.token) E.loadState();
};

// Outlook "New Search Folder" (Thunderbird Saved Search, Apple Smart Mailbox): kept here because it uses the state store.
Commands.def({ id: "folder.newSearchFolder", label: "New Search Folder…", icon: "filter", tab: "Folder", group: "New", keywords: "saved search smart mailbox virtual folder thunderbird apple", tip: "A folder that shows the results of a saved search across all folders",
  run: async () => {
    const v = await formDialog.open({ title: "New search folder", ok: "Create", fields: [
      { key: "name", label: "Name", required: true, value: "Unread mail" },
      { key: "preset", label: "Start from", type: "select", value: "unread", options: [["unread", "Unread mail"], ["flagged", "Mail flagged for follow up"], ["attach", "Mail with attachments"], ["from", "Mail from specific people"], ["words", "Mail with specific words"], ["recent", "Received in the last 7 days"]] },
      { key: "value", label: "People or words (for those choices)" },
    ] });
    if (!v) return;
    const opts = { scope: "all" };
    if (v.preset === "unread") opts.unread = true;
    if (v.preset === "flagged") opts.flagged = true;
    if (v.preset === "attach") opts.hasAttachments = true;
    if (v.preset === "from") opts.from = v.value.trim();
    if (v.preset === "words") opts.text = v.value.trim();
    if (v.preset === "recent") opts.since = Date.now() - 7 * 86400e3;
    if ((v.preset === "from" || v.preset === "words") && !v.value.trim()) throw new MC.UserError("Enter the people or words to look for.");
    const cur = await MC.store.get("searches", []);
    await MC.store.set("searches", [...cur, { id: "ss-" + Date.now().toString(36), name: v.name.trim(), opts }]);
    folders.render();
    toast(`Search folder "${v.name.trim()}" created under Search Folders`);
  } });
