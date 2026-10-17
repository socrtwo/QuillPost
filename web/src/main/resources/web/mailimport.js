/* QuillPost — importing mailboxes and mail stores from other mail programs, and Local Archives.
 *
 * File ▸ Import Mailbox… (mail.importMailbox), Local Archives (mail.localArchives), and every
 * mailbox that reaches File ▸ Open File… (mail.openFile, fileopen.js) — picked, dropped, or handed
 * over by the Android/desktop/iOS apps — end here. Files go to the server
 * (POST /api/mailbox/upload, folders file by file with their relative paths), which works out what
 * they are from their bytes (MailImport.kt, mailstore/Sniffer.kt). A mailbox or mail store (.pst,
 * .ost, mbox, Maildir, .dbx, Eudora, Apple Mail, Thunderbird…) opens the Import Mailbox dialog:
 * detected format, folder tree with counts, and a destination (a folder of the signed-in IMAP
 * account, or Local Archives), then progress, cancel and a report. Single messages, contacts,
 * calendars and office files are handed back to fileopen.js.
 */
"use strict";

const mailImport = {
  archives: [],
  archiveFolders: [],
  job: null,

  // ------------------------------------------------------------------ Local Archives in the folder pane
  async loadArchives() {
    try { const r = await api("/api/archives"); this.archives = r.archives || []; this.archiveFolders = r.folders || []; }
    catch { this.archives = []; this.archiveFolders = []; }
  },
  isArchive(p) { return typeof p === "string" && p.startsWith("@archive/"); },
  archiveOf(p) { return this.archives.find((a) => p.startsWith("@archive/" + a.id + "/")); },

  install() {
    const self = this;
    if (typeof folders === "undefined" || folders.__archives) return;
    folders.__archives = true;
    const load = folders.load.bind(folders), render = folders.render.bind(folders), byPath = folders.byPath.bind(folders);
    folders.load = async function () { await self.loadArchives(); return load(); };
    folders.byPath = function (p) { return byPath(p) || self.archiveFolders.find((f) => f.path === p); };
    folders.render = function () { render(); try { self.renderSection(); } catch (e) { console.error(e); } };
    this.wireDrop();
    this.hookFileOpen();
  },

  whenSignedIn(fn) {
    const t = setInterval(() => { if (typeof state !== "undefined" && state.token && state.settings) { clearInterval(t); fn(); } }, 300);
    setTimeout(() => clearInterval(t), 120000);
  },

  renderSection() {
    const host = $("folderTree");
    if (!host || !this.archives.length) return;
    const collapsed = folders.collapsed();
    const id = "localArchives";
    const head = el("div", { class: "tree-head" + (collapsed.has(id) ? " collapsed" : ""), title: "Mail imported from other programs, kept on this device (read-only)", dataset: { section: "archives" } },
      icon("chev-down", 14), el("span", { class: "name", text: "Local Archives" }));
    head.querySelector("svg").classList.add("chev");
    head.onclick = () => folders.toggleCollapsed(id);
    head.oncontextmenu = (e) => { e.preventDefault(); menu.openAt(e.clientX, e.clientY, [{ label: "Import mailbox…", icon: "upload", onClick: () => Commands.run("mail.importMailbox") }]); };
    const ul = el("ul", { class: "folder-list", id: "archiveList" });
    for (const a of this.archives) {
      const fs = this.archiveFolders.filter((f) => f.path.startsWith("@archive/" + a.id + "/"));
      if (!fs.length) continue;
      if (fs.length === 1) {
        // A one-folder archive (an mbox file, Opened files) is a single row.
        const li = folders.item({ ...fs[0], name: a.name });
        li.querySelector("svg use")?.setAttribute("href", "#i-box");
        li.title = [a.formatLabel, a.sourceName].filter(Boolean).join(" — ") || a.name;
        li.oncontextmenu = (e) => { e.preventDefault(); this.folderMenu(e, fs[0], a); };
        ul.appendChild(li);
        continue;
      }
      const title = el("li", { class: "archive-head other", title: [a.formatLabel, a.sourceName].filter(Boolean).join(" — "), style: { "--depth": 0 } }, icon("box", 18), el("span", { class: "name", text: a.name }));
      title.onclick = () => { const first = fs.find((f) => f.total > 0) || fs[0]; list.load(first.path); shell.setModule("mail", { silent: true }); };
      title.oncontextmenu = (e) => { e.preventDefault(); this.archiveMenu(e, a); };
      ul.appendChild(title);
      for (const f of fs) {
        const li = folders.item(f);
        li.oncontextmenu = (e) => { e.preventDefault(); this.folderMenu(e, f, a); };
        ul.appendChild(li);
      }
    }
    const sub = el("div", { class: "tree-sub" + (collapsed.has(id) ? " collapsed" : "") }, ul);
    const before = host.querySelector(".add-account");
    host.insertBefore(head, before); host.insertBefore(sub, before);
  },

  archiveMenu(e, a) {
    menu.openAt(e.clientX, e.clientY, [
      { header: a.formatLabel || a.name },
      { label: "Remove from Local Archives…", icon: "trash", danger: true, onClick: () => this.removeArchive(a) },
    ]);
  },
  folderMenu(e, f, a) {
    menu.openAt(e.clientX, e.clientY, [
      { label: "Open", icon: "folder", onClick: () => list.load(f.path) },
      { label: "Mark all as read", icon: "check", onClick: async () => { await api("/api/flags", { method: "POST", body: { items: (await api("/api/messages", { query: { folder: f.path, limit: 500 } })).messages.map((m) => ({ folder: f.path, uid: m.uid })), read: true } }); folders.load(); if (state.folder === f.path) list.fetch(); } },
      { label: "Export folder (.mbox)", icon: "download", onClick: () => download(apiUrl("/api/export/mbox", { folder: f.path })) },
      { sep: true },
      { label: "Remove archive \"" + a.name + "\"…", icon: "trash", danger: true, onClick: () => this.removeArchive(a) },
    ]);
  },
  async removeArchive(a) {
    if (!confirm(`Remove "${a.name}" from Local Archives? Its messages are deleted from this device (the file you imported is not touched).`)) return;
    try { await api("/api/archives", { method: "DELETE", query: { id: a.id } }); if (this.isArchive(state.folder) && state.folder.includes("/" + a.id + "/")) list.load(folders.byRole("inbox")?.path || "INBOX"); await folders.load(); toast(`Removed "${a.name}".`); }
    catch (e) { toast(e.message, { danger: true }); }
  },

  async showArchives() {
    await this.loadArchives(); folders.render();
    shell.setModule("mail", { silent: true });
    if (!this.archives.length) {
      const body = el("div", {}, el("p", { text: "Local Archives keep mail imported from other programs on this device — Outlook .pst files, Thunderbird, Apple Mail, Eudora, Outlook Express and more — so you can read and search it next to your server folders." }),
        el("div", { class: "btn-row" }, el("button", { class: "btn primary", onclick: () => { $("popupDialog").close(); this.chooseSource(); } }, icon("upload"), "Import mailbox…")));
      popup("Local Archives", { node: body });
      return;
    }
    folders.collapsed().has("localArchives") && folders.toggleCollapsed("localArchives");
    const first = this.archiveFolders.find((f) => f.total > 0) || this.archiveFolders[0];
    if (first) list.load(first.path);
    $("archiveList")?.scrollIntoView({ block: "nearest" });
  },

  // ------------------------------------------------------------------ choosing files and folders
  pick({ directory = false } = {}) {
    return new Promise((resolve) => {
      const input = el("input", { type: "file", class: "hidden" });
      input.multiple = true;
      if (directory) { input.webkitdirectory = true; input.setAttribute("webkitdirectory", ""); }
      input.onchange = () => { resolve(Array.from(input.files || []).map((f) => ({ file: f, path: f.webkitRelativePath || f.name }))); input.remove(); };
      document.body.appendChild(input); input.click();
    });
  },

  /** The Import Mailbox start: a file, a folder, or drop here. */
  chooseSource() {
    const d = this.dialog("qbImportSource", "Import Mailbox");
    const body = qs(".dlg-body", d);
    const zone = el("div", { class: "qb-drop-zone" },
      icon("mailbox-in", 40),
      el("b", { text: "Drop a mailbox file or folder here" }),
      el("div", { class: "small muted", text: "Outlook (.pst, .ost), mbox (Thunderbird, Apple Mail, Gmail Takeout, Eudora .mbx, Pine, mutt…), Maildir, MH, Apple Mail .mbox/.emlx, Outlook Express (.dbx, OE4 .mbx), Pegasus Mail (.pmm), The Bat! (.tbb), Windows Live Mail folders of .eml, or a .zip / .tar.gz of any of them." }));
    zone.addEventListener("dragover", (e) => { e.preventDefault(); zone.classList.add("over"); });
    zone.addEventListener("dragleave", () => zone.classList.remove("over"));
    zone.addEventListener("drop", async (e) => { e.preventDefault(); e.stopPropagation(); zone.classList.remove("over"); const entries = await this.entriesFromDrop(e.dataTransfer); d.close(); if (entries.length) this.open(entries, { mailboxOnly: true }); });
    body.append(zone, el("div", { class: "btn-row", style: { justifyContent: "center", marginTop: "12px" } },
      el("button", { class: "btn primary", onclick: async () => { const en = await this.pick(); if (en.length) { d.close(); this.open(en, { mailboxOnly: true }); } } }, icon("upload"), "Choose file…"),
      el("button", { class: "btn", onclick: async () => { const en = await this.pick({ directory: true }); if (en.length) { d.close(); this.open(en, { mailboxOnly: true }); } } }, icon("folder-open"), "Choose folder…")));
    qs(".dlg-foot", d).append(el("button", { class: "btn", onclick: () => d.close() }, "Cancel"));
    d.showModal();
  },

  dialog(id, title) {
    let d = $(id);
    if (!d) {
      d = el("dialog", { id, class: "wide qb-dialog qb-import" }, el("div", { class: "dlg-head" }, el("h3", { class: "qb-dlg-title" }), el("button", { class: "icon-btn", title: "Close", onclick: () => d.close() }, icon("close"))), el("div", { class: "dlg-body" }), el("div", { class: "dlg-foot" }));
      document.body.appendChild(d);
    }
    qs(".qb-dlg-title", d).textContent = title;
    qs(".dlg-body", d).innerHTML = ""; qs(".dlg-foot", d).innerHTML = "";
    return d;
  },

  // ------------------------------------------------------------------ drag and drop
  /**
   * Dropped files are opened by fileopen.js (and reach this module through hookFileOpen); dropped
   * folders — a Maildir, an Apple Mail or Thunderbird folder, an Outlook Express store — are taken
   * here, before fileopen.js sees the drop.
   */
  wireDrop() {
    const isFolder = (dt) => Array.from(dt?.items || []).some((i) => i.webkitGetAsEntry && i.webkitGetAsEntry()?.isDirectory);
    window.addEventListener("drop", async (e) => {
      if (!state.token || !isFolder(e.dataTransfer) || (e.target?.closest && e.target.closest("#composeDialog, #cEditor, .qb-drop-zone"))) return;
      e.preventDefault();
      const entries = await this.entriesFromDrop(e.dataTransfer);
      if (entries.length) this.open(entries, { mailboxOnly: true });
    }, true);
  },

  /** True when the server's sniffer says the first bytes of [file] are a mail store or an archive. */
  async isMailStore(file) {
    try {
      const det = await api("/api/mailbox/sniff", { method: "POST", query: { name: file.name, size: file.size }, rawBody: file.slice(0, 65536) });
      return (det.category === "mailbox" || det.category === "archive") ? det : null;
    } catch { return null; }
  },

  /**
   * Routes mailboxes away from fileopen.js: files it is asked to open are sniffed first (only their
   * first 64 KB is sent), and a mailbox it has already opened (handed over by a shell) is passed on.
   */
  hookFileOpen() {
    if (typeof FileOpen === "undefined" || !FileOpen || FileOpen.__mailboxes) return;
    FileOpen.__mailboxes = true;
    const openFiles = FileOpen.openFiles.bind(FileOpen), show = FileOpen.show.bind(FileOpen);
    this.fileOpenFiles = openFiles;
    FileOpen.openFiles = async (files) => {
      const rest = [];
      for (const f of files) { if (await this.isMailStore(f)) await this.open([{ file: f, path: f.name }], { mailboxOnly: true }); else rest.push(f); }
      if (rest.length) return openFiles(rest);
    };
    const fromOpened = async (r) => {
      const blob = await (await fetch(FileOpen.fileUrl("/api/file/raw", r))).blob();
      return this.open([{ file: new File([blob], r.name), path: r.name }], { mailboxOnly: true });
    };
    FileOpen.show = (r) => (r && r.kind === "mailbox" ? fromOpened(r) : show(r));
    FileOpen.importMailbox = (r) => fromOpened(r);
  },

  /** Files and folders from a drop, with their relative paths (folders are walked). */
  async entriesFromDrop(dt) {
    const items = Array.from(dt.items || []).map((i) => (i.webkitGetAsEntry ? i.webkitGetAsEntry() : null)).filter(Boolean);
    if (!items.length) return Array.from(dt.files || []).map((f) => ({ file: f, path: f.name }));
    const out = [];
    const readAll = (reader) => new Promise((res) => { const acc = []; const next = () => reader.readEntries((batch) => { if (!batch.length) res(acc); else { acc.push(...batch); next(); } }, () => res(acc)); next(); });
    const walk = async (entry, prefix) => {
      if (entry.isFile) { const f = await new Promise((res, rej) => entry.file(res, rej)).catch(() => null); if (f) out.push({ file: f, path: prefix + f.name }); }
      else if (entry.isDirectory) for (const c of await readAll(entry.createReader())) await walk(c, prefix + entry.name + "/");
    };
    for (const it of items) await walk(it, "");
    return out;
  },

  // ------------------------------------------------------------------ upload and analysis
  xhrUpload(query, file, onProgress) {
    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      const u = new URL("/api/mailbox/upload", location.origin);
      for (const [k, v] of Object.entries(query)) if (v) u.searchParams.set(k, v);
      x.open("POST", u);
      x.setRequestHeader("Authorization", "Bearer " + state.token);
      x.setRequestHeader("Content-Type", "application/octet-stream");
      x.upload.onprogress = (e) => onProgress && onProgress(e.loaded);
      x.onload = () => { let j = null; try { j = JSON.parse(x.responseText); } catch {} if (x.status >= 200 && x.status < 300) resolve(j); else reject(new Error((j && j.error) || x.statusText || "Upload failed")); };
      x.onerror = () => reject(new Error("Upload failed"));
      x.send(file);
    });
  },

  /** Uploads [entries] and returns the server's analysis. */
  async upload(entries, d) {
    const total = entries.reduce((s, e) => s + (e.file.size || 0), 0) || 1;
    let sent = 0, upload = "";
    const body = qs(".dlg-body", d);
    const bar = el("progress", { max: 1000, value: 0, style: { width: "100%" } });
    const line = el("div", { class: "small muted" });
    body.append(el("p", { text: entries.length === 1 ? `Reading ${entries[0].file.name}…` : `Reading ${plural(entries.length, "file")}…` }), bar, line);
    for (const en of entries) {
      line.textContent = `${en.path} · ${fmtSize(sent)} of ${fmtSize(total)}`;
      const r = await this.xhrUpload({ upload, path: en.path }, en.file, (n) => { bar.value = Math.round(((sent + n) / total) * 1000); });
      upload = r.upload; sent += en.file.size || 0;
      bar.value = Math.round((sent / total) * 1000);
    }
    line.textContent = "Working out what it is…";
    bar.removeAttribute("value");
    return api("/api/mailbox/analyze", { method: "POST", query: { upload, open: this.fileOpenFiles && entries.length === 1 ? "false" : "" } });
  },

  /** Opens whatever [entries] are: a message, a mailbox (import), PIM files, or a document hand-off. */
  async open(entries, { mailboxOnly = false } = {}) {
    if (!state.token) { toast("Sign in first, then open the file again."); return; }
    const d = this.dialog("qbImport", "Open");
    if (!d.open) d.showModal();
    let a;
    try { a = await this.upload(entries, d); }
    catch (e) { qs(".dlg-body", d).innerHTML = ""; qs(".dlg-body", d).append(el("p", { class: "danger", text: e.message })); qs(".dlg-foot", d).append(el("button", { class: "btn", onclick: () => d.close() }, "Close")); return; }
    return this.route(a, d, entries, { mailboxOnly });
  },

  async route(a, d, entries, { mailboxOnly }) {
    const det = a.detection || {};
    if (a.opened) {
      d.close();
      await folders.load();
      shell.setModule("mail", { silent: true });
      await list.load(a.opened.folder);
      reading.open({ folder: a.opened.folder, uid: a.opened.uid });
      toast(`Opened ${a.name} (${det.label || "message"}). It is kept under Local Archives ▸ Opened files.`);
      for (const w of a.warnings || []) toast(w, { danger: true });
      return;
    }
    if (det.category === "mailbox" && det.supported) return this.importDialog(a, d);
    if (this.fileOpenFiles && entries.length === 1 && det.category !== "mailbox" && det.category !== "archive" && det.category !== "unknown") {
      // Not a mailbox after all (a zip that is a .docx, a single message…): the Open File window shows it.
      d.close(); api("/api/mailbox/upload", { method: "DELETE", query: { upload: a.upload } }).catch(() => {});
      return this.fileOpenFiles([entries[0].file]);
    }
    if ((det.category === "calendar" || det.category === "contacts") && entries.length === 1 && !mailboxOnly) return this.pimImport(a, d, entries[0].file);
    this.describe(a, d, entries);
  },

  async pimImport(a, d, file) {
    d.close();
    const kind = a.detection.category === "calendar" ? "ics" : "vcf";
    try {
      const r = await api("/api/pim/import", { method: "POST", query: { kind, tz: VIEW_TZ }, rawBody: file, contentType: "text/plain" });
      toast(`${a.detection.category === "calendar" ? "Calendar" : "Contacts"}: ${plural(r.imported || 0, "item")} imported${r.updated ? `, ${r.updated} updated` : ""}.`);
      if (typeof pim !== "undefined" && pim.load) await pim.load();
      shell.setModule(a.detection.category === "calendar" ? "calendar" : "people");
    } catch (e) { toast(e.message, { danger: true }); }
  },

  /** Files QuillPost does not import itself: say what they are and where they open. */
  describe(a, d, entries) {
    const det = a.detection || {};
    qs(".qb-dlg-title", d).textContent = a.name || "Open";
    const body = qs(".dlg-body", d); body.innerHTML = "";
    const apps = { document: ["QuillPen", "word processor"], spreadsheet: ["QuillGrid", "spreadsheet"], presentation: ["QuillStage", "presentation program"] };
    const app = apps[det.category];
    body.append(el("p", {}, el("b", { text: det.label || "Unknown file" }), det.program ? ` — ${det.program}` : ""));
    if (app) body.append(el("p", { text: `This is not mail. It opens in ${app[0]}, the Quill suite's ${app[1]}, with its formatting — or in any other ${app[1]} on this device. You can also send it as an attachment.` }));
    else if (det.note) body.append(el("p", { text: det.note }));
    else if (!det.supported) body.append(el("p", { text: "QuillPost could not open this file." }));
    if (det.category === "mailbox" && !det.supported) body.append(el("p", { class: "small muted", text: "See docs/FORMATS.md for the mail programs QuillPost can import from." }));
    const foot = qs(".dlg-foot", d);
    const file = entries.length === 1 ? entries[0].file : null;
    if (file && det.category !== "unknown") {
      foot.append(el("button", { class: "btn", onclick: () => { const u = URL.createObjectURL(file); const x = el("a", { href: u, download: file.name }); document.body.appendChild(x); x.click(); x.remove(); setTimeout(() => URL.revokeObjectURL(u), 30000); } }, icon("external"), app ? `Open in ${app[0]} / another app` : "Save a copy"));
      foot.append(el("button", { class: "btn", onclick: async () => { d.close(); const b64 = await readFileDataUrl(file); compose.open({ mode: "new", attachments: [{ fileName: file.name, mimeType: file.type || det.mime || "application/octet-stream", base64: b64.substring(b64.indexOf(",") + 1) }] }); } }, icon("attach"), "Attach to new email"));
    }
    foot.append(el("button", { class: "btn primary", onclick: () => d.close() }, "Close"));
    if (a.upload) api("/api/mailbox/upload", { method: "DELETE", query: { upload: a.upload } }).catch(() => {});
  },

  // ------------------------------------------------------------------ the Import Mailbox dialog
  importDialog(a, d) {
    const det = a.detection;
    qs(".qb-dlg-title", d).textContent = "Import Mailbox";
    const body = qs(".dlg-body", d); body.innerHTML = "";
    const foot = qs(".dlg-foot", d); foot.innerHTML = "";
    body.append(el("div", { class: "qb-import-head" }, icon("box", 32), el("div", {},
      el("b", { text: a.name }),
      el("div", { class: "small", text: [det.label, det.program, det.variant && !String(det.label).includes(det.variant) ? det.variant : ""].filter(Boolean).join(" · ") }),
      el("div", { class: "small muted", text: `${plural(a.totalMessages || 0, "message")}${a.totalItems ? ` · ${plural(a.totalItems, "contact/calendar/task item")}` : ""} in ${plural(a.folders.length, "folder")}` }))));
    if (det.note) body.append(el("p", { class: "small muted", text: det.note }));
    for (const w of (a.warnings || []).slice(0, 8)) body.append(el("p", { class: "small warn", text: "⚠ " + w }));

    // Folder tree with check boxes.
    const kindIcon = { mail: "folder", contacts: "contact", calendar: "calendar", tasks: "todo", notes: "note", journal: "note" };
    const checks = new Map();
    const tree = el("div", { class: "qb-import-tree", role: "tree" });
    for (const f of a.folders) {
      const cb = el("input", { type: "checkbox" }); cb.checked = f.count > 0 || a.folders.some((g) => g.path.startsWith(f.path + "/") && g.count > 0);
      checks.set(f.id, cb);
      cb.onchange = () => { for (const g of a.folders) if (g.path.startsWith(f.path + "/")) checks.get(g.id).checked = cb.checked; };
      tree.append(el("label", { class: "qb-import-row", style: { paddingLeft: 6 + f.depth * 18 + "px" }, title: f.path }, cb, icon(kindIcon[f.kind] || "folder", 16), el("span", { class: "grow", text: f.name }),
        el("span", { class: "small muted", text: f.kind === "mail" || f.kind === "notes" || f.kind === "journal" ? (f.count ? `${f.count}${f.unread > 0 ? ` (${f.unread} unread)` : ""}` : "") : `${f.count} ${f.kind}` })));
    }
    body.append(el("div", { class: "btn-row small", style: { margin: "6px 0" } },
      el("button", { class: "btn sm", onclick: () => checks.forEach((c) => (c.checked = true)) }, "Select all"),
      el("button", { class: "btn sm", onclick: () => checks.forEach((c) => (c.checked = false)) }, "Select none")), tree);

    // Destination.
    const imapOk = !!state.capabilities?.folders;
    const rArchive = el("input", { type: "radio", name: "qbImpDest", value: "archive" }); rArchive.checked = !imapOk || prefs.get("importDest", "archive") === "archive";
    const rImap = el("input", { type: "radio", name: "qbImpDest", value: "imap", disabled: !imapOk || null }); rImap.checked = !rArchive.checked;
    const archName = el("input", { type: "text", value: a.name, style: { width: "260px" }, "aria-label": "Archive name" });
    const parentSel = select([["", "(top level)"], ...state.folders.map((f) => [f.path, f.path])], prefs.get("importParent", ""), { "aria-label": "Put imported folders under" });
    const sub = el("input", { type: "text", value: "Imported - " + a.name, style: { width: "220px" }, "aria-label": "New folder name" });
    body.append(el("h4", { text: "Import into" }),
      el("label", { class: "qb-import-dest" }, rArchive, el("div", {}, el("b", { text: "Local Archives on this device" }), el("div", { class: "small muted", text: "A read-only copy you can browse and search in the folder pane. Nothing is uploaded to your mail server." }), el("div", {}, "Name ", archName))),
      el("label", { class: "qb-import-dest" + (imapOk ? "" : " disabled") }, rImap, el("div", {}, el("b", { text: `Folders in ${state.account?.email || "your account"}` }), el("div", { class: "small muted", text: imapOk ? "Copied to your mail server with their read, replied, flagged and forwarded status and original arrival dates, so they appear on every device." : "Not available: this account uses POP3, which has no server folders." }), el("div", {}, "Under ", parentSel, " in a new folder ", sub))));
    const pimBox = el("input", { type: "checkbox" }); pimBox.checked = true;
    const delBox = el("input", { type: "checkbox" });
    if (a.totalItems) body.append(el("label", { class: "check" }, pimBox, " Also import contacts, calendar items and tasks into People, Calendar and To Do"));
    body.append(el("label", { class: "check" }, delBox, " Include messages the other program had marked as deleted"));

    const go = el("button", { class: "btn primary" }, icon("upload"), "Import");
    go.onclick = async () => {
      const ids = a.folders.filter((f) => checks.get(f.id).checked).map((f) => f.id);
      if (!ids.length) { toast("Choose at least one folder."); return; }
      const dest = rImap.checked ? "imap" : "archive";
      prefs.set("importDest", dest); prefs.set("importParent", parentSel.value);
      const parent = dest === "imap" ? [parentSel.value, sub.value.trim()].filter(Boolean).join(this.delim()) : "";
      try {
        const job = await api("/api/mailbox/import", { method: "POST", body: { upload: a.upload, folders: ids, destination: dest, imapParent: parent, archiveName: archName.value.trim(), includePim: pimBox.checked, includeDeleted: delBox.checked } });
        this.progress(job, d, a, dest);
      } catch (e) { toast(e.message, { danger: true }); }
    };
    foot.append(el("button", { class: "btn", onclick: () => { d.close(); api("/api/mailbox/upload", { method: "DELETE", query: { upload: a.upload } }).catch(() => {}); } }, "Cancel"), go);
    if (!d.open) d.showModal();
  },

  delim() { return (state.folders.find((f) => f.delimiter) || {}).delimiter || "/"; },

  progress(job, d, a, dest) {
    const body = qs(".dlg-body", d); body.innerHTML = "";
    const foot = qs(".dlg-foot", d); foot.innerHTML = "";
    const bar = el("progress", { max: Math.max(1, job.total), value: 0, style: { width: "100%" } });
    const line = el("div", { class: "small muted" });
    const counts = el("div", {});
    body.append(el("p", { text: `Importing ${a.name} into ${dest === "imap" ? state.account?.email : "Local Archives"}…` }), bar, line, counts);
    const cancel = el("button", { class: "btn", onclick: async () => { cancel.disabled = true; try { await api("/api/mailbox/job/cancel", { method: "POST", query: { id: job.id } }); } catch {} } }, "Cancel");
    foot.append(cancel);
    const tick = async () => {
      let j;
      try { j = await api("/api/mailbox/job", { query: { id: job.id } }); } catch (e) { line.textContent = e.message; return setTimeout(tick, 1500); }
      bar.max = Math.max(1, j.total); bar.value = j.done;
      line.textContent = j.current ? `${j.current} · ${j.done} of ${j.total}` : `${j.done} of ${j.total}`;
      counts.textContent = `${plural(j.imported, "item")} imported${j.skipped ? `, ${j.skipped} skipped` : ""}${j.contacts ? ` · ${j.contacts} contacts` : ""}${j.events ? ` · ${j.events} calendar items` : ""}${j.tasks ? ` · ${j.tasks} tasks` : ""}`;
      if (j.state === "running") return setTimeout(tick, 700);
      this.finished(j, d, a, dest);
    };
    setTimeout(tick, 300);
  },

  async finished(j, d, a, dest) {
    const body = qs(".dlg-body", d); const foot = qs(".dlg-foot", d); foot.innerHTML = "";
    const title = j.state === "done" ? "Import finished" : j.state === "cancelled" ? "Import cancelled" : "Import failed";
    qs(".qb-dlg-title", d).textContent = title;
    if (j.error) body.append(el("p", { class: "danger", text: j.error }));
    const notes = [...(j.report || []), ...(j.warnings || [])];
    if (notes.length) {
      const det = el("details", { class: "qb-import-report" }, el("summary", { text: `Report: ${plural(notes.length, "note")} (skipped or changed items)` }), el("ul", {}, ...notes.slice(0, 300).map((n) => el("li", { class: "small", text: n }))));
      if (j.skipped) det.open = true;
      body.append(det);
      foot.append(el("button", { class: "btn", onclick: () => { const blob = new Blob([`${title}: ${a.name}\n${j.imported} imported, ${j.skipped} skipped\n\n` + notes.join("\n")], { type: "text/plain" }); const u = URL.createObjectURL(blob); const x = el("a", { href: u, download: "import-report.txt" }); document.body.appendChild(x); x.click(); x.remove(); } }, icon("download"), "Save report"));
    }
    if (j.firstFolder) foot.append(el("button", { class: "btn", onclick: () => { d.close(); shell.setModule("mail", { silent: true }); list.load(j.firstFolder); } }, icon("folder"), "Show imported mail"));
    foot.append(el("button", { class: "btn primary", onclick: () => d.close() }, "Close"));
    folders.load();
    if (typeof pim !== "undefined" && pim.load && (j.contacts || j.events || j.tasks)) pim.load().catch(() => {});
    api("/api/mailbox/upload", { method: "DELETE", query: { upload: a.upload } }).catch(() => {});
  },
};

