/* Anne's Post — To Do: My Day, Important, Planned, Tasks, Flagged email and custom lists, with
 * steps, due dates, reminders, repeats, categories and notes. Loaded before app.js.
 */
"use strict";

const SMART_LISTS = [
  { id: "myday", name: "My Day", icon: "sun" },
  { id: "important", name: "Important", icon: "star" },
  { id: "planned", name: "Planned", icon: "calendar" },
  { id: "tasks", name: "Tasks", icon: "home" },
  { id: "flagged", name: "Flagged email", icon: "flag" },
];

const todoUI = {
  built: false,
  current: "myday",
  selected: null,
  query: "",
  flagged: [],

  show() {
    if (!this.built) this.build();
    this.render();
    if (this.current === "flagged") this.loadFlagged();
  },
  build() {
    this.built = true;
    this.current = prefs.get("todoList", "myday");
    const host = $("modTodo"); host.innerHTML = "";
    host.append(el("div", { class: "todo-body" },
      el("nav", { class: "side-pane", id: "tdSide" }),
      el("section", { class: "todo-main pane-card", id: "tdMain" }),
      el("section", { class: "todo-detail pane-card hidden", id: "tdDetail" })));
    pim.on(() => { if (state.module === "todo") this.render(); });
  },
  today() { return ymd(new Date()); },
  tasksFor(id) {
    const t = pim.data.tasks;
    const today = this.today();
    if (id === "myday") return t.filter((x) => x.myDay === today || (!x.completed && x.due && x.due <= today && prefs.get("myDayIncludesDue", true)));
    if (id === "important") return t.filter((x) => x.important);
    if (id === "planned") return t.filter((x) => x.due);
    return t.filter((x) => x.listId === id);
  },
  render() {
    if (!this.built) return;
    this.renderSide(); this.renderMain(); this.renderDetail();
  },
  renderSide() {
    const side = $("tdSide"); side.innerHTML = "";
    const count = (id) => (id === "flagged" ? this.flagged.length : this.tasksFor(id).filter((x) => !x.completed).length) || "";
    for (const s of SMART_LISTS) {
      side.appendChild(el("button", { class: "nav-item" + (this.current === s.id ? " active" : ""), onclick: () => this.open(s.id) }, icon(s.icon, 18), el("span", { class: "name", text: s.name }), el("span", { class: "count muted", text: String(count(s.id)) })));
    }
    side.appendChild(el("div", { class: "side-sep" }));
    for (const l of pim.data.taskLists.filter((x) => x.id !== "tasks")) {
      const b = el("button", { class: "nav-item" + (this.current === l.id ? " active" : ""), onclick: () => this.open(l.id), oncontextmenu: (e) => { e.preventDefault(); menu.openAt(e.clientX, e.clientY, this.listMenu(l)); } }, icon("list", 18), el("span", { class: "name", text: l.name }), el("span", { class: "count muted", text: String(count(l.id)) }));
      side.appendChild(b);
    }
    side.appendChild(el("button", { class: "add-account", onclick: async () => { const v = await formDialog.open({ title: "New list", ok: "Create", fields: [{ key: "name", label: "List name", required: true }] }); if (v) { const l = await pim.save("taskLists", { name: v.name.trim() }); this.open(l.id); } } }, icon("list-plus"), "New list"));
  },
  listMenu(l) {
    return [
      { label: "Rename list", icon: "edit", onClick: async () => { const v = await formDialog.open({ title: "Rename list", fields: [{ key: "name", label: "Name", value: l.name, required: true }] }); if (v) pim.save("taskLists", { ...l, name: v.name.trim() }); } },
      { label: "Delete list", icon: "trash", danger: true, onClick: async () => { if (!confirm(`Delete "${l.name}" and its tasks?`)) return; await pim.remove("taskLists", l.id); this.open("tasks"); } },
    ];
  },
  open(id) {
    this.current = id; this.selected = null; prefs.set("todoList", id);
    $("modTodo").classList.remove("nav-open", "show-detail");
    this.render();
    if (id === "flagged") this.loadFlagged();
  },
  back() { const m = $("modTodo"); if (m.classList.contains("show-detail")) { this.selected = null; m.classList.remove("show-detail"); this.render(); return true; } return false; },
  async loadFlagged() {
    try {
      const r = await api("/api/search/advanced", { query: { folder: "INBOX", scope: "all", flagged: "true", limit: 200 } });
      this.flagged = r.messages;
      if (state.module === "todo") this.render();
    } catch (e) { toast("Could not load flagged email: " + e.message, { danger: true }); }
  },
  title() {
    const s = SMART_LISTS.find((x) => x.id === this.current);
    return s ? s.name : pim.data.taskLists.find((l) => l.id === this.current)?.name || "Tasks";
  },
  renderMain() {
    const host = $("tdMain"); host.innerHTML = "";
    const head = el("div", { class: "todo-head" },
      el("button", { class: "icon-btn mobile-only", title: "Lists", onclick: () => $("modTodo").classList.toggle("nav-open") }, icon("menu")),
      el("div", {}, el("h2", { text: this.title() }), this.current === "myday" ? el("div", { class: "muted", text: new Date().toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" }) }) : null),
      el("span", { class: "grow" }),
      el("button", { class: "icon-btn", title: "Sort", onclick: (e) => { e.stopPropagation(); const cur = prefs.get("todoSort", "created"); menu.openFor(e.currentTarget, [["created", "Creation date"], ["due", "Due date"], ["importance", "Importance"], ["title", "Alphabetically"]].map(([v, l]) => ({ label: l, checked: cur === v, onClick: () => { prefs.set("todoSort", v); this.renderMain(); } }))); } }, icon("sort")),
      this.current !== "flagged" && !SMART_LISTS.some((s) => s.id === this.current) ? el("button", { class: "icon-btn", title: "List options", onclick: (e) => { e.stopPropagation(); menu.openFor(e.currentTarget, this.listMenu(pim.data.taskLists.find((l) => l.id === this.current))); } }, icon("more")) : null);
    host.appendChild(head);
    if (this.current === "flagged") return this.renderFlagged(host);
    const add = el("input", { type: "text", id: "tdAdd", class: "todo-add", placeholder: this.current === "myday" ? "Add a task to My Day" : "Add a task" });
    add.onkeydown = async (e) => {
      if (e.key !== "Enter" || !add.value.trim()) return;
      const t = { title: add.value.trim(), listId: SMART_LISTS.some((s) => s.id === this.current) ? "tasks" : this.current };
      if (this.current === "myday") t.myDay = this.today();
      if (this.current === "important") t.important = true;
      if (this.current === "planned") t.due = this.today();
      add.value = "";
      try { await pim.save("tasks", t); } catch (er) { toast(er.message, { danger: true }); }
      setTimeout(() => $("tdAdd")?.focus(), 30);
    };
    host.appendChild(el("div", { class: "todo-add-row" }, icon("plus", 18), add));
    const q = this.query.toLowerCase();
    let tasks = this.tasksFor(this.current).filter((t) => !q || t.title.toLowerCase().includes(q) || (t.notes || "").toLowerCase().includes(q));
    const sortBy = prefs.get("todoSort", "created");
    const key = { created: (t) => -t.created, due: (t) => t.due || "9999", importance: (t) => (t.important ? 0 : 1), title: (t) => t.title.toLowerCase() }[sortBy];
    tasks = tasks.sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0));
    const open = tasks.filter((t) => !t.completed), done = tasks.filter((t) => t.completed);
    const scroll = el("div", { class: "message-list todo-list" });
    if (this.current === "planned") {
      const groups = [["Overdue", (t) => t.due < this.today()], ["Today", (t) => t.due === this.today()], ["Tomorrow", (t) => t.due === ymd(addDays(new Date(), 1))], ["This week", (t) => t.due > ymd(addDays(new Date(), 1)) && t.due <= ymd(addDays(new Date(), 7))], ["Later", (t) => t.due > ymd(addDays(new Date(), 7))]];
      for (const [label, f] of groups) { const g = open.filter(f).sort((a, b) => (a.due < b.due ? -1 : 1)); if (g.length) { scroll.appendChild(el("div", { class: "day-sep", text: label })); g.forEach((t) => scroll.appendChild(this.row(t))); } }
    } else open.forEach((t) => scroll.appendChild(this.row(t)));
    if (!open.length && !done.length) scroll.appendChild(el("div", { class: "empty-list" }, icon(SMART_LISTS.find((s) => s.id === this.current)?.icon || "todo", 56), el("div", { text: this.current === "myday" ? "Focus on your day — add tasks here, or add them from any list." : "No tasks yet" })));
    if (done.length) {
      const showDone = prefs.get("todoShowDone", false);
      scroll.appendChild(el("button", { class: "done-toggle", onclick: () => { prefs.set("todoShowDone", !showDone); this.renderMain(); } }, icon(showDone ? "chev-down" : "chev-right", 14), `Completed ${done.length}`));
      if (showDone) done.sort((a, b) => b.completedAt - a.completedAt).forEach((t) => scroll.appendChild(this.row(t)));
    }
    host.appendChild(scroll);
  },
  row(t) {
    const listName = this.current !== t.listId && !SMART_LISTS.some((s) => s.id === t.listId) ? pim.data.taskLists.find((l) => l.id === t.listId)?.name : (this.current !== "tasks" && t.listId === "tasks" ? "Tasks" : null);
    const overdue = t.due && !t.completed && t.due < this.today();
    const sub = [
      t.myDay === this.today() && this.current !== "myday" ? el("span", {}, icon("sun", 12), "My Day") : null,
      listName ? el("span", { text: listName }) : null,
      t.steps?.length ? el("span", { text: `${t.steps.filter((s) => s.done).length} of ${t.steps.length}` }) : null,
      t.due ? el("span", { class: overdue ? "overdue" : "" }, icon("calendar", 12), this.dueLabel(t.due)) : null,
      t.reminder && !t.completed ? el("span", {}, icon("bell", 12), fmtDate(t.reminder)) : null,
      t.repeat ? el("span", {}, icon("repeat", 12)) : null,
      t.notes ? el("span", {}, icon("note", 12)) : null,
      ...(t.categories || []).map((c) => el("span", { class: "cat-chip", style: { background: mailplus.categoryColor(c) } }, c)),
    ].filter(Boolean);
    const check = el("button", { class: "todo-check" + (t.completed ? " done" : ""), title: t.completed ? "Mark as not completed" : "Mark as completed", onclick: (e) => { e.stopPropagation(); this.toggleComplete(t); } }, icon(t.completed ? "check-circle-fill" : "circle", 20));
    const star = el("button", { class: "todo-star" + (t.important ? " on" : ""), title: t.important ? "Remove importance" : "Mark as important", onclick: (e) => { e.stopPropagation(); pim.save("tasks", { ...t, important: !t.important }); } }, icon(t.important ? "star-fill" : "star", 18));
    return el("div", { class: "todo-row" + (this.selected === t.id ? " open" : "") + (t.completed ? " completed" : ""), onclick: () => { this.selected = t.id; $("modTodo").classList.add("show-detail"); this.render(); }, oncontextmenu: (e) => { e.preventDefault(); menu.openAt(e.clientX, e.clientY, this.taskMenu(t)); } },
      check, el("div", { class: "t-main" }, el("div", { class: "t-title", text: t.title }), sub.length ? el("div", { class: "t-sub" }, ...sub) : null), star);
  },
  dueLabel(due) {
    const today = this.today(), tomorrow = ymd(addDays(new Date(), 1)), yesterday = ymd(addDays(new Date(), -1));
    if (due === today) return "Today"; if (due === tomorrow) return "Tomorrow"; if (due === yesterday) return "Yesterday";
    return fmtDay(parseYmd(due), { weekday: "short", month: "short", day: "numeric", year: due.slice(0, 4) !== today.slice(0, 4) ? "numeric" : undefined });
  },
  taskMenu(t) {
    const today = this.today();
    return [
      { label: t.myDay === today ? "Remove from My Day" : "Add to My Day", icon: "sun", onClick: () => pim.save("tasks", { ...t, myDay: t.myDay === today ? null : today }) },
      { label: t.important ? "Remove importance" : "Mark as important", icon: "star", onClick: () => pim.save("tasks", { ...t, important: !t.important }) },
      { label: t.completed ? "Mark as not completed" : "Mark as completed", icon: "check-circle", onClick: () => this.toggleComplete(t) },
      { sep: true },
      { label: "Due today", icon: "calendar", onClick: () => pim.save("tasks", { ...t, due: today }) },
      { label: "Due tomorrow", icon: "calendar", onClick: () => pim.save("tasks", { ...t, due: ymd(addDays(new Date(), 1)) }) },
      t.due ? { label: "Remove due date", icon: "close", onClick: () => pim.save("tasks", { ...t, due: null }) } : null,
      { label: "Move to list", icon: "move", sub: () => pim.data.taskLists.map((l) => ({ label: l.name, checked: t.listId === l.id, onClick: () => pim.save("tasks", { ...t, listId: l.id }) })) },
      { sep: true },
      { label: "Delete task", icon: "trash", danger: true, onClick: () => this.remove(t) },
    ];
  },
  async remove(t) {
    if (!confirm(`Delete "${t.title}"?`)) return;
    await pim.remove("tasks", t.id);
    if (this.selected === t.id) { this.selected = null; $("modTodo").classList.remove("show-detail"); }
    toast("Task deleted");
  },
  /** Completing a repeating task schedules its next occurrence, like a desktop task list. */
  async toggleComplete(t) {
    const completing = !t.completed;
    await pim.save("tasks", { ...t, completed: completing });
    if (completing && t.repeat) {
      const base = t.due ? parseYmd(t.due) : new Date();
      const occ = expandEvent({ start: ymd(base), end: ymd(addDays(base, 1)), allDay: true, recurrence: t.repeat }, addDays(base, 1), addDays(base, 800));
      const next = occ[0];
      if (next) await pim.save("tasks", { ...t, id: "", completed: false, completedAt: 0, due: ymd(next.start), myDay: null, reminder: t.reminder ? t.reminder + (next.start - base) : null, steps: (t.steps || []).map((s) => ({ ...s, id: "", done: false })) });
    }
    if (completing) mailplus.chime && prefs.get("notifySound", false) && mailplus.chime();
  },
  renderFlagged(host) {
    const scroll = el("div", { class: "message-list todo-list" });
    if (!this.flagged.length) scroll.appendChild(el("div", { class: "empty-list" }, icon("flag", 56), el("div", { text: "Flag a message in Mail and it shows up here as a task." })));
    for (const m of this.flagged) {
      scroll.appendChild(el("div", { class: "todo-row", onclick: () => { shell.setModule("mail"); reading.open(m); } },
        el("button", { class: "todo-check", title: "Mark complete", onclick: async (e) => { e.stopPropagation(); await actions.completeFlag([m]); this.flagged = this.flagged.filter((x) => mkey(x) !== mkey(m)); this.render(); } }, icon("circle", 20)),
        el("div", { class: "t-main" }, el("div", { class: "t-title", text: m.subject || "(no subject)" }), el("div", { class: "t-sub" }, el("span", {}, icon("mail", 12), m.fromName || m.fromAddress), m.flagDue ? el("span", { class: m.flagDue < this.today() ? "overdue" : "" }, icon("calendar", 12), this.dueLabel(m.flagDue)) : null, el("span", { text: folders.byPath(m.folder)?.name || m.folder }))),
        icon("flag-fill", 16)));
    }
    host.appendChild(scroll);
  },
  renderDetail() {
    const host = $("tdDetail");
    const t = pim.data.tasks.find((x) => x.id === this.selected);
    host.classList.toggle("hidden", !t);
    host.innerHTML = "";
    if (!t) return;
    const save = (patch) => pim.save("tasks", { ...t, ...patch });
    const title = el("input", { type: "text", class: "td-title", value: t.title });
    title.onchange = () => title.value.trim() && save({ title: title.value.trim() });
    const steps = el("div", { class: "td-steps" });
    (t.steps || []).forEach((s, i) => {
      const inp = el("input", { type: "text", value: s.title });
      inp.onchange = () => { const st = [...t.steps]; st[i] = { ...s, title: inp.value }; save({ steps: st }); };
      steps.appendChild(el("div", { class: "td-step" + (s.done ? " done" : "") },
        el("button", { class: "todo-check small" + (s.done ? " done" : ""), onclick: () => { const st = [...t.steps]; st[i] = { ...s, done: !s.done }; save({ steps: st }); } }, icon(s.done ? "check-circle-fill" : "circle", 16)),
        inp, el("button", { class: "icon-btn", title: "Delete step", onclick: () => save({ steps: t.steps.filter((_, j) => j !== i) }) }, icon("close", 14))));
    });
    const addStep = el("input", { type: "text", placeholder: t.steps?.length ? "Next step" : "Add step" });
    addStep.onkeydown = (e) => { if (e.key === "Enter" && addStep.value.trim()) { save({ steps: [...(t.steps || []), { id: "", title: addStep.value.trim(), done: false }] }); } };
    steps.appendChild(el("div", { class: "td-step add" }, icon("plus", 16), addStep));
    const today = this.today();
    const due = el("input", { type: "date", value: t.due || "" }); due.onchange = () => save({ due: due.value || null });
    const rem = el("input", { type: "datetime-local", value: t.reminder ? localIso(new Date(t.reminder)) : "" }); rem.onchange = () => save({ reminder: rem.value ? new Date(rem.value).getTime() : null });
    const rep = select([["", "Does not repeat"], ["DAILY", "Daily"], ["WEEKDAYS", "Weekdays"], ["WEEKLY", "Weekly"], ["MONTHLY", "Monthly"], ["YEARLY", "Yearly"]], !t.repeat ? "" : t.repeat.freq === "WEEKLY" && t.repeat.byDay?.length === 5 ? "WEEKDAYS" : t.repeat.freq);
    rep.onchange = () => {
      const d = t.due ? parseYmd(t.due) : new Date();
      const v = rep.value;
      save({ repeat: !v ? null : v === "WEEKDAYS" ? { freq: "WEEKLY", interval: 1, byDay: ["MO", "TU", "WE", "TH", "FR"] } : v === "WEEKLY" ? { freq: "WEEKLY", interval: 1, byDay: [WEEKDAYS[d.getDay()]] } : v === "MONTHLY" ? { freq: "MONTHLY", interval: 1, byMonthDay: d.getDate() } : v === "YEARLY" ? { freq: "YEARLY", interval: 1, byMonth: d.getMonth() + 1, byMonthDay: d.getDate() } : { freq: v, interval: 1 }, due: t.due || (v ? today : null) });
    };
    const cat = select([["", "No category"], ...(state.settings?.categories || []).map((c) => [c.name, c.name])], (t.categories || [])[0] || "");
    cat.onchange = () => save({ categories: cat.value ? [cat.value] : [] });
    const listSel = select(pim.data.taskLists.map((l) => [l.id, l.name]), t.listId); listSel.onchange = () => save({ listId: listSel.value });
    const notes = el("textarea", { rows: 6, placeholder: "Add note" }); notes.value = t.notes || ""; notes.onchange = () => save({ notes: notes.value });
    const card = (...kids) => el("div", { class: "td-card" }, ...kids);
    const line = (ic, label, control) => el("label", { class: "td-line" }, icon(ic, 16), el("span", { class: "lbl", text: label }), control);
    host.append(
      el("div", { class: "reading-toolbar" }, el("button", { class: "icon-btn mobile-only", title: "Back", onclick: () => this.back() }, icon("back")), el("span", { class: "grow" }), el("button", { class: "icon-btn", title: "Close", onclick: () => { this.selected = null; $("modTodo").classList.remove("show-detail"); this.render(); } }, icon("close"))),
      el("div", { class: "reading-scroll" },
        card(el("div", { class: "td-head" }, el("button", { class: "todo-check" + (t.completed ? " done" : ""), onclick: () => this.toggleComplete(t) }, icon(t.completed ? "check-circle-fill" : "circle", 22)), title, el("button", { class: "todo-star" + (t.important ? " on" : ""), onclick: () => save({ important: !t.important }) }, icon(t.important ? "star-fill" : "star", 20))), steps),
        card(el("button", { class: "td-myday" + (t.myDay === today ? " on" : ""), onclick: () => save({ myDay: t.myDay === today ? null : today }) }, icon("sun", 16), t.myDay === today ? "Added to My Day" : "Add to My Day")),
        card(line("bell", "Remind me", rem), line("calendar", "Due", due), line("repeat", "Repeat", rep)),
        card(line("tag", "Category", cat), line("list", "List", listSel)),
        card(notes),
        el("div", { class: "small muted td-meta", text: `Created ${fmtDate(t.created, { long: true })}${t.completed ? ` · completed ${fmtDate(t.completedAt, { long: true })}` : ""}` }),
        el("div", { class: "btn-row" }, el("button", { class: "btn danger", onclick: () => this.remove(t) }, icon("trash"), "Delete task"))));
  },
  search(q) { this.query = q; if (state.module !== "todo") shell.setModule("todo"); this.renderMain(); },
  quickAdd() { shell.setModule("todo"); if (this.current === "flagged") this.open("tasks"); setTimeout(() => $("tdAdd")?.focus(), 60); },
  /** Compact list for the My Day panel. */
  miniList() {
    const box = el("div", { class: "md-todo" });
    const add = el("input", { type: "text", placeholder: "Add a task to My Day" });
    add.onkeydown = async (e) => { if (e.key === "Enter" && add.value.trim()) { await pim.save("tasks", { title: add.value.trim(), listId: "tasks", myDay: this.today() }); } };
    box.appendChild(el("div", { class: "todo-add-row" }, icon("plus", 16), add));
    const tasks = this.tasksFor("myday").filter((t) => !t.completed);
    if (!tasks.length) box.appendChild(el("div", { class: "small muted", style: { padding: "8px 4px" }, text: "Nothing for today yet." }));
    for (const t of tasks) box.appendChild(el("div", { class: "todo-row compact" },
      el("button", { class: "todo-check", onclick: () => this.toggleComplete(t) }, icon("circle", 18)),
      el("div", { class: "t-main", onclick: () => { shell.setModule("todo"); this.selected = t.id; this.open(t.myDay ? "myday" : t.listId); this.selected = t.id; this.render(); } }, el("div", { class: "t-title", text: t.title }), t.due ? el("div", { class: "t-sub" }, el("span", { class: t.due < this.today() ? "overdue" : "" }, this.dueLabel(t.due))) : null)));
    box.appendChild(el("button", { class: "btn sm", style: { marginTop: "8px" }, onclick: () => { shell.setModule("todo"); this.open("myday"); } }, icon("todo"), "Open To Do"));
    return box;
  },
};
