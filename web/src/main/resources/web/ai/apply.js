/* Quillbox AI — text helpers shared by the commands and the tests: cleaning model output,
 * lenient JSON, safe Markdown-lite rendering, HTML <-> text, List-Unsubscribe parsing and the
 * checks applied to extracted tasks, events and contacts before they are offered.
 *
 * Pure functions only (no DOM at load time). Browser: window.QbAiApply; Node: require().
 */
(function (root, factory) {
  const mod = factory();
  if (typeof module === "object" && module.exports) module.exports = mod;
  else root.QbAiApply = mod;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const escHtml = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  /** Removes reasoning blocks, code fences, "Sure, here is…" lead-ins and wrapping quotes. */
  function cleanOutput(raw, { keepMarkdown = true } = {}) {
    let t = String(raw || "").replace(/\r\n?/g, "\n").trim();
    t = t.replace(/^<think>[\s\S]*?<\/think>\s*/i, "");
    const fence = /^```[\w-]*\n([\s\S]*?)\n```$/.exec(t);
    if (fence) t = fence[1].trim();
    const lines = t.split("\n");
    if (lines.length > 1 && /^(sure|certainly|of course|here(?:'s| is| are)|below is|okay|ok)\b.{0,120}[:：]\s*$/i.test(lines[0])) t = lines.slice(1).join("\n").trim();
    if (/^["“].*["”]$/s.test(t) && !/["“”]/.test(t.slice(1, -1))) t = t.slice(1, -1);
    if (!keepMarkdown) t = t.replace(/^\*\*(.+)\*\*$/s, "$1");
    return t;
  }

  /** Parses JSON from a model answer: tolerates code fences, prose around it and trailing commas. */
  function parseJsonLoose(raw) {
    let t = cleanOutput(raw);
    const fence = /```(?:json)?\s*\n?([\s\S]*?)```/i.exec(t);
    if (fence) t = fence[1].trim();
    const tryParse = (s) => { try { return JSON.parse(s); } catch { try { return JSON.parse(s.replace(/,\s*([}\]])/g, "$1")); } catch { return undefined; } } };
    let v = tryParse(t);
    if (v !== undefined) return v;
    // The first balanced {...} or [...] in the text.
    for (const open of ["{", "["]) {
      const start = t.indexOf(open);
      if (start < 0) continue;
      const close = open === "{" ? "}" : "]";
      let depth = 0, inStr = false, escp = false;
      for (let i = start; i < t.length; i++) {
        const c = t[i];
        if (inStr) { if (escp) escp = false; else if (c === "\\") escp = true; else if (c === '"') inStr = false; continue; }
        if (c === '"') inStr = true;
        else if (c === open) depth++;
        else if (c === close && --depth === 0) { v = tryParse(t.slice(start, i + 1)); if (v !== undefined) return v; break; }
      }
    }
    return null;
  }

  /** Inline Markdown (bold, italic, code) on already-escaped text. */
  function inlineMd(s) {
    return s
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>")
      .replace(/(^|[\s(])\*([^*\s][^*]*)\*(?=[\s).,;:!?]|$)/g, "$1<i>$2</i>");
  }

  /**
   * Markdown-lite to HTML for the AI pane and previews: headings, bullet and numbered lists,
   * paragraphs, bold/italic/code. Everything is escaped first; no links, images or raw HTML,
   * so a model (or the email it read) cannot inject markup.
   */
  function mdToHtml(md) {
    const lines = String(md || "").replace(/\r\n?/g, "\n").split("\n");
    const out = [];
    let list = null, para = [];
    const flushPara = () => { if (para.length) { out.push(`<p>${inlineMd(para.join("<br>"))}</p>`); para = []; } };
    const flushList = () => { if (list) { out.push(`<${list.tag}>${list.items.map((i) => `<li>${inlineMd(i)}</li>`).join("")}</${list.tag}>`); list = null; } };
    for (const raw of lines) {
      const line = escHtml(raw);
      let m;
      if (!raw.trim()) { flushPara(); flushList(); continue; }
      if ((m = /^\s*#{1,6}\s+(.*)$/.exec(line))) { flushPara(); flushList(); out.push(`<h4>${inlineMd(m[1])}</h4>`); continue; }
      if ((m = /^\s*[-*•]\s+(?:\[( |x|X)\]\s+)?(.*)$/.exec(line))) { flushPara(); if (!list || list.tag !== "ul") { flushList(); list = { tag: "ul", items: [] }; } list.items.push(m[2]); continue; }
      if ((m = /^\s*\d+[.)]\s+(.*)$/.exec(line))) { flushPara(); if (!list || list.tag !== "ol") { flushList(); list = { tag: "ol", items: [] }; } list.items.push(m[1]); continue; }
      flushList();
      para.push(line);
    }
    flushPara(); flushList();
    return out.join("");
  }

  /** Plain text (as a model writes it) to email HTML: paragraphs, line breaks and simple lists. */
  function textToHtml(text) {
    const t = cleanOutput(text).replace(/\*\*([^*]+)\*\*/g, "$1");
    const blocks = t.split(/\n{2,}/).map((b) => b.trim()).filter(Boolean);
    return blocks.map((b) => {
      const lines = b.split("\n");
      if (lines.every((l) => /^\s*[-*•]\s+/.test(l))) return `<ul>${lines.map((l) => `<li>${escHtml(l.replace(/^\s*[-*•]\s+/, ""))}</li>`).join("")}</ul>`;
      if (lines.every((l) => /^\s*\d+[.)]\s+/.test(l))) return `<ol>${lines.map((l) => `<li>${escHtml(l.replace(/^\s*\d+[.)]\s+/, ""))}</li>`).join("")}</ol>`;
      return `<div>${lines.map(escHtml).join("<br>")}</div><div><br></div>`;
    }).join("");
  }

  /** HTML string to readable plain text without a DOM (used for message bodies in Node tests too). */
  function htmlToText(html) {
    return String(html || "")
      .replace(/<(script|style|head|title)[^>]*>[\s\S]*?<\/\1>/gi, "")
      .replace(/<br\s*\/?>/gi, "\n")
      .replace(/<\/(p|div|li|tr|h[1-6]|blockquote|pre|table)>/gi, "\n")
      .replace(/<li[^>]*>/gi, "• ")
      .replace(/<[^>]+>/g, "")
      .replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&")
      .replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  }

  /** Cuts text to [max] characters at a line or word boundary and says so. */
  function truncate(text, max) {
    const t = String(text || "");
    if (t.length <= max) return { text: t, truncated: false };
    let cut = t.lastIndexOf("\n", max);
    if (cut < max * 0.8) cut = t.lastIndexOf(" ", max);
    if (cut < max * 0.5) cut = max;
    return { text: t.slice(0, cut).trimEnd() + "\n[…truncated]", truncated: true };
  }

  /**
   * List-Unsubscribe (RFC 2369) and List-Unsubscribe-Post (RFC 8058): the ways this sender
   * offers to unsubscribe. One-click means an HTTPS POST that needs no web page.
   */
  function parseListUnsubscribe(header, postHeader) {
    const out = { https: [], http: [], mailto: [], oneClick: false };
    for (const m of String(header || "").matchAll(/<\s*([^>]+?)\s*>/g)) {
      const u = m[1];
      if (/^https:/i.test(u)) out.https.push(u);
      else if (/^http:/i.test(u)) out.http.push(u);
      else if (/^mailto:/i.test(u)) out.mailto.push(u);
    }
    out.oneClick = out.https.length > 0 && /List-Unsubscribe=One-Click/i.test(String(postHeader || ""));
    return out;
  }

  const isYmd = (s) => typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s) && !isNaN(Date.parse(s + "T00:00:00Z"));
  const isLocalDateTime = (s) => typeof s === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(s) && !isNaN(Date.parse(s + ":00Z"));
  const asList = (v, key) => (Array.isArray(v) ? v : v && Array.isArray(v[key]) ? v[key] : []);

  /** Action items: [{title, due?, notes?}] with valid dates only. */
  function normalizeTasks(v) {
    return asList(v, "tasks").filter((t) => t && typeof t.title === "string" && t.title.trim()).slice(0, 30).map((t) => ({
      title: t.title.trim().slice(0, 200),
      due: isYmd(t.due) ? t.due : null,
      notes: typeof t.notes === "string" ? t.notes.trim().slice(0, 2000) : "",
    }));
  }

  /** Events: [{title, start, end, allDay, location, notes}]; times are local "YYYY-MM-DDTHH:mm", all-day dates "YYYY-MM-DD". */
  function normalizeEvents(v) {
    const out = [];
    for (const e of asList(v, "events").slice(0, 20)) {
      if (!e || typeof e.title !== "string" || !e.title.trim()) continue;
      const allDay = !!e.allDay || (isYmd(e.start) && !isLocalDateTime(e.start));
      let start = e.start, end = e.end;
      if (allDay) {
        if (!isYmd(String(start).slice(0, 10))) continue;
        start = String(start).slice(0, 10);
        end = isYmd(String(end || "").slice(0, 10)) && String(end).slice(0, 10) > start ? String(end).slice(0, 10) : null;
      } else {
        if (!isLocalDateTime(String(start).slice(0, 16))) continue;
        start = String(start).slice(0, 16);
        end = isLocalDateTime(String(end || "").slice(0, 16)) && String(end).slice(0, 16) > start ? String(end).slice(0, 16) : null;
      }
      out.push({ title: e.title.trim().slice(0, 200), start, end, allDay, location: typeof e.location === "string" ? e.location.trim().slice(0, 300) : "", notes: typeof e.notes === "string" ? e.notes.trim().slice(0, 2000) : "" });
    }
    return out;
  }

  /** Contacts: [{name, email, phone, company, jobTitle}] with a plausible email or phone. */
  function normalizeContacts(v) {
    const out = [];
    const seen = new Set();
    for (const c of asList(v, "contacts").slice(0, 30)) {
      if (!c || typeof c !== "object") continue;
      const email = typeof c.email === "string" && /^[^@\s<>]+@[^@\s<>]+\.[^@\s<>]+$/.test(c.email.trim()) ? c.email.trim() : "";
      const phone = typeof c.phone === "string" && /\d{5,}/.test(c.phone.replace(/\D/g, "")) ? c.phone.trim() : "";
      const name = typeof c.name === "string" ? c.name.trim().slice(0, 120) : "";
      if (!email && !phone) continue;
      const k = (email || phone).toLowerCase();
      if (seen.has(k)) continue;
      seen.add(k);
      out.push({ name, email, phone, company: typeof c.company === "string" ? c.company.trim().slice(0, 120) : "", jobTitle: typeof c.jobTitle === "string" ? c.jobTitle.trim().slice(0, 120) : "" });
    }
    return out;
  }

  /** Splits "First Middle Last" for a contact record. */
  function splitName(name) {
    const parts = String(name || "").trim().split(/\s+/).filter(Boolean);
    if (parts.length < 2) return { firstName: parts[0] || "", lastName: "" };
    return { firstName: parts.slice(0, -1).join(" "), lastName: parts[parts.length - 1] };
  }

  return { escHtml, cleanOutput, parseJsonLoose, mdToHtml, textToHtml, htmlToText, truncate, parseListUnsubscribe, normalizeTasks, normalizeEvents, normalizeContacts, splitName, isYmd, isLocalDateTime };
});