/** The iOS app hands over files opened with QuillPost as base64 (the server is remote there). */
if (typeof window !== "undefined") window.quillpostOpenData = function (name, base64) {
  const go = () => {
    const bin = atob(base64); const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const file = new File([bytes], name);
    if (typeof FileOpen !== "undefined" && FileOpen) FileOpen.openFiles([file]); else mailImport.open([{ file, path: name }]);
  };
  if (typeof state === "undefined" || !state.token) { mailImport.whenSignedIn(go); return false; }
  go();
  return true;
};

(function defineImportCommands() {
  if (typeof Commands === "undefined") return;
  const signedIn = () => typeof state !== "undefined" && !!state.token;
  Commands.def([
    { id: "mail.importMailbox", label: "Import Mailbox…", icon: "mailbox-in", tab: "File", group: "Open & Export", scope: "global", popular: true, isEnabled: signedIn,
      tip: "Imports mail from Outlook, Thunderbird, Apple Mail, Eudora, Outlook Express, Pegasus, Gmail Takeout and others into a folder of this account or Local Archives",
      keywords: "pst ost mbox maildir mh dbx eudora thunderbird apple mail outlook express pegasus takeout import migrate", run: () => mailImport.chooseSource() },
    { id: "mail.localArchives", label: "Local Archives", icon: "box", tab: "Folder", group: "Actions", scope: "global", isEnabled: signedIn,
      tip: "Mail imported from other programs, kept read-only on this device", keywords: "archive imported pst offline", run: () => mailImport.showArchives() },
  ]);
})();

if (typeof document !== "undefined") { try { mailImport.install(); } catch (e) { console.error("Mail import failed to start", e); } }
