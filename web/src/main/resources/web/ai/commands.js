/* QuillPost AI — the AI tab: email commands, their ribbon buttons, the compose window's AI bar,
 * the reading pane's AI menu, the pane chat ("Ask about this mailbox"), the Prompt library and
 * start-up. Loaded last, after app.js and the other ai/*.js files.
 *
 * Nothing is sent until a command runs. Cloud providers show a one-time notice of where the text
 * goes (and, if chosen in AI settings, the exact text before every request); every result has a
 * "What was sent" section. Text that changes a draft is previewed and applied only with an explicit
 * Insert/Replace, as one undoable step; tasks, events, contacts and categories are added only for
 * the items ticked in the preview, with Undo.
 *
 * Every command is also listed in window.QB_AI_COMMANDS ({id, label, icon, group, tip, run}) so a
 * command registry or Quick Access Bar can pick them up. Menu commands add type "menu" and
 * items() ({id, label, checked, run}); toggles add type "toggle" and checked().
 */
(function () {
  "use strict";
  const C = window.QbAiClient, S = window.QbAiStore, P = window.QbAiProviders, A = window.QbAiApply, PR = window.QbAiPrompts;
  const qbAi = window.qbAi;

  // ================================================================== plumbing
  /** The text budget: smaller when only on-device models will read it. */
  const limit = () => {
    const plan = C.ensemblePlan();
    if (plan) return plan.members.every((m) => m.serial) ? 3500 : 24000;
    return P.isOnDevice(S.activeProviderDef()) ? 3500 : 24000;
  };
  const language = () => S.settings().language || "auto";

  function anySignal(signals) {
    const ctl = new AbortController();
    for (const s of signals) {
      if (!s) continue;
      if (s.aborted) { ctl.abort(); break; }
      s.addEventListener("abort", () => ctl.abort(), { once: true });
    }
    return ctl.signal;
  }

  const isAbort = (e) => e && e.code === "aborted";
  const errText = (e) => String((e && e.message) || e);
  function fail(e) {
    if (isAbort(e)) { toast("Stopped"); return; }
    if (e && (e.code === "auth" || e.code === "no-provider" || e.code === "model")) toast(errText(e), { danger: true, action: "Fix", onAction: () => qbAi.providers(), timeout: 10000 });
    else toast(errText(e), { danger: true, timeout: 10000 });
    console.warn("AI:", errText(e));
  }

  /** Makes sure AI is on and a provider is set up (offering the wizard). */
  async function ensureReady() {
    const s = S.settings();
    if (!s.enabled) { toast("AI features are turned off.", { action: "AI settings", onAction: () => qbAi.settings() }); return false; }
    if (!(s.active && (await S.resolveProvider()))) {
      return qbAi.setup({ reason: "This command needs an AI provider. Choose one below — free options, including one that runs on this device, are at the top. Nothing has been sent." });
    }
    return true;
  }

  /** Where a request to one provider goes: {providerName, model, endpoint, route}; never the key. */
  async function serviceInfo(id, model) {
    const rp = await S.resolveProvider(id);
    if (!rp) return null;
    const m = model || rp.cfg.model || rp.def.models.quality;
    if (P.isOnDevice(rp.def)) return { id: rp.def.id, def: rp.def, providerName: rp.def.name, model: m, route: "device" };
    const url = C.buildChatRequest({ ...rp, key: "" }, m, { messages: [] }, { stream: true }).url;
    return { id: rp.def.id, def: rp.def, providerName: rp.def.name, model: m, endpoint: url, route: C.routeFor(rp.def, url) === "proxy" ? "proxy" : "direct" };
  }

  /** Every service a normal request is sent to: the ensemble's members and master/clerk, else the active provider. */
  async function servicesFor() {
    const plan = C.ensemblePlan();
    if (!plan) return null;
    const out = [];
    for (const m of plan.members) { const x = await serviceInfo(m.provider, m.model); if (x) out.push({ ...x, role: m.provider === plan.master.provider ? (plan.mode === "consensus" ? "member and clerk" : "member and master") : "member" }); }
    if (!plan.members.some((m) => m.provider === plan.master.provider)) { const x = await serviceInfo(plan.master.provider, plan.master.model); if (x) out.push({ ...x, role: plan.mode === "consensus" ? "clerk" : "master" }); }
    return { plan, services: out };
  }

  /** Where a request would go, for "What was sent" (computed before sending; never includes the key). */
  async function sentInfo(messages, tier) {
    const ens = await servicesFor();
    if (ens) return { messages, ensembleMode: ens.plan.mode, services: ens.services.map(({ def, id, ...x }) => x), providerName: `${ens.services.length} AIs`, route: "ensemble" };
    const rp = await S.resolveProvider();
    if (!rp) return { messages };
    const model = tier === "fast" ? rp.cfg.fastModel || rp.cfg.model : rp.cfg.model;
    if (P.isOnDevice(rp.def)) return { messages, providerName: rp.def.name, model, route: "device" };
    const url = C.buildChatRequest({ ...rp, key: "" }, model || rp.def.models.quality, { messages }, { stream: true }).url;
    return { messages, providerName: rp.def.name, model, endpoint: url, route: C.routeFor(rp.def, url) === "proxy" ? "proxy" : "direct" };
  }

  /**
   * Cloud providers: the first time, say where data goes; if "ask before every request" is on,
   * show the exact text. Resolves false when the user cancels (nothing is sent).
   */
  async function confirmSend(messages, tier) {
    const s = S.settings();
    const ens = await servicesFor();
    if (ens) return confirmSendEnsemble(messages, ens);
    const p = S.activeProviderDef();
    if (!p || P.isOnDevice(p)) return true;
    const first = !s.privacyAck.includes(p.id);
    if (!first && !s.confirmEachSend) return true;
    const info = await sentInfo(messages, tier);
    const details = qbAi.sentDetails(info);
    if (details && s.confirmEachSend) details.open = true;
    const askEach = el("input", { type: "checkbox" });
    askEach.checked = !!s.confirmEachSend;
    const body = el("div", {},
      el("p", { text: first
        ? `AI commands send the text they need from this device to ${p.name} (${qbAi.hostOf(info.endpoint)}). QuillPost keeps no copy and has no AI server of its own. ${p.name}'s terms and privacy policy apply${p.free === "free" ? ", and free tiers may use what you send to improve their models" : ""}. For confidential mail, use the on-device model or a local server.`
        : `This request goes to ${p.name}. Check what will be sent:` }),
      details,
      el("label", { class: "check" }, askEach, el("span", { text: "Show what will be sent and ask before every request" })));
    const ok = await qbAi.dialog({ title: "Send to an AI service?", body, wide: true, buttons: [{ label: "Cancel", value: false }, { label: "Send", value: true, primary: true }] });
    if (ok) S.saveSettings((x) => { if (!x.privacyAck.includes(p.id)) x.privacyAck.push(p.id); x.confirmEachSend = askEach.checked; });
    return !!ok;
  }

  /**
   * Several AIs: the same notice, listing every service that will receive the text (members and
   * the master or clerk). Shown until each cloud service has been accepted once, or every time
   * when "ask before every request" is on.
   */
  async function confirmSendEnsemble(messages, ens) {
    const s = S.settings();
    const cloud = ens.services.filter((x) => !P.isOnDevice(x.def));
    if (!cloud.length) return true;
    const first = cloud.some((x) => !s.privacyAck.includes(x.id));
    if (!first && !s.confirmEachSend) return true;
    const details = qbAi.sentDetails(await sentInfo(messages));
    if (details && s.confirmEachSend) details.open = true;
    const askEach = el("input", { type: "checkbox" });
    askEach.checked = !!s.confirmEachSend;
    const mode = ens.plan.mode === "consensus" ? "The AIs deliberate until they agree (Quaker consensus): in the discussion each one also receives the others' answers." : "One AI combines the answers: the master also receives the others' answers.";
    const body = el("div", {},
      el("p", { text: `Several AIs are set up for each request (AI settings ▸ Use several AIs). The text this command needs goes from this device to each of these services:` }),
      el("ul", { class: "ai-svc-list" }, ...ens.services.map((x) => el("li", {}, el("b", { text: x.providerName }), ` · ${x.model} · `, x.route === "device" ? "stays on this device" : qbAi.hostOf(x.endpoint) + (x.route === "proxy" ? " (relayed by the QuillPost backend)" : ""), x.role ? el("span", { class: "muted", text: ` — ${x.role}` }) : null))),
      el("p", { class: "small", text: `${mode} QuillPost keeps no copy and has no AI server of its own. Each service's terms and privacy policy apply${cloud.some((x) => x.def.free === "free") ? ", and free tiers may use what you send to improve their models" : ""}. For confidential mail, use the on-device model or a local server.` }),
      details,
      el("label", { class: "check" }, askEach, el("span", { text: "Show what will be sent and ask before every request" })));
    const ok = await qbAi.dialog({ title: "Send to several AI services?", body, wide: true, buttons: [{ label: "Cancel", value: false }, { label: "Send", value: true, primary: true }] });
    if (ok) S.saveSettings((x) => { for (const c of cloud) if (!x.privacyAck.includes(c.id)) x.privacyAck.push(c.id); x.confirmEachSend = askEach.checked; });
    return !!ok;
  }

  /**
   * One chat request with the busy chip; resolves the client result plus {sent}. Errors carry .sent
   * too. onStatus receives progress lines while several AIs work (they also show on the chip).
   */
  async function runChat({ messages, cmd, label, onToken, onStatus, signal }) {
    const tier = (cmd && cmd.tier) || "quality";
    const sent = await sentInfo(messages, tier);
    const b = qbAi.busy(label || "AI is working…");
    try {
      const r = await C.chat({ messages, tier, temperature: cmd && cmd.temperature, maxTokens: cmd && cmd.maxTokens, signal: signal ? anySignal([signal, b.signal]) : b.signal,
        onStatus: (t) => { b.label(t); if (onStatus) onStatus(t); },
        onToken: (d, full) => { if (!onToken) b.label(`${label || "Receiving"} ${full.length.toLocaleString()} characters…`); else onToken(d, full); } });
      if (!r.ensemble) Object.assign(sent, { model: r.model, endpoint: r.endpoint || sent.endpoint, route: r.route || sent.route });
      return { ...r, sent };
    } catch (e) { if (e && typeof e === "object") e.sent = sent; throw e; }
    finally { b.done(); }
  }

  // ================================================================== context
  async function detailFor(ref) {
    const d0 = state.openDetail;
    if (d0 && d0.uid === ref.uid && (d0.folder || state.folder) === ref.folder) return d0;
    const d = await api("/api/message", { query: { folder: ref.folder, uid: ref.uid, tz: VIEW_TZ } });
    d.folder = ref.folder;
    return d;
  }

  /** The message a reader command works on: the reply's original in the compose window, else the open message. */
  async function targetMessage(origin) {
    try {
      if (origin === "compose" && state.compose && state.compose.ref && (state.compose.mode === "reply" || state.compose.mode === "replyAll" || state.compose.mode === "forward")) return await detailFor(state.compose.ref);
    } catch (e) { toast("Could not load the original message: " + e.message, { danger: true }); return null; }
    if (origin === "compose") {
      const q = quoteText();
      if (q) return { subject: $("cSubject").value.replace(/^\s*(re|fw|fwd)\s*:\s*/i, ""), bodyText: q, fromName: "", fromAddress: "" };
    }
    if (state.openDetail) return state.openDetail;
    toast("Open a message first.");
    return null;
  }

  async function threadDetails(d) {
    const others = (state.thread || []).filter((m) => !(m.uid === d.uid && (m.folder || d.folder) === d.folder) && !(m.messageId && m.messageId === d.messageId)).slice(-9);
    const out = [d];
    for (const m of others) { try { const x = await api("/api/message", { query: { folder: m.folder || d.folder, uid: m.uid, tz: VIEW_TZ } }); out.push(x); } catch { /* skip */ } }
    return out;
  }

  function verdictText(d) {
    const v = d && d.verdict;
    if (!v) return "";
    const lines = [`Level: ${v.level} (score ${v.score})`];
    const au = v.auth || {};
    if (au.spf || au.dkim || au.dmarc) lines.push(`Authentication: SPF ${au.spf || "none"}, DKIM ${au.dkim || "none"}${au.dkimDomain ? ` (${au.dkimDomain})` : ""}, DMARC ${au.dmarc || "none"}`);
    const b = v.brand || {};
    if (b.claimedBrand) lines.push(`Claims to be: ${b.claimedBrand}; sender domain ${b.senderDomain || v.senderDomain}; ${b.mismatch ? "NOT one of its real domains" : b.verified ? "a genuine domain of it" : "domain not confirmed"}${b.lookalike ? "; look-alike domain" : ""}${b.freemailSender ? "; sent from free webmail" : ""}`);
    for (const h of v.blacklistHits || []) lines.push(`Blocklist ${h.list}: ${h.subject}`);
    for (const r of (v.reasons || []).slice(0, 10)) lines.push(`- ${r.title}${r.detail ? `: ${r.detail}` : ""}`);
    if (v.safeSender) lines.push("The user marked this sender as safe.");
    if (v.matchedRule) lines.push(`A user rule matched: ${v.matchedRule}`);
    return lines.join("\n");
  }

  function linksText(d) {
    if (!d || !d.bodyHtml) return "";
    try {
      const doc = new DOMParser().parseFromString(d.bodyHtml, "text/html");
      const seen = new Set(), out = [];
      for (const a of doc.querySelectorAll("a[href]")) {
        const href = a.getAttribute("href") || "";
        if (!/^https?:|^mailto:/i.test(href)) continue;
        const text = (a.textContent || "").replace(/\s+/g, " ").trim().slice(0, 80) || "(image or empty)";
        const k = text + "→" + href;
        if (seen.has(k)) continue;
        seen.add(k);
        out.push(`${text} → ${href.slice(0, 200)}`);
        if (out.length >= 40) break;
      }
      return out.join("\n");
    } catch { return ""; }
  }

  function unsubscribeText(d) {
    const u = A.parseListUnsubscribe(d.listUnsubscribe || (d.headers && (d.headers["List-Unsubscribe"] || d.headers["list-unsubscribe"])), d.headers && (d.headers["List-Unsubscribe-Post"] || d.headers["list-unsubscribe-post"]));
    const parts = [];
    if (u.oneClick) parts.push("one-click unsubscribe (RFC 8058)");
    if (u.https.length) parts.push(`web link ${u.https[0]}`);
    if (u.http.length) parts.push(`unencrypted web link ${u.http[0]}`);
    if (u.mailto.length) parts.push(`email to ${u.mailto[0].replace(/^mailto:/i, "").split("?")[0]}`);
    return { parsed: u, text: parts.join("; ") };
  }

  function messageCtx(d) {
    return {
      me: (state.account && (state.account.displayName || state.account.email)) || "",
      tz: typeof VIEW_TZ !== "undefined" ? VIEW_TZ : "",
      categories: ((state.settings && state.settings.categories) || []).map((c) => c.name),
      signature: !!(state.settings && state.settings.defaultSignatureReply),
      verdict: verdictText(d),
      links: linksText(d),
      unsubscribe: unsubscribeText(d).text,
    };
  }

  // ================================================================== the draft
  const editor = () => $("cEditor");
  let lastRange = null;
  document.addEventListener("selectionchange", () => {
    const sel = getSelection();
    if (!sel || !sel.rangeCount) return;
    const r = sel.getRangeAt(0);
    const ed = editor();
    if (ed && ed.contains(r.commonAncestorContainer)) lastRange = r.cloneRange();
  });

  function composeOpen() { return !!state.compose && !state.compose.minimized && $("composeDialog").open; }
  /** The first top-level node of the quoted original or the signature (the end of the user's own text). */
  function boundaryNode() {
    const ed = editor();
    for (const n of ed.childNodes) if (n.nodeType === 1 && (n.classList.contains("qb-quote") || n.classList.contains("qb-signature"))) return n;
    return null;
  }
  function userRange() {
    const ed = editor(), r = document.createRange();
    r.setStart(ed, 0);
    const b = boundaryNode();
    if (b) r.setEndBefore(b); else r.setEnd(ed, ed.childNodes.length);
    return r;
  }
  const rangeText = (r) => { const d = document.createElement("div"); d.appendChild(r.cloneContents()); return A.htmlToText(d.innerHTML); };
  function userText() { return rangeText(userRange()); }
  function quoteText() { const q = editor() && editor().querySelector(".qb-quote"); return q ? A.htmlToText(q.innerHTML) : ""; }
  function selectionInEditor() {
    const r = lastRange;
    if (!r || r.collapsed || !editor().contains(r.commonAncestorContainer)) return null;
    const text = rangeText(r);
    return text.trim() ? { range: r.cloneRange(), text } : null;
  }

  /** The top-level nodes of the user's own text (before the quoted original or signature). */
  function userNodes() {
    const out = [];
    for (const n of editor().childNodes) { if (n.nodeType === 1 && (n.classList.contains("qb-quote") || n.classList.contains("qb-signature"))) break; out.push(n); }
    return out;
  }
  /** A range inside the user's own nodes (not touching the block after them, which the browser would merge into). */
  function userContentRange() {
    const ed = editor();
    let nodes = userNodes();
    if (!nodes.length) { const d = document.createElement("div"); d.appendChild(document.createElement("br")); ed.insertBefore(d, boundaryNode()); nodes = [d]; }
    const r = document.createRange();
    const first = nodes[0], last = nodes[nodes.length - 1];
    r.setStart(first, 0);
    r.setEnd(last, last.nodeType === 3 ? last.length : last.childNodes.length);
    return r;
  }

  /**
   * Puts AI text into the draft as one editing step: the browser's own Undo (Ctrl+Z) reverts it,
   * and the notice offers Undo too. target: {range} | "user" (your part of the draft) | "cursor".
   * The quoted original and the signature are checked afterwards; if the browser merged the new
   * text into them, the draft is restored and the text is placed by hand instead.
   */
  function applyToDraft(text, target, label) {
    if (!composeOpen()) { toast("The draft is not open any more.", { danger: true }); return false; }
    const ed = editor();
    const snap = ed.innerHTML;
    const html = A.textToHtml(text);
    const b = boundaryNode();
    const guard = b ? b.outerHTML : null;
    ed.focus();
    let r;
    if (target && target.range) r = target.range;
    else if (target === "cursor" && lastRange && ed.contains(lastRange.commonAncestorContainer)) { r = lastRange.cloneRange(); r.collapse(false); }
    else r = userContentRange();
    const insideBoundary = b && b.contains(r.commonAncestorContainer);
    const sel = getSelection();
    sel.removeAllRanges(); sel.addRange(r);
    const ok = document.execCommand("insertHTML", false, html);
    const b2 = boundaryNode();
    if (!ok || (guard && !insideBoundary && (!b2 || b2.outerHTML !== guard))) {
      ed.innerHTML = snap;
      if (target && target.range) { toast("Could not put the text there; it was copied to the clipboard instead.", { danger: true }); copy(text); return false; }
      const tpl = document.createElement("template");
      tpl.innerHTML = html;
      if (target === "cursor") ed.insertBefore(tpl.content, boundaryNode());
      else { const before = boundaryNode(); userNodes().forEach((n) => n.remove()); ed.insertBefore(tpl.content, before); }
    }
    if (state.compose) state.compose.dirty = true;
    toast(`${label}: done.`, { action: "Undo", timeout: 12000, onAction: () => { ed.innerHTML = snap; if (state.compose) state.compose.dirty = true; } });
    return true;
  }

  function setSubject(s) {
    const inp = $("cSubject");
    const before = inp.value;
    inp.focus(); inp.select();
    if (!document.execCommand("insertText", false, s)) inp.value = s;
    if (state.compose) state.compose.dirty = true;
    toast("Subject changed.", { action: "Undo", onAction: () => { inp.value = before; } });
  }

  async function copy(text) { try { await navigator.clipboard.writeText(text); toast("Copied"); } catch { toast("The clipboard is not available here."); } }

  // ================================================================== command runners
  /** Commands whose answer streams into the AI pane. */
  async function paneCommand(cmd, arg, origin) {
    const d = await targetMessage(origin);
    if (!d || !(await ensureReady())) return;
    let text;
    if (cmd.scope === "thread") { const b = qbAi.busy("Collecting the conversation…"); try { text = PR.threadText(await threadDetails(d), limit()); } finally { b.done(); } }
    else text = PR.messageText(d, limit(), { headers: !!cmd.headers });
    const messages = PR.buildMessages(cmd, { text, arg, ...messageCtx(d) }, { language: language() });
    if (!(await confirmSend(messages, cmd.tier))) return;
    const turn = qbAi.pane.result(`${cmd.label}${arg ? ` · ${arg}` : ""} — ${d.subject || "(no subject)"}`);
    try {
      const r = await runChat({ messages, cmd, label: `${cmd.label}…`, onToken: (_d, full) => turn.update(full), onStatus: turn.status });
      const out = A.cleanOutput(r.text);
      const actions = [];
      if (cmd.id === "translate" && state.openDetail === d) actions.push({ label: "Show under the message", icon: "globe", run: () => showTranslation(d, out, arg, r.providerName) });
      if (composeOpen()) actions.push({ label: "Insert into draft", icon: "plus", run: () => applyToDraft(out, "cursor", "Insert") });
      turn.done(out, { actions, sent: r.sent, ensemble: r.ensemble, note: r.truncated ? "The answer was cut short (length limit)." : null });
    } catch (e) {
      if (isAbort(e)) turn.stopped(e.sent); else { turn.fail(errText(e), e.sent, e.ensemble); if (e.code === "auth" || e.code === "model") fail(e); }
    }
  }

  function showTranslation(d, text, lang, by) {
    if (!state.openDetail || state.openDetail.uid !== d.uid) return;
    const host = $("rdTranslation");
    host.innerHTML = "";
    host.append(el("div", { class: "t-head" }, icon("globe", 14), `Translated into ${lang || "English"} by ${by || "AI"}`, el("button", { class: "btn sm", style: { marginLeft: "auto" }, onclick: () => host.classList.add("hidden") }, "Show original only")), el("div", { class: "ai-translation-text", text }));
    host.classList.remove("hidden");
    host.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }

  /** Builds a JSON command's messages and asks for consent when needed; null when cancelled. */
  async function prepare(cmd, ctx) {
    const messages = PR.buildMessages(cmd, ctx, { language: language() });
    return (await confirmSend(messages, cmd.tier)) ? messages : null;
  }

  /** Runs a JSON command and parses the answer (with one clear error when it is not JSON). */
  async function jsonCommand(cmd, ctx, label, prepared = null, onStatus = null) {
    const messages = prepared || (await prepare(cmd, ctx));
    if (!messages) return null;
    const r = await runChat({ messages, cmd, label, onStatus });
    const value = A.parseJsonLoose(r.text);
    if (value == null) { const e = new C.AiError("The AI did not answer in the expected format. Try again, or choose a more capable model."); e.sent = r.sent; e.raw = r.text; throw e; }
    return { value, r };
  }

  async function phishing(cmd, origin) {
    const d = await targetMessage(origin);
    if (!d || !(await ensureReady())) return;
    const messages = await prepare(cmd, { text: PR.messageText(d, limit(), { headers: true }), ...messageCtx(d) });
    if (!messages) return;
    let res;
    const turn = qbAi.pane.result(`Phishing check — ${d.subject || "(no subject)"}`);
    try { res = await jsonCommand(cmd, null, "Checking for phishing signals…", messages, turn.status); }
    catch (e) { if (isAbort(e)) turn.stopped(e.sent); else turn.fail(errText(e) + (e.raw ? `\n\n${e.raw.slice(0, 1500)}` : ""), e.sent, e.ensemble); return; }
    const v = res.value || {};
    const risk = ["low", "medium", "high"].includes(v.risk) ? v.risk : "medium";
    const node = el("div", { class: "ai-phish" },
      el("div", { class: `ai-risk ai-risk-${risk}` }, icon(risk === "low" ? "shield-ok" : "warning", 16), `AI risk assessment: ${risk}`),
      v.summary ? el("p", { text: String(v.summary) }) : null,
      Array.isArray(v.signals) && v.signals.length ? el("ul", { class: "ai-signals" }, ...v.signals.slice(0, 15).map((s) => el("li", {}, el("span", { class: `ai-sev ai-sev-${["low", "medium", "high"].includes(s.severity) ? s.severity : "low"}`, text: s.severity || "" }), el("b", { text: String(s.signal || "") }), s.evidence ? el("div", { class: "small", text: String(s.evidence) }) : null))) : null,
      v.advice ? el("p", {}, el("b", { text: "What to do: " }), String(v.advice)) : null,
      d.verdict ? el("p", { class: "small muted", text: `Built-in junk engine (no AI): ${d.verdict.level.toLowerCase()}, score ${d.verdict.score}. The AI assessment is a second opinion; it can be wrong.` }) : null);
    const actions = [];
    if (state.openDetail === d && d.uid && risk !== "low") actions.push({ label: "Report junk", icon: "junk", run: () => reportJunk(d) });
    turn.done([v.summary, ...(v.signals || []).map((s) => `- ${s.signal}: ${s.evidence || ""}`), v.advice].filter(Boolean).join("\n"), { html: node, actions, sent: res.r.sent, ensemble: res.r.ensemble });
  }
  function reportJunk(d) { actions.junk([d], true); }

  async function classify(cmd, origin) {
    const d = await targetMessage(origin);
    if (!d || !(await ensureReady())) return;
    let res;
    try { res = await jsonCommand(cmd, { text: PR.messageText(d, limit()), ...messageCtx(d) }, "Classifying…"); } catch (e) { fail(e); return; }
    if (!res) return;
    const v = res.value || {};
    const cats = (state.settings.categories || []).map((c) => c.name);
    const name = el("input", { type: "text", value: String(v.category || "").slice(0, 40) });
    const existing = cats.find((c) => c.toLowerCase() === name.value.trim().toLowerCase());
    const body = el("div", { class: "ai-classify" },
      el("div", { class: "field" }, el("label", { text: "Category" }), name, el("span", { class: "hint", text: existing ? "One of your categories." : "A new category; it is created when you apply it." })),
      el("p", {}, el("b", { text: "Kind: " }), String(v.kind || "—"), " · ", el("b", { text: "Priority: " }), String(v.priority || "normal"), " · ", el("b", { text: "Needs a reply: " }), v.needsReply ? "yes" : "no"),
      v.reason ? el("p", { class: "small muted", text: String(v.reason) }) : null,
      qbAi.deliberation(res.r.ensemble), qbAi.sentDetails(res.r.sent));
    const ok = await qbAi.dialog({ title: `Classify — ${d.subject || "(no subject)"}`, body, wide: false, buttons: [{ label: "Close", value: false }, { label: "Apply category", value: true, primary: true, disabled: !d.uid }] });
    if (!ok) return;
    const label = name.value.trim();
    if (!label) return;
    const canonical = cats.find((c) => c.toLowerCase() === label.toLowerCase()) || label;
    if (!cats.includes(canonical)) await settingsSave({ ...state.settings, categories: [...(state.settings.categories || []), { name: canonical, color: "#8764b8" }] });
    await actions.categorize([d], canonical, true);
    if (state.openDetail === d) { d.categories = [...new Set([...(d.categories || []), canonical])]; reading.renderCats(d); }
    toast(`Category "${canonical}" applied.`, { action: "Undo", onAction: () => actions.categorize([d], canonical, false) });
  }

  /** Tasks, events and contacts: a checklist preview, then add the ticked items (with Undo). */
  async function extract(cmd, origin) {
    const d = await targetMessage(origin);
    if (!d || !(await ensureReady())) return;
    let res;
    try { res = await jsonCommand(cmd, { text: PR.messageText(d, limit()), ...messageCtx(d), now: Date.now() }, `Looking for ${cmd.label.toLowerCase()}…`); } catch (e) { fail(e); return; }
    if (!res) return;
    const kind = cmd.json;
    const items = kind === "tasks" ? A.normalizeTasks(res.value) : kind === "events" ? A.normalizeEvents(res.value) : A.normalizeContacts(res.value);
    const source = `From the email "${d.subject || "(no subject)"}"${d.fromAddress ? ` from ${d.fromName || d.fromAddress}` : ""}`;
    if (!items.length) { toast(`No ${cmd.label.toLowerCase()} found in this message.`); return; }
    const rows = items.map((it) => {
      const cb = el("input", { type: "checkbox" });
      const dup = kind === "contacts" && it.email && pim.contactByEmail(it.email);
      cb.checked = !dup;
      const title = el("input", { type: "text", value: kind === "contacts" ? it.name || it.email : it.title });
      let extra;
      if (kind === "tasks") { const due = el("input", { type: "date", value: it.due || "" }); extra = due; it._due = due; }
      else if (kind === "events") { const st = el("input", { type: it.allDay ? "date" : "datetime-local", value: it.start }); extra = el("span", { class: "ai-ev-when" }, st, it.location ? el("span", { class: "small muted", text: " · " + it.location }) : null); it._start = st; }
      else extra = el("span", { class: "small muted", text: [it.email, it.phone, it.company, it.jobTitle].filter(Boolean).join(" · ") + (dup ? " · already in People" : "") });
      it._cb = cb; it._title = title;
      return el("div", { class: "ai-extract-row" }, cb, title, extra);
    });
    const body = el("div", { class: "ai-extract" }, el("p", { class: "small muted", text: "Tick what to add; edit before adding if needed." }), ...rows, qbAi.deliberation(res.r.ensemble), qbAi.sentDetails(res.r.sent));
    const target = { tasks: "To Do", events: "Calendar", contacts: "People" }[kind];
    const ok = await qbAi.dialog({ title: `${cmd.label} — ${d.subject || "(no subject)"}`, body, buttons: [{ label: "Cancel", value: false }, { label: `Add to ${target}`, value: true, primary: true }] });
    if (!ok) return;
    const created = [];
    for (const it of items) {
      if (!it._cb.checked) continue;
      try {
        if (kind === "tasks") created.push(await pim.save("tasks", { title: it._title.value.trim() || it.title, listId: "tasks", due: it._due.value || null, notes: [it.notes, source].filter(Boolean).join("\n") }));
        else if (kind === "events") {
          const start = it._start.value || it.start;
          const end = it.end && it.end > start ? it.end : it.allDay ? ymd(addDays(parseYmd(start), 1)) : localIso(addMinutes(new Date(start), Number(prefs.get("defaultDuration", 30)) || 30));
          const rem = Number(prefs.get("defaultReminder", 15));
          created.push(await pim.save("events", { calendarId: prefs.get("defaultCalendar", "calendar"), title: it._title.value.trim() || it.title, location: it.location, notes: [it.notes, source].filter(Boolean).join("\n"), allDay: it.allDay, tz: VIEW_TZ, start, end, reminderMinutes: rem < 0 ? null : rem, showAs: it.allDay ? "free" : "busy", attendees: [], categories: [], exdates: [] }));
        } else {
          const n = A.splitName(it._title.value.trim() || it.name);
          created.push(await pim.save("contacts", { ...n, emails: it.email ? [{ label: "work", value: it.email }] : [], phones: it.phone ? [{ label: "work", value: it.phone }] : [], company: it.company, jobTitle: it.jobTitle, notes: source }));
        }
      } catch (e) { toast(`Could not add "${it.title || it.name}": ${e.message}`, { danger: true }); }
    }
    if (!created.length) return;
    const kindKey = kind;
    toast(`${plural(created.length, kind === "tasks" ? "task" : kind === "events" ? "event" : "contact")} added to ${target}.`, {
      action: "Undo", timeout: 12000, onAction: async () => { for (const c of created) await pim.remove(kindKey, c.id).catch(() => {}); toast("Removed again."); },
    });
  }

  async function followUp(cmd, origin) {
    const d = await targetMessage(origin);
    if (!d || !(await ensureReady())) return;
    let res;
    try { res = await jsonCommand(cmd, { text: PR.messageText(d, limit()), ...messageCtx(d), now: Date.now() }, "Thinking about a follow-up…"); } catch (e) { fail(e); return; }
    if (!res) return;
    const v = res.value || {};
    const due = el("input", { type: "date", value: A.isYmd(v.due) ? v.due : ymd(addDays(new Date(), 2)) });
    const task = el("input", { type: "text", value: String(v.task || `Follow up: ${d.subject || ""}`).slice(0, 200) });
    const body = el("div", {},
      el("p", { text: v.needed === false ? "The AI does not think a follow-up is needed." : "Suggested follow-up:" }),
      v.reason ? el("p", { class: "small muted", text: String(v.reason) }) : null,
      el("div", { class: "field" }, el("label", { text: "Task" }), task),
      el("div", { class: "field" }, el("label", { text: "Remind me on" }), due),
      qbAi.deliberation(res.r.ensemble), qbAi.sentDetails(res.r.sent));
    const choice = await qbAi.dialog({ title: `Follow-up — ${d.subject || "(no subject)"}`, body, wide: false, buttons: [{ label: "Close", value: null }, ...(d.uid ? [{ label: "Flag message", value: "flag" }] : []), { label: "Add reminder to To Do", value: "task", primary: true }] });
    if (choice === "flag") { await actions.flag([d], true, due.value); toast("Flagged for follow-up.", { action: "Undo", onAction: () => actions.flag([d], false) }); }
    if (choice === "task") {
      const when = parseYmd(due.value || ymd(new Date())); when.setHours(9, 0, 0, 0);
      const t = await pim.save("tasks", { title: task.value.trim(), listId: "tasks", due: due.value || null, reminder: when.getTime(), notes: `From the email "${d.subject || ""}"${d.fromAddress ? ` from ${d.fromName || d.fromAddress}` : ""}` });
      toast("Reminder added to To Do.", { action: "Undo", onAction: () => pim.remove("tasks", t.id) });
    }
  }

  /** Draft reply: options (or a quick intent), streaming preview, then Insert into the reply. */
  async function draftReply(cmd, intent, origin) {
    const fromCompose = origin === "compose" || (composeOpen() && state.compose.ref && origin !== "reader");
    const d = await targetMessage(fromCompose ? "compose" : origin);
    if (!d) return;
    let tone = prefs.get("ai.replyTone", "Professional"), length = prefs.get("ai.replyLength", "medium"), extra = "";
    if (!intent) {
      const v = await formDialog.open({ title: "Draft reply with AI", ok: "Write", fields: [
        { key: "intent", label: "Reply", type: "select", value: "reply", options: Object.entries(PR.INTENTS).map(([k, x]) => [k, x.label]) },
        { key: "tone", label: "Tone", type: "select", value: tone, options: PR.TONES.map((t) => [t, t]) },
        { key: "length", label: "Length", type: "select", value: length, options: Object.entries(PR.LENGTHS).map(([k, x]) => [k, x.label]) },
        { key: "extra", label: "What should it say? (optional)", type: "textarea", placeholder: "e.g. Yes for Thursday, but not before 10am; ask them to send the agenda" },
      ] });
      if (!v) return;
      intent = v.intent; tone = v.tone; length = v.length; extra = v.extra.trim();
      prefs.set("ai.replyTone", tone); prefs.set("ai.replyLength", length);
    }
    if (!(await ensureReady())) return;
    const draft = fromCompose && composeOpen() ? userText().trim() : "";
    const messages = PR.buildMessages(cmd, { text: PR.messageText(d, limit()), intent, tone, length, extra, draft, ...messageCtx(d) }, { language: language() });
    if (!(await confirmSend(messages, cmd.tier))) return;
    const inReply = () => composeOpen() && state.compose.ref && d.uid && state.compose.ref.uid === d.uid;
    await qbAi.preview({
      title: `Draft reply (${PR.INTENTS[intent] ? PR.INTENTS[intent].label : "Reply"}) — ${d.subject || "(no subject)"}`,
      note: `Tone: ${tone} · Length: ${(PR.LENGTHS[length] || PR.LENGTHS.medium).label}`,
      generate: (onToken, signal, onStatus) => runChat({ messages, cmd, label: "Writing a reply…", signal, onStatus, onToken: (_d, full) => onToken(full) }),
      actions: [
        { label: "Copy", icon: "copy", keepOpen: true, run: (t) => copy(t) },
        (fromCompose && composeOpen()) || inReply()
          ? { label: "Insert into reply", icon: "plus", primary: true, run: (t) => { applyToDraft(t, "user", "Reply"); } }
          : { label: "Reply with this text", icon: "reply", primary: true, run: async (t) => { if (!d.uid) { await copy(t); return; } await compose.open({ mode: "reply", detail: d }); applyToDraft(t, "user", "Reply"); } },
      ],
    });
  }

  /** Rewrite / Translate draft / Proofread: works on the selection, else your part of the draft. */
  async function draftText(cmd, arg) {
    if (!composeOpen()) { toast("Open a draft first (New mail, Reply or Forward), then select text or run this on the whole draft."); return; }
    const selr = selectionInEditor();
    const target = selr ? { range: selr.range } : "user";
    const text = selr ? selr.text : userText();
    if (!text.trim()) { toast("There is no text to work on yet."); return; }
    if (!(await ensureReady())) return;
    const t = A.truncate(text, limit());
    if (t.truncated) { toast("The text is too long for this model; select a part of it.", { danger: true }); return; }
    const messages = PR.buildMessages(cmd, { text, arg, subject: $("cSubject").value }, { language: language() });
    if (!(await confirmSend(messages, cmd.tier))) return;
    const argLabel = cmd.id === "rewrite" ? (PR.REWRITES[arg] || PR.REWRITES.improve).label : arg;
    await qbAi.preview({
      title: `${cmd.label}${argLabel ? ` — ${argLabel}` : ""}`,
      original: text, originalLabel: selr ? "Selected text" : "Your draft (without the quoted message and signature)",
      generate: (onToken, signal, onStatus) => runChat({ messages, cmd, label: `${cmd.label}…`, signal, onStatus, onToken: (_d, full) => onToken(full) }),
      actions: [
        { label: "Copy", icon: "copy", keepOpen: true, run: (x) => copy(x) },
        { label: selr ? "Replace selection" : "Replace draft text", icon: "check", primary: true, run: (x) => { applyToDraft(x, target, cmd.label); } },
      ],
    });
  }

  async function subjectLine(cmd) {
    if (!composeOpen()) { toast("Open a draft first."); return; }
    const text = userText().trim() || quoteText();
    if (!text) { toast("Write some of the message first."); return; }
    if (!(await ensureReady())) return;
    let res;
    try { res = await jsonCommand(cmd, { text: A.truncate(text, limit()).text, subject: $("cSubject").value }, "Suggesting subject lines…"); } catch (e) { fail(e); return; }
    if (!res) return;
    const list = (Array.isArray(res.value) ? res.value : res.value.subjects || []).filter((x) => typeof x === "string" && x.trim()).slice(0, 5);
    if (!list.length) { toast("No suggestions came back. Try again."); return; }
    const inputs = list.map((sbj, i) => { const r = el("input", { type: "radio", name: "aiSubject", value: String(i) }); r.checked = i === 0; const t = el("input", { type: "text", value: sbj.trim() }); return { r, t, row: el("div", { class: "ai-extract-row" }, r, t) }; });
    const ok = await qbAi.dialog({ title: "Subject line", wide: false, body: el("div", {}, ...inputs.map((x) => x.row), qbAi.deliberation(res.r.ensemble), qbAi.sentDetails(res.r.sent)), buttons: [{ label: "Cancel", value: false }, { label: "Use subject", value: true, primary: true }] });
    if (!ok) return;
    const pick = inputs.find((x) => x.r.checked) || inputs[0];
    if (composeOpen()) setSubject(pick.t.value.trim());
  }

  async function template(cmd) {
    const v = await formDialog.open({ title: cmd.ask.title, ok: "Generate", fields: [{ key: "extra", label: cmd.ask.label, type: "textarea", placeholder: cmd.ask.placeholder, required: true }] });
    if (!v || !(await ensureReady())) return;
    let res;
    try { res = await jsonCommand(cmd, { extra: v.extra.trim() }, "Writing a template…"); } catch (e) { fail(e); return; }
    if (!res) return;
    const t = res.value || {};
    const name = el("input", { type: "text", value: String(t.name || v.extra).slice(0, 80) });
    const subject = el("input", { type: "text", value: String(t.subject || "") });
    const bodyIn = el("textarea", { rows: 10 }); bodyIn.value = A.cleanOutput(String(t.body || ""));
    const choice = await qbAi.dialog({ title: "Generated template", body: el("div", {},
      el("div", { class: "field" }, el("label", { text: "Template name" }), name),
      el("div", { class: "field" }, el("label", { text: "Subject" }), subject),
      el("div", { class: "field" }, el("label", { text: "Text" }), bodyIn), qbAi.deliberation(res.r.ensemble), qbAi.sentDetails(res.r.sent)),
    buttons: [{ label: "Cancel", value: null }, { label: "Save as template", value: "save" }, composeOpen() ? { label: "Insert into draft", value: "insert", primary: true } : { label: "New message", value: "new", primary: true }] });
    if (!choice) return;
    const html = A.textToHtml(bodyIn.value);
    if (choice === "save") {
      const tpl = { id: "tpl-" + Math.random().toString(36).slice(2, 10), name: name.value.trim() || "AI template", subject: subject.value.trim(), html };
      await settingsSave({ ...state.settings, templates: [...(state.settings.templates || []), tpl] });
      toast(`Template "${tpl.name}" saved (Insert ▸ Templates).`);
    } else if (choice === "insert") {
      applyToDraft(bodyIn.value, "cursor", "Template");
      if (!$("cSubject").value.trim() && subject.value.trim()) setSubject(subject.value.trim());
    } else compose.open({ mode: "new", subject: subject.value.trim(), html });
  }

  /** Unsubscribe helper: what the headers offer (no AI needed), plus an explanation on request. */
  async function unsubscribeHelper(cmd, origin) {
    const d = await targetMessage(origin);
    if (!d) return;
    const u = unsubscribeText(d);
    const p = u.parsed;
    const has = p.https.length || p.http.length || p.mailto.length;
    const level = d.verdict && d.verdict.level;
    const lines = [];
    if (!has) lines.push("This message has no List-Unsubscribe header, so the sender offers no standard way to unsubscribe. Look for an unsubscribe link at the bottom of the message, or use Report junk / Block sender.");
    if (p.oneClick) lines.push("**One-click unsubscribe** (RFC 8058): the mail program sends a single request and you are off the list — no web page to visit.");
    if (p.https.length) lines.push(`**Web page:** ${p.https[0]}`);
    if (p.http.length) lines.push(`**Unencrypted web page:** ${p.http[0]}`);
    if (p.mailto.length) lines.push(`**By email:** a message to ${p.mailto[0].replace(/^mailto:/i, "").split("?")[0]}`);
    if (level && level !== "CLEAN") lines.push(`The junk engine rated this message **${level.toLowerCase()}**. For junk from unknown senders, unsubscribing confirms your address is read: prefer **Report junk** or **Block sender**.`);
    const turn = qbAi.pane.result(`Unsubscribe helper — ${d.subject || "(no subject)"}`);
    const actions = [];
    if (has && d.listUnsubscribe && d.uid) actions.push({ label: "Unsubscribe", icon: "mail-x", run: () => mailplus.unsubscribe(d) });
    actions.push({ label: "Explain with AI", icon: "ai-chat", run: () => paneCommand(cmd, null, origin) });
    turn.done(lines.join("\n\n"), { actions, note: "Read from the message headers on this device; nothing was sent." });
  }

  // ================================================================== saved prompts
  async function runSavedPrompt(sp, origin) {
    if (sp.input === "message" || sp.input === "thread") {
      const d = await targetMessage(origin);
      if (!d || !(await ensureReady())) return;
      const text = sp.input === "thread" ? PR.threadText(await threadDetails(d), limit()) : PR.messageText(d, limit());
      return savedToPane(sp, text, d.subject);
    }
    if (sp.input === "mailbox") {
      if (!(await ensureReady())) return;
      const mb = mailboxContext();
      if (!(await mailboxDisclosure(mb))) return;
      return savedToPane(sp, mb.text, folderName());
    }
    if (sp.input === "draft" || sp.input === "selection") {
      if (!composeOpen()) { toast("Open a draft first."); return; }
      const selr = sp.input === "selection" ? selectionInEditor() : null;
      const text = selr ? selr.text : userText();
      if (!(await ensureReady())) return;
      const messages = PR.savedPromptMessages(sp, text, { language: language() });
      if (!(await confirmSend(messages, "quality"))) return;
      if (sp.output === "pane") return savedRun(sp, messages, $("cSubject").value);
      return qbAi.preview({ title: sp.name, original: text, originalLabel: selr ? "Selected text" : "Your draft",
        generate: (onToken, signal, onStatus) => runChat({ messages, cmd: { tier: "quality", temperature: 0.4 }, label: `${sp.name}…`, signal, onStatus, onToken: (_d, f) => onToken(f) }),
        actions: [{ label: "Copy", icon: "copy", keepOpen: true, run: (x) => copy(x) },
          sp.output === "insert" ? { label: "Insert", primary: true, icon: "plus", run: (x) => { applyToDraft(x, "cursor", sp.name); } } : { label: selr ? "Replace selection" : "Replace draft text", primary: true, icon: "check", run: (x) => { applyToDraft(x, selr ? { range: selr.range } : "user", sp.name); } }] });
    }
    if (!(await ensureReady())) return;
    return savedToPane(sp, "", "");
  }
  async function savedToPane(sp, text, about) {
    const messages = PR.savedPromptMessages(sp, text, { language: language() });
    if (!(await confirmSend(messages, "quality"))) return;
    return savedRun(sp, messages, about);
  }
  async function savedRun(sp, messages, about) {
    const turn = qbAi.pane.result(`${sp.name}${about ? ` — ${about}` : ""}`);
    try {
      const r = await runChat({ messages, cmd: { tier: "quality", temperature: 0.4 }, label: `${sp.name}…`, onToken: (_d, full) => turn.update(full), onStatus: turn.status });
      const out = A.cleanOutput(r.text);
      turn.done(out, { sent: r.sent, ensemble: r.ensemble, actions: composeOpen() ? [{ label: "Insert into draft", icon: "plus", run: () => applyToDraft(out, "cursor", "Insert") }] : [] });
    } catch (e) { if (isAbort(e)) turn.stopped(e.sent); else turn.fail(errText(e), e.sent, e.ensemble); }
  }

  async function managePrompts() {
    const root = el("div", { class: "ai-prompts" });
    const INPUTS = [["message", "The open message"], ["thread", "The open conversation"], ["selection", "Selected draft text (else the draft)"], ["draft", "My draft"], ["mailbox", "This folder's message list"], ["none", "Nothing (just the instruction)"]];
    const OUTPUTS = [["pane", "Answer in the AI pane"], ["replace", "Preview, then replace the draft text"], ["insert", "Preview, then insert into the draft"]];
    const edit = async (cur) => {
      const v = await formDialog.open({ title: cur ? "Edit prompt" : "New prompt", wide: true, fields: [
        { key: "name", label: "Name", value: cur ? cur.name : "", required: true },
        { key: "prompt", label: "Instruction", type: "textarea", value: cur ? cur.prompt : "", placeholder: "e.g. List every date and deadline in this message", required: true },
        { key: "input", label: "Works on", type: "select", value: cur ? cur.input : "message", options: INPUTS },
        { key: "output", label: "Result", type: "select", value: cur ? cur.output : "pane", options: OUTPUTS },
      ] });
      if (!v) return;
      const p = { id: cur ? cur.id : "p" + Date.now().toString(36), name: v.name.trim(), prompt: v.prompt.trim(), input: v.input, output: (v.input === "draft" || v.input === "selection") ? v.output : "pane" };
      S.saveSettings((s) => { s.prompts = cur ? s.prompts.map((q) => (q.id === cur.id ? p : q)) : [...s.prompts, p]; });
      draw();
    };
    const draw = () => {
      root.innerHTML = "";
      const s = S.settings();
      root.appendChild(el("p", { class: "small muted", text: "Your own AI instructions. They appear under AI ▸ My prompts, on the reading pane's AI menu and in the compose window's AI tab." }));
      if (!s.prompts.length) root.appendChild(el("p", { text: "No saved prompts yet." }));
      for (const p of s.prompts) {
        root.appendChild(el("div", { class: "ai-prompt-row" },
          el("div", { class: "grow" }, el("b", { text: p.name }), el("div", { class: "small muted", text: `${(INPUTS.find((x) => x[0] === p.input) || INPUTS[0])[1]} · ${p.prompt.slice(0, 120)}` })),
          el("button", { class: "btn sm", onclick: () => { if (closeIt) closeIt(null); runSavedPrompt(p, composeOpen() && !state.openDetail ? "compose" : "reader"); } }, "Run"),
          el("button", { class: "btn sm", onclick: () => edit(p) }, "Edit"),
          el("button", { class: "icon-btn", title: "Delete", onclick: () => { S.saveSettings((x) => { x.prompts = x.prompts.filter((q) => q.id !== p.id); }); draw(); } }, icon("trash"))));
      }
      root.appendChild(el("div", { class: "btn-row" },
        el("button", { class: "btn primary", onclick: () => edit(null) }, icon("plus"), "New prompt"),
        el("button", { class: "btn", onclick: () => { S.saveSettings((x) => { for (const e of PR.EXAMPLE_PROMPTS) if (!x.prompts.some((q) => q.name === e.name)) x.prompts.push({ id: "p" + Math.random().toString(36).slice(2, 9), ...e }); }); draw(); } }, "Add examples")));
    };
    let closeIt = null;
    draw();
    await qbAi.dialog({ title: "Prompt library", body: root, onOpen: (close) => { closeIt = close; } });
  }
  function myPromptsMenu(origin) {
    const s = S.settings();
    return [...s.prompts.map((p) => ({ label: p.name, icon: "ai-library", onClick: () => runSavedPrompt(p, origin) })), s.prompts.length ? { sep: true } : null, { label: "Prompt library…", icon: "settings", onClick: () => managePrompts() }];
  }

  // ================================================================== the pane chat
  const folderName = () => (typeof folders !== "undefined" && folders.byPath && folders.byPath(state.folder) ? folders.byPath(state.folder).name : state.folder) || "";
  function mailboxContext() {
    const s = S.settings();
    const msgs = typeof list !== "undefined" && list.visible ? list.visible() : state.messages || [];
    return PR.mailboxText(msgs, { previews: !!s.mailboxPreviews, limit: Number(s.mailboxLimit) || 100, folder: folderName() });
  }
  const disclosed = new Set();
  /** Before a folder's list is first sent this session: say exactly what goes (cloud only). */
  async function mailboxDisclosure(mb) {
    const p = S.activeProviderDef();
    const ens = await servicesFor();
    const cloud = ens ? ens.services.filter((x) => !P.isOnDevice(x.def)).map((x) => x.providerName) : p && !P.isOnDevice(p) ? [p.name] : [];
    if (!cloud.length || disclosed.has(state.folder)) return true;
    const ok = await qbAi.dialog({ title: "Send this folder's message list?", wide: true, body: el("div", {},
      el("p", { text: `QuillPost will send ${plural(mb.count, "line")} describing messages in "${folderName()}" — date, sender, subject and flags${S.settings().mailboxPreviews ? ", and the first words of each message" : ""} — to ${cloud.join(", ")}. Message bodies are not sent. Change this in AI settings.` }),
      el("details", { class: "ai-sent" }, el("summary", { text: "Show the exact text" }), el("pre", { text: mb.text }))),
    buttons: [{ label: "Cancel", value: false }, { label: "Send", value: true, primary: true }] });
    if (ok) disclosed.add(state.folder);
    return !!ok;
  }

  qbAi.paneAsk = async (question, include) => {
    if (!(await ensureReady())) return;
    let context = "";
    if (include === "message" || include === "thread") {
      const d = state.openDetail;
      if (!d) { toast("Open a message first, or choose another context under the box."); return; }
      context = include === "thread" ? `The open email conversation:\n<thread>\n${PR.threadText(await threadDetails(d), limit())}\n</thread>` : `The open email:\n<email>\n${PR.messageText(d, limit())}\n</email>`;
    } else if (include === "mailbox") {
      const mb = mailboxContext();
      if (!mb.count) { toast("This folder has no loaded messages."); return; }
      if (!(await mailboxDisclosure(mb))) return;
      context = `${PR.commandById("askMailbox").system}\n<mailbox>\n${mb.text}\n</mailbox>`;
    }
    const prior = qbAi.pane.history.filter((t) => !t.pending && !t.title);
    const messages = PR.chatMessages(prior, question, context, { language: language() });
    if (!(await confirmSend(messages, "quality"))) return;
    qbAi.pane.user(question);
    const turn = qbAi.pane.result(null);
    try {
      const r = await runChat({ messages, cmd: { tier: "quality", temperature: 0.3 }, label: "AI is answering…", onToken: (_d, full) => turn.update(full), onStatus: turn.status });
      const out = A.cleanOutput(r.text);
      turn.done(out, { sent: r.sent, ensemble: r.ensemble, actions: composeOpen() ? [{ label: "Insert into draft", icon: "plus", run: () => applyToDraft(out, "cursor", "Insert") }] : [] });
    } catch (e) { if (isAbort(e)) turn.stopped(e.sent); else turn.fail(errText(e), e.sent, e.ensemble); }
  };

  // ================================================================== dispatch
  async function run(id, arg, origin = "ribbon") {
    const cmd = PR.commandById(id);
    if (!cmd) return;
    try {
      switch (id) {
        case "summarize": case "summarizeThread": case "translate": case "jargon": return await paneCommand(cmd, arg, origin);
        case "unsubscribe": return await unsubscribeHelper(cmd, origin);
        case "phishing": return await phishing(cmd, origin);
        case "classify": return await classify(cmd, origin);
        case "tasks": case "events": case "contacts": return await extract(cmd, origin);
        case "followUp": return await followUp(cmd, origin);
        case "draftReply": return await draftReply(cmd, arg, origin);
        case "rewrite": case "translateDraft": case "proofread": return await draftText(cmd, arg);
        case "subject": return await subjectLine(cmd);
        case "template": return await template(cmd);
        case "askMailbox": qbAi.pane.open({ include: "mailbox" }); qbAi.pane.focusInput(); return;
      }
    } catch (e) { fail(e); }
  }

  async function clearModels() {
    const L = window.QbAiLocal;
    const before = await L.localStorageEstimate();
    if (!(await qbAi.confirmDialog("Delete downloaded models", `Delete the on-device AI model files from this browser? ${before} They are downloaded again the next time the on-device model is used.`, "Delete"))) return;
    const n = await L.clearLocalModels();
    toast(n ? "Downloaded model files deleted." : "No downloaded model files were found.");
  }

  function activeMenu() {
    const s = S.settings();
    const items = Object.keys(s.providers).map((id) => ({ label: `${(P.providerById(id) || { name: id }).name} · ${s.providers[id].model}`, checked: s.active === id, onClick: () => { S.saveSettings((x) => { x.active = id; }); toast(`AI: ${P.providerById(id).name}`); } }));
    return [...items, items.length ? { sep: true } : null, { label: "Providers & models…", icon: "settings", onClick: () => qbAi.providers() }, { label: "Add a provider…", icon: "plus", onClick: () => qbAi.setup() }];
  }

  // ================================================================== several AIs (AI tab ▸ Providers)
  const ENSEMBLE_MODES = [["single", "Single AI"], ["master", "Master Combines Answers"], ["consensus", "Quaker Consensus"]];
  /** The mode in effect: "single" unless an ensemble is set up with at least two usable AIs. */
  const ensembleMode = () => { const plan = C.ensemblePlan(); return plan ? plan.mode : "single"; };
  function setEnsembleMode(mode) {
    if (!ENSEMBLE_MODES.some(([m]) => m === mode)) return;
    if (mode !== "single") {
      const s = S.settings();
      const usable = s.ensemble.members.filter((m) => s.providers[m.provider] && P.providerById(m.provider)).length;
      if (usable < 2) {
        S.saveSettings((x) => { x.ensemble.mode = mode; });
        toast("Choose at least two AIs to use several at once.");
        qbAi.settings({ section: "ensemble" });
        return;
      }
    }
    S.saveSettings((x) => { x.ensemble.mode = mode; });
    toast(mode === "single" ? "AI: single AI" : mode === "master" ? "AI: one AI combines the answers" : "AI: the AIs deliberate until they agree");
  }
  const ensembleModeMenu = () => [
    ...ENSEMBLE_MODES.map(([m, label]) => ({ label, checked: ensembleMode() === m, onClick: () => setEnsembleMode(m) })),
    { sep: true },
    { label: "Choose AIs…", icon: "ai-members", onClick: () => chooseAis() },
  ];
  const chooseAis = () => qbAi.settings({ section: "ensemble" });
  const deliberationShown = () => S.settings().ensemble.showDeliberation !== false;
  function toggleDeliberation() {
    const on = !deliberationShown();
    S.saveSettings((x) => { x.ensemble.showDeliberation = on; });
    toast(on ? "The AIs' discussion is shown below answers." : "Only the final answer is shown.");
    qbAi.pane.render && qbAi.pane.render();
  }
  /** Reflects the mode and the Show AI Discussion toggle on the ribbon buttons. */
  function refreshEnsembleButtons() {
    const t = qs('[data-rbar="ai"] .rb[data-ai="showDeliberation"]');
    if (t) { const on = deliberationShown(); t.classList.toggle("active", on); t.setAttribute("aria-pressed", String(on)); }
    const m = qs('[data-rbar="ai"] .rb[data-ai="ensembleMode"]');
    if (m) { const mode = ensembleMode(); m.classList.toggle("active", mode !== "single"); m.title = `How several AIs answer — now: ${ENSEMBLE_MODES.find(([x]) => x === mode)[1]}`; }
  }

  // ================================================================== menus shared by the ribbon, compose bar and reading pane
  const COMMON_LANGS = ["English", "Spanish", "French", "German", "Italian", "Portuguese", "Dutch", "Chinese (Simplified)", "Japanese", "Korean", "Arabic", "Russian", "Hindi"];
  function languageMenu(id, origin) {
    const last = prefs.get("ai.lang", "English");
    const pick = (l) => { prefs.set("ai.lang", l); run(id, l, origin); };
    const common = [last, ...COMMON_LANGS.filter((l) => l !== last)];
    return [...common.map((l) => ({ label: l, checked: l === last, onClick: () => pick(l) })), { sep: true }, { label: "More languages", icon: "globe", sub: () => PR.LANGUAGES.filter((l) => !common.includes(l)).map((l) => ({ label: l, onClick: () => pick(l) })) }];
  }
  const rewriteMenu = (origin) => Object.entries(PR.REWRITES).map(([k, x]) => ({ label: x.label, onClick: () => run("rewrite", k, origin) }));
  const replyMenu = (origin) => [
    { label: "Reply…", icon: "ai-reply", hint: "tone & length", onClick: () => run("draftReply", null, origin) },
    { sep: true },
    ...["accept", "decline", "details", "thank"].map((k) => ({ label: PR.INTENTS[k].label, onClick: () => run("draftReply", k, origin) })),
  ];
  function readerMenu(origin = "reader") {
    return [
      { label: "Summarize", icon: "ai-summary", onClick: () => run("summarize", null, origin) },
      { label: "Summarize thread", icon: "ai-thread", onClick: () => run("summarizeThread", null, origin) },
      { label: "Translate", icon: "globe", sub: () => languageMenu("translate", origin) },
      { label: "Explain jargon", icon: "help", onClick: () => run("jargon", null, origin) },
      { label: "Phishing check", icon: "phish", onClick: () => run("phishing", null, origin) },
      { label: "Classify", icon: "tag", onClick: () => run("classify", null, origin) },
      { label: "Unsubscribe helper", icon: "mail-x", onClick: () => run("unsubscribe", null, origin) },
      { sep: true },
      { label: "Draft reply", icon: "ai-reply", sub: () => replyMenu(origin) },
      { label: "Extract", icon: "list", sub: () => [
        { label: "Action items → To Do", icon: "todo", onClick: () => run("tasks", null, origin) },
        { label: "Events → Calendar", icon: "calendar", onClick: () => run("events", null, origin) },
        { label: "Contacts → People", icon: "person-add", onClick: () => run("contacts", null, origin) },
        { label: "Follow-up reminder", icon: "bell", onClick: () => run("followUp", null, origin) },
      ] },
      { label: "My prompts", icon: "ai-library", sub: () => myPromptsMenu(origin) },
      { sep: true },
      { label: "AI pane", icon: "ai-chat", onClick: () => qbAi.pane.toggle() },
      { label: "Set up AI…", icon: "ai-setup", onClick: () => qbAi.setup() },
    ];
  }

  // ================================================================== ribbon (the AI tab)
  function rb({ id, label, icon: ic, tip, onClick, menuItems, cls = "" }) {
    const b = el("button", { class: `rb ${cls}`, title: tip || label, "data-ai": id || null }, icon(ic), el("span", { class: "lbl", text: label }));
    if (menuItems) { const ch = icon("chev-down"); ch.setAttribute("class", "chev"); b.appendChild(ch); }
    // Keep the draft's selection when a ribbon button is pressed.
    b.addEventListener("mousedown", (e) => e.preventDefault());
    b.onclick = (e) => { e.stopPropagation(); if (menuItems) menu.openFor(b, menuItems()); else onClick(); };
    return b;
  }
  const tipOf = (id) => (PR.commandById(id) || {}).tip;
  /** Reader commands from the ribbon work on the open message, or on the original of the reply being written. */
  const origin = () => (composeOpen() && !state.openDetail ? "compose" : "reader");

  function buildRibbon() {
    const bar = qs('[data-rbar="ai"]');
    if (!bar || bar.dataset.built) return;
    bar.dataset.built = "1";
    const sep = () => el("span", { class: "rsep" });
    bar.append(
      rb({ id: "setup", label: "Set up AI", icon: "ai-setup", cls: "primary keep", tip: "Choose a free or paid AI: on this device, free cloud tiers, your own computer, or a paid key", onClick: () => qbAi.setup() }),
      rb({ id: "active", label: "Active AI", icon: "ai-switch", tip: "Switch between the AI providers you have set up", menuItems: activeMenu }),
      rb({ id: "ensembleMode", label: "Several AIs", icon: "ai-ensemble", tip: "How several AIs answer: Single AI, Master Combines Answers, or Quaker Consensus", menuItems: ensembleModeMenu }),
      rb({ id: "ensembleMembers", label: "Choose AIs…", icon: "ai-members", tip: "Choose which AIs answer together, the master or clerk, and the rounds of discussion", onClick: () => chooseAis() }),
      rb({ id: "showDeliberation", label: "Show AI Discussion", icon: "ai-discuss", tip: "Show how each AI answered and the discussion below the final answer", onClick: () => toggleDeliberation() }),
      sep(),
      rb({ id: "summarize", cls: "keep", label: "Summarize", icon: "ai-summary", tip: tipOf("summarize"), onClick: () => run("summarize", null, origin()) }),
      rb({ id: "summarizeThread", label: "Summarize thread", icon: "ai-thread", tip: tipOf("summarizeThread"), onClick: () => run("summarizeThread", null, origin()) }),
      rb({ id: "translate", label: "Translate", icon: "globe", tip: tipOf("translate"), menuItems: () => languageMenu("translate", origin()) }),
      rb({ id: "jargon", label: "Explain jargon", icon: "help", tip: tipOf("jargon"), onClick: () => run("jargon", null, origin()) }),
      rb({ id: "phishing", label: "Phishing check", icon: "phish", tip: tipOf("phishing"), onClick: () => run("phishing", null, origin()) }),
      rb({ id: "classify", label: "Classify", icon: "tag", tip: tipOf("classify"), onClick: () => run("classify", null, origin()) }),
      rb({ id: "unsubscribe", label: "Unsubscribe helper", icon: "mail-x", tip: tipOf("unsubscribe"), onClick: () => run("unsubscribe", null, origin()) }),
      sep(),
      rb({ id: "draftReply", cls: "keep", label: "Draft reply", icon: "ai-reply", tip: tipOf("draftReply"), menuItems: () => replyMenu(origin()) }),
      rb({ id: "rewrite", cls: "keep", label: "Rewrite", icon: "ai-wand", tip: tipOf("rewrite"), menuItems: () => rewriteMenu("compose") }),
      rb({ id: "proofread", label: "Proofread", icon: "check-circle", tip: tipOf("proofread"), onClick: () => run("proofread", null, "compose") }),
      rb({ id: "translateDraft", label: "Translate draft", icon: "globe", tip: tipOf("translateDraft"), menuItems: () => languageMenu("translateDraft", "compose") }),
      rb({ id: "subject", label: "Subject line", icon: "edit", tip: tipOf("subject"), onClick: () => run("subject", null, "compose") }),
      rb({ id: "template", label: "Template", icon: "note", tip: tipOf("template"), onClick: () => run("template", null, "compose") }),
      sep(),
      rb({ id: "tasks", cls: "keep", label: "Action items", icon: "todo", tip: tipOf("tasks"), onClick: () => run("tasks", null, origin()) }),
      rb({ id: "events", label: "Events", icon: "calendar", tip: tipOf("events"), onClick: () => run("events", null, origin()) }),
      rb({ id: "contacts", label: "Contacts", icon: "person-add", tip: tipOf("contacts"), onClick: () => run("contacts", null, origin()) }),
      rb({ id: "followUp", label: "Follow-up", icon: "bell", tip: tipOf("followUp"), onClick: () => run("followUp", null, origin()) }),
      sep(),
      rb({ id: "pane", cls: "keep", label: "AI pane", icon: "ai-chat", tip: "Show or hide the AI assistant pane", onClick: () => qbAi.pane.toggle() }),
      rb({ id: "askMailbox", label: "Ask mailbox", icon: "search", tip: tipOf("askMailbox"), onClick: () => run("askMailbox") }),
      rb({ id: "prompts", label: "My prompts", icon: "ai-library", tip: "Run or manage your saved AI prompts (Prompt library)", menuItems: () => myPromptsMenu(origin()) }),
      rb({ id: "stop", label: "Stop", icon: "stop", cls: "ai-stop", tip: "Stop the AI request in progress", onClick: () => { if (!qbAi.stop()) toast("No AI request is running."); } }),
      el("span", { class: "grow" }),
      rb({ id: "privacy", label: "AI & privacy", icon: "ai-shield", tip: "Where your mail goes when you use AI, and where keys are kept", onClick: () => qbAi.privacy() }),
      rb({ id: "settings", label: "AI settings", icon: "settings", tip: "AI on/off, language, what Ask mailbox sends, network route, usage, backup", menuItems: () => [
        { label: "AI settings…", icon: "settings", onClick: () => qbAi.settings() },
        { label: "Providers & models…", icon: "ai-switch", onClick: () => qbAi.providers() },
        { label: "Prompt library…", icon: "ai-library", onClick: () => managePrompts() },
        { sep: true },
        { label: "Delete downloaded models…", icon: "trash", onClick: () => clearModels() },
      ] }));
    refreshEnsembleButtons();
    S.onChange(refreshEnsembleButtons);
  }

  /** The compose window's AI tab. */
  function buildComposeBar() {
    const bar = qs('[data-cbar="ai"]');
    if (!bar || bar.dataset.built) return;
    bar.dataset.built = "1";
    const b = (label, ic, onClick, menuItems, title) => {
      const btn = el("button", { class: "btn subtle sm", title: title || label }, icon(ic), label);
      if (menuItems) { const ch = icon("chev-down"); ch.setAttribute("class", "chev"); btn.appendChild(ch); }
      btn.addEventListener("mousedown", (e) => e.preventDefault());
      btn.onclick = (e) => { e.stopPropagation(); if (menuItems) menu.openFor(btn, menuItems()); else onClick(); };
      return btn;
    };
    bar.append(
      b("Draft reply", "ai-reply", null, () => replyMenu("compose"), tipOf("draftReply")),
      b("Rewrite", "ai-wand", null, () => rewriteMenu("compose"), tipOf("rewrite")),
      b("Proofread", "check-circle", () => run("proofread", null, "compose"), null, tipOf("proofread")),
      b("Translate", "globe", null, () => languageMenu("translateDraft", "compose"), tipOf("translateDraft")),
      b("Subject line", "edit", () => run("subject", null, "compose"), null, tipOf("subject")),
      b("Template", "note", () => run("template", null, "compose"), null, tipOf("template")),
      b("Original message", "ai-summary", null, () => readerMenu("compose").slice(0, 7), "AI commands on the message you are replying to"),
      b("My prompts", "ai-library", null, () => myPromptsMenu("compose")),
      el("span", { class: "vsep" }),
      b("Stop", "stop", () => { if (!qbAi.stop()) toast("No AI request is running."); }),
      b("AI & privacy", "ai-shield", () => qbAi.privacy()));
  }

  /** An "AI" button in the reading pane's toolbar (also reachable on phones, where the ribbon is hidden). */
  function buildReadingButton() {
    const tb = qs("#reading .reading-toolbar");
    if (!tb || $("rdAi")) return;
    const btn = el("button", { id: "rdAi", class: "btn ai", title: "AI commands for this message" }, icon("sparkle"), "AI");
    btn.onclick = (e) => { e.stopPropagation(); menu.openFor(btn, readerMenu("reader")); };
    const anchor = $("rdAnalyze");
    if (anchor) anchor.after(btn); else tb.appendChild(btn);
  }

  function buildTopbarButton() {
    const right = qs(".topbar-right");
    if (!right || $("btnAiPane")) return;
    const btn = el("button", { id: "btnAiPane", class: "icon-btn", title: "AI assistant" }, icon("ai-chat"));
    btn.onclick = () => qbAi.pane.toggle();
    right.insertBefore(btn, right.firstChild);
  }

  // ================================================================== start-up
  async function init() {
    qbAi.injectIcons();
    C.configure({ token: () => state.token });
    // The desktop app's backend keeps keys in its private data folder (the page cannot reach an OS keychain).
    if (!window.QuillPostAndroid) {
      try {
        const r = await fetch("/api/ai/info", { headers: { Accept: "application/json" } });
        if (r.ok) { const info = await r.json(); if (info.keyStore === "desktop-file") S.setKeyBackend(S.desktopBackend((u, o) => fetch(u, o), () => state.token)); }
      } catch { /* older backend: browser storage */ }
    }
    buildRibbon(); buildComposeBar(); buildReadingButton(); buildTopbarButton();
  }

  Object.assign(qbAi, { run, clearModels, setEnsembleMode, toggleDeliberation, managePrompts, runSavedPrompt, refreshRibbon: () => qbAi.pane.refreshHead(), _test: { applyToDraft, userText, selectionInEditor } });
  window.QB_AI_COMMANDS = PR.COMMANDS.map((c) => ({ id: "ai." + c.id, label: c.label, icon: c.icon, group: c.group, tip: c.tip, run: (arg) => run(c.id, arg, origin()) }))
    .concat([
      { id: "ai.setup", label: "Set up AI", icon: "ai-setup", group: "Setup", run: () => qbAi.setup() },
      { id: "ai.providers", label: "Providers & models", icon: "ai-switch", group: "Setup", run: () => qbAi.providers() },
      // Several AIs (AI tab, group "Providers"). The menu command lists its choices with a checked state; run(mode) picks one.
      { id: "ai.ensembleMode", label: "Several AIs", icon: "ai-ensemble", group: "Providers", type: "menu", tip: "How several AIs answer",
        items: () => ENSEMBLE_MODES.map(([m, label]) => ({ id: m, label, checked: ensembleMode() === m, run: () => setEnsembleMode(m) })),
        run: (mode) => (mode ? setEnsembleMode(mode) : chooseAis()) },
      { id: "ai.ensembleMembers", label: "Choose AIs…", icon: "ai-members", group: "Providers", tip: "Choose which AIs answer together", run: () => chooseAis() },
      { id: "ai.showDeliberation", label: "Show AI Discussion", icon: "ai-discuss", group: "Providers", type: "toggle", tip: "Show how each AI answered below the final answer", checked: () => deliberationShown(), run: () => toggleDeliberation() },
      { id: "ai.pane", label: "AI pane", icon: "ai-chat", group: "Assistant", run: () => qbAi.pane.toggle() },
      { id: "ai.prompts", label: "Prompt library", icon: "ai-library", group: "Assistant", run: () => managePrompts() },
      { id: "ai.stop", label: "Stop AI", icon: "stop", group: "Assistant", run: () => qbAi.stop() },
      { id: "ai.privacy", label: "AI & privacy", icon: "ai-shield", group: "Setup", run: () => qbAi.privacy() },
      { id: "ai.settings", label: "AI settings", icon: "settings", group: "Setup", run: () => qbAi.settings() },
      { id: "ai.clearModels", label: "Delete downloaded models", icon: "trash", group: "Setup", run: () => clearModels() },
    ]);

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init); else init();
})();
