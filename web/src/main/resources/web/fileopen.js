/* QuillPost — "Open file": any email, contact, calendar, word-processor, spreadsheet or
 * presentation file, whatever its extension. The backend recognises the file by its content
 * (FileRoutes.kt, fileformats/*.kt); this module shows it:
 *   - messages (.eml, .msg, winmail.dat, .emlx, .mht, .nws, .oft, S/MIME, PGP…) in a message
 *     window with Reply, Reply all, Forward, Save to folder and the attachments;
 *   - contacts (.vcf, .contact, Outlook contacts) with "Add to People";
 *   - calendars (.ics, .vcs, Outlook appointments) with "Add to calendar";
 *   - documents, spreadsheets and presentations with "Open in QuillPen / QuillGrid / QuillStage"
 *     and "Send as attachment".
 * Commands: mail.openFile, mail.sendFileAsAttachment. Files can also be dropped on the window,
 * and the desktop and Android apps hand over files the system asks QuillPost to open.
 * The helpers in FileOpen.logic have no DOM dependencies and are tested in Node.
 */
"use strict";

const FileOpenLogic = (() => {
  const SUITE = {
    document: { id: "quillpen", label: "QuillPen", pkg: "io.github.socrtwo.quillpen", url: "https://github.com/socrtwo/QuillPen", what: "word processor" },
    spreadsheet: { id: "quillgrid", label: "QuillGrid", pkg: "io.github.socrtwo.quillgrid", url: "https://github.com/socrtwo/QuillGrid", what: "spreadsheet" },
    presentation: { id: "quillstage", label: "QuillStage", pkg: "io.github.socrtwo.quillstage", url: "https://github.com/socrtwo/QuillStage", what: "presentation program" },
  };
  const ICONS = { message: "mail", contacts: "people", calendar: "calendar", document: "note", spreadsheet: "table", presentation: "pane", mailbox: "inbox", other: "attach" };

  /** The suite program for a file kind, or null. */
  function suiteApp(kind) { return SUITE[kind] || null; }

  function iconFor(kind) { return ICONS[kind] || "attach"; }

  /**
   * How "Open in QuillPen/QuillGrid/QuillStage" works here: the Android app starts the suite
   * app with the file, the desktop app hands it to the program the system registered, a web
   * browser can only download it.
   */
  function openPlan(r, env = {}) {
    const app = suiteApp(r && r.kind);
    if (!app) return null;
    if (env.android) return { method: "android", app, label: `Open in ${app.label}` };
    if (r.nativeOpen) return { method: "desktop", app, label: `Open in ${app.label}` };
    return { method: "web", app, label: `Open in ${app.label}` };
  }

  /** The buttons a reader window offers for an opened file, in order. */
  function actions(r, env = {}) {
    if (!r) return [];
    const out = [];
    const signedIn = env.signedIn !== false;
    switch (r.kind) {
      case "message":
        if (!r.message) break;
        if (r.message.template) out.push("useTemplate");
        if (signedIn) out.push("reply", "replyAll", "forward");
        if (signedIn && env.canSave !== false) out.push("saveToFolder");
        if (r.events && r.events.length) out.push("addToCalendar");
        if (r.contacts && r.contacts.length) out.push("addToPeople");
        out.push("downloadEml", "headers");
        break;
      case "contacts": if (r.contacts && r.contacts.length) out.push("addToPeople"); break;
      case "calendar": if (r.events && r.events.length) out.push("addToCalendar"); break;
      case "document": case "spreadsheet": case "presentation": out.push("openInApp"); break;
      case "mailbox": out.push("importMailbox"); break;
      default: break;
    }
    if (r.kind !== "message") out.push("sendAsAttachment", "download");
    else out.push("sendAsAttachment");
    return out;
  }

  /** "Name <address>" lists for the header block. */
  function people(list) { return (list || []).filter(Boolean).join("; "); }

  /** Replaces cid: references in HTML (img src and CSS url()) with [map]'s data: URLs (for quoting in a reply). */
  function inlineCids(html, map) {
    if (!html) return html;
    return html.replace(/(["'(])cid:([^"')\s>]+)/gi, (m0, q, id) => (map[id] || map[id.replace(/^<|>$/g, "")] ? q + (map[id] || map[id.replace(/^<|>$/g, "")]) : m0));
  }

  /** A short line describing an event ("Tue 3 Nov 2026, 09:00–10:00 · Room 4"). */
  function eventLine(e) {
    const when = e.allDay ? `${e.start}${e.end && e.end !== e.start ? " – " + e.end : ""} (all day)` : `${String(e.start).replace("T", " ")}${e.end ? " – " + String(e.end).slice(11) : ""}`;
    return [when, e.location].filter(Boolean).join(" · ");
  }

  function contactLine(c) {
    const name = c.displayName || [c.firstName, c.middleName, c.lastName].filter(Boolean).join(" ") || c.company || (c.emails[0] || {}).value || "(no name)";
    const bits = [(c.emails || []).map((x) => x.value).join(", "), (c.phones || []).map((x) => x.value).join(", "), [c.jobTitle, c.company].filter(Boolean).join(", ")].filter(Boolean);
    return { name, detail: bits.join(" · ") };
  }

  /** The files a drop carries, or none for drags of messages and text. */
  function droppedFiles(dt) {
    if (!dt) return [];
    const types = Array.from(dt.types || []);
    if (!types.includes("Files") || types.includes("application/x-quillpost")) return [];
    return Array.from(dt.files || []);
  }

  return { SUITE, suiteApp, iconFor, openPlan, actions, people, inlineCids, eventLine, contactLine, droppedFiles };
})();


// ------------------------------------------------------------------------------------------- UI
const FileOpen = typeof document === "undefined" ? null : {
  logic: FileOpenLogic,
  history: [],
  current: null,
  showImages: false,

  dialog() {
    let d = document.getElementById("fileDialog");
    if (d) return d;
    d = el("dialog", { id: "fileDialog", class: "wide qp-file-dialog", "aria-label": "Opened file" },
      el("div", { class: "dlg-head" }, el("span", { id: "fileIcon", class: "qp-file-icon" }), el("h3", { id: "fileTitle" }), el("span", { id: "fileFormat", class: "badge" }),
        el("button", { class: "icon-btn", title: "Close", "aria-label": "Close", onclick: () => d.close() }, icon("close"))),
      el("div", { id: "fileBar", class: "qp-file-bar" }),
      el("div", { id: "fileBody", class: "dlg-body qp-file-body" }));
    document.body.appendChild(d);
    return d;
  },

  /** Lets the person choose files and opens them. */
  async pick() {
    const files = await pickFiles({ multiple: true });
    if (files.length) await this.openFiles(files);
  },

  async openFiles(files) {
    let last = null;
    for (const f of files) {
      if (f.size > 200 * 1048576) { toast(`${f.name} is larger than 200 MB.`, { danger: true }); continue; }
      try {
        const r = await api("/api/file/open", { method: "POST", query: { name: f.name }, rawBody: f });
        last = r;
        if (r.message && r.message.template && r.format === "oft") { await this.useTemplate(r); continue; }
        this.remember(r);
      } catch (e) { toast(`${f.name}: ${e.message}`, { danger: true }); }
    }
    if (last && !(last.message && last.message.template && last.format === "oft")) this.show(last);
  },

  /** Files the desktop or Android app was asked to open. */
  async openPending() {
    if (!state.token) return;
    try {
      const list = await api("/api/file/pending");
      for (const r of list || []) this.remember(r);
      if (list && list.length) { const r = list[list.length - 1]; if (r.message && r.message.template && r.format === "oft") this.useTemplate(r); else this.show(r); }
    } catch {}
  },

  remember(r) {
    this.history = this.history.filter((x) => x.id !== r.id);
    this.history.push(r);
    if (this.history.length > 12) this.history.shift();
  },

  fileUrl(path, r, extra = {}) { return apiUrl(path, { id: r.id, ...extra }); },
  cidUrl(r) { const base = apiUrl("/api/file/attachment", { id: r.id, inline: "true" }); return (cid) => base + "&cid=" + encodeURIComponent(cid); },

  show(r) {
    this.current = r; this.showImages = false;
    const d = this.dialog();
    $("fileTitle").textContent = (r.message && r.message.subject) || r.name;
    $("fileTitle").title = r.name;
    $("fileFormat").textContent = r.label;
    $("fileFormat").title = `Recognised from the file's content${r.confidence < 0.5 ? " (by its name)" : ""}`;
    const ic = $("fileIcon"); ic.innerHTML = ""; ic.appendChild(icon(FileOpenLogic.iconFor(r.kind), 20));
    this.renderBar(r);
    if (this.history.length > 1) {
      const hist = el("select", { id: "fileHistory", class: "qp-file-history", title: "Files you opened", "aria-label": "Files you opened", onchange: (e) => { const x = this.history.find((h) => h.id === e.target.value); if (x) this.show(x); } });
      for (const x of this.history) hist.appendChild(el("option", { value: x.id, text: (x.message && x.message.subject) || x.name }));
      hist.value = r.id;
      $("fileBar").appendChild(hist);
    }
    const body = $("fileBody"); body.innerHTML = "";
    const notes = el("div", { class: "info-bars" });
    for (const n of r.notes || []) notes.appendChild(el("div", { class: "info-bar" + (/cannot|could not|not supported|undocumented/i.test(n) ? " warn" : "") }, icon(/sign|encrypt/i.test(n) ? "lock" : "info"), el("span", { class: "grow", text: n })));
    body.appendChild(notes);
    if (r.kind === "message" && r.message) this.renderMessage(r, body);
    else if (r.kind === "contacts") this.renderContacts(r, body);
    else if (r.kind === "calendar") this.renderEvents(r, body);
    else if (FileOpenLogic.suiteApp(r.kind)) this.renderDocument(r, body);
    else if (r.kind === "mailbox") body.appendChild(el("div", { class: "qp-file-doc" }, icon("inbox", 48), el("div", {}, el("h2", { text: r.label }), el("p", { text: "This file holds a whole mailbox rather than one message. Import it to copy its messages into a folder." }))));
    else body.appendChild(el("div", { class: "qp-file-doc" }, icon("attach", 48), el("div", {}, el("h2", { text: r.name }), el("p", { text: r.format === "wab" ? "" : "QuillPost does not recognise this kind of file. You can download it or send it as an attachment." }))));
    if (!d.open) d.showModal();
  },

  renderBar(r) {
    const bar = $("fileBar"); bar.innerHTML = "";
    const env = { android: !!(window.QuillPostAndroid && window.QuillPostAndroid.openInApp), signedIn: !!state.token, canSave: !!(state.capabilities && state.capabilities.folders) };
    const B = (ic, label, fn, primary = false, title = "") => bar.appendChild(el("button", { class: "btn" + (primary ? " primary" : ""), title: title || label, onclick: (e) => this.run(fn, e) }, icon(ic), label));
    for (const a of FileOpenLogic.actions(r, env)) {
      switch (a) {
        case "useTemplate": B("compose", "New message from template", () => this.useTemplate(r), true); break;
        case "reply": B("reply", "Reply", () => this.respond(r, "reply")); break;
        case "replyAll": B("reply-all", "Reply all", () => this.respond(r, "replyAll")); break;
        case "forward": B("forward", "Forward", () => this.respond(r, "forward")); break;
        case "saveToFolder": B("folder", "Save to folder", (e) => this.saveMenu(r, e.currentTarget), false, "Copy this message into one of your mail folders"); break;
        case "addToCalendar": B("calendar", r.events.length > 1 ? `Add ${r.events.length} events to calendar` : "Add to calendar", (e) => this.addToCalendar(r, e.currentTarget), r.kind === "calendar"); break;
        case "addToPeople": B("person-add", r.contacts.length > 1 ? `Add ${r.contacts.length} contacts to People` : "Add to People", () => this.addToPeople(r), r.kind === "contacts"); break;
        case "downloadEml": B("download", "Save as .eml", () => download(this.fileUrl("/api/file/eml", r)), false, "Download the message as an Internet message (.eml) file"); break;
        case "headers": B("list", "Headers", () => this.headers(r)); break;
        case "openInApp": { const p = FileOpenLogic.openPlan(r, env); B("external", p.label, () => this.openInApp(r), true, `Open this ${r.label} in ${p.app.label}, the suite's ${p.app.what}`); break; }
        case "importMailbox": B("upload", "Import into folder…", (e) => this.importMailbox(r, e.currentTarget), true); break;
        case "sendAsAttachment": B("attach", "Send as attachment", () => this.sendAsAttachment(r)); break;
        case "download": B("download", "Download", () => download(this.fileUrl("/api/file/raw", r))); break;
      }
    }
  },

  async run(fn, e) {
    try { await fn(e); } catch (err) { toast(err.message || String(err), { danger: true }); }
  },

  renderMessage(r, body) {
    const m = r.message;
    const card = el("div", { class: "qp-file-msg" });
    const fromName = m.fromName || (m.fromAddress || "").split("@")[0] || "No sender recorded";
    const avatar = el("div", { class: "avatar big", text: initials(m.fromName, m.fromAddress), style: { background: avatarColor(m.fromAddress || m.fromName) } });
    const meta = el("div", { class: "sender-meta" });
    const line = (label, value) => value && meta.appendChild(el("span", {}, el("b", { text: label + ": " }), value));
    line("To", FileOpenLogic.people(m.toDisplay)); line("Cc", FileOpenLogic.people(m.ccDisplay)); line("Bcc", FileOpenLogic.people(m.bccDisplay));
    line("Newsgroups", m.newsgroups);
    if (m.senderAddress) line("Sent by", `${m.senderName ? m.senderName + " " : ""}<${m.senderAddress}>`);
    if (m.replyTo && m.replyTo !== m.fromAddress) line("Replies go to", m.replyTo);
    card.appendChild(el("div", { class: "sender-card" }, avatar, el("div", { class: "sender-lines" },
      el("div", { class: "sender-top" }, el("span", { class: "sender-name", text: fromName }), el("span", { class: "grow" }),
        m.importance === "high" ? el("span", { class: "badge danger", text: "High importance" }) : m.importance === "low" ? el("span", { class: "badge info", text: "Low importance" }) : null,
        m.security ? el("span", { class: "badge", title: "Signatures are not verified and encrypted content is not decrypted" }, icon("lock", 12), m.security.startsWith("smime") ? "S/MIME" : "OpenPGP") : null),
      el("div", { class: "sender-from-row" }, el("span", { class: "sender-label", text: "From:" }), el("div", { class: "sender-address" + (m.fromAddress ? "" : " qp-none"), text: m.fromAddress || (m.fromName ? "(no Internet address recorded)" : "(not in this file)") })),
      meta)));
    card.appendChild(el("div", { class: "sender-date-row" }, el("span", { class: "sender-date", text: m.date ? fmtDate(m.date, { long: true }) : "No date" })));
    if (r.events && r.events.length) card.appendChild(this.eventsBlock(r));
    if (r.contacts && r.contacts.length) card.appendChild(this.contactsBlock(r));
    const files = (m.attachments || []).filter((a) => !a.inline);
    if (files.length) {
      const host = el("div", { class: "attachments" });
      for (const a of files) {
        const isImg = /^image\/(png|jpe?g|gif|webp|bmp)/.test(a.mimeType);
        const openable = a.kind && a.kind !== "other";
        const chip = el("button", { class: "att", title: `${a.fileName} (${a.mimeType})${openable ? " — click to open" : " — click to download"}` },
          isImg ? el("img", { class: "thumb", src: this.fileUrl("/api/file/attachment", r, { index: a.index, inline: "true" }), alt: "" }) : icon(FileOpenLogic.iconFor(a.kind), 16),
          el("span", { text: a.fileName }), el("span", { class: "size", text: fmtSize(a.size) }));
        chip.onclick = () => this.run(() => (openable ? this.openAttachment(r, a) : download(this.fileUrl("/api/file/attachment", r, { index: a.index }))));
        chip.oncontextmenu = (e) => { e.preventDefault(); menu.openAt(e.clientX, e.clientY, [openable ? { label: "Open", icon: "external", onClick: () => this.run(() => this.openAttachment(r, a)) } : null, { label: "Download", icon: "download", onClick: () => download(this.fileUrl("/api/file/attachment", r, { index: a.index })) }]); };
        host.appendChild(chip);
      }
      card.appendChild(host);
    }
    const banner = el("div", { class: "banner images-banner hidden" }, icon("image", 16), el("span", { class: "grow", text: "Remote images (which can tell the sender you opened the message) are blocked." }),
      el("button", { class: "btn sm", onclick: () => { this.showImages = true; this.renderMessageBody(r, frame, pre, banner); } }, "Show images"));
    card.appendChild(banner);
    const frame = el("iframe", { class: "body-frame hidden", sandbox: "allow-same-origin allow-popups allow-popups-to-escape-sandbox", title: "Message body" });
    const pre = el("pre", { class: "body-text hidden" });
    card.appendChild(el("div", { class: "body-wrap" }, frame, pre));
    body.appendChild(card);
    this.renderMessageBody(r, frame, pre, banner);
  },

  renderMessageBody(r, frame, pre, banner) {
    const m = r.message;
    if (m.bodyHtml) {
      const { html, blockedImages } = sanitizeHtml(m.bodyHtml, { allowImages: this.showImages, cidUrl: this.cidUrl(r) });
      pre.classList.add("hidden"); frame.classList.remove("hidden");
      frame.style.height = "200px";
      frame.srcdoc = html;
      const fit = () => { try { const h = frame.contentDocument?.documentElement?.scrollHeight; if (h) frame.style.height = Math.min(Math.max(h + 24, 80), 20000) + "px"; } catch {} };
      frame.onload = () => { fit(); setTimeout(fit, 400); setTimeout(fit, 1500); };
      banner.classList.toggle("hidden", !(blockedImages > 0));
    } else {
      frame.classList.add("hidden"); pre.classList.remove("hidden"); pre.innerHTML = "";
      linkify(m.bodyText || "(no text content)", pre);
      banner.classList.add("hidden");
    }
  },

  eventsBlock(r) {
    const box = el("div", { class: "qp-file-list" });
    for (const e of r.events.slice(0, 200)) box.appendChild(el("div", { class: "qp-file-row" }, icon("calendar", 18), el("div", { class: "grow" }, el("b", { text: e.title || "(no title)" }), el("div", { class: "small muted", text: FileOpenLogic.eventLine(e) }), e.recurrence ? el("div", { class: "small muted", text: "Repeats" }) : null)));
    if (r.events.length > 200) box.appendChild(el("p", { class: "muted small", text: `…and ${r.events.length - 200} more` }));
    return box;
  },

  contactsBlock(r) {
    const box = el("div", { class: "qp-file-list" });
    for (const c of r.contacts.slice(0, 300)) {
      const { name, detail } = FileOpenLogic.contactLine(c);
      box.appendChild(el("div", { class: "qp-file-row" }, el("div", { class: "avatar", text: initials(name, (c.emails[0] || {}).value), style: { background: avatarColor(name) } }), el("div", { class: "grow" }, el("b", { text: name }), el("div", { class: "small muted", text: detail }))));
    }
    if (r.contacts.length > 300) box.appendChild(el("p", { class: "muted small", text: `…and ${r.contacts.length - 300} more` }));
    return box;
  },

  renderContacts(r, body) {
    body.appendChild(el("p", { class: "muted", text: r.contacts.length ? `${plural(r.contacts.length, "contact")} in ${r.name}.` : "" }));
    body.appendChild(this.contactsBlock(r));
  },

  renderEvents(r, body) {
    body.appendChild(el("p", { class: "muted", text: r.events.length ? `${plural(r.events.length, "event")} in ${r.name}.` : "No events were found in the file." }));
    body.appendChild(this.eventsBlock(r));
  },

  renderDocument(r, body) {
    const app = FileOpenLogic.suiteApp(r.kind);
    const plan = FileOpenLogic.openPlan(r, { android: !!(window.QuillPostAndroid && window.QuillPostAndroid.openInApp) });
    const how = plan.method === "web"
      ? `In a web browser QuillPost cannot start other programs. Choose "${plan.label}" to download the file, then open it in ${app.label} (the Quill suite's ${app.what}) or any ${app.what}.`
      : plan.method === "android" ? `${app.label} opens it with the same layout as the program that made it. If ${app.label} is not installed, you will be offered other apps.`
      : `QuillPost hands the file to the program your computer opens ${r.label} files with — ${app.label} when it is installed.`;
    body.appendChild(el("div", { class: "qp-file-doc" }, icon(FileOpenLogic.iconFor(r.kind), 48),
      el("div", {}, el("h2", { text: r.name }), el("p", { class: "muted", text: `${r.label} · ${fmtSize(r.size)}${r.wrappedIn ? " · decoded from " + r.wrappedIn : ""}` }), el("p", { text: how }))));
  },

  // ---------------------------------------------------------------- actions

  /** Inline images as data: URLs, so a reply or forward carries them (the composer sends them as related parts). */
  async inlineImages(r) {
    const map = {};
    for (const a of (r.message.attachments || []).filter((x) => x.inline && x.contentId && /^image\//.test(x.mimeType) && x.size < 3e6)) {
      try { const blob = await (await fetch(this.fileUrl("/api/file/attachment", r, { index: a.index }))).blob(); map[a.contentId] = await readFileDataUrl(blob); } catch {}
    }
    return map;
  },

  async detailFor(r) {
    const m = r.message;
    const html = m.bodyHtml ? FileOpenLogic.inlineCids(m.bodyHtml, await this.inlineImages(r)) : null;
    return { subject: m.subject, fromName: m.fromName, fromAddress: m.fromAddress, to: m.to, cc: m.cc, replyTo: m.replyTo, messageId: m.messageId, references: m.references, date: m.date || Date.now(), bodyHtml: html, bodyText: m.bodyText, attachments: [] };
  },

  /** Attaches the opened message's files (or [only]) to the message being written. */
  async attachFrom(r, only = null) {
    const list = only ? [only] : (r.message.attachments || []).filter((a) => !a.inline);
    for (const a of list) {
      const res = await fetch(this.fileUrl("/api/file/attachment", r, { index: a.index }));
      if (!res.ok) throw new Error(`Could not attach ${a.fileName}`);
      const base64 = (await readFileDataUrl(await res.blob())).split(",")[1];
      if (!state.compose) return;
      state.compose.attachments.push({ fileName: a.fileName, mimeType: a.mimeType || "application/octet-stream", base64 });
    }
    if (state.compose) compose.renderAtts();
  },

  async respond(r, mode) {
    const detail = await this.detailFor(r);
    $("fileDialog").close();
    await compose.open({ mode, detail });
    // The original is a file, not a message in a mail folder: nothing to flag as answered or forwarded.
    if (state.compose) { state.compose.ref = null; state.compose.forwardOf = null; state.compose.attachMessages = []; }
    if (mode === "forward") await this.attachFrom(r);
  },

  async useTemplate(r) {
    const d = await this.detailFor(r);
    if ($("fileDialog") && $("fileDialog").open) $("fileDialog").close();
    await compose.open({ mode: "new", to: (r.message.toDisplay || []).join(", "), cc: (r.message.ccDisplay || []).join(", "), subject: r.message.subject, html: d.bodyHtml ? sanitizeHtml(d.bodyHtml, { allowImages: true }).html.replace(/^[\s\S]*<body>|<\/body>[\s\S]*$/g, "") : esc(r.message.bodyText || "").replace(/\n/g, "<br>") });
    await this.attachFrom(r);
    toast(`Opened the template "${r.name}" as a new message`);
  },

  saveMenu(r, anchor) {
    const items = (state.folders || []).filter((f) => f.path).map((f) => ({ label: f.name, icon: FOLDER_ICONS[f.role] || "folder", onClick: () => this.run(async () => {
      await api("/api/file/save", { method: "POST", query: { id: r.id, folder: f.path } });
      toast(`Saved to ${f.name}`); if (state.folder === f.path) list.fetch(); folders.load();
    }) }));
    if (!items.length) throw new Error("No mail folders are available.");
    menu.openFor(anchor, [{ header: "Save a copy to" }, ...items]);
  },

  async addToPeople(r) {
    const res = await api("/api/file/import", { method: "POST", query: { id: r.id } });
    try { await pim.load(); } catch {}
    const msg = res.imported ? `Added ${plural(res.imported, "contact")} to People${res.updated ? ` (${res.updated} already there, updated)` : ""}` : `${res.updated === 1 ? "This contact is" : "These contacts are"} already in People — updated ${plural(res.updated, "contact")}`;
    toast(msg, { action: "Open People", onAction: () => { $("fileDialog").close(); shell.setModule("people"); } });
  },

  addToCalendar(r, anchor) {
    const cals = ((typeof pim !== "undefined" && pim.data && pim.data.calendars) || []).filter((c) => !c.readOnly && !c.subscription);
    const go = (id) => this.run(async () => {
      const res = await api("/api/file/import", { method: "POST", query: { id: r.id, calendarId: id } });
      try { await pim.load(); } catch {}
      toast(`Added ${plural(res.imported + res.updated, "event")} to your calendar`, { action: "Open Calendar", onAction: () => { $("fileDialog").close(); shell.setModule("calendar"); } });
    });
    if (cals.length <= 1) return go(cals[0] ? cals[0].id : "");
    menu.openFor(anchor, [{ header: "Add to calendar" }, ...cals.map((c) => ({ label: c.name, color: c.color, onClick: () => go(c.id) }))]);
  },

  async openAttachment(r, a) {
    const r2 = await api("/api/file/open-attachment", { method: "POST", query: { id: r.id, index: a.index } });
    this.remember(r2);
    this.show(r2);
  },

  headers(r) {
    const t = el("table", { class: "qp-file-headers" });
    for (const h of r.message.headers || []) t.appendChild(el("tr", {}, el("th", { text: h.name }), el("td", { text: h.value })));
    if (!(r.message.headers || []).length) t.appendChild(el("tr", {}, el("td", { text: `This ${r.label} has no Internet headers.` })));
    $("popupTitle").textContent = "Message headers";
    $("popupBody").innerHTML = ""; $("popupBody").appendChild(t);
    $("popupDialog").showModal();
  },

  async openInApp(r) {
    const plan = FileOpenLogic.openPlan(r, { android: !!(window.QuillPostAndroid && window.QuillPostAndroid.openInApp) });
    const url = this.fileUrl("/api/file/raw", r);
    if (plan.method === "android") { window.QuillPostAndroid.openInApp(url, r.name, r.mimeType || "application/octet-stream", plan.app.pkg); return; }
    if (plan.method === "desktop") { await api("/api/file/native", { method: "POST", query: { id: r.id } }); toast(`Opening ${r.name}…`); return; }
    download(url);
    toast(`Downloaded ${r.name}. Open it in ${plan.app.label} or another ${plan.app.what}.`, { action: `Get ${plan.app.label}`, onAction: () => openExternal(plan.app.url), timeout: 10000 });
  },

  async sendAsAttachment(r) {
    const res = await fetch(this.fileUrl("/api/file/raw", r));
    if (!res.ok) throw new Error("The file is no longer open; open it again.");
    const blob = await res.blob();
    const base64 = (await readFileDataUrl(blob)).split(",")[1];
    if ($("fileDialog") && $("fileDialog").open) $("fileDialog").close();
    await compose.open({ mode: "new", subject: r.name.replace(/\.[^.]+$/, ""), attachments: [{ fileName: r.name, mimeType: blob.type || "application/octet-stream", base64 }] });
  },

  importMailbox(r, anchor) {
    if (r.format !== "mbox") throw new Error(`${r.label} files are read by Import (File › Open & Export › Import/Export).`);
    const items = (state.folders || []).map((f) => ({ label: f.name, icon: FOLDER_ICONS[f.role] || "folder", onClick: () => this.run(async () => {
      const blob = await (await fetch(this.fileUrl("/api/file/raw", r))).blob();
      const res = await api("/api/import/messages", { method: "POST", query: { folder: f.path }, rawBody: blob });
      toast(`Imported ${plural(res.imported, "message")} into ${f.name}`); folders.load(); if (state.folder === f.path) list.fetch();
    }) }));
    menu.openFor(anchor, [{ header: "Import into" }, ...items]);
  },

  // ---------------------------------------------------------------- drag and drop

  installDrop() {
    let overlay = null, depth = 0;
    const hide = () => { depth = 0; overlay && overlay.remove(); overlay = null; };
    const inCompose = (e) => !!(e.target && e.target.closest && e.target.closest("#composeDialog, .qb-embed, #cEditor"));
    document.addEventListener("dragenter", (e) => {
      if (!state.token || inCompose(e) || !Array.from(e.dataTransfer?.types || []).includes("Files")) return;
      depth++;
      if (!overlay) { overlay = el("div", { class: "qp-drop-overlay" }, el("div", {}, icon("upload", 40), el("b", { text: "Drop to open in QuillPost" }), el("span", { text: "Messages, contacts, calendars, documents, spreadsheets and presentations" }))); document.body.appendChild(overlay); }
    });
    document.addEventListener("dragleave", () => { if (--depth <= 0) hide(); });
    document.addEventListener("dragover", (e) => { if (overlay && !inCompose(e)) e.preventDefault(); });
    document.addEventListener("drop", (e) => {
      const files = FileOpenLogic.droppedFiles(e.dataTransfer);
      const had = !!overlay; hide();
      if (!files.length || !had || inCompose(e) || e.defaultPrevented) return;
      e.preventDefault();
      this.openFiles(files);
    });
  },
};

/** The two commands (pure data; nothing runs until a command does, so the Node tests can load this file). */
const FILE_COMMANDS = [
  { id: "mail.openFile", label: "Open File…", icon: "upload", tab: "File", group: "Open & Export", popular: true, scope: "global", origin: "QuillPost",
    tip: "Open any message (.eml, .msg, winmail.dat, .mht…), contact, calendar, document, spreadsheet or presentation file",
    keywords: "eml msg oft tnef winmail mht mhtml emlx nws vcf vcard ics vcs calendar contact doc docx xls xlsx ppt pptx odt rtf open file drop",
    run: () => FileOpen.pick() },
  { id: "mail.sendFileAsAttachment", label: "Send File as Attachment…", icon: "attach", tab: "File", group: "Open & Export", scope: "global", origin: "QuillPost",
    tip: "Start a new message with a file attached (the opened file, or one you choose)",
    keywords: "send to mail recipient attach file share",
    run: async (ctx = {}) => {
      const r = ctx.arg && ctx.arg.id ? ctx.arg : ($("fileDialog") && $("fileDialog").open ? FileOpen.current : null);
      if (r) return FileOpen.sendAsAttachment(r);
      const files = await pickFiles({ multiple: true });
      if (!files.length) return;
      await compose.open({ mode: "new", subject: files.length === 1 ? files[0].name.replace(/\.[^.]+$/, "") : "" });
      await compose.addFiles(files);
    } },
];
if (typeof Commands !== "undefined") Commands.def(FILE_COMMANDS);

if (FileOpen) {
  FileOpen.installDrop();
  // Files handed over by the desktop or Android app ("Open with QuillPost"): checked once signed in, and on request.
  window.quillpostOpenPending = () => FileOpen.openPending();
  let seenToken = null;
  setInterval(() => { const t = typeof state !== "undefined" && state.settings ? state.token : null; if (t && t !== seenToken) { seenToken = t; FileOpen.openPending(); } }, 1500);
}

if (typeof module !== "undefined" && module.exports) module.exports = { FileOpenLogic, FILE_COMMANDS };
