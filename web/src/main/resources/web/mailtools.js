/* Quillbox — pure mail utilities used by the command set: quoted-text folding and thread digests,
 * mutt-style message patterns, the Pine/mutt "pipe" filters, Eudora MoodWatch scoring, tracking
 * pixel and tracking-parameter detection, free time slots from a calendar, receipt detection,
 * snippet expansion, quote-integrity checking and pre-send checks. No DOM access: also loaded by
 * the Node unit tests (web/src/test/js).
 */
"use strict";
(function (root) {
  // ================================================================== quoted text
  const ATTRIBUTION = [
    /^\s*On .{4,200}(wrote|schrieb|a écrit|escribió|ha scritto|написал)\s*:\s*$/i,
    /^\s*-{2,}\s*(Original Message|Forwarded message|Ursprüngliche Nachricht|Message d'origine)\s*-{2,}\s*$/i,
    /^\s*_{10,}\s*$/,
    /^\s*From:\s.+$/i,
  ];

  /** Splits a plain-text body into the new part and the quoted history. */
  function splitQuoted(text) {
    const lines = String(text || "").replace(/\r\n/g, "\n").split("\n");
    let cut = lines.length;
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      if (ATTRIBUTION.slice(0, 3).some((re) => re.test(l))) { cut = i; break; }
      // Outlook-style header block: From: … followed within 4 lines by Sent:/Date: and Subject:.
      if (ATTRIBUTION[3].test(l) && lines.slice(i + 1, i + 5).some((x) => /^\s*(Sent|Date):\s/i.test(x))) { cut = i; break; }
      if (/^\s*>/.test(l)) {
        // A run of quoted lines that continues to the end (allowing blank lines and a signature-less tail).
        const rest = lines.slice(i).filter((x) => x.trim());
        if (rest.length && rest.every((x) => /^\s*>/.test(x))) { cut = i; break; }
      }
    }
    const fresh = lines.slice(0, cut).filter((l) => !/^\s*>/.test(l)).join("\n").replace(/\n{3,}/g, "\n\n").trim();
    return { fresh, quoted: lines.slice(cut).join("\n").trim() };
  }

  /** Text without signature ("-- " line) and without quoted history. */
  function freshText(text) {
    const f = splitQuoted(text).fresh;
    const sig = f.search(/\n-- ?\n/);
    return (sig >= 0 ? f.slice(0, sig) : f).trim();
  }

  const norm = (s) => String(s || "").toLowerCase().replace(/^\s*>+/gm, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();

  /** Sentences ending in a question mark. */
  function questions(text) {
    return (String(text || "").replace(/\n+/g, " ").match(/[^.!?\n]{6,240}\?/g) || []).map((q) => q.trim());
  }

  /**
   * Non-AI thread digest: per message only what is new (quoted history folded away), the questions
   * asked, requests ("please…", "can you…"), dates mentioned and links; plus participant counts.
   */
  function threadDigest(messages) {
    const sorted = [...messages].sort((a, b) => (a.date || 0) - (b.date || 0));
    const people = new Map();
    const items = sorted.map((m) => {
      const who = m.fromName || m.from || "(unknown)";
      people.set(who, (people.get(who) || 0) + 1);
      const fresh = freshText(m.text);
      const asks = fresh.replace(/\n+/g, " ").split(/(?<=[.!?])\s+/).map((x) => x.trim())
        .filter((x) => /^(please|pls|can you|could you|would you|will you|let me know|kindly|we need|i need|action:|todo:?|remember to|don't forget)/i.test(x) && x.length < 240);
      const dates = fresh.match(/\b(\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?|(mon|tues|wednes|thurs|fri|satur|sun)day|tomorrow|today|next week|(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.? \d{1,2}(st|nd|rd|th)?)\b/gi) || [];
      const links = fresh.match(/https?:\/\/[^\s<>"')]+/g) || [];
      return { from: who, date: m.date, fresh, words: fresh.split(/\s+/).filter(Boolean).length, questions: questions(fresh), asks: [...new Set(asks)], dates: [...new Set(dates.map((d) => d.trim()))], links: [...new Set(links)] };
    });
    return {
      participants: [...people.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count),
      items,
      questions: items.flatMap((i) => i.questions.map((q) => ({ from: i.from, q }))),
      asks: items.flatMap((i) => i.asks.map((a) => ({ from: i.from, a }))),
      links: [...new Set(items.flatMap((i) => i.links))],
      words: items.reduce((n, i) => n + i.words, 0),
    };
  }

  /**
   * Messages of a conversation whose own new text is fully contained in a later message
   * (Outlook "Clean Up Conversation"). Input: [{key, date, text}]; output: the redundant keys.
   */
  function redundantMessages(messages) {
    const sorted = [...messages].sort((a, b) => (a.date || 0) - (b.date || 0));
    const out = [];
    for (let i = 0; i < sorted.length - 1; i++) {
      const own = norm(freshText(sorted[i].text));
      if (own.length < 12) continue;
      if (sorted.slice(i + 1).some((later) => norm(later.text).includes(own))) out.push(sorted[i].key);
    }
    return out;
  }

  /**
   * Quote integrity: compares the text you originally sent with how a reply quotes it. Returns the
   * original sentences missing from the quote (trimmed) and quoted sentences you never wrote (altered).
   */
  function compareQuote(original, quoted) {
    const sentences = (t) => String(t || "").replace(/^\s*>+ ?/gm, "").replace(/\s+/g, " ").split(/(?<=[.!?])\s+/).map((s) => s.trim()).filter((s) => norm(s).length >= 8);
    const o = sentences(freshText(original)), q = sentences(quoted);
    const qn = q.map(norm).join(" | "), on = o.map(norm).join(" | ");
    const missing = o.filter((s) => !qn.includes(norm(s)));
    const altered = q.filter((s) => !on.includes(norm(s)));
    return { ok: !altered.length, trimmed: missing.length > 0, missing, altered, total: o.length };
  }

  // ================================================================== mutt / Pine message patterns
  const PATTERN_OPS = {
    f: "From", t: "To", c: "Cc", C: "To or Cc", L: "From, To or Cc", s: "Subject", b: "Body (preview)", B: "Whole message text", h: "Headers (from, to, subject)",
    e: "Sender", i: "Message-ID", x: "References", y: "Tag (keyword)", N: "New (unread)", U: "Unread", R: "Read", O: "Old (unread)", F: "Flagged", Q: "Replied",
    A: "All", T: "Tagged", P: "From me", p: "To me", d: "Date (e.g. <2w, >1m, 2026-01-01-2026-02-01)", z: "Size (e.g. >1M, <10K)", X: "Has attachments", l: "Mailing list", D: "Marked deleted",
    g: "Categorised", G: "Pinned", V: "Suspicious / junk verdict",
  };
  const NEEDS_ARG = new Set(["f", "t", "c", "C", "L", "s", "b", "B", "h", "e", "i", "x", "y", "d", "z", "g"]);

  function tokenize(src) {
    const toks = [];
    let i = 0;
    const s = String(src || "");
    while (i < s.length) {
      const ch = s[i];
      if (/\s/.test(ch)) { i++; continue; }
      if (ch === "(" || ch === ")" || ch === "|" || ch === "!") { toks.push({ t: ch }); i++; continue; }
      if (ch === "~" || ch === "=" || ch === "%") {
        const op = s[i + 1]; i += 2;
        let arg = null;
        if (NEEDS_ARG.has(op)) {
          while (i < s.length && /\s/.test(s[i])) i++;
          arg = readWord();
        }
        toks.push({ t: "op", op, arg, exact: ch === "=" });
        continue;
      }
      toks.push({ t: "word", v: readWord() });
    }
    function readWord() {
      let out = "";
      if (s[i] === '"' || s[i] === "'") {
        const q = s[i++];
        while (i < s.length && s[i] !== q) { if (s[i] === "\\" && i + 1 < s.length) i++; out += s[i++]; }
        i++;
        return out;
      }
      while (i < s.length && !/[\s()|]/.test(s[i])) { if (s[i] === "\\" && i + 1 < s.length) i++; out += s[i++]; }
      return out;
    }
    return toks;
  }

  /** Parses a mutt-style pattern ("~f jane ~s report !~F", "~d <2w | ~z >1M"). Throws on bad input. */
  function parsePattern(src) {
    const toks = tokenize(src);
    let p = 0;
    const peek = () => toks[p];
    function orExpr() {
      const parts = [andExpr()];
      while (peek() && peek().t === "|") { p++; parts.push(andExpr()); }
      return parts.length === 1 ? parts[0] : { or: parts };
    }
    function andExpr() {
      const parts = [];
      while (peek() && peek().t !== "|" && peek().t !== ")") parts.push(unary());
      if (!parts.length) throw new Error("Empty pattern");
      return parts.length === 1 ? parts[0] : { and: parts };
    }
    function unary() {
      const t = peek();
      if (!t) throw new Error("Unexpected end of pattern");
      if (t.t === "!") { p++; return { not: unary() }; }
      if (t.t === "(") { p++; const e = orExpr(); if (!peek() || peek().t !== ")") throw new Error("Missing )"); p++; return e; }
      if (t.t === "op") {
        p++;
        if (!PATTERN_OPS[t.op]) throw new Error(`Unknown pattern ~${t.op}`);
        if (NEEDS_ARG.has(t.op) && (t.arg === null || t.arg === "")) throw new Error(`~${t.op} needs a value`);
        return { op: t.op, arg: t.arg, exact: t.exact };
      }
      if (t.t === "word") { p++; return { or: [{ op: "f", arg: t.v }, { op: "s", arg: t.v }] }; }
      throw new Error(`Unexpected "${t.t}"`);
    }
    const ast = orExpr();
    if (p < toks.length) throw new Error(`Unexpected "${toks[p].t === "word" ? toks[p].v : toks[p].t}"`);
    return ast;
  }

  const UNITS = { s: 1e3, M: 6e4, h: 36e5, H: 36e5, d: 864e5, w: 6048e5, m: 2592e6, y: 31536e6 };
  function parseDateSpec(spec, now) {
    const rel = spec.match(/^([<>=])?(\d+)([sMhHdwmy])$/);
    if (rel) {
      const ago = now - Number(rel[2]) * UNITS[rel[3]];
      if (rel[1] === ">") return { max: ago };       // older than
      if (rel[1] === "=") return { min: ago - 864e5 / 2, max: ago + 864e5 / 2 };
      return { min: ago };                             // "<2w" or bare: younger than
    }
    const d = (x) => {
      if (!x) return null;
      let m = x.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/); if (m) return new Date(+m[1], +m[2] - 1, +m[3]).getTime();
      m = x.match(/^(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?$/); if (m) { const y = m[3] ? (m[3].length === 2 ? 2000 + +m[3] : +m[3]) : new Date(now).getFullYear(); return new Date(y, +m[2] - 1, +m[1]).getTime(); }
      throw new Error(`Bad date "${x}"`);
    };
    const r = spec.match(/^(\d{4}-\d{1,2}-\d{1,2}|\d{1,2}\/\d{1,2}(?:\/\d{2,4})?)?(-)?(\d{4}-\d{1,2}-\d{1,2}|\d{1,2}\/\d{1,2}(?:\/\d{2,4})?)?$/);
    if (!r) throw new Error(`Bad date "${spec}"`);
    const a = d(r[1]), b = d(r[3]);
    if (!r[2]) return { min: a, max: a + 864e5 };
    return { min: a, max: b !== null ? b + 864e5 : null };
  }
  function parseSize(x) {
    const m = String(x).match(/^(\d+(?:\.\d+)?)([KMG])?$/i); if (!m) throw new Error(`Bad size "${x}"`);
    return Number(m[1]) * ({ k: 1024, m: 1048576, g: 1073741824 }[(m[2] || "").toLowerCase()] || 1);
  }

  function textMatch(arg, hay, exact) {
    hay = String(hay || "");
    if (exact) return hay.toLowerCase() === arg.toLowerCase() || hay.toLowerCase().split(/[\s,]+/).includes(arg.toLowerCase());
    const ci = arg === arg.toLowerCase();
    try { return new RegExp(arg, ci ? "i" : "").test(hay); } catch { return (ci ? hay.toLowerCase() : hay).includes(arg); }
  }

  /** Does message [m] match the parsed pattern? env: { me: Set of own addresses, tagged: Set of keys, key(m), now } */
  function matchPattern(ast, m, env = {}) {
    if (ast.or) return ast.or.some((x) => matchPattern(x, m, env));
    if (ast.and) return ast.and.every((x) => matchPattern(x, m, env));
    if (ast.not) return !matchPattern(ast.not, m, env);
    const a = ast.arg, now = env.now || Date.now();
    const to = (m.to || []).join(", "), cc = (m.cc || []).join(", "), from = `${m.fromName || ""} <${m.fromAddress || ""}>`;
    const me = env.me || new Set();
    switch (ast.op) {
      case "f": case "e": return textMatch(a, from, ast.exact);
      case "t": return textMatch(a, to, ast.exact);
      case "c": return textMatch(a, cc, ast.exact);
      case "C": return textMatch(a, to + ", " + cc, ast.exact);
      case "L": return textMatch(a, from + ", " + to + ", " + cc, ast.exact);
      case "s": return textMatch(a, m.subject, ast.exact);
      case "b": case "B": return textMatch(a, (m.preview || "") + " " + (m.bodyText || ""), ast.exact);
      case "h": return textMatch(a, `${from}\n${to}\n${cc}\n${m.subject || ""}`, ast.exact);
      case "i": return textMatch(a, m.messageId, ast.exact);
      case "x": return textMatch(a, (m.references || []).join(" ") + " " + (m.inReplyTo || ""), ast.exact);
      case "y": return (m.keywords || []).some((k) => textMatch(a, k, ast.exact));
      case "g": return (m.categories || []).some((k) => textMatch(a, k, ast.exact));
      case "N": case "U": case "O": return !m.read;
      case "R": return !!m.read;
      case "F": return !!m.flagged;
      case "Q": return !!m.answered;
      case "A": return true;
      case "T": return !!(env.tagged && env.key && env.tagged.has(env.key(m)));
      case "P": return me.has(String(m.fromAddress || "").toLowerCase());
      case "p": return [...(m.to || []), ...(m.cc || [])].some((x) => me.has(String(x).toLowerCase()));
      case "X": return !!m.hasAttachments;
      case "l": return m.focused === false || /^(newsletter|news|digest|list|lists|info|noreply|no-reply|mailer)[@.+-]/i.test(m.fromAddress || "");
      case "D": return (m.keywords || []).some((k) => /^\\deleted$/i.test(k)) || !!m.deleted;
      case "G": return !!m.pinned;
      case "V": return !!(m.verdict && m.verdict.level && m.verdict.level !== "CLEAN");
      case "d": { const r = parseDateSpec(a, now); const t = m.date || 0; return (r.min == null || t >= r.min) && (r.max == null || t < r.max); }
      case "z": {
        const mm = a.match(/^([<>])?(.+?)(?:-(.+))?$/); const size = m.size || 0;
        if (mm[3] !== undefined || /-$/.test(a)) { const lo = mm[2] ? parseSize(mm[2]) : 0, hi = mm[3] ? parseSize(mm[3]) : Infinity; return size >= lo && size <= hi; }
        const n = parseSize(mm[2]); return mm[1] === "<" ? size < n : mm[1] === ">" ? size > n : size === n;
      }
      default: return false;
    }
  }

  /** Convenience: compile once, test many. */
  function compilePattern(src) { const ast = parsePattern(src); return (m, env) => matchPattern(ast, m, env); }

  // ================================================================== pipe filters (Pine/mutt "|")
  const PIPE_HELP = "grep [-i -v -c -n] RE · sort [-r -n -u] · uniq [-c] · head [-n N] · tail [-n N] · wc [-l -w -c] · sed s/RE/TEXT/[gi] · cut -d X -f N · cut -c A-B · tr SET1 SET2 · tr -d SET · rev · tac · nl · fold [-w N] · lower · upper · rot13 · urls · emails · quoted · fresh · base64 [-d] · cat";

  function splitArgs(cmd) {
    const out = []; let cur = "", q = null, has = false;
    for (let i = 0; i < cmd.length; i++) {
      const ch = cmd[i];
      if (q) { if (ch === q) q = null; else if (ch === "\\" && q === '"' && i + 1 < cmd.length) cur += cmd[++i]; else cur += ch; continue; }
      if (ch === '"' || ch === "'") { q = ch; has = true; continue; }
      if (/\s/.test(ch)) { if (cur || has) out.push(cur); cur = ""; has = false; continue; }
      cur += ch;
    }
    if (cur || has) out.push(cur);
    return out;
  }
  function splitPipes(src) {
    const parts = []; let cur = "", q = null;
    for (const ch of String(src)) {
      if (q) { if (ch === q) q = null; cur += ch; continue; }
      if (ch === '"' || ch === "'") { q = ch; cur += ch; continue; }
      if (ch === "|") { parts.push(cur.trim()); cur = ""; continue; }
      cur += ch;
    }
    parts.push(cur.trim());
    return parts.filter(Boolean);
  }
  function expandSet(s) { return String(s).replace(/(.)-(.)/g, (_, a, b) => { let r = ""; for (let c = a.charCodeAt(0); c <= b.charCodeAt(0); c++) r += String.fromCharCode(c); return r; }).replace(/\\n/g, "\n").replace(/\\t/g, "\t"); }
  const b64 = (s, decode) => {
    if (typeof Buffer !== "undefined") return decode ? Buffer.from(s.replace(/\s+/g, ""), "base64").toString("utf8") : Buffer.from(s, "utf8").toString("base64");
    return decode ? decodeURIComponent(escape(atob(s.replace(/\s+/g, "")))) : btoa(unescape(encodeURIComponent(s)));
  };

  function runFilter(lines, argv) {
    const [name, ...args] = argv;
    const flag = (f) => args.includes(f);
    const opt = (f, d) => { const i = args.indexOf(f); if (i >= 0 && i + 1 < args.length) return args[i + 1]; const j = args.find((a) => a.startsWith(f) && a.length > f.length); return j ? j.slice(f.length) : d; };
    const VALUE_OPTS = { cut: ["-d", "-f", "-c"], head: ["-n"], tail: ["-n"], fold: ["-w"] }[name] || [];
    const positional = () => args.filter((a, i) => !/^-./.test(a) && !(i > 0 && VALUE_OPTS.includes(args[i - 1])));
    const num = (d) => { const n = opt("-n", null); if (n !== null) return Number(n); const dash = args.find((a) => /^-\d+$/.test(a)); return dash ? Number(dash.slice(1)) : d; };
    switch (name) {
      case "cat": return lines;
      case "grep": case "egrep": {
        const re = positional()[0]; if (re === undefined) throw new Error("grep: pattern missing");
        let rx; try { rx = new RegExp(re, flag("-i") ? "i" : ""); } catch { throw new Error("grep: bad pattern"); }
        let out = lines.map((l, i) => [l, i]).filter(([l]) => rx.test(l) !== flag("-v"));
        if (flag("-c")) return [String(out.length)];
        return out.map(([l, i]) => (flag("-n") ? `${i + 1}:${l}` : l));
      }
      case "sort": {
        let out = [...lines];
        out.sort(flag("-n") ? (a, b) => parseFloat(a) - parseFloat(b) || a.localeCompare(b) : (a, b) => a.localeCompare(b));
        if (flag("-r")) out.reverse();
        if (flag("-u")) out = out.filter((l, i) => i === 0 || l !== out[i - 1]);
        return out;
      }
      case "uniq": {
        const out = [];
        for (const l of lines) { const last = out[out.length - 1]; if (last && last.l === l) last.n++; else out.push({ l, n: 1 }); }
        return out.map((x) => (flag("-c") ? `${String(x.n).padStart(7)} ${x.l}` : x.l));
      }
      case "head": return lines.slice(0, num(10));
      case "tail": return lines.slice(-num(10));
      case "wc": {
        const text = lines.join("\n");
        const l = lines.length, w = text.split(/\s+/).filter(Boolean).length, c = text.length;
        if (flag("-l")) return [String(l)]; if (flag("-w")) return [String(w)]; if (flag("-c") || flag("-m")) return [String(c)];
        return [`${l} ${w} ${c}`];
      }
      case "sed": {
        const expr = positional()[0] || "";
        const m = expr.match(/^s(.)(.*?)\1(.*?)\1([gi]*)$/); if (!m) throw new Error("sed: only s/RE/TEXT/flags is supported");
        const rx = new RegExp(m[2], m[4].includes("i") ? (m[4].includes("g") ? "gi" : "i") : (m[4].includes("g") ? "g" : ""));
        return lines.map((l) => l.replace(rx, m[3].replace(/\\(\d)/g, "$$$1").replace(/&/g, "$$&")));
      }
      case "cut": {
        const d = opt("-d", "\t"), f = opt("-f", null), c = opt("-c", null);
        const range = (spec, len) => { const set = new Set(); for (const part of spec.split(",")) { const [a, b] = part.split("-"); const lo = a ? Number(a) : 1, hi = part.includes("-") ? (b ? Number(b) : len) : lo; for (let i = lo; i <= hi; i++) set.add(i); } return [...set].sort((x, y) => x - y); };
        if (c) return lines.map((l) => range(c, l.length).map((i) => l[i - 1] || "").join(""));
        if (!f) throw new Error("cut: give -f or -c");
        return lines.map((l) => { const parts = l.split(d); return range(f, parts.length).map((i) => parts[i - 1]).filter((x) => x !== undefined).join(d); });
      }
      case "tr": {
        if (flag("-d")) { const set = expandSet(positional()[0] || ""); return lines.map((l) => [...l].filter((ch) => !set.includes(ch)).join("")); }
        const [a, b] = positional().map(expandSet); if (a === undefined || b === undefined) throw new Error("tr: two sets needed");
        return lines.map((l) => [...l].map((ch) => { const i = a.indexOf(ch); return i < 0 ? ch : b[Math.min(i, b.length - 1)]; }).join(""));
      }
      case "rev": return lines.map((l) => [...l].reverse().join(""));
      case "tac": return [...lines].reverse();
      case "nl": return lines.map((l, i) => `${String(i + 1).padStart(6)}\t${l}`);
      case "fold": { const w = Number(opt("-w", 72)) || 72; return lines.flatMap((l) => { const out = []; let cur = ""; for (const word of l.split(/(\s+)/)) { if ((cur + word).length > w && cur.trim()) { out.push(cur.trimEnd()); cur = word.trimStart(); } else cur += word; } out.push(cur); return out; }); }
      case "lower": return lines.map((l) => l.toLowerCase());
      case "upper": return lines.map((l) => l.toUpperCase());
      case "rot13": return lines.map((l) => l.replace(/[a-z]/gi, (ch) => { const base = ch <= "Z" ? 65 : 97; return String.fromCharCode(((ch.charCodeAt(0) - base + 13) % 26) + base); }));
      case "urls": return [...new Set(lines.join("\n").match(/https?:\/\/[^\s<>"')\]]+/g) || [])];
      case "emails": return [...new Set((lines.join("\n").match(/[\w.+-]+@[\w-]+(\.[\w-]+)+/g) || []).map((x) => x.toLowerCase()))];
      case "quoted": return splitQuoted(lines.join("\n")).quoted.split("\n");
      case "fresh": return freshText(lines.join("\n")).split("\n");
      case "base64": return b64(lines.join("\n"), flag("-d")).split("\n");
      default: throw new Error(`Unknown filter "${name}". Available: ${PIPE_HELP}`);
    }
  }

  /** Runs "grep -i invoice | sort | uniq -c" over [text]. No shell is involved: every filter is built in. */
  function runPipeline(text, pipeline) {
    let lines = String(text || "").replace(/\r\n/g, "\n").replace(/\n$/, "").split("\n");
    for (const stage of splitPipes(pipeline)) {
      const argv = splitArgs(stage);
      if (!argv.length) continue;
      lines = runFilter(lines, argv);
    }
    return lines.join("\n");
  }

  // ================================================================== Eudora MoodWatch
  const MOOD_WORDS = {
    3: ["idiot", "moron", "stupid", "imbecile", "incompetent", "pathetic", "worthless", "shut up", "screw you", "go to hell", "how dare you", "liar", "loser", "disgusting", "hate you", "wtf"],
    2: ["ridiculous", "useless", "lazy", "dumb", "crap", "sucks", "garbage", "clueless", "your fault", "you always", "you never", "unacceptable", "nonsense", "damn", "hell", "bs", "furious", "fed up", "sick of", "outrageous", "absurd"],
    1: ["frankly", "obviously", "seriously", "again!", "as i said", "as i already", "per my last", "for the last time", "whatever", "annoyed", "disappointed", "irritated", "upset", "fail", "terrible", "awful"],
  };
  /**
   * Scores how heated a text reads, 0–3 "chili peppers", with the reasons. Looks at insults and
   * blame, shouting (capitals), exclamation runs and accusatory "you" phrasing.
   */
  function moodScore(text) {
    const t = freshText(text);
    const lower = " " + t.toLowerCase().replace(/[^\p{L}\p{N}!' ]+/gu, " ") + " ";
    let points = 0; const hits = [];
    for (const [w, list] of Object.entries(MOOD_WORDS)) for (const word of list) {
      const re = new RegExp(`(^|[^\\p{L}])${word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?=[^\\p{L}]|$)`, "giu");
      const n = (lower.match(re) || []).length;
      if (n) { points += Number(w) * n; hits.push(word); }
    }
    const words = t.split(/\s+/).filter((x) => /\p{L}{3,}/u.test(x));
    const caps = words.filter((x) => x.length > 3 && x === x.toUpperCase() && /\p{Lu}/u.test(x)).length;
    if (words.length >= 4 && caps / words.length > 0.3) { points += 3; hits.push("SHOUTING"); }
    else if (caps >= 3) { points += 1; hits.push("capitals"); }
    const bangs = (t.match(/!{2,}|\?!|!\?/g) || []).length;
    if (bangs) { points += Math.min(3, bangs); hits.push("!!"); }
    const yous = (lower.match(/\byou(r|'re)?\b/g) || []).length;
    if (yous >= 6 && points > 0) points += 1;
    const peppers = points >= 7 ? 3 : points >= 4 ? 2 : points >= 2 ? 1 : 0;
    return { peppers, points, hits: [...new Set(hits)] };
  }

  // ================================================================== tracking pixels and parameters
  const TRACKER_HOSTS = ["list-manage.com", "mailchimp.com", "sendgrid.net", "mandrillapp.com", "hubspot", "hs-analytics", "mailtrack.io", "mixmax.com", "yesware.com", "bananatag.com", "streak.com", "superhuman.com", "getnotify.com", "readnotify.com", "emltrk.com", "doubleclick.net", "google-analytics.com", "exct.net", "rs6.net", "sparkpostmail.com", "createsend", "cmail", "mailgun", "pardot", "marketo", "mktoresp", "eloqua", "klaviyo", "customer.io", "intercom-mail", "mailjet", "sendinblue", "brevo", "postmarkapp.com", "mcsv.net", "returnpath.net", "litmus.com", "awstrack.me", "salesforce", "click.e."];
  const TRACKER_PATH = /(\/(open|o|track|tracking|pixel|beacon|wf\/open|e\/o|trk|t)\b[^"]*\.(gif|png)|\/open\.(gif|png)|[?&](open|track|pixel)=|\/track\/open|\/o\.gif|\/t\.gif|\/pixel\b|\/beacon\b|\/imp\b)/i;

  /**
   * Remote images in [html] that look like read-tracking pixels: 1×1 or hidden images, and images
   * on known tracking hosts or paths. Returns [{ src, reason }].
   */
  function findTrackers(html) {
    const out = [];
    const re = /<img\b[^>]*>/gi;
    let m;
    while ((m = re.exec(String(html || "")))) {
      const tag = m[0];
      const src = (tag.match(/\ssrc\s*=\s*["']?([^"'\s>]+)/i) || [])[1] || "";
      if (!/^https?:/i.test(src)) continue;
      const w = (tag.match(/\swidth\s*=\s*["']?(\d+)/i) || [])[1], h = (tag.match(/\sheight\s*=\s*["']?(\d+)/i) || [])[1];
      const style = ((tag.match(/\sstyle\s*=\s*"([^"]*)"/i) || tag.match(/\sstyle\s*=\s*'([^']*)'/i) || [])[1] || "").toLowerCase();
      let host = ""; try { host = new URL(src).hostname.toLowerCase(); } catch {}
      let reason = null;
      if ((w !== undefined && Number(w) <= 2) && (h === undefined || Number(h) <= 2)) reason = "1×1 image";
      else if (h !== undefined && Number(h) <= 1 && (w === undefined || Number(w) <= 3)) reason = "invisible image";
      else if (/display\s*:\s*none|visibility\s*:\s*hidden|(^|;)\s*(width|height)\s*:\s*[01]px/.test(style)) reason = "hidden image";
      else if (TRACKER_HOSTS.some((t) => host.includes(t)) && TRACKER_PATH.test(src)) reason = "tracking service";
      else if (TRACKER_PATH.test(src)) reason = "tracking address";
      if (reason) out.push({ src, host, reason });
    }
    return out;
  }

  const TRACKING_PARAMS = /^(utm_[a-z]+|fbclid|gclid|dclid|gbraid|wbraid|msclkid|yclid|mc_eid|mc_cid|_hsenc|_hsmi|__hssc|__hstc|__hsfp|hsctatracking|mkt_tok|igshid|oly_enc_id|oly_anon_id|vero_id|vero_conv|rb_clickid|s_cid|ncid|cmpid|trk|trkcampaign|spm|scid|ss_campaign_id|ss_source|ref_src|si)$/i;
  /** Removes tracking parameters (utm_*, fbclid, mc_eid, …) from a link; other parameters are kept. */
  function cleanUrl(url) {
    let u; try { u = new URL(url); } catch { return url; }
    let changed = false;
    for (const k of [...u.searchParams.keys()]) if (TRACKING_PARAMS.test(k)) { u.searchParams.delete(k); changed = true; }
    if (!changed) return url;
    let s = u.toString();
    if (!u.search) s = s.replace(/\?$/, "");
    return s;
  }

  // ================================================================== calendar time slots
  /**
   * Free slots of [duration] minutes on working days within working hours, starting after [from],
   * avoiding [busy] intervals ({start, end} Dates or ms). Returns up to [max] {start, end} Dates.
   */
  function freeSlots(busy, { from = new Date(), days = 5, workStart = 9, workEnd = 17, workDays = [1, 2, 3, 4, 5], duration = 30, step = 30, max = 6, perDay = 2 } = {}) {
    const iv = busy.map((b) => [+new Date(b.start), +new Date(b.end)]).sort((a, b) => a[0] - b[0]);
    const out = [];
    const start = new Date(from); start.setSeconds(0, 0);
    let day = new Date(start.getFullYear(), start.getMonth(), start.getDate());
    let examined = 0;
    while (out.length < max && examined < 60) {
      if (workDays.includes(day.getDay())) {
        examined++;
        let n = 0;
        const dayStart = new Date(day); dayStart.setHours(workStart, 0, 0, 0);
        const dayEnd = new Date(day); dayEnd.setHours(workEnd, 0, 0, 0);
        let t = Math.max(+dayStart, Math.ceil(+start / (step * 6e4)) * step * 6e4);
        while (t + duration * 6e4 <= +dayEnd && n < perDay && out.length < max) {
          const end = t + duration * 6e4;
          const clash = iv.find(([a, b]) => a < end && b > t);
          if (clash) { t = Math.ceil(clash[1] / (step * 6e4)) * step * 6e4; continue; }
          out.push({ start: new Date(t), end: new Date(end) });
          n++;
          t = end + 90 * 6e4; // spread the offered slots out a little
        }
        if (examined >= days && out.length) break;
      }
      day = new Date(day.getFullYear(), day.getMonth(), day.getDate() + 1);
    }
    return out;
  }

  // ================================================================== classification helpers
  /** Receipts, invoices, orders and shipping notices (Hey's "Paper Trail"). */
  function isReceipt(m) {
    const s = `${m.subject || ""} ${m.preview || ""}`;
    return /\b(receipt|invoice|order (confirmation|#|no\.?|number)|your order|order has shipped|has shipped|payment (received|confirmation)|you (paid|sent a payment)|transaction|booking confirmation|reservation confirmation|e-?ticket|itinerary|statement is ready|subscription (renewed|confirmation)|refund)\b/i.test(s)
      && !(m.verdict && m.verdict.level === "SPAM");
  }
  /** Newsletters and bulk mail (for bundling). */
  function isNewsletter(m) {
    return m.focused === false || /^(newsletter|news|digest|info|hello|marketing|updates?|noreply|no-reply|mailer|team)[@.+-]/i.test(m.fromAddress || "") || /\b(newsletter|digest|issue #?\d+|weekly|unsubscribe)\b/i.test(m.subject || "");
  }

  /** Fills a snippet: {first_name}, {name}, {email}, {my_name}, {date}, {time}, {subject}; {cursor} marks the caret. */
  function expandSnippet(template, vars = {}) {
    const now = vars.now ? new Date(vars.now) : new Date();
    const v = { date: now.toLocaleDateString(), time: now.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }), ...vars };
    if (!v.first_name && v.name) v.first_name = String(v.name).split(/[\s,]+/)[0];
    return String(template).replace(/\{(\w+)\}/g, (all, k) => (k === "cursor" ? all : v[k] !== undefined && v[k] !== null ? String(v[k]) : ""));
  }

  /** Changes the case of a text (Word's Change Case). */
  function changeCase(text, mode) {
    const s = String(text);
    switch (mode) {
      case "upper": return s.toUpperCase();
      case "lower": return s.toLowerCase();
      case "title": return s.toLowerCase().replace(/(^|[\s(\["'-])(\p{L})/gu, (_, a, b) => a + b.toUpperCase());
      case "sentence": return s.toLowerCase().replace(/(^\s*|[.!?]\s+)(\p{L})/gu, (_, a, b) => a + b.toUpperCase());
      case "toggle": return [...s].map((c) => (c === c.toUpperCase() ? c.toLowerCase() : c.toUpperCase())).join("");
      default: return s;
    }
  }

  /** Words, characters, sentences and paragraphs (Review → Word Count). */
  function wordStats(text) {
    const t = String(text || "");
    return { words: t.split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w)).length, chars: t.length, charsNoSpaces: t.replace(/\s/g, "").length, sentences: (t.match(/[^.!?]+[.!?]+/g) || []).length, paragraphs: t.split(/\n\s*\n/).filter((p) => p.trim()).length };
  }

  /**
   * Checks before sending: missing subject, an attachment mentioned but not attached, a named
   * person not among the recipients, many recipients in To/Cc (use Bcc), external domains,
   * heated tone, and an empty body.
   */
  function presendChecks({ to = [], cc = [], bcc = [], subject = "", body = "", attachments = 0, myDomain = "", names = [], mood = true } = {}) {
    const w = [];
    const all = [...to, ...cc, ...bcc];
    const addr = (a) => (String(a).match(/<([^>]+)>/) || [0, a])[1].trim().toLowerCase();
    if (!all.length) w.push({ level: "error", text: "There are no recipients." });
    if (!subject.trim()) w.push({ level: "warn", text: "The subject is empty." });
    const fresh = freshText(body);
    if (!fresh.trim()) w.push({ level: "warn", text: "The message has no text of its own." });
    if (/\b(attached|attachment|enclosed|see the file)\b/i.test(fresh) && !attachments) w.push({ level: "warn", text: "You mention an attachment but nothing is attached." });
    if (to.length + cc.length > 10) w.push({ level: "info", text: `${to.length + cc.length} people in To and Cc can all see each other's addresses — consider Bcc.` });
    if (myDomain) {
      const ext = [...new Set(all.map(addr).filter((a) => a.includes("@") && !a.endsWith("@" + myDomain.toLowerCase())).map((a) => a.split("@")[1]))];
      if (ext.length && ext.length <= 5) w.push({ level: "info", text: `Going outside ${myDomain}: ${ext.join(", ")}.` });
    }
    for (const n of names) {
      const first = String(n.name || "").split(/\s+/)[0];
      if (first.length < 3) continue;
      const mentioned = new RegExp(`(^|[\\s,@(])${first.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`, "i").test(fresh.split("\n").slice(0, 4).join("\n")) || new RegExp(`@${first}\\b`, "i").test(fresh);
      if (mentioned && !all.map(addr).includes(String(n.email || "").toLowerCase())) w.push({ level: "warn", text: `You address ${first}, but ${n.email} is not a recipient.` });
    }
    if (mood) { const s = moodScore(body); if (s.peppers >= 2) w.push({ level: "warn", text: `MoodWatch: this reads as heated (${"🌶".repeat(s.peppers)} — ${s.hits.slice(0, 4).join(", ")}).` }); }
    return w;
  }

  /** A vCard 3.0 for a contact (Outlook "Business Card"). */
  function vcardOf(c) {
    const e = (s) => String(s || "").replace(/\\/g, "\\\\").replace(/\n/g, "\\n").replace(/[,;]/g, (x) => "\\" + x);
    const fn = c.displayName || [c.firstName, c.lastName].filter(Boolean).join(" ") || (c.emails && c.emails[0] && c.emails[0].value) || "Contact";
    const lines = ["BEGIN:VCARD", "VERSION:3.0", `N:${e(c.lastName)};${e(c.firstName)};${e(c.middleName)};${e(c.prefix)};${e(c.suffix)}`, `FN:${e(fn)}`];
    if (c.company) lines.push(`ORG:${e(c.company)}${c.department ? ";" + e(c.department) : ""}`);
    if (c.jobTitle) lines.push(`TITLE:${e(c.jobTitle)}`);
    for (const m of c.emails || []) lines.push(`EMAIL;TYPE=INTERNET:${m.value}`);
    for (const p of c.phones || []) lines.push(`TEL;TYPE=${(p.label || "voice").toUpperCase()}:${p.value}`);
    if (c.website) lines.push(`URL:${c.website}`);
    if (c.birthday) lines.push(`BDAY:${c.birthday}`);
    lines.push("END:VCARD");
    return lines.join("\r\n") + "\r\n";
  }

  /** A conversation as Markdown (newest last). */
  function threadMarkdown(subject, messages) {
    const out = [`# ${subject || "(no subject)"}`, ""];
    for (const m of [...messages].sort((a, b) => (a.date || 0) - (b.date || 0))) {
      out.push(`## ${m.fromName || m.from || ""}${m.from && m.fromName ? ` <${m.from}>` : ""} — ${new Date(m.date || 0).toISOString().slice(0, 16).replace("T", " ")}`, "");
      out.push(freshText(m.text) || "_(no new text)_", "");
    }
    return out.join("\n");
  }

  const api = { splitQuoted, freshText, questions, threadDigest, redundantMessages, compareQuote, PATTERN_OPS, parsePattern, matchPattern, compilePattern, PIPE_HELP, runPipeline, moodScore, findTrackers, cleanUrl, freeSlots, isReceipt, isNewsletter, expandSnippet, changeCase, wordStats, presendChecks, vcardOf, threadMarkdown };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.MailTools = api;
})(typeof window !== "undefined" ? window : globalThis);
