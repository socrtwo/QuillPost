/* Quillbox — People: contacts, favourites, contact lists, categories, contact cards and the
 * contact editor, with vCard / CSV import and export. Loaded before app.js.
 */
"use strict";

const PHONE_LABELS = [["mobile", "Mobile"], ["home", "Home"], ["work", "Work"], ["fax", "Fax"], ["other", "Other"]];
const EMAIL_LABELS = [["home", "Personal"], ["work", "Work"], ["other", "Other"]];

const peopleUI = {
  built: false,
  filter: "all",
  query: "",
  selected: null,

  show() {
    if (!this.built) this.build();
    this.render();
  },
  build() {
    this.built = true;
    const host = $("modPeople"); host.innerHTML = "";
    host.append(
      el("div", { class: "ribbon" }, el("div", { class: "ribbon-bar" },
        el("div", { class: "split primary-split" }, el("button", { class: "rb primary", onclick: () => this.edit(null) }, icon("person-add"), el("span", { class: "lbl", text: "New contact" })), el("button", { class: "rb drop primary", title: "More", onclick: (e) => { e.stopPropagation(); menu.openFor(e.currentTarget, [{ label: "New contact", icon: "person-add", onClick: () => this.edit(null) }, { label: "New contact list", icon: "people", onClick: () => this.editList(null) }]); } }, icon("chev-down"))),
        el("span", { class: "rsep" }),
        el("button", { class: "rb", onclick: () => this.importFile() }, icon("upload"), el("span", { class: "lbl", text: "Import contacts" })),
        el("button", { class: "rb", onclick: (e) => { e.stopPropagation(); menu.openFor(e.currentTarget, [{ label: "vCard (.vcf)", icon: "contact", onClick: () => download(apiUrl("/api/pim/export", { kind: "vcf" })) }, { label: "CSV (spreadsheet)", icon: "table", onClick: () => download(apiUrl("/api/pim/export", { kind: "csv" })) }]); } }, icon("download"), el("span", { class: "lbl", text: "Export contacts" }), icon("chev-down", 12)))),
      el("div", { class: "people-body" },
        el("nav", { class: "side-pane", id: "pplSide" }),
        el("section", { class: "people-list pane-card", id: "pplList" }),
        el("section", { class: "people-detail pane-card", id: "pplDetail" })));
    pim.on(() => { if (state.module === "people") this.render(); });
  },
  render() {
    if (!this.built) return;
    this.renderSide(); this.renderList(); this.renderDetail();
  },
  renderSide() {
    const side = $("pplSide"); side.innerHTML = "";
    const c = pim.data.contacts, l = pim.data.contactLists;
    const item = (id, ic, label, n) => el("button", { class: "nav-item" + (this.filter === id ? " active" : ""), onclick: () => { this.filter = id; this.selected = null; this.render(); $("modPeople").classList.remove("nav-open"); } }, icon(ic, 18), el("span", { class: "name", text: label }), n != null ? el("span", { class: "count", text: String(n) }) : null);
    side.append(el("div", { class: "side-head", text: "Your contacts" }),
      item("all", "people", "All contacts", c.length),
      item("favorites", "star", "Favorites", c.filter((x) => x.favorite).length),
      item("lists", "list", "Contact lists", l.length));
    const cats = state.settings?.categories || [];
    const used = cats.filter((cat) => c.some((x) => (x.categories || []).includes(cat.name)));
    if (used.length) { side.appendChild(el("div", { class: "side-head", text: "Categories" })); used.forEach((cat) => { const b = item("cat:" + cat.name, "tag", cat.name, c.filter((x) => (x.categories || []).includes(cat.name)).length); b.querySelector("svg").style.color = cat.color; side.appendChild(b); }); }
  },
  visible() {
    const q = this.query.toLowerCase();
    if (this.filter === "lists") return pim.data.contactLists.filter((l) => !q || l.name.toLowerCase().includes(q) || l.members.some((m) => (m.email + " " + m.name).toLowerCase().includes(q))).sort((a, b) => a.name.localeCompare(b.name)).map((l) => ({ list: l }));
    let cs = pim.data.contacts;
    if (this.filter === "favorites") cs = cs.filter((x) => x.favorite);
    if (this.filter.startsWith("cat:")) cs = cs.filter((x) => (x.categories || []).includes(this.filter.slice(4)));
    if (q) cs = cs.filter((x) => [pim.contactName(x), x.company, x.jobTitle, x.nickname, ...(x.emails || []).map((e) => e.value), ...(x.phones || []).map((p) => p.value)].some((v) => (v || "").toLowerCase().includes(q)));
    const sortBy = prefs.get("peopleSort", "first");
    const key = (x) => (sortBy === "last" ? (x.lastName || pim.contactName(x)) : pim.contactName(x)).toLowerCase();
    return [...cs].sort((a, b) => key(a).localeCompare(key(b))).map((x) => ({ contact: x }));
  },
  renderList() {
    const host = $("pplList"); host.innerHTML = "";
    const title = this.filter === "all" ? "All contacts" : this.filter === "favorites" ? "Favorites" : this.filter === "lists" ? "Contact lists" : this.filter.slice(4);
    host.appendChild(el("div", { class: "list-header" },
      el("button", { class: "icon-btn mobile-only", title: "Navigation", onclick: () => $("modPeople").classList.toggle("nav-open") }, icon("menu")),
      el("h2", { text: title }), el("span", { class: "grow" }),
      this.filter !== "lists" ? el("button", { class: "icon-btn", title: "Sort", onclick: (e) => { e.stopPropagation(); const cur = prefs.get("peopleSort", "first"); menu.openFor(e.currentTarget, [{ label: "First name", checked: cur === "first", onClick: () => { prefs.set("peopleSort", "first"); this.renderList(); } }, { label: "Last name", checked: cur === "last", onClick: () => { prefs.set("peopleSort", "last"); this.renderList(); } }]); } }, icon("sort")) : null));
    const items = this.visible();
    const scroll = el("div", { class: "message-list" });
    if (!items.length) scroll.appendChild(el("div", { class: "empty-list" }, icon(this.filter === "lists" ? "list" : "people", 56), el("div", { text: this.query ? "No matches" : this.filter === "lists" ? "No contact lists yet" : "No contacts yet" }),
      el("div", { class: "btn-row", style: { justifyContent: "center", marginTop: "10px" } }, this.filter === "lists" ? el("button", { class: "btn", onclick: () => this.editList(null) }, icon("plus"), "New contact list") : el("button", { class: "btn", onclick: () => this.edit(null) }, icon("person-add"), "New contact"), this.filter === "lists" ? null : el("button", { class: "btn", onclick: () => this.importFile() }, icon("upload"), "Import"))));
    let letter = null;
    const sortBy = prefs.get("peopleSort", "first");
    for (const it of items) {
      if (it.list) {
        const l = it.list;
        scroll.appendChild(el("div", { class: "person-row" + (this.selected === l.id ? " open" : ""), onclick: () => this.select(l.id) },
          el("div", { class: "avatar", style: { background: "var(--accent)" } }, icon("people", 16)),
          el("div", { class: "p-main" }, el("div", { class: "p-name", text: l.name }), el("div", { class: "p-sub", text: plural(l.members.length, "member") }))));
        continue;
      }
      const c = it.contact;
      const n = pim.contactName(c);
      const L = ((sortBy === "last" ? c.lastName || n : n).trim()[0] || "#").toUpperCase();
      const letterKey = /[A-Z]/.test(L) ? L : "#";
      if (letterKey !== letter) { letter = letterKey; scroll.appendChild(el("div", { class: "day-sep", text: letter })); }
      scroll.appendChild(el("div", { class: "person-row" + (this.selected === c.id ? " open" : ""), onclick: () => this.select(c.id), oncontextmenu: (e) => { e.preventDefault(); menu.openAt(e.clientX, e.clientY, this.contactMenu(c)); } },
        el("div", { class: "avatar", text: initials(n, c.emails?.[0]?.value), style: { background: avatarColor(c.emails?.[0]?.value || n) } }),
        el("div", { class: "p-main" }, el("div", { class: "p-name", text: n }), el("div", { class: "p-sub", text: c.emails?.[0]?.value || c.phones?.[0]?.value || c.company || "" })),
        c.favorite ? icon("star-fill", 14) : null));
    }
    host.appendChild(scroll);
  },
  select(id) { this.selected = id; if (state.module !== "people") shell.setModule("people"); this.render(); $("modPeople").classList.add("show-detail"); },
  back() { const m = $("modPeople"); if (m.classList.contains("show-detail")) { m.classList.remove("show-detail"); return true; } return false; },
  contactMenu(c) {
    return [
      c.emails?.length ? { label: "Send email", icon: "mail", onClick: () => this.emailTo([c]) } : null,
      c.emails?.length ? { label: "Schedule a meeting", icon: "calendar", onClick: () => calendarUI.newEvent({ attendees: [{ email: c.emails[0].value, name: pim.contactName(c) }] }) } : null,
      { label: c.favorite ? "Remove from favorites" : "Add to favorites", icon: "star", onClick: () => pim.save("contacts", { ...c, favorite: !c.favorite }) },
      { label: "Edit", icon: "edit", onClick: () => this.edit(c) },
      { label: "Add to contact list…", icon: "people", onClick: () => this.addToList([c]) },
      { label: "Categorize", icon: "tag", sub: () => (state.settings.categories || []).map((cat) => ({ label: cat.name, color: cat.color, checked: (c.categories || []).includes(cat.name), onClick: () => pim.save("contacts", { ...c, categories: (c.categories || []).includes(cat.name) ? c.categories.filter((x) => x !== cat.name) : [...(c.categories || []), cat.name] }) })) },
      { label: "Export as vCard", icon: "download", onClick: () => this.exportOne(c) },
      { sep: true },
      { label: "Delete", icon: "trash", danger: true, onClick: async () => { if (!confirm(`Delete ${pim.contactName(c)}?`)) return; await pim.remove("contacts", c.id); this.selected = null; } },
    ];
  },
  emailTo(cs) {
    const to = cs.flatMap((c) => (c.emails?.[0] ? [`${pim.contactName(c)} <${c.emails[0].value}>`] : []));
    shell.setModule("mail");
    compose.open({ mode: "new", to: to.join(", ") });
  },
  exportOne(c) {
    // Build a one-contact vCard in the browser so it works without a round trip.
    const lines = ["BEGIN:VCARD", "VERSION:3.0", `N:${c.lastName || ""};${c.firstName || ""};${c.middleName || ""};;`, `FN:${pim.contactName(c)}`];
    if (c.company) lines.push(`ORG:${c.company}`); if (c.jobTitle) lines.push(`TITLE:${c.jobTitle}`);
    (c.emails || []).forEach((e) => lines.push(`EMAIL;TYPE=INTERNET:${e.value}`)); (c.phones || []).forEach((p) => lines.push(`TEL;TYPE=${p.label.toUpperCase()}:${p.value}`));
    if (c.birthday) lines.push(`BDAY:${c.birthday}`); lines.push("END:VCARD");
    const blob = new Blob([lines.join("\r\n") + "\r\n"], { type: "text/vcard" });
    const a = el("a", { href: URL.createObjectURL(blob), download: (pim.contactName(c) || "contact").replace(/[\\/:*?"<>|]/g, "_") + ".vcf" }); document.body.appendChild(a); a.click(); a.remove();
  },
  renderDetail() {
    const host = $("pplDetail"); host.innerHTML = "";
    const c = pim.data.contacts.find((x) => x.id === this.selected);
    const l = pim.data.contactLists.find((x) => x.id === this.selected);
    if (!c && !l) { host.appendChild(el("div", { class: "reading-empty" }, icon("contact", 96), el("div", { text: "Select a contact to see the details" }))); return; }
    const top = el("div", { class: "reading-toolbar" }, el("button", { class: "icon-btn mobile-only", title: "Back", onclick: () => $("modPeople").classList.remove("show-detail") }, icon("back")));
    host.appendChild(top);
    const scroll = el("div", { class: "reading-scroll" });
    host.appendChild(scroll);
    if (l) {
      top.append(el("span", { class: "grow" }), el("button", { class: "btn subtle", onclick: () => this.emailList(l) }, icon("mail"), "Email the list"), el("button", { class: "btn subtle", onclick: () => this.editList(l) }, icon("edit"), "Edit"), el("button", { class: "btn subtle danger", onclick: async () => { if (!confirm(`Delete the list "${l.name}"? The contacts themselves stay.`)) return; await pim.remove("contactLists", l.id); this.selected = null; } }, icon("trash"), "Delete"));
      scroll.append(el("div", { class: "contact-hero" }, el("div", { class: "avatar huge", style: { background: "var(--accent)" } }, icon("people", 34)), el("div", {}, el("h2", { text: l.name }), el("div", { class: "muted", text: plural(l.members.length, "member") }))));
      if (l.notes) scroll.appendChild(el("p", { text: l.notes }));
      const ul = el("div", { class: "member-list" });
      l.members.forEach((m) => ul.appendChild(el("div", { class: "person-row", onclick: () => { const c2 = pim.contactByEmail(m.email); if (c2) this.select(c2.id); else this.card({ name: m.name, email: m.email }); } }, el("div", { class: "avatar", text: initials(m.name, m.email), style: { background: avatarColor(m.email) } }), el("div", { class: "p-main" }, el("div", { class: "p-name", text: m.name || m.email }), el("div", { class: "p-sub", text: m.email })))));
      scroll.appendChild(ul);
      return;
    }
    const n = pim.contactName(c);
    top.append(...[el("span", { class: "grow" }),
      c.emails?.length ? el("button", { class: "btn subtle", onclick: () => this.emailTo([c]) }, icon("mail"), "Email") : null,
      c.emails?.length ? el("button", { class: "btn subtle", onclick: () => calendarUI.newEvent({ attendees: [{ email: c.emails[0].value, name: n }] }) }, icon("calendar"), "Meeting") : null,
      el("button", { class: "btn subtle", onclick: () => this.edit(c) }, icon("edit"), "Edit"),
      el("button", { class: "icon-btn", title: c.favorite ? "Remove from favorites" : "Add to favorites", onclick: () => pim.save("contacts", { ...c, favorite: !c.favorite }) }, icon(c.favorite ? "star-fill" : "star")),
      el("button", { class: "icon-btn", title: "More", onclick: (e) => { e.stopPropagation(); menu.openFor(e.currentTarget, this.contactMenu(c)); } }, icon("more"))].filter(Boolean));
    scroll.appendChild(el("div", { class: "contact-hero" },
      el("div", { class: "avatar huge", text: initials(n, c.emails?.[0]?.value), style: { background: avatarColor(c.emails?.[0]?.value || n) } }),
      el("div", {}, el("h2", { text: n }), el("div", { class: "muted", text: [c.jobTitle, c.department, c.company].filter(Boolean).join(" · ") }),
        (c.categories || []).length ? el("div", { class: "cat-chips", style: { marginTop: "6px" } }, ...c.categories.map((x) => el("span", { class: "cat-chip", style: { background: mailplus.categoryColor(x) } }, x))) : null)));
    const sec = (title, rows) => { const r = rows.filter(Boolean); if (!r.length) return; scroll.append(el("h4", { class: "section-title", text: title }), el("div", { class: "detail-card" }, ...r)); };
    const line = (ic, label, value, href) => value ? el("div", { class: "detail-line" }, icon(ic, 16), el("div", {}, el("div", { class: "small muted", text: label }), href ? el("a", { href, text: value, onclick: href.startsWith("mailto:") ? (e) => { e.preventDefault(); shell.setModule("mail"); compose.open({ mode: "new", to: `${n} <${value}>` }); } : null }) : el("div", { text: value }))) : null;
    sec("Contact information", [
      ...(c.emails || []).map((e) => line("mail", (EMAIL_LABELS.find((x) => x[0] === e.label) || [0, e.label])[1] + " email", e.value, "mailto:" + e.value)),
      ...(c.phones || []).map((p) => line("phone", (PHONE_LABELS.find((x) => x[0] === p.label) || [0, p.label])[1], p.value, "tel:" + p.value.replace(/[^\d+]/g, ""))),
      ...(c.addresses || []).map((a) => line("map-pin", (a.label === "work" ? "Work" : a.label === "home" ? "Home" : a.label) + " address", [a.street, [a.city, a.region, a.postalCode].filter(Boolean).join(", "), a.country].filter(Boolean).join("\n"), "https://www.openstreetmap.org/search?query=" + encodeURIComponent([a.street, a.city, a.region, a.postalCode, a.country].filter(Boolean).join(", ")))),
      line("globe", "Website", c.website, /^https?:/i.test(c.website) ? c.website : c.website ? "https://" + c.website : null),
    ]);
    const bday = c.birthday ? (c.birthday.startsWith("--") ? parseYmd("2000" + c.birthday.slice(1)).toLocaleDateString(undefined, { month: "long", day: "numeric" }) : parseYmd(c.birthday).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" })) : "";
    const anniv = c.anniversary && /^\d{4}/.test(c.anniversary) ? parseYmd(c.anniversary).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" }) : "";
    sec("Personal", [line("cake", "Birthday", bday), line("star", "Anniversary", anniv), line("user", "Nickname", c.nickname)]);
    sec("Work", [line("building", "Company", c.company), line("contact", "Job title", c.jobTitle), line("people", "Department", c.department)]);
    if (c.notes) sec("Notes", [el("div", { class: "detail-line notes", text: c.notes })]);
    const lists = pim.data.contactLists.filter((x) => x.members.some((m) => (c.emails || []).some((e) => e.value.toLowerCase() === m.email.toLowerCase())));
    if (lists.length) sec("Member of", lists.map((x) => el("button", { class: "btn sm", onclick: () => { this.filter = "lists"; this.select(x.id); } }, icon("people"), x.name)));
    if (c.emails?.length) {
      const recent = el("div", { class: "detail-card", text: "Searching your mail…" });
      scroll.append(el("h4", { class: "section-title", text: "Recent messages" }), recent);
      api("/api/search/advanced", { query: { folder: "INBOX", scope: "all", from: c.emails[0].value, limit: 8 } }).then((r) => {
        recent.innerHTML = "";
        if (!r.messages.length) recent.appendChild(el("div", { class: "small muted", text: "No messages from this contact." }));
        r.messages.forEach((m) => recent.appendChild(el("div", { class: "detail-line link", onclick: () => { shell.setModule("mail"); reading.open(m); } }, icon("mail", 16), el("div", { class: "grow" }, el("div", { text: m.subject }), el("div", { class: "small muted", text: fmtDate(m.date) })))));
      }).catch(() => { recent.textContent = "Could not search mail."; });
    }
  },
  search(q) { this.query = q; if (state.module !== "people") shell.setModule("people"); this.renderList(); },

  /** The contact editor; [prefill] = { name, email } when adding a sender from a message. */
  edit(c, prefill = null) {
    const isNew = !c;
    c = c ? JSON.parse(JSON.stringify(c)) : { firstName: "", lastName: "", emails: [], phones: [], addresses: [], categories: [] };
    if (prefill) {
      const existing = pim.contactByEmail(prefill.email);
      if (existing && isNew) { shell.setModule("people"); this.select(existing.id); toast("Already in your contacts"); return; }
      const parts = String(prefill.name || "").replace(/^"|"$/g, "").trim().split(/\s+/);
      if (parts.length > 1) { c.firstName = parts.slice(0, -1).join(" "); c.lastName = parts[parts.length - 1]; } else c.firstName = parts[0] || "";
      if (prefill.email) c.emails = [{ label: "home", value: prefill.email }];
    }
    if (!c.emails.length) c.emails.push({ label: "home", value: "" });
    if (!c.phones.length) c.phones.push({ label: "mobile", value: "" });
    let dlg = $("contactDialog");
    if (!dlg) { dlg = el("dialog", { id: "contactDialog", class: "wide" }); document.body.appendChild(dlg); }
    dlg.innerHTML = "";
    const inp = (v, ph, type = "text") => { const i = el("input", { type, placeholder: ph }); i.value = v || ""; return i; };
    const f = { prefix: inp(c.prefix, "Title"), firstName: inp(c.firstName, "First name"), middleName: inp(c.middleName, "Middle name"), lastName: inp(c.lastName, "Last name"), suffix: inp(c.suffix, "Suffix"), nickname: inp(c.nickname, "Nickname"), displayName: inp(c.displayName, "Display as (optional)"),
      company: inp(c.company, "Company"), department: inp(c.department, "Department"), jobTitle: inp(c.jobTitle, "Job title"), website: inp(c.website, "Website", "url"),
      birthday: inp(/^\d{4}-\d{2}-\d{2}$/.test(c.birthday || "") ? c.birthday : "", "", "date"), anniversary: inp(/^\d{4}-\d{2}-\d{2}$/.test(c.anniversary || "") ? c.anniversary : "", "", "date") };
    const notes = el("textarea", { rows: 4, placeholder: "Notes" }); notes.value = c.notes || "";
    const multi = (arr, labels, ph, type) => {
      const box = el("div", { class: "multi" });
      const render = () => {
        box.innerHTML = "";
        arr.forEach((x, i) => { const l = select(labels, x.label || labels[0][0], { style: { width: "130px" } }); const v = inp(x.value, ph, type); l.onchange = () => (x.label = l.value); v.oninput = () => (x.value = v.value); box.appendChild(el("div", { class: "multi-row" }, l, v, el("button", { class: "icon-btn", title: "Remove", onclick: () => { arr.splice(i, 1); render(); } }, icon("close")))); });
        box.appendChild(el("button", { class: "btn sm", onclick: () => { arr.push({ label: labels[0][0], value: "" }); render(); } }, icon("plus"), "Add"));
      };
      render(); return box;
    };
    const addrBox = el("div", { class: "multi" });
    const renderAddr = () => {
      addrBox.innerHTML = "";
      c.addresses.forEach((a, i) => {
        const fields = ["street", "city", "region", "postalCode", "country"].map((k) => { const v = inp(a[k], { street: "Street", city: "City", region: "State / region", postalCode: "ZIP / postal code", country: "Country" }[k]); v.oninput = () => (a[k] = v.value); return v; });
        const l = select([["home", "Home"], ["work", "Work"], ["other", "Other"]], a.label || "home", { style: { width: "130px" } }); l.onchange = () => (a.label = l.value);
        addrBox.appendChild(el("div", { class: "addr-block" }, el("div", { class: "multi-row" }, l, fields[0], el("button", { class: "icon-btn", onclick: () => { c.addresses.splice(i, 1); renderAddr(); } }, icon("close"))), el("div", { class: "grid-3" }, fields[1], fields[2], fields[3]), fields[4]));
      });
      addrBox.appendChild(el("button", { class: "btn sm", onclick: () => { c.addresses.push({ label: "home", street: "", city: "", region: "", postalCode: "", country: "" }); renderAddr(); } }, icon("plus"), "Add address"));
    };
    renderAddr();
    const cats = el("div", { class: "swatches" });
    (state.settings?.categories || []).forEach((cat) => { const cb = el("input", { type: "checkbox", value: cat.name }); cb.checked = (c.categories || []).includes(cat.name); cats.appendChild(el("label", { class: "check", style: { margin: "0 10px 0 0" } }, cb, el("span", { class: "cat-chip", style: { background: cat.color } }, cat.name))); });
    const fav = el("input", { type: "checkbox" }); fav.checked = !!c.favorite;
    const sect = (t, ...kids) => el("div", { class: "edit-sect" }, el("h4", { text: t }), ...kids);
    const body = el("div", { class: "dlg-body" },
      sect("Name", el("div", { class: "grid-3" }, f.prefix, f.firstName, f.middleName), el("div", { class: "grid-3" }, f.lastName, f.suffix, f.nickname), f.displayName),
      sect("Email", multi(c.emails, EMAIL_LABELS, "name@example.com", "email")),
      sect("Phone", multi(c.phones, PHONE_LABELS, "Phone number", "tel")),
      sect("Work", el("div", { class: "grid-3" }, f.company, f.department, f.jobTitle)),
      sect("Addresses", addrBox),
      sect("Other", el("div", { class: "grid-3" }, el("div", { class: "field" }, el("label", { text: "Birthday" }), f.birthday), el("div", { class: "field" }, el("label", { text: "Anniversary" }), f.anniversary), el("div", { class: "field" }, el("label", { text: "Website" }), f.website)), notes),
      sect("Categories", cats, el("label", { class: "check" }, fav, el("span", { text: "Favorite" }))));
    const save = async () => {
      const out = { ...c, notes: notes.value, favorite: fav.checked, categories: qsa("input:checked", cats).map((x) => x.value) };
      for (const [k, i] of Object.entries(f)) out[k] = i.value.trim();
      if (!out.birthday && c.birthday && c.birthday.startsWith("--")) out.birthday = c.birthday;
      out.emails = c.emails.filter((e) => e.value.trim()).map((e) => ({ label: e.label, value: e.value.trim() }));
      const bad = out.emails.find((e) => !isEmail(e.value));
      if (bad) return toast(`"${bad.value}" is not a valid email address.`, { danger: true });
      out.phones = c.phones.filter((p) => p.value.trim());
      out.addresses = c.addresses.filter((a) => [a.street, a.city, a.country].some((x) => (x || "").trim()));
      try { const saved = await pim.save("contacts", out); dlg.close(); toast(isNew ? "Contact added" : "Contact saved"); if (state.module === "people") this.select(saved.id); }
      catch (e) { toast("Could not save: " + e.message, { danger: true }); }
    };
    dlg.append(el("div", { class: "dlg-head" }, icon("contact", 20), el("h3", { text: isNew ? "New contact" : "Edit contact" }), el("button", { class: "icon-btn", onclick: () => dlg.close() }, icon("close"))), body,
      el("div", { class: "dlg-foot" }, !isNew ? el("button", { class: "btn danger", onclick: async () => { if (!confirm("Delete this contact?")) return; await pim.remove("contacts", c.id); dlg.close(); this.selected = null; } }, icon("trash"), "Delete") : null, el("span", { class: "grow" }), el("button", { class: "btn", onclick: () => dlg.close() }, "Cancel"), el("button", { class: "btn primary", onclick: save }, "Save")));
    dlg.showModal();
    setTimeout(() => f.firstName.focus(), 40);
  },
  editList(l) {
    const isNew = !l;
    const members = l ? l.members.map((m) => ({ ...m })) : [];
    let dlg = $("listDialog");
    if (!dlg) { dlg = el("dialog", { id: "listDialog", class: "narrow" }); document.body.appendChild(dlg); }
    dlg.innerHTML = "";
    const name = el("input", { type: "text", placeholder: "List name", value: l?.name || "" });
    const notes = el("textarea", { rows: 2, placeholder: "Description (optional)" }); notes.value = l?.notes || "";
    const box = el("div", { class: "member-list" });
    const add = el("input", { type: "text", placeholder: "Add a contact or an email address, then Enter" });
    const pop = el("div", { class: "ac-popup hidden", style: { position: "absolute" } });
    const render = () => { box.innerHTML = ""; members.forEach((m, i) => box.appendChild(el("div", { class: "person-row" }, el("div", { class: "avatar", text: initials(m.name, m.email), style: { background: avatarColor(m.email) } }), el("div", { class: "p-main" }, el("div", { class: "p-name", text: m.name || m.email }), el("div", { class: "p-sub", text: m.email })), el("button", { class: "icon-btn", onclick: () => { members.splice(i, 1); render(); } }, icon("close"))))); };
    const addRaw = (raw) => { for (const t of mailplus.expandRecipients(splitAddresses(raw))) { const email = emailOf(t).toLowerCase(); if (!isEmail(email)) { toast(`"${t}" is not an email address`, { danger: true }); continue; } if (members.some((m) => m.email.toLowerCase() === email)) continue; const nm = (t.match(/^\s*"?([^"<]+?)"?\s*</) || [])[1] || pim.contactName(pim.contactByEmail(email) || {}) || ""; members.push({ name: nm, email }); } add.value = ""; render(); };
    add.onkeydown = (e) => { if (e.key === "Enter") { e.preventDefault(); if (add.value.trim()) addRaw(add.value); pop.classList.add("hidden"); } };
    add.oninput = () => { const s = mailplus.suggestions(add.value).filter((x) => x.kind !== "list"); pop.innerHTML = ""; s.forEach((x) => pop.appendChild(el("button", { onmousedown: (e) => { e.preventDefault(); addRaw(x.name ? `${x.name} <${x.email}>` : x.email); pop.classList.add("hidden"); } }, el("div", { class: "avatar", text: initials(x.name, x.email), style: { background: avatarColor(x.email) } }), el("div", {}, el("div", { class: "n", text: x.name || x.email }), el("div", { class: "e", text: x.email }))))); pop.classList.toggle("hidden", !s.length); };
    add.onblur = () => setTimeout(() => pop.classList.add("hidden"), 150);
    render();
    dlg.append(el("div", { class: "dlg-head" }, icon("people", 20), el("h3", { text: isNew ? "New contact list" : "Edit contact list" }), el("button", { class: "icon-btn", onclick: () => dlg.close() }, icon("close"))),
      el("div", { class: "dlg-body" }, el("div", { class: "field" }, el("label", { text: "Name" }), name), el("div", { class: "field", style: { position: "relative" } }, el("label", { text: "Members" }), add, pop), box, el("div", { class: "field", style: { marginTop: "10px" } }, el("label", { text: "Description" }), notes)),
      el("div", { class: "dlg-foot" }, el("button", { class: "btn", onclick: () => dlg.close() }, "Cancel"), el("button", { class: "btn primary", onclick: async () => {
        if (!name.value.trim()) return toast("Give the list a name.", { danger: true });
        try { const saved = await pim.save("contactLists", { ...(l || {}), name: name.value.trim(), notes: notes.value, members }); dlg.close(); toast(isNew ? "Contact list created" : "Contact list saved"); if (state.module === "people") { this.filter = "lists"; this.select(saved.id); } }
        catch (e) { toast("Could not save: " + e.message, { danger: true }); }
      } }, "Save")));
    dlg.showModal();
    setTimeout(() => name.focus(), 40);
  },
  emailList(l) { shell.setModule("mail"); compose.open({ mode: "new", to: l.members.map((m) => (m.name ? `${m.name} <${m.email}>` : m.email)).join(", ") }); },
  async addToList(cs) {
    const lists = pim.data.contactLists;
    const v = await formDialog.open({ title: "Add to contact list", fields: [{ key: "list", label: "List", type: "select", options: [["__new", "New list…"], ...lists.map((l) => [l.id, l.name])], value: lists[0]?.id || "__new" }, { key: "name", label: "Name for a new list" }] });
    if (!v) return;
    const newMembers = cs.filter((c) => c.emails?.length).map((c) => ({ name: pim.contactName(c), email: c.emails[0].value }));
    if (v.list === "__new") { if (!v.name.trim()) return toast("Enter a name for the new list.", { danger: true }); await pim.save("contactLists", { name: v.name.trim(), members: newMembers }); }
    else { const l = lists.find((x) => x.id === v.list); await pim.save("contactLists", { ...l, members: [...l.members, ...newMembers] }); }
    toast("Added to the list");
  },
  /** A contact card for any address (used from the reading pane). */
  card({ name, email }) {
    const c = pim.contactByEmail(email);
    const n = c ? pim.contactName(c) : (name || email);
    const node = el("div", { class: "peek contact-card" },
      el("div", { class: "contact-hero" }, el("div", { class: "avatar big", text: initials(n, email), style: { background: avatarColor(email || n) } }), el("div", {}, el("h4", { text: n }), el("div", { class: "small muted", text: email }), c?.jobTitle || c?.company ? el("div", { class: "small", text: [c.jobTitle, c.company].filter(Boolean).join(", ") }) : null)),
      c?.phones?.length ? el("div", { class: "row" }, icon("phone", 14), el("a", { href: "tel:" + c.phones[0].value, text: c.phones[0].value })) : null,
      el("div", { class: "btn-row" },
        el("button", { class: "btn sm", onclick: () => { menu.close(); shell.setModule("mail"); compose.open({ mode: "new", to: name ? `${name} <${email}>` : email }); } }, icon("mail"), "Email"),
        el("button", { class: "btn sm", onclick: () => { menu.close(); calendarUI.newEvent({ attendees: [{ email, name: n }] }); } }, icon("calendar"), "Meeting"),
        c ? el("button", { class: "btn sm", onclick: () => { menu.close(); shell.setModule("people"); this.select(c.id); } }, icon("contact"), "Open contact") : el("button", { class: "btn sm", onclick: () => { menu.close(); this.edit(null, { name, email }); } }, icon("person-add"), "Add to contacts"),
        el("button", { class: "btn sm", onclick: () => { menu.close(); $("searchInput").value = email; list.search("", { opts: { scope: "all", from: email } }); shell.setModule("mail", { silent: true }); } }, icon("search"), "Messages"),
        el("button", { class: "btn sm", onclick: () => { menu.close(); navigator.clipboard?.writeText(email).then(() => toast("Address copied")); } }, icon("copy"), "Copy")));
    const r = $("rdAvatar").getBoundingClientRect();
    menu.openAt(r.left, r.bottom + 6, [{ node }], r);
  },
  async importFile() {
    const [f] = await pickFiles({ accept: ".vcf,.csv,text/vcard,text/csv" });
    if (!f) return;
    const kind = /\.csv$/i.test(f.name) || f.type === "text/csv" ? "csv" : "vcf";
    try { const r = await api("/api/pim/import", { method: "POST", query: { kind }, rawBody: await readFileText(f), contentType: "text/plain" }); await pim.load(); toast(`Imported ${plural(r.imported, "contact")}${r.updated ? `, merged ${r.updated} into existing contacts` : ""}`); if (r.errors?.length) toast(r.errors[0], { danger: true }); }
    catch (e) { toast("Import failed: " + e.message, { danger: true }); }
  },
  async importFromUrl(url) {
    try { const text = await (await fetch(url)).text(); const r = await api("/api/pim/import", { method: "POST", query: { kind: "vcf" }, rawBody: text, contentType: "text/plain" }); await pim.load(); toast(`Added ${plural(r.imported, "contact")}${r.updated ? `, updated ${r.updated}` : ""}`); }
    catch (e) { toast("Could not import: " + e.message, { danger: true }); }
  },
};
