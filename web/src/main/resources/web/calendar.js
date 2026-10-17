/* QuillPost — Calendar: day / three-day / work week / week / month / agenda views, mini
 * month, calendar list, event editor with recurrence, reminders, attendees and invitations,
 * .ics import/export, internet calendar subscriptions. Loaded before app.js.
 */
"use strict";

const CAL_COLORS = ["#0f6cbd", "#038387", "#107c10", "#8764b8", "#c239b3", "#c4314b", "#ca5010", "#c19c00", "#4f6bed", "#5c5c5c"];
const SHOW_AS = [["free", "Free"], ["tentative", "Tentative"], ["busy", "Busy"], ["oof", "Away"], ["workingElsewhere", "Working elsewhere"]];
const REMINDERS = [["-1", "Don't remind me"], ["0", "At time of event"], ["5", "5 minutes before"], ["15", "15 minutes before"], ["30", "30 minutes before"], ["60", "1 hour before"], ["120", "2 hours before"], ["720", "12 hours before"], ["1080", "18 hours before"], ["1440", "1 day before"], ["10080", "1 week before"]];

function isoWeek(d) {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const day = t.getUTCDay() || 7; t.setUTCDate(t.getUTCDate() + 4 - day);
  return Math.ceil(((t - new Date(Date.UTC(t.getUTCFullYear(), 0, 1))) / DAY_MS + 1) / 7);
}

const calendarUI = {
  built: false,
  view: null,
  date: startOfDay(new Date()),
  query: "",
  hourPx: 48,

  defaultView() { return innerWidth <= 700 ? "agenda" : "workweek"; },
  show() {
    if (!this.built) this.build();
    this.render();
  },
  build() {
    this.built = true;
    this.view = prefs.get("calView", this.defaultView());
    if (innerWidth <= 700 && !["day", "threeday", "month", "agenda"].includes(this.view)) this.view = "agenda";
    const host = $("modCalendar"); host.innerHTML = "";
    const views = [["day", "Day"], ["threeday", "3 days"], ["workweek", "Work week"], ["week", "Week"], ["month", "Month"], ["agenda", "Agenda"]];
    this.viewBtns = views.map(([v, l]) => el("button", { class: "rb seg", dataset: { view: v }, onclick: () => this.setView(v) }, l));
    host.append(
      el("div", { class: "ribbon" }, el("div", { class: "ribbon-bar cal-bar" },
        el("button", { class: "rb primary", onclick: () => this.newEvent({}) }, icon("plus"), el("span", { class: "lbl", text: "New event" })),
        el("span", { class: "rsep" }),
        el("button", { class: "rb", onclick: () => { this.date = startOfDay(new Date()); this.render(); } }, icon("calendar"), el("span", { class: "lbl", text: "Today" })),
        el("button", { class: "rb", title: "Previous", onclick: () => this.step(-1) }, icon("chev-left")),
        el("button", { class: "rb", title: "Next", onclick: () => this.step(1) }, icon("chev-right")),
        el("span", { class: "cal-title", id: "calTitle" }),
        el("span", { class: "grow" }),
        el("div", { class: "seg-group" }, ...this.viewBtns),
        el("span", { class: "rsep" }),
        el("button", { class: "rb", title: "Add calendar", onclick: (e) => { e.stopPropagation(); this.addCalendarMenu(e.currentTarget); } }, icon("plus"), el("span", { class: "lbl", text: "Add calendar" })),
        el("button", { class: "rb", title: "Print", onclick: () => this.print() }, icon("print"), el("span", { class: "lbl", text: "Print" })))),
      el("div", { class: "cal-body" }, el("nav", { class: "cal-side side-pane", id: "calSide" }), el("section", { class: "cal-main", id: "calMain" })));
    pim.on(() => { if (state.module === "calendar") this.render(); if (prefs.get("myDay", false)) mailplus.renderMyDay(); });
  },
  setView(v) { this.view = v; prefs.set("calView", v); this.render(); },
  step(dir) {
    const d = this.date;
    if (this.view === "month") this.date = new Date(d.getFullYear(), d.getMonth() + dir, 1);
    else if (this.view === "day") this.date = addDays(d, dir);
    else if (this.view === "threeday") this.date = addDays(d, 3 * dir);
    else if (this.view === "agenda") this.date = addDays(d, 14 * dir);
    else this.date = addDays(d, 7 * dir);
    this.render();
  },
  goTo(d) { this.date = startOfDay(d); if (this.view === "month" || this.view === "agenda") this.view = this.defaultView() === "agenda" ? "day" : "workweek"; this.render(); },
  onResize() { if (state.module === "calendar" && this.built) this.render(); },
  range() {
    const d = this.date, fd = firstDayOfWeek();
    if (this.view === "day") return [d, addDays(d, 1)];
    if (this.view === "threeday") return [d, addDays(d, 3)];
    if (this.view === "week") { const s = startOfWeek(d, fd); return [s, addDays(s, 7)]; }
    if (this.view === "workweek") {
      const s = startOfWeek(d, 1);
      const work = prefs.get("workDays", [1, 2, 3, 4, 5]);
      const first = Math.min(...work.map((x) => (x + 6) % 7)), last = Math.max(...work.map((x) => (x + 6) % 7));
      return [addDays(s, first), addDays(s, last + 1)];
    }
    if (this.view === "month") { const first = new Date(d.getFullYear(), d.getMonth(), 1); const s = startOfWeek(first, fd); return [s, addDays(s, 42)]; }
    return [d, addDays(d, 30)];
  },
  render() {
    if (!this.built) return;
    this.viewBtns.forEach((b) => b.classList.toggle("active", b.dataset.view === this.view));
    const [s, e] = this.range();
    const t = $("calTitle");
    if (this.view === "month") t.textContent = this.date.toLocaleDateString(undefined, { month: "long", year: "numeric" });
    else if (this.view === "day") t.textContent = this.date.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: "numeric" });
    else { const last = addDays(e, -1); t.textContent = s.getMonth() === last.getMonth() ? `${s.toLocaleDateString(undefined, { month: "long", day: "numeric" })} – ${last.getDate()}, ${last.getFullYear()}` : `${fmtDay(s, { month: "short", day: "numeric" })} – ${fmtDay(last, { month: "short", day: "numeric", year: "numeric" })}`; }
    this.renderSide();
    const main = $("calMain"); main.innerHTML = "";
    if (this.query) return this.renderSearch(main);
    if (this.view === "month") this.renderMonth(main, s);
    else if (this.view === "agenda") this.renderAgenda(main, s, e);
    else this.renderGrid(main, s, e);
  },

  // ---------------------------------------------------------------- side pane
  renderSide() {
    const side = $("calSide"); side.innerHTML = "";
    side.appendChild(this.miniMonth());
    const hidden = new Set(prefs.get("hiddenCalendars", []));
    const toggleHidden = (id) => { const h = new Set(prefs.get("hiddenCalendars", [])); if (h.has(id)) h.delete(id); else h.add(id); prefs.set("hiddenCalendars", [...h]); this.render(); };
    const row = (c, extraMenu) => {
      const cb = el("input", { type: "checkbox" }); cb.checked = !hidden.has(c.id);
      cb.style.accentColor = c.color; cb.onchange = () => toggleHidden(c.id);
      const r = el("label", { class: "cal-row", title: c.error ? "Last refresh failed: " + c.error : c.name }, cb, el("span", { class: "cal-swatch", style: { background: c.color } }), el("span", { class: "name", text: c.name }), c.error ? icon("warning", 14) : null);
      if (extraMenu) { r.oncontextmenu = (e) => { e.preventDefault(); menu.openAt(e.clientX, e.clientY, extraMenu()); }; const more = el("button", { class: "icon-btn more", title: "Options", onclick: (e) => { e.preventDefault(); e.stopPropagation(); menu.openFor(more, extraMenu()); } }, icon("more", 14)); r.appendChild(more); }
      return r;
    };
    side.appendChild(el("div", { class: "side-head", text: "My calendars" }));
    for (const c of pim.data.calendars.filter((x) => x.kind !== "subscription")) side.appendChild(row(c, () => this.calendarMenu(c)));
    const subs = pim.data.calendars.filter((x) => x.kind === "subscription");
    side.appendChild(el("div", { class: "side-head", text: "Other calendars" }));
    for (const c of subs) side.appendChild(row(c, () => this.calendarMenu(c)));
    for (const hc of HOLIDAY_CALENDARS) if (prefs.get("cal." + hc.id, hc.id === "holidays-us")) side.appendChild(row(hc, () => [{ label: "Remove holiday calendar", icon: "close", onClick: () => { prefs.set("cal." + hc.id, false); this.render(); } }]));
    if (prefs.get("cal.birthdays", true)) side.appendChild(row(pim.calendar("birthdays"), () => [{ label: "Hide birthdays calendar", icon: "close", onClick: () => { prefs.set("cal.birthdays", false); this.render(); } }]));
    side.appendChild(el("button", { class: "add-account", onclick: (e) => { e.stopPropagation(); this.addCalendarMenu(e.currentTarget); } }, icon("plus"), "Add calendar"));
  },
  miniMonth() {
    const ref = this.mini || new Date(this.date.getFullYear(), this.date.getMonth(), 1);
    this.mini = ref;
    const fd = firstDayOfWeek();
    const start = startOfWeek(ref, fd);
    const box = el("div", { class: "mini-month" });
    box.appendChild(el("div", { class: "mm-head" },
      el("b", { text: ref.toLocaleDateString(undefined, { month: "long", year: "numeric" }) }),
      el("button", { class: "icon-btn", title: "Previous month", onclick: () => { this.mini = new Date(ref.getFullYear(), ref.getMonth() - 1, 1); this.renderSide(); } }, icon("chev-left", 14)),
      el("button", { class: "icon-btn", title: "Next month", onclick: () => { this.mini = new Date(ref.getFullYear(), ref.getMonth() + 1, 1); this.renderSide(); } }, icon("chev-right", 14))));
    const grid = el("div", { class: "mm-grid" });
    for (let i = 0; i < 7; i++) grid.appendChild(el("span", { class: "dow", text: addDays(start, i).toLocaleDateString(undefined, { weekday: "narrow" }) }));
    const [rs, re] = this.range();
    const busy = new Set(pim.occurrences(start, addDays(start, 42)).filter((o) => !o.virtual).map((o) => ymd(o.start)));
    for (let i = 0; i < 42; i++) {
      const d = addDays(start, i);
      const cls = ["d"];
      if (d.getMonth() !== ref.getMonth()) cls.push("other");
      if (sameDay(d, new Date())) cls.push("today");
      if (d >= rs && d < re && this.view !== "month") cls.push("in-range");
      if (sameDay(d, this.date)) cls.push("sel");
      if (busy.has(ymd(d))) cls.push("busy");
      grid.appendChild(el("button", { class: cls.join(" "), text: String(d.getDate()), onclick: () => { this.date = d; if (this.view === "month") this.view = this.defaultView() === "agenda" ? "day" : "workweek"; this.mini = null; this.render(); } }));
    }
    box.appendChild(grid);
    return box;
  },
  calendarMenu(c) {
    const isSub = c.kind === "subscription";
    return [
      { header: c.name },
      { label: "Colour", icon: "sparkle", sub: CAL_COLORS.map((col) => ({ label: col, color: col, checked: c.color === col, onClick: () => pim.save("calendars", { ...c, color: col }) })) },
      { label: "Rename", icon: "edit", onClick: async () => { const v = await formDialog.open({ title: "Rename calendar", fields: [{ key: "name", label: "Name", value: c.name, required: true }] }); if (v) pim.save("calendars", { ...c, name: v.name.trim() }); } },
      isSub ? { label: "Refresh now", icon: "refresh", onClick: async () => { try { const r = await api(`/api/calendars/${encodeURIComponent(c.id)}/refresh`, { method: "POST" }); await pim.load(); toast(r.error ? "Refresh failed: " + r.error : "Calendar refreshed", { danger: !!r.error }); } catch (e) { toast(e.message, { danger: true }); } } } : null,
      !isSub ? { label: "Import events (.ics)…", icon: "upload", onClick: () => this.importFile(c.id) } : null,
      { label: "Export (.ics)", icon: "download", onClick: () => download(apiUrl("/api/pim/export", { kind: "ics", calendarId: c.id })) },
      c.id !== "calendar" ? { sep: true } : null,
      c.id !== "calendar" ? { label: isSub ? "Unsubscribe" : "Delete calendar", icon: "trash", danger: true, onClick: async () => { if (!confirm(`${isSub ? "Unsubscribe from" : "Delete"} "${c.name}"${isSub ? "" : " and all its events"}?`)) return; await pim.remove("calendars", c.id); } } : null,
    ];
  },
  addCalendarMenu(anchor) {
    menu.openFor(anchor, [
      { label: "Create blank calendar", icon: "plus", onClick: async () => { const v = await formDialog.open({ title: "New calendar", fields: [{ key: "name", label: "Name", required: true }, { key: "color", label: "Colour", type: "select", value: CAL_COLORS[1], options: CAL_COLORS.map((c, i) => [c, ["Blue", "Teal", "Green", "Purple", "Pink", "Red", "Orange", "Gold", "Indigo", "Grey"][i]]) }] }); if (v) pim.save("calendars", { name: v.name.trim(), color: v.color }); } },
      { label: "Subscribe from web…", icon: "globe", onClick: () => this.subscribe() },
      { label: "Import from file (.ics)…", icon: "upload", onClick: () => this.importFile("calendar") },
      { label: "Holidays…", icon: "star", onClick: () => settingsUI.open("calendar") },
    ]);
  },
  async subscribe() {
    const v = await formDialog.open({ title: "Subscribe to an internet calendar", ok: "Subscribe", fields: [
      { key: "url", label: "Calendar address", type: "url", placeholder: "https://… .ics or webcal://…", required: true, hint: "Many services publish a secret or public iCal address, for example a shared Google or iCloud calendar, sports schedules and school calendars." },
      { key: "name", label: "Name (optional)" },
      { key: "color", label: "Colour", type: "select", value: CAL_COLORS[3], options: CAL_COLORS.map((c) => [c, c]) },
    ] });
    if (!v) return;
    try { const c = await api("/api/calendars/subscribe", { method: "POST", body: { url: v.url.trim(), name: v.name.trim(), color: v.color } }); await pim.load(); toast(`Subscribed to "${c.name}"`); }
    catch (e) { toast("Could not subscribe: " + e.message, { danger: true }); }
  },
  async importFile(calendarId) {
    const [f] = await pickFiles({ accept: ".ics,text/calendar" });
    if (!f) return;
    try { const r = await api("/api/pim/import", { method: "POST", query: { kind: "ics", calendarId, tz: VIEW_TZ }, rawBody: await readFileText(f), contentType: "text/calendar" }); await pim.load(); toast(`Imported ${plural(r.imported, "event")}${r.updated ? `, updated ${r.updated}` : ""}`); if (r.errors?.length) toast(r.errors[0], { danger: true }); }
    catch (e) { toast("Import failed: " + e.message, { danger: true }); }
  },
  async importFromUrl(url) {
    try { const text = await (await fetch(url)).text(); const r = await api("/api/pim/import", { method: "POST", query: { kind: "ics", calendarId: "calendar", tz: VIEW_TZ }, rawBody: text, contentType: "text/calendar" }); await pim.load(); toast(`Added ${plural(r.imported + r.updated, "event")} to your calendar`); }
    catch (e) { toast("Could not import: " + e.message, { danger: true }); }
  },

  // ---------------------------------------------------------------- time grid
  minutePx() { return this.hourPx / 60; },
  renderGrid(main, s, e) {
    const days = []; for (let d = s; d < e; d = addDays(d, 1)) days.push(d);
    const scale = Number(prefs.get("timeScale", 30)) || 30;
    this.hourPx = scale === 15 ? 72 : scale === 60 ? 40 : 48;
    const occ = pim.occurrences(s, e);
    const allDay = occ.filter((o) => o.allDay || (o.end - o.start) >= DAY_MS);
    const timed = occ.filter((o) => !allDay.includes(o));
    const wrap = el("div", { class: "tg" });
    wrap.style.setProperty("--days", days.length);
    // header
    const head = el("div", { class: "tg-head" }, el("div", { class: "tg-gutter" }, prefs.get("weekNumbers", false) ? el("span", { class: "wk", text: "W" + isoWeek(days[0]) }) : null));
    for (const d of days) head.appendChild(el("button", { class: "tg-day-h" + (sameDay(d, new Date()) ? " today" : ""), onclick: () => { this.date = d; this.setView("day"); } }, el("span", { class: "dn", text: d.toLocaleDateString(undefined, { weekday: "short" }) }), el("span", { class: "dd", text: String(d.getDate()) })));
    wrap.appendChild(head);
    // all-day row
    const ad = el("div", { class: "tg-allday" }, el("div", { class: "tg-gutter small muted", text: "All day" }));
    const adGrid = el("div", { class: "tg-ad-grid" });
    const lanes = [];
    for (const o of allDay.sort((a, b) => a.start - b.start || (b.end - b.start) - (a.end - a.start))) {
      const c0 = Math.max(0, Math.round((startOfDay(o.start) - s) / DAY_MS)), c1 = Math.min(days.length, Math.round((startOfDay(addDays(o.end, o.allDay ? 0 : 1)) - s) / DAY_MS) || c0 + 1);
      let lane = lanes.findIndex((l) => l.every(([a, b]) => c1 <= a || c0 >= b));
      if (lane < 0) { lanes.push([]); lane = lanes.length - 1; }
      lanes[lane].push([c0, Math.max(c1, c0 + 1)]);
      const chip = this.chip(o, { allDay: true });
      chip.style.gridColumn = `${c0 + 1} / ${Math.max(c1, c0 + 1) + 1}`; chip.style.gridRow = String(lane + 1);
      adGrid.appendChild(chip);
    }
    adGrid.style.gridTemplateRows = `repeat(${Math.max(1, lanes.length)}, 24px)`;
    adGrid.ondblclick = (ev) => { if (ev.target !== adGrid) return; const col = Math.floor((ev.offsetX / adGrid.clientWidth) * days.length); this.newEvent({ start: days[col], allDay: true }); };
    ad.appendChild(adGrid);
    wrap.appendChild(ad);
    // body
    const body = el("div", { class: "tg-body", id: "tgBody" });
    const gutter = el("div", { class: "tg-gutter hours" });
    for (let h = 0; h < 24; h++) gutter.appendChild(el("div", { class: "hour", style: { height: this.hourPx + "px" } }, h ? el("span", { text: new Date(2000, 0, 1, h).toLocaleTimeString(undefined, { hour: "numeric" }) }) : null));
    body.appendChild(gutter);
    const cols = el("div", { class: "tg-cols" });
    const ws = Number(prefs.get("workStart", 8)), we = Number(prefs.get("workEnd", 17)), work = prefs.get("workDays", [1, 2, 3, 4, 5]);
    days.forEach((d) => {
      const col = el("div", { class: "tg-col" + (sameDay(d, new Date()) ? " today" : ""), dataset: { day: ymd(d) } });
      col.style.height = 24 * this.hourPx + "px";
      col.style.backgroundSize = `100% ${this.hourPx * (scale / 60)}px`;
      if (work.includes(d.getDay())) col.appendChild(el("div", { class: "work-hours", style: { top: ws * this.hourPx + "px", height: Math.max(0, we - ws) * this.hourPx + "px" } }));
      const mine = timed.filter((o) => o.start < addDays(d, 1) && o.end > d);
      this.layout(mine, d).forEach(({ o, left, width }) => {
        const top = Math.max(0, (Math.max(o.start, d) - d) / 60000) * this.minutePx();
        const bottom = Math.min(24 * 60, (Math.min(o.end, addDays(d, 1)) - d) / 60000) * this.minutePx();
        const ch = this.chip(o, {});
        Object.assign(ch.style, { top: top + "px", height: Math.max(20, bottom - top - 2) + "px", left: `calc(${left * 100}% + 2px)`, width: `calc(${width * 100}% - 4px)` });
        col.appendChild(ch);
      });
      if (sameDay(d, new Date())) { const now = new Date(); col.appendChild(el("div", { class: "now-line", style: { top: ((now - d) / 60000) * this.minutePx() + "px" } })); }
      this.wireSlotDrag(col, d, scale);
      cols.appendChild(col);
    });
    body.appendChild(cols);
    wrap.appendChild(body);
    main.appendChild(wrap);
    const target = sameDay(this.date, new Date()) || days.some((d) => sameDay(d, new Date())) ? Math.max(0, new Date().getHours() - 2) : ws;
    body.scrollTop = target * this.hourPx;
  },
  /** Side-by-side columns for overlapping events (greedy, like most calendar programs). */
  layout(items, day) {
    const sorted = [...items].sort((a, b) => a.start - b.start || b.end - a.end);
    const out = [];
    let cluster = [], clusterEnd = 0;
    const flush = () => {
      const colsEnd = [];
      const placed = cluster.map((o) => { let c = colsEnd.findIndex((end) => end <= o.start); if (c < 0) { colsEnd.push(0); c = colsEnd.length - 1; } colsEnd[c] = Math.max(o.end, addMinutes(o.start, 20)); return { o, c }; });
      placed.forEach(({ o, c }) => out.push({ o, left: c / colsEnd.length, width: 1 / colsEnd.length }));
      cluster = []; clusterEnd = 0;
    };
    for (const o of sorted) { if (cluster.length && o.start >= clusterEnd) flush(); cluster.push(o); clusterEnd = Math.max(clusterEnd, Math.max(o.end, addMinutes(o.start, 20))); }
    if (cluster.length) flush();
    return out;
  },
  wireSlotDrag(col, day, scale) {
    let startY = null, ghost = null;
    const snap = (y) => Math.round(y / (this.minutePx() * scale)) * scale;
    col.addEventListener("pointerdown", (e) => {
      if (e.target !== col && !e.target.classList.contains("work-hours")) return;
      if (e.button !== 0) return;
      const r = col.getBoundingClientRect();
      startY = e.clientY - r.top;
      ghost = el("div", { class: "cal-ev ghost" });
      ghost.style.top = snap(startY) * this.minutePx() + "px"; ghost.style.height = scale * this.minutePx() + "px"; ghost.style.left = "2px"; ghost.style.width = "calc(100% - 4px)";
      col.appendChild(ghost);
      col.setPointerCapture(e.pointerId);
    });
    col.addEventListener("pointermove", (e) => {
      if (startY == null) return;
      const y = e.clientY - col.getBoundingClientRect().top;
      const a = snap(Math.min(startY, y)), b = Math.max(snap(Math.max(startY, y)), a + scale);
      ghost.style.top = a * this.minutePx() + "px"; ghost.style.height = (b - a) * this.minutePx() + "px";
    });
    col.addEventListener("pointerup", (e) => {
      if (startY == null) return;
      const y = e.clientY - col.getBoundingClientRect().top;
      const a = snap(Math.min(startY, y)), b0 = snap(Math.max(startY, y));
      const dragged = Math.abs(y - startY) > 8;
      const b = dragged ? Math.max(b0, a + scale) : a + Number(prefs.get("defaultDuration", 30));
      ghost?.remove(); startY = null;
      this.newEvent({ start: addMinutes(day, a), end: addMinutes(day, b) });
    });
  },
  chip(o, { allDay = false, month = false } = {}) {
    const e = o.ev, c = o.cal || {};
    const cls = ["cal-ev"];
    if (allDay) cls.push("allday"); if (month) cls.push("month");
    if (e.showAs === "tentative" || e.myStatus === "TENTATIVE" || e.myStatus === "NEEDS-ACTION") cls.push("tentative");
    if (e.showAs === "free") cls.push("free");
    if (o.end < new Date()) cls.push("past");
    const chip = el("div", { class: cls.join(" "), title: `${e.title}\n${o.allDay ? "All day" : fmtTime(o.start) + " – " + fmtTime(o.end)}${e.location ? "\n" + e.location : ""}`, style: { "--ev": c.color || "#0f6cbd" } });
    if (month && !o.allDay) chip.append(el("span", { class: "dot" }), el("span", { class: "t", text: fmtTime(o.start) + " " }));
    chip.appendChild(el("span", { class: "ti", text: e.title || "(No title)" }));
    if (e.recurrence || e.recurrenceId) chip.appendChild(icon("repeat", 11));
    if (e.attendees?.length) chip.appendChild(icon("people", 11));
    if (!allDay && !month) { if (e.location) chip.appendChild(el("span", { class: "loc", text: e.location })); }
    chip.onclick = (ev) => { ev.stopPropagation(); this.peek(o, chip); };
    chip.ondblclick = (ev) => { ev.stopPropagation(); this.openEvent(o); };
    if (!o.virtual && !c.readOnly && !allDay && !month) this.wireEventDrag(chip, o);
    return chip;
  },
  /** Drag an event to move it, or its bottom edge to change its length (single events only). */
  wireEventDrag(chip, o) {
    const scale = Number(prefs.get("timeScale", 30)) || 30;
    const handle = el("div", { class: "resize" });
    chip.appendChild(handle);
    let mode = null, sy = 0, sx = 0, moved = false, colW = 0, origTop = 0, origH = 0;
    chip.addEventListener("pointerdown", (e) => {
      if (e.button !== 0 || o.ev.recurrence || o.ev.recurrenceId) return;
      mode = e.target === handle ? "resize" : "move"; sy = e.clientY; sx = e.clientX; moved = false;
      colW = chip.parentElement.offsetWidth; origTop = chip.offsetTop; origH = chip.offsetHeight;
      chip.setPointerCapture(e.pointerId); e.stopPropagation();
    });
    chip.addEventListener("pointermove", (e) => {
      if (!mode) return;
      const dy = e.clientY - sy, dx = e.clientX - sx;
      if (!moved && Math.abs(dy) < 6 && Math.abs(dx) < 6) return;
      moved = true; chip.classList.add("dragging");
      const snapPx = scale * this.minutePx();
      if (mode === "move") { chip.style.top = Math.round((origTop + dy) / snapPx) * snapPx + "px"; chip.style.transform = `translateX(${Math.round(dx / colW) * colW}px)`; }
      else chip.style.height = Math.max(snapPx, Math.round((origH + dy) / snapPx) * snapPx) + "px";
    });
    chip.addEventListener("pointerup", async (e) => {
      if (!mode) return;
      const m = mode; mode = null;
      if (!moved) return;
      chip.classList.remove("dragging");
      const snapMin = scale;
      const dyMin = Math.round((e.clientY - sy) / this.minutePx() / snapMin) * snapMin;
      const dDays = m === "move" ? Math.round((e.clientX - sx) / colW) : 0;
      const ev = o.ev;
      const s0 = zonedToDate(ev.start, ev.tz || VIEW_TZ), e0 = zonedToDate(ev.end, ev.tz || VIEW_TZ);
      const ns = m === "move" ? addMinutes(addDays(s0, dDays), dyMin) : s0;
      const ne = m === "move" ? addMinutes(addDays(e0, dDays), dyMin) : addMinutes(e0, dyMin);
      if (ne <= ns) return this.render();
      try {
        const saved = await pim.save("events", { ...ev, start: dateToZoned(ns, ev.tz || VIEW_TZ), end: dateToZoned(ne, ev.tz || VIEW_TZ) });
        if (saved.attendees?.length && this.isOrganizer(saved) && confirm("Send the new time to the attendees?")) this.sendInvite(saved, "REQUEST");
      } catch (er) { toast(er.message, { danger: true }); this.render(); }
    });
  },

  // ---------------------------------------------------------------- month & agenda
  renderMonth(main, s) {
    const fd = firstDayOfWeek();
    const wrap = el("div", { class: "month" + (prefs.get("weekNumbers", false) ? " with-wk" : "") });
    const head = el("div", { class: "month-head" });
    if (prefs.get("weekNumbers", false)) head.appendChild(el("span"));
    for (let i = 0; i < 7; i++) head.appendChild(el("span", { text: addDays(s, i).toLocaleDateString(undefined, { weekday: innerWidth <= 700 ? "narrow" : "long" }) }));
    wrap.appendChild(head);
    const occ = pim.occurrences(s, addDays(s, 42));
    const grid = el("div", { class: "month-grid" });
    for (let w = 0; w < 6; w++) {
      if (prefs.get("weekNumbers", false)) grid.appendChild(el("span", { class: "wk", text: String(isoWeek(addDays(s, w * 7 + (fd === 0 ? 1 : 0)))) }));
      for (let i = 0; i < 7; i++) {
        const d = addDays(s, w * 7 + i);
        const cell = el("div", { class: "mcell" + (d.getMonth() !== this.date.getMonth() ? " other" : "") + (sameDay(d, new Date()) ? " today" : "") });
        cell.appendChild(el("button", { class: "mday", text: d.getDate() === 1 ? d.toLocaleDateString(undefined, { month: "short", day: "numeric" }) : String(d.getDate()), onclick: () => { this.date = d; this.setView("day"); } }));
        const mine = occ.filter((o) => o.start < addDays(d, 1) && o.end > d);
        const max = innerWidth <= 700 ? 2 : 3;
        mine.slice(0, max).forEach((o) => cell.appendChild(this.chip(o, { month: true, allDay: o.allDay })));
        if (mine.length > max) cell.appendChild(el("button", { class: "more", text: `+${mine.length - max} more`, onclick: () => { this.date = d; this.setView("day"); } }));
        cell.ondblclick = (e) => { if (e.target === cell) this.newEvent({ start: at(d, 9), end: addMinutes(at(d, 9), Number(prefs.get("defaultDuration", 30))) }); };
        grid.appendChild(cell);
      }
    }
    wrap.appendChild(grid);
    main.appendChild(wrap);
  },
  renderAgenda(main, s, e, items = null, title = null) {
    const wrap = el("div", { class: "agenda" });
    if (title) wrap.appendChild(el("h3", { text: title }));
    const occ = items || pim.occurrences(s, e);
    if (!occ.length) wrap.appendChild(el("div", { class: "empty-list" }, icon("calendar", 48), el("div", { text: items ? "No events found" : "Nothing planned for these days" })));
    let day = null;
    for (const o of occ) {
      const d = startOfDay(o.start < s && !items ? s : o.start);
      if (!day || !sameDay(day, d)) { day = d; wrap.appendChild(el("div", { class: "agenda-day" + (sameDay(d, new Date()) ? " today" : ""), text: fmtDay(d, { weekday: "long", month: "long", day: "numeric", year: d.getFullYear() !== new Date().getFullYear() ? "numeric" : undefined }) })); }
      wrap.appendChild(this.agendaItem(o));
    }
    main.appendChild(wrap);
  },
  agendaItem(o) {
    const e = o.ev;
    return el("div", { class: "agenda-item", onclick: (ev) => this.peek(o, ev.currentTarget) },
      el("span", { class: "bar", style: { background: o.cal?.color || "#0f6cbd" } }),
      el("span", { class: "time", html: o.allDay ? "All day" : `${esc(fmtTime(o.start))}<br>${esc(fmtTime(o.end))}` }),
      el("span", {}, el("div", { class: "t", text: e.title }), e.location ? el("div", { class: "l", text: e.location }) : null, e.myStatus === "NEEDS-ACTION" ? el("div", { class: "l", text: "Not yet answered" }) : null));
  },
  search(q) { this.query = q.trim(); if (state.module !== "calendar") shell.setModule("calendar"); this.render(); },
  renderSearch(main) {
    const q = this.query.toLowerCase();
    const now = new Date();
    const hits = pim.occurrences(addDays(now, -365), addDays(now, 365)).filter((o) => [o.ev.title, o.ev.location, o.ev.notes].some((x) => (x || "").toLowerCase().includes(q)));
    const back = el("button", { class: "btn sm", onclick: () => { this.query = ""; $("searchInput").value = ""; this.render(); } }, icon("close"), "Clear search");
    main.appendChild(el("div", { class: "agenda-tools" }, back));
    this.renderAgenda(main, addDays(now, -365), addDays(now, 365), hits, `${plural(hits.length, "result")} for "${this.query}"`);
  },
  print() {
    const [s, e] = this.range();
    const occ = pim.occurrences(s, e);
    let html = `<title>Calendar</title><style>body{font-family:Segoe UI,system-ui,sans-serif;margin:24px}h2{margin:0 0 12px}h3{margin:16px 0 4px;border-bottom:1px solid #ccc}div{margin:2px 0}b{display:inline-block;width:150px}</style><h2>${esc($("calTitle").textContent)}</h2>`;
    let day = null;
    for (const o of occ) { if (!day || !sameDay(day, o.start)) { day = startOfDay(o.start); html += `<h3>${esc(fmtDay(day, { weekday: "long", month: "long", day: "numeric", year: "numeric" }))}</h3>`; } html += `<div><b>${o.allDay ? "All day" : esc(fmtTime(o.start) + " – " + fmtTime(o.end))}</b>${esc(o.ev.title)}${o.ev.location ? " — " + esc(o.ev.location) : ""}</div>`; }
    if (!occ.length) html += "<p>No events.</p>";
    if (nativePrint("Calendar")) return;
    const w = window.open("", "_blank"); if (!w) return popup("Calendar", { html }); w.document.write(html); w.document.close(); setTimeout(() => w.print(), 300);
  },

  // ---------------------------------------------------------------- peek card & editor
  isOrganizer(e) { const me = list.myAddresses(); return !e.organizer || me.has((e.organizer.email || "").toLowerCase()); },
  peek(o, anchor) {
    const e = o.ev, c = o.cal || {};
    const when = o.allDay ? (sameDay(o.start, addDays(o.end, -1)) ? fmtDay(o.start, { weekday: "long", month: "long", day: "numeric" }) : `${fmtDay(o.start)} – ${fmtDay(addDays(o.end, -1))}`) + " (all day)" : `${fmtDay(o.start, { weekday: "long", month: "long", day: "numeric" })}, ${fmtTime(o.start)} – ${sameDay(o.start, o.end) ? fmtTime(o.end) : fmtDay(o.end) + " " + fmtTime(o.end)}`;
    const node = el("div", { class: "peek" },
      el("div", { class: "peek-bar", style: { background: c.color || "#0f6cbd" } }),
      el("h4", { text: e.title }),
      el("div", { class: "row" }, icon("clock", 14), el("span", { text: when })),
      e.recurrence ? el("div", { class: "row" }, icon("repeat", 14), el("span", { text: describeRecurrence(e.recurrence) })) : null,
      e.location ? el("div", { class: "row" }, icon("map-pin", 14), el("a", { href: "https://www.openstreetmap.org/search?query=" + encodeURIComponent(e.location), target: "_blank", rel: "noopener", text: e.location })) : null,
      e.onlineUrl ? el("div", { class: "row" }, icon("video", 14), el("a", { href: e.onlineUrl, target: "_blank", rel: "noopener", text: "Join online meeting" })) : null,
      el("div", { class: "row" }, el("span", { class: "cal-swatch", style: { background: c.color } }), el("span", { class: "small muted", text: c.name || "" })),
      e.organizer && !this.isOrganizer(e) ? el("div", { class: "row" }, icon("user", 14), el("span", { text: "Organizer: " + (e.organizer.name || e.organizer.email) })) : null,
      e.attendees?.length ? el("div", { class: "row small" }, icon("people", 14), el("span", { text: e.attendees.map((a) => `${a.name || a.email}${a.status && a.status !== "NEEDS-ACTION" ? " (" + a.status.toLowerCase() + ")" : ""}`).join(", ") })) : null,
      e.notes ? el("div", { class: "notes", text: e.notes.slice(0, 400) }) : null);
    const btns = el("div", { class: "btn-row" });
    if (!o.virtual && !c.readOnly) {
      if (e.myStatus && e.organizer && !this.isOrganizer(e)) {
        for (const [resp, label] of [["ACCEPTED", "Accept"], ["TENTATIVE", "Tentative"], ["DECLINED", "Decline"]]) btns.appendChild(el("button", { class: "btn sm" + (e.myStatus === resp ? " primary" : ""), onclick: () => { menu.close(); this.respond(e, resp); } }, label));
      }
      btns.append(el("button", { class: "btn sm", onclick: () => { menu.close(); this.openEvent(o); } }, icon("edit"), "Edit"),
        el("button", { class: "btn sm danger", onclick: () => { menu.close(); this.deleteOccurrence(o); } }, icon("trash"), "Delete"));
      if (e.attendees?.length) btns.appendChild(el("button", { class: "btn sm", onclick: () => { menu.close(); compose.open({ mode: "new", to: e.attendees.map((a) => a.email).join(", "), subject: e.title }); shell.setModule("mail"); } }, icon("mail"), "Email attendees"));
    } else if (e.contactId) btns.appendChild(el("button", { class: "btn sm", onclick: () => { menu.close(); shell.setModule("people"); peopleUI.select(e.contactId); } }, icon("contact"), "Open contact"));
    else if (c.readOnly) btns.appendChild(el("span", { class: "small muted", text: "Read-only calendar" }));
    node.appendChild(btns);
    const r = anchor.getBoundingClientRect();
    menu.openAt(Math.min(r.right + 6, innerWidth - 340), r.top, [{ node }], r);
  },
  async respond(e, response) {
    const send = confirm("Send your response to the organizer?\n\nOK = send now, Cancel = update your calendar only.");
    try { await api("/api/invite/respondEvent", { method: "POST", body: { eventId: e.id, response, sendResponse: send } }); await pim.load(); toast(response === "DECLINED" ? "Declined — removed from your calendar" : "Response saved"); }
    catch (er) { toast(er.message, { danger: true }); }
  },
  async sendInvite(e, method = "REQUEST", comment = "") {
    try { await api("/api/invite/send", { method: "POST", body: { eventId: e.id, method, comment } }); await pim.load(); toast(method === "CANCEL" ? "Cancellation sent" : `Invitation sent to ${plural(e.attendees.length, "attendee")}`); }
    catch (er) { toast("Could not send the invitation: " + er.message, { danger: true }); }
  },
  async deleteOccurrence(o) {
    const e = o.ev;
    const master = e.recurrenceId ? null : e;
    let scope = "series";
    if (master?.recurrence) {
      scope = await new Promise((res) => menu.openAt(innerWidth / 2 - 120, innerHeight / 3, [{ header: "Delete a recurring event" }, { label: "Delete this occurrence", icon: "calendar", onClick: () => res("one") }, { label: "Delete the whole series", icon: "repeat", danger: true, onClick: () => res("series") }, { label: "Cancel", icon: "close", onClick: () => res(null) }]));
      if (!scope) return;
    } else if (!confirm(`Delete "${e.title}"?`)) return;
    try {
      if (scope === "one") { await pim.save("events", { ...master, exdates: [...(master.exdates || []), o.key] }); }
      else {
        if (e.attendees?.length && this.isOrganizer(e) && e.organizer && confirm("Send a cancellation to the attendees?")) await this.sendInvite(e, "CANCEL");
        for (const x of pim.data.events.filter((x) => x.uid === e.uid && x.id !== e.id)) await pim.remove("events", x.id);
        await pim.remove("events", e.id);
      }
      toast("Deleted");
    } catch (er) { toast(er.message, { danger: true }); }
  },
  async openEvent(o) {
    const e = o.ev;
    if (o.virtual) return;
    if (e.recurrence && !e.recurrenceId) {
      const scope = await new Promise((res) => menu.openAt(innerWidth / 2 - 120, innerHeight / 3, [{ header: "Open a recurring event" }, { label: "Just this one", icon: "calendar", onClick: () => res("one") }, { label: "The entire series", icon: "repeat", onClick: () => res("series") }, { label: "Cancel", icon: "close", onClick: () => res(null) }]));
      if (!scope) return;
      if (scope === "one") {
        // An edited occurrence becomes its own event with RECURRENCE-ID pointing at the original slot.
        const tz = e.tz || VIEW_TZ;
        const occ = { ...e, id: "", recurrence: null, exdates: [], recurrenceId: o.key, start: o.allDay ? ymd(o.start) : dateToZoned(o.start, tz), end: o.allDay ? ymd(o.end) : dateToZoned(o.end, tz) };
        return this.editor(occ, { occurrenceOf: e });
      }
    }
    this.editor(e);
  },
  newEvent({ start = null, end = null, allDay = false, title = "", attendees = [], location = "" } = {}) {
    if (state.module !== "calendar" && !$("composeDialog").open) { /* stay where we are; the editor is a dialog */ }
    const now = new Date();
    const s = start || new Date(Math.ceil(now.getTime() / (30 * 60000)) * 30 * 60000);
    const e = end || addMinutes(s, Number(prefs.get("defaultDuration", 30)));
    const rem = Number(prefs.get("defaultReminder", 15));
    this.editor({ id: "", calendarId: prefs.get("defaultCalendar", "calendar"), title, location, notes: "", allDay, tz: VIEW_TZ,
      start: allDay ? ymd(s) : localIso(s), end: allDay ? ymd(addDays(startOfDay(s), 1)) : localIso(e),
      reminderMinutes: rem < 0 ? null : (allDay ? 1080 : rem), showAs: allDay ? "free" : "busy", attendees, categories: [], exdates: [] });
  },

  /** The event editor dialog. */
  editor(ev, { occurrenceOf = null } = {}) {
    let dlg = $("eventDialog");
    if (!dlg) { dlg = el("dialog", { id: "eventDialog", class: "wide event-dialog" }); document.body.appendChild(dlg); }
    dlg.innerHTML = "";
    const tz = ev.allDay ? VIEW_TZ : (ev.tz || VIEW_TZ);
    const startD = ev.allDay ? parseYmd(ev.start) : zonedToDate(ev.start, tz);
    const endD = ev.allDay ? addDays(parseYmd(ev.end || ev.start), -1) : zonedToDate(ev.end || ev.start, tz);
    const cals = pim.data.calendars.filter((c) => !c.readOnly);
    const title = el("input", { type: "text", class: "ev-title", placeholder: "Add a title", value: ev.title || "" });
    const attendees = [...(ev.attendees || [])];
    const attBox = el("div", { class: "att-chips" });
    const attInput = el("input", { type: "text", placeholder: "Invite attendees (name or email, Enter to add)" });
    const renderAtt = () => {
      attBox.innerHTML = "";
      attendees.forEach((a, i) => attBox.appendChild(el("span", { class: "person-chip", title: a.status ? `Response: ${a.status.toLowerCase()}` : "" },
        el("span", { class: "avatar", text: initials(a.name, a.email), style: { background: avatarColor(a.email) } }), el("span", { text: a.name || a.email }),
        a.status && a.status !== "NEEDS-ACTION" ? el("span", { class: "resp " + a.status.toLowerCase(), text: { ACCEPTED: "✓", DECLINED: "✕", TENTATIVE: "?" }[a.status] || "" }) : null,
        el("button", { class: "opt-btn" + (a.role === "OPT-PARTICIPANT" ? " on" : ""), title: "Optional attendee", text: "opt", onclick: () => { a.role = a.role === "OPT-PARTICIPANT" ? "REQ-PARTICIPANT" : "OPT-PARTICIPANT"; renderAtt(); } }),
        el("button", { title: "Remove", html: "&#x2715;", onclick: () => { attendees.splice(i, 1); renderAtt(); } }))));
    };
    const addAtt = (raw) => {
      for (const t of mailplus.expandRecipients(splitAddresses(raw))) {
        const email = emailOf(t).toLowerCase(); if (!isEmail(email)) { toast(`"${t}" is not an email address or a contact.`, { danger: true }); continue; }
        if (attendees.some((a) => a.email.toLowerCase() === email)) continue;
        const name = (t.match(/^\s*"?([^"<]+?)"?\s*</) || [])[1] || pim.contactName(pim.contactByEmail(email) || {}) || "";
        attendees.push({ email, name, role: "REQ-PARTICIPANT", status: "NEEDS-ACTION" });
      }
      attInput.value = ""; renderAtt();
    };
    const sugg = el("div", { class: "ac-popup hidden", style: { position: "absolute" } });
    attInput.onkeydown = (e) => { if (e.key === "Enter" || e.key === ",") { e.preventDefault(); if (attInput.value.trim()) addAtt(attInput.value); sugg.classList.add("hidden"); } };
    attInput.oninput = () => {
      const s = mailplus.suggestions(attInput.value);
      sugg.innerHTML = "";
      s.forEach((x) => sugg.appendChild(el("button", { onmousedown: (e) => { e.preventDefault(); addAtt(x.kind === "list" ? x.members.map((m) => m.email).join(",") : (x.name ? `${x.name} <${x.email}>` : x.email)); sugg.classList.add("hidden"); } }, el("div", { class: "avatar", text: initials(x.name, x.email), style: { background: avatarColor(x.email) } }), el("div", {}, el("div", { class: "n", text: x.name || x.email }), el("div", { class: "e", text: x.kind === "list" ? "Contact list" : x.email })))));
      sugg.classList.toggle("hidden", !s.length);
    };
    attInput.onblur = () => setTimeout(() => sugg.classList.add("hidden"), 150);
    renderAtt();
    const allDay = el("input", { type: "checkbox" }); allDay.checked = !!ev.allDay;
    const sDate = el("input", { type: "date", value: ymd(startD) }), sTime = el("input", { type: "time", value: `${pad2(startD.getHours())}:${pad2(startD.getMinutes())}`, step: 300 });
    const eDate = el("input", { type: "date", value: ymd(endD) }), eTime = el("input", { type: "time", value: `${pad2(endD.getHours())}:${pad2(endD.getMinutes())}`, step: 300 });
    let zones = []; try { zones = Intl.supportedValuesOf("timeZone"); } catch {}
    if (!zones.includes(tz)) zones.unshift(tz);
    const tzSel = select(zones.map((z) => [z, z.replace(/_/g, " ")]), tz, { title: "Time zone" });
    const syncAllDay = () => { for (const x of [sTime, eTime, tzSel]) x.classList.toggle("hidden", allDay.checked); };
    allDay.onchange = syncAllDay; syncAllDay();
    let lastStart = startD;
    sDate.onchange = sTime.onchange = () => {
      // Moving the start keeps the duration, as calendar programs do.
      const ns = allDay.checked ? parseYmd(sDate.value) : new Date(`${sDate.value}T${sTime.value || "00:00"}`);
      const oldEnd = allDay.checked ? parseYmd(eDate.value) : new Date(`${eDate.value}T${eTime.value || "00:00"}`);
      if (isNaN(ns)) return;
      const ne = new Date(oldEnd.getTime() + (ns - lastStart));
      eDate.value = ymd(ne); eTime.value = `${pad2(ne.getHours())}:${pad2(ne.getMinutes())}`;
      lastStart = ns;
    };
    // recurrence
    let rec = ev.recurrence ? { ...ev.recurrence } : null;
    const repeatSel = el("select", {});
    const recDesc = el("span", { class: "small muted" });
    const fillRepeat = () => {
      const d = new Date(`${sDate.value}T00:00`);
      const wd = WEEKDAYS[d.getDay()], nthOf = Math.ceil(d.getDate() / 7), last = d.getDate() + 7 > daysInMonth(d.getFullYear(), d.getMonth() + 1);
      const dayName = d.toLocaleDateString(undefined, { weekday: "long" });
      const ord = ["", "first", "second", "third", "fourth", "fifth"][nthOf] || "";
      const opts = [["none", "Doesn't repeat"], ["daily", "Every day"], ["weekdays", "Every weekday (Mon–Fri)"], ["weekly", `Weekly on ${dayName}`], ["monthlyDay", `Monthly on day ${d.getDate()}`], [last ? "monthlyLast" : "monthlyNth", last ? `Monthly on the last ${dayName}` : `Monthly on the ${ord} ${dayName}`], ["yearly", `Yearly on ${d.toLocaleDateString(undefined, { month: "long", day: "numeric" })}`], ["custom", "Custom…"]];
      repeatSel.innerHTML = ""; opts.forEach(([v, l]) => repeatSel.appendChild(el("option", { value: v, text: l })));
      repeatSel.value = !rec ? "none" : (rec._preset || "custom");
      recDesc.textContent = rec ? describeRecurrence(rec) : "";
      return { wd, nthOf: last ? -1 : nthOf, d };
    };
    const presetFrom = () => {
      if (!rec) return;
      const r = rec, d = new Date(`${sDate.value}T00:00`);
      if (r.freq === "DAILY" && r.interval === 1 && !r.byDay?.length) r._preset = "daily";
      else if (r.freq === "WEEKLY" && r.interval === 1 && r.byDay?.length === 5 && ["MO", "TU", "WE", "TH", "FR"].every((x) => r.byDay.includes(x))) r._preset = "weekdays";
      else if (r.freq === "WEEKLY" && r.interval === 1 && r.byDay?.length === 1 && r.byDay[0] === WEEKDAYS[d.getDay()]) r._preset = "weekly";
      else if (r.freq === "MONTHLY" && r.interval === 1 && r.byMonthDay === d.getDate() && !r.bySetPos) r._preset = "monthlyDay";
      else if (r.freq === "MONTHLY" && r.interval === 1 && r.bySetPos === -1) r._preset = "monthlyLast";
      else if (r.freq === "MONTHLY" && r.interval === 1 && r.bySetPos > 0) r._preset = "monthlyNth";
      else if (r.freq === "YEARLY" && r.interval === 1 && !r.bySetPos) r._preset = "yearly";
    };
    presetFrom();
    fillRepeat();
    repeatSel.onchange = async () => {
      const chosen = repeatSel.value; // fillRepeat() resets the select to the current rule
      const { wd, nthOf, d } = fillRepeat();
      const keepEnd = rec ? { until: rec.until, count: rec.count } : {};
      const v = chosen === "custom" ? null : chosen;
      if (v === "none") rec = null;
      else if (v === "daily") rec = { freq: "DAILY", interval: 1, ...keepEnd, _preset: v };
      else if (v === "weekdays") rec = { freq: "WEEKLY", interval: 1, byDay: ["MO", "TU", "WE", "TH", "FR"], ...keepEnd, _preset: v };
      else if (v === "weekly") rec = { freq: "WEEKLY", interval: 1, byDay: [wd], ...keepEnd, _preset: v };
      else if (v === "monthlyDay") rec = { freq: "MONTHLY", interval: 1, byMonthDay: d.getDate(), ...keepEnd, _preset: v };
      else if (v === "monthlyNth" || v === "monthlyLast") rec = { freq: "MONTHLY", interval: 1, byDay: [wd], bySetPos: nthOf, ...keepEnd, _preset: v };
      else if (v === "yearly") rec = { freq: "YEARLY", interval: 1, byMonth: d.getMonth() + 1, byMonthDay: d.getDate(), ...keepEnd, _preset: v };
      else rec = await this.customRecurrence(rec, d) || rec;
      fillRepeat();
    };
    sDate.addEventListener("change", () => { if (rec && rec._preset) { const p = rec._preset; repeatSel.value = p; repeatSel.onchange(); } else fillRepeat(); });
    const location = el("input", { type: "text", value: ev.location || "", placeholder: "Add a location" });
    const online = el("input", { type: "url", value: ev.onlineUrl || "", placeholder: "Online meeting link (https://…)" });
    const reminder = select(REMINDERS, ev.reminderMinutes == null ? "-1" : String(ev.reminderMinutes));
    if (ev.reminderMinutes != null && !REMINDERS.some((r) => r[0] === String(ev.reminderMinutes))) reminder.appendChild(el("option", { value: String(ev.reminderMinutes), text: `${ev.reminderMinutes} minutes before`, selected: true }));
    const showAs = select(SHOW_AS, ev.showAs || "busy");
    const calSel = select(cals.map((c) => [c.id, c.name]), ev.calendarId || "calendar");
    const catSel = select([["", "No category"], ...(state.settings?.categories || []).map((c) => [c.name, c.name])], (ev.categories || [])[0] || "");
    const priv = el("input", { type: "checkbox" }); priv.checked = !!ev.private;
    const notes = el("textarea", { rows: 6, placeholder: "Add a description" }); notes.value = ev.notes || "";
    const isNew = !ev.id;
    const amOrganizer = this.isOrganizer(ev);
    const invitedOnly = !!ev.organizer && !amOrganizer;
    const head = el("div", { class: "dlg-head" }, icon("calendar", 20), el("h3", { text: isNew ? (occurrenceOf ? "Edit occurrence" : "New event") : "Edit event" }), el("button", { class: "icon-btn", title: "Close", onclick: () => dlg.close() }, icon("close")));
    const field = (ic, ...kids) => el("div", { class: "ev-row" }, icon(ic, 18), el("div", { class: "ev-fields" }, ...kids));
    const body = el("div", { class: "dlg-body ev-body" },
      title,
      invitedOnly ? el("div", { class: "info-bar" }, icon("user"), el("span", { class: "grow", text: `Organizer: ${ev.organizer.name || ev.organizer.email}. Your response: ${({ ACCEPTED: "accepted", TENTATIVE: "tentative", DECLINED: "declined" })[ev.myStatus] || "not yet answered"}.` }),
        ...[["ACCEPTED", "Accept"], ["TENTATIVE", "Tentative"], ["DECLINED", "Decline"]].map(([r, l]) => el("button", { class: "btn", onclick: () => { dlg.close(); this.respond(ev, r); } }, l))) : null,
      field("people", el("div", { class: "att-wrap", style: { position: "relative" } }, attBox, attInput, sugg)),
      field("clock", el("div", { class: "ev-when" }, sDate, sTime, el("span", { text: "to" }), eDate, eTime, el("label", { class: "check inline" }, allDay, el("span", { text: "All day" })), tzSel)),
      field("repeat", el("div", { class: "ev-when" }, repeatSel, recDesc)),
      field("map-pin", location),
      field("video", online),
      field("bell", el("div", { class: "ev-when" }, reminder, el("label", { class: "small muted", text: "Show as" }), showAs)),
      field("calendar", el("div", { class: "ev-when" }, calSel, catSel, el("label", { class: "check inline" }, priv, el("span", { text: "Private" })))),
      field("note", notes));
    const foot = el("div", { class: "dlg-foot" });
    const collect = () => {
      const ad = allDay.checked;
      const sd = ad ? sDate.value : `${sDate.value}T${sTime.value || "00:00"}`;
      const edDay = ad ? ymd(addDays(parseYmd(eDate.value || sDate.value), 1)) : `${eDate.value || sDate.value}T${eTime.value || "00:00"}`;
      if (!sDate.value) throw new Error("Choose a start date");
      const zone = ad ? "" : tzSel.value;
      if (!ad && zonedToDate(edDay, zone) <= zonedToDate(sd, zone)) throw new Error("The event must end after it starts");
      if (ad && edDay <= sd) throw new Error("The event must end on or after its start date");
      const r = rec ? Object.fromEntries(Object.entries(rec).filter(([k]) => !k.startsWith("_"))) : null;
      return { ...ev, title: title.value.trim() || "(No title)", allDay: ad, tz: zone, start: sd, end: edDay, recurrence: occurrenceOf ? null : r,
        location: location.value.trim(), onlineUrl: online.value.trim(), reminderMinutes: reminder.value === "-1" ? null : Number(reminder.value), showAs: showAs.value,
        calendarId: calSel.value, categories: catSel.value ? [catSel.value] : [], private: priv.checked, notes: notes.value, attendees };
    };
    const save = async (send) => {
      let e;
      try { e = collect(); } catch (er) { return toast(er.message, { danger: true }); }
      try {
        // Moving a series start changes which occurrence an EXDATE refers to; keep the
        // exceptions only when the time of day is unchanged.
        if (!isNew && ev.recurrence && ev.start.slice(10) !== e.start.slice(10)) e.exdates = [];
        const saved = await pim.save("events", e);
        prefs.set("defaultCalendar", saved.calendarId);
        dlg.close();
        if (send) await this.sendInvite(saved, "REQUEST");
        else toast(isNew ? "Event created" : "Event saved");
        if (state.module === "calendar") { this.date = startOfDay(e.allDay ? parseYmd(e.start) : zonedToDate(e.start, e.tz)); this.render(); }
      } catch (er) { toast("Could not save: " + er.message, { danger: true }); }
    };
    const hasAtt = () => attendees.length > 0;
    const sendBtn = el("button", { class: "btn primary", onclick: () => save(hasAtt() && amOrganizer) }, icon("send"), "Send");
    const saveBtn = el("button", { class: "btn primary", onclick: () => save(false) }, icon("check"), "Save");
    const sync = () => { const inv = hasAtt() && amOrganizer; sendBtn.classList.toggle("hidden", !inv); saveBtn.classList.toggle("primary", !inv); };
    new MutationObserver(sync).observe(attBox, { childList: true });
    foot.append(...[sendBtn, saveBtn,
      !isNew ? el("button", { class: "btn danger", onclick: () => { dlg.close(); this.deleteOccurrence({ ev, key: ev.start }); } }, icon("trash"), "Delete") : null,
      !isNew && hasAtt() && amOrganizer ? el("button", { class: "btn", title: "Send a cancellation and delete", onclick: async () => { if (!confirm("Cancel this meeting and notify the attendees?")) return; dlg.close(); await this.sendInvite(ev, "CANCEL"); await pim.remove("events", ev.id); } }, "Cancel meeting") : null,
      el("span", { class: "grow" }),
      el("button", { class: "btn", onclick: () => dlg.close() }, "Discard")].filter(Boolean));
    sync();
    dlg.append(head, body, foot);
    dlg.showModal();
    setTimeout(() => (isNew && !ev.title ? title : title).focus(), 40);
  },
  async customRecurrence(rec, d) {
    const r = rec || { freq: "WEEKLY", interval: 1, byDay: [WEEKDAYS[d.getDay()]] };
    const dayBoxes = el("div", { class: "swatches" });
    const picked = new Set(r.byDay?.length ? r.byDay : [WEEKDAYS[d.getDay()]]);
    for (const wd of ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]) { const cb = el("input", { type: "checkbox", value: wd }); cb.checked = picked.has(wd); dayBoxes.appendChild(el("label", { class: "check", style: { margin: "0 6px 0 0" } }, cb, el("span", { text: wd.charAt(0) + wd.charAt(1).toLowerCase() }))); }
    const v = await formDialog.open({ title: "Repeat", ok: "Save", extra: el("div", { class: "field" }, el("label", { text: "On these days (weekly)" }), dayBoxes), fields: [
      { key: "interval", label: "Repeat every", type: "number", value: r.interval || 1, min: 1, max: 99 },
      { key: "freq", label: "Unit", type: "select", value: r.freq, options: [["DAILY", "day(s)"], ["WEEKLY", "week(s)"], ["MONTHLY", "month(s)"], ["YEARLY", "year(s)"]] },
      { key: "monthly", label: "Monthly / yearly on", type: "select", value: r.bySetPos ? "nth" : "day", options: [["day", `day ${d.getDate()}`], ["nth", `the ${["", "first", "second", "third", "fourth", "last"][Math.min(5, Math.ceil(d.getDate() / 7))]} ${d.toLocaleDateString(undefined, { weekday: "long" })}`]] },
      { key: "end", label: "Ends", type: "select", value: r.until ? "until" : r.count ? "count" : "never", options: [["never", "Never"], ["until", "On a date"], ["count", "After a number of times"]] },
      { key: "until", label: "End date", type: "date", value: r.until || ymd(addDays(d, 90)) },
      { key: "count", label: "Number of times", type: "number", value: r.count || 10, min: 1, max: 999 },
    ] });
    if (!v) return null;
    const out = { freq: v.freq, interval: Math.max(1, Number(v.interval) || 1) };
    if (v.freq === "WEEKLY") out.byDay = qsa("input:checked", dayBoxes).map((x) => x.value);
    if (v.freq === "WEEKLY" && !out.byDay.length) out.byDay = [WEEKDAYS[d.getDay()]];
    if (v.freq === "MONTHLY" || v.freq === "YEARLY") {
      if (v.monthly === "nth") { const n = Math.ceil(d.getDate() / 7); out.byDay = [WEEKDAYS[d.getDay()]]; out.bySetPos = d.getDate() + 7 > daysInMonth(d.getFullYear(), d.getMonth() + 1) ? -1 : n; }
      else out.byMonthDay = d.getDate();
      if (v.freq === "YEARLY") out.byMonth = d.getMonth() + 1;
    }
    if (v.end === "until") out.until = v.until; if (v.end === "count") out.count = Math.max(1, Number(v.count) || 1);
    return out;
  },
};
