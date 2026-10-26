/* Anne's Post — Quick Access Toolbar and the command UI: the QAT (above or below the ribbon, or at the
 * bottom near the keyboard), its drop-down checklist, the "More Commands…" chooser, command search
 * (Alt+Q), keyboard customisation, the command reference, the File backstage, the extra ribbon and
 * compose tabs, and the keyboard dispatcher that runs registered commands. Loaded after app.js.
 */
"use strict";

/** Icons for commands that the main sprite does not have (same 24×24 stroke style). */
const EXTRA_ICONS = {
  "c-redo": '<path d="M15 14l5-5-5-5"/><path d="M20 9H9.5a5.5 5.5 0 0 0 0 11H13"/>',
  "c-touch": '<path d="M9 11V5a2 2 0 0 1 4 0v6"/><path d="M13 10a2 2 0 0 1 4 0v2a2 2 0 0 1 4 0v3a7 7 0 0 1-7 7h-1a7 7 0 0 1-6-3.4L4 15a2 2 0 0 1 3.4-2l1.6 2"/>',
  "c-qat": '<path d="M4 6h16"/><path d="M4 12h10"/><path d="M4 18h6"/><path d="m16 15 3 3 3-3"/>',
  "c-cut": '<circle cx="6" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M20 4 8.1 15.9M14.5 14.5 20 20M8.1 8.1 12 12"/>',
  "c-paste": '<rect x="8" y="2" width="8" height="4" rx="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/>',
  "c-brush": '<path d="M9.06 11.9 17 4a2.12 2.12 0 0 1 3 3l-7.94 7.94"/><path d="M7 14c-1.66 0-3 1.34-3 3 0 1.3-1 2-2 2 1 1.5 2.5 2 4 2a4 4 0 0 0 4-4c0-1.66-1.34-3-3-3z"/>',
  "c-high": '<path d="M12 4v11"/><circle cx="12" cy="19.5" r=".8" fill="currentColor"/>',
  "c-low": '<path d="M12 4v14"/><path d="m6 12 6 6 6-6"/>',
  "c-screenshot": '<path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3"/><circle cx="12" cy="12" r="3"/>',
  "c-pen": '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
  "c-omega": '<path d="M5 20h4v-2a7 7 0 1 1 6 0v2h4"/>',
  "c-justify": '<path d="M4 6h16M4 10h16M4 14h16M4 18h16"/>',
  "c-spacing": '<path d="M11 6h9M11 12h9M11 18h9"/><path d="m4 8 2-3 2 3M4 16l2 3 2-3M6 5v14"/>',
  "c-pilcrow": '<path d="M13 4v16M17 4v16M19 4H9.5a4.5 4.5 0 0 0 0 9H13"/>',
  "c-quote": '<path d="M3 21c3 0 7-1 7-8V5H3v7h4c0 4-2 6-4 6zM14 21c3 0 7-1 7-8V5h-7v7h4c0 4-2 6-4 6z"/>',
  "c-sup": '<path d="m4 19 8-8M12 19l-8-8"/><path d="M20 12h-4c0-1.5.4-2 1.5-2.8C18.6 8.3 20 7.6 20 6c0-1.1-.9-2-2-2s-2 .9-2 2"/>',
  "c-sub": '<path d="m4 5 8 8M12 5l-8 8"/><path d="M20 21h-4c0-1.5.4-2 1.5-2.8 1.1-.9 2.5-1.6 2.5-3.2 0-1.1-.9-2-2-2s-2 .9-2 2"/>',
  "c-case": '<path d="M3 19 7.5 6 12 19M4.5 15h6"/><circle cx="17.5" cy="15.5" r="3.5"/><path d="M21 12v7"/>',
  "c-pepper": '<path d="M12 7c4-1 8 1 8 6 0 5-6 9-12 9 2-2 3-4 3-7 0-4 1-8 1-8z"/><path d="M12 7c0-2 1-4 3-5"/>',
  "c-pipe": '<path d="M12 3v18"/><path d="M6 8 3 12l3 4M18 8l3 4-3 4"/>',
  "c-screener": '<path d="M4 5h16l-6 7v6l-4 2v-8z"/>',
  "c-bundle": '<rect x="3" y="4" width="18" height="5" rx="1"/><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4"/>',
  "c-split": '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M9 4v6M15 4v6"/>',
  "c-important": '<path d="M5 4h10l4 8-4 8H5l4-8z"/>',
  "c-question": '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14"/><circle cx="12" cy="17" r=".8" fill="currentColor"/>',
  "c-up": '<path d="M12 19V5M5 12l7-7 7 7"/>',
  "c-down": '<path d="M12 5v14M5 12l7 7 7-7"/>',
  "c-big": '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M12 18v-6M9 15l3 3 3-3"/>',
};

const qatUI = {
  lastSync: 0,
  prefs() { return Qat.sanitizeQatPrefs(prefs.get("qat", null)); },
  update(patch) { prefs.set("qat", { ...this.prefs(), ...patch }); this.apply(); },
  apply() { this.render(); this.applyViewPrefs(); this.refresh(); },

  /** Resolves an id to a command, creating Quick Step ("macro") commands on demand. */
  cmd(id) {
    let c = Commands.get(id);
    if (!c && id.startsWith("quickstep:")) {
      const q = (state.settings?.quickSteps || []).find((x) => x.id === id.slice(10));
      if (q) { Commands.def({ id, label: q.name, icon: "bolt", tab: "Macros", group: "Quick Steps", tip: "Quick step: " + q.actions.map((a) => a.type.toLowerCase().replace(/_/g, " ")).join(", "), isEnabled: (ctx) => MC.has(ctx), run: (ctx) => mailplus.runQuickStep(q, MC.T(ctx)) }); c = Commands.get(id); }
    }
    return c;
  },
  registerQuickSteps() { for (const q of state.settings?.quickSteps || []) this.cmd("quickstep:" + q.id); },

  // ================================================================== start-up
  init() {
    if (this.inited) return; this.inited = true;
    this.injectIcons();
    Commands.keymap = prefs.get("keymap", {}) || {};
    Commands.scheme = prefs.get("keyScheme", "default");
    Commands.hooks.error = (c, e) => toast(e && e.user ? e.message : `${c.label}: ${e && e.message ? e.message : e}`, { danger: !(e && e.user) });
    Commands.hooks.disabled = (c, ctx) => { if (ctx.source !== "key") toast(MC.composing() || c.scope !== "compose" ? (c.scope === "compose" ? "Open a message you are writing first." : "Select a message first.") : "Open a message you are writing first."); };
    Commands.hooks.showMenu = (items, ctx) => MC.showMenu(items, ctx);
    Commands.hooks.after = (c) => { this.remember(c.id); setTimeout(() => this.refresh(), 50); };
    mailUndo.install();
    mailExtras.install();
    this.bindButtons();
    this.buildRibbonTabs();
    this.buildComposeTabs();
    document.addEventListener("keydown", (e) => this.onKey(e), true);
    document.addEventListener("contextmenu", (e) => this.onContext(e));
    const refresh = debounce(() => this.refresh(), 120);
    document.addEventListener("click", refresh); document.addEventListener("keyup", refresh);
    const origSet = shell.setModule.bind(shell);
    shell.setModule = (...a) => { const r = origSet(...a); this.render(); return r; };
    const origStart = app.start.bind(app);
    app.start = async (...a) => { const r = await origStart(...a); this.registerQuickSteps(); this.apply(); return r; };
    this.apply();
  },
  injectIcons() {
    if ($("qbIcons")) return;
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg"); svg.id = "qbIcons"; svg.setAttribute("width", "0"); svg.setAttribute("height", "0"); svg.setAttribute("aria-hidden", "true"); svg.style.position = "absolute";
    svg.innerHTML = "<defs>" + Object.entries(EXTRA_ICONS).map(([k, v]) => `<symbol id="i-${k}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${v}</symbol>`).join("") + "</defs>";
    document.body.prepend(svg);
  },

  /** Points the existing ribbon, toolbar and reading-pane buttons at their commands. */
  bindButtons() {
    const bind = (map, ctxFn) => {
      for (const [elId, cmdId] of Object.entries(map)) {
        const b = $(elId); if (!b || !Commands.get(cmdId)) continue;
        b.dataset.cmd = cmdId;
        b.onclick = (e) => { e.stopPropagation(); Commands.run(cmdId, { anchor: e.currentTarget, source: "ribbon", ...(ctxFn ? ctxFn() : {}) }); };
      }
    };
    bind(RIBBON_BINDINGS);
    bind(READING_BINDINGS, () => ({ items: state.openDetail ? [state.messages.find((m) => mkey(m) === mkey(state.openDetail)) || state.openDetail] : [] }));
    for (const [elId, cmdId] of Object.entries(COMPOSE_TAGS)) { const b = $(elId); if (b) b.dataset.cmd = cmdId; }
  },

  /** File tab, Send / Receive, Folder and Tools tabs on the mail ribbon. */
  buildRibbonTabs() {
    const tabs = qs("#ribbon .ribbon-tabs"); if (!tabs || qs("[data-rtab=sendreceive]", tabs)) return;
    const file = el("button", { class: "qb-file-tab", title: "File (backstage)", onclick: (e) => { e.stopPropagation(); this.backstage(); } }, "File");
    tabs.prepend(file);
    const activate = (b) => {
      qsa(".ribbon-tabs button", $("ribbon")).forEach((x) => x.classList.toggle("active", x === b));
      qsa("#ribbon [data-rbar]").forEach((bar) => bar.classList.toggle("hidden", bar.dataset.rbar !== b.dataset.rtab));
      $("ribbon").classList.add("qb-peek");
    };
    for (const t of EXTRA_RIBBON_TABS) {
      const btn = el("button", { dataset: { rtab: t.id } }, t.label);
      btn.onclick = () => activate(btn);
      const after = qs(`[data-rtab="${t.after}"]`, tabs);
      if (after) after.after(btn); else tabs.appendChild(btn);
      const bar = el("div", { class: "ribbon-bar hidden qb-bar", dataset: { rbar: t.id } }, ...t.items.map((id) => this.ribbonItem(id)).filter(Boolean));
      const afterBar = qs(`#ribbon [data-rbar="${t.after}"]`);
      (afterBar || qs("#ribbon [data-rbar]")).after(bar);
    }
    // Existing tabs keep their handler; clicking any tab re-opens a collapsed ribbon for a moment.
    qsa("button", tabs).forEach((b) => b.addEventListener("click", () => $("ribbon").classList.add("qb-peek")));
    document.addEventListener("click", (e) => { if (!e.target.closest("#ribbon")) $("ribbon").classList.remove("qb-peek"); });
  },
  ribbonItem(id) {
    if (id === "|") return el("span", { class: "rsep" });
    const c = Commands.get(id); if (!c) return null;
    const b = el("button", { class: "rb", dataset: { cmd: id }, title: this.tip(c) }, icon(c.icon || "bolt"), el("span", { class: "lbl", text: c.label.replace(/…$/, "") }));
    if (c.kind === "menu") { const chev = icon("chev-down"); chev.setAttribute("class", "chev"); b.appendChild(chev); }
    b.onclick = (e) => { e.stopPropagation(); Commands.run(id, { anchor: e.currentTarget, source: "ribbon" }); };
    return b;
  },
  /** Extra buttons on the compose Message, Insert, Format and Options tabs, and new Review and Draw tabs. */
  buildComposeTabs() {
    const tabs = qs("#composeDialog .compose-tabs"); if (!tabs || qs("[data-ctab=review]", tabs)) return;
    const mk = (id) => {
      const c = Commands.get(id); if (!c) return null;
      const b = el("button", { class: "btn subtle sm qb-cbtn", dataset: { cmd: id }, title: this.tip(c), onmousedown: (e) => { e.preventDefault(); MC.rememberSelection(); } }, icon(c.icon || "bolt"), c.label.replace(/…$/, ""));
      b.onclick = (e) => { e.stopPropagation(); Commands.run(id, { anchor: e.currentTarget, source: "ribbon" }); };
      return b;
    };
    for (const [bar, ids] of Object.entries(COMPOSE_EXTRA)) {
      if (Array.isArray(ids)) { const host = qs(`#composeDialog [data-cbar="${bar}"]`); if (host) host.append(el("span", { class: "vsep" }), ...ids.map(mk).filter(Boolean)); continue; }
      const btn = el("button", { dataset: { ctab: bar } }, ids.label);
      tabs.appendChild(btn);
      const host = el("div", { class: "fmt-bar hidden", dataset: { cbar: bar } }, ...ids.items.map(mk).filter(Boolean));
      qsa("#composeDialog [data-cbar]").pop().after(host);
    }
    qsa("button", tabs).forEach((b) => (b.onclick = () => { qsa("button", tabs).forEach((x) => x.classList.toggle("active", x === b)); qsa("#composeDialog [data-cbar]").forEach((bar) => bar.classList.toggle("hidden", bar.dataset.cbar !== b.dataset.ctab)); }));
  },
  tip(c) {
    const keys = Commands.keysFor(c.id);
    return `${c.label}${keys.length ? ` (${keys.slice(0, 2).join(", ")})` : ""}${c.tip ? " — " + c.tip : ""}${c.origin ? ` · from ${c.origin}` : ""}`;
  },

  // ================================================================== the toolbar
  render() {
    const p = this.prefs();
    qsa(".qb-qat").forEach((n) => n.remove());
    document.documentElement.dataset.qat = p.hidden ? "hidden" : p.position;
    if (p.hidden || !$("app")) return;
    const bar = el("div", { class: "qb-qat qb-qat-" + p.position + (p.labels ? " labels" : ""), role: "toolbar", "aria-label": "Quick Access Toolbar" });
    for (const id of p.items) {
      if (id === "|") { bar.appendChild(el("span", { class: "qb-qat-sep" })); continue; }
      const c = this.cmd(id); if (!c) continue;
      bar.appendChild(this.qatButton(c, p.labels));
    }
    bar.appendChild(el("button", { class: "qb-qat-btn qb-qat-more", title: "Customize Quick Access Toolbar", "aria-label": "Customize Quick Access Toolbar", onclick: (e) => { e.stopPropagation(); this.dropdown(e.currentTarget); } }, icon("chev-down", 14)));
    if (p.position === "top") { const brand = qs(".topbar .brand"); (brand || qs(".topbar").firstChild).after(bar); }
    else if (p.position === "below") {
      const mod = $(shell.modules[state.module] || "modMail");
      const rib = mod && qs(".ribbon, .cal-ribbon, [class*=ribbon]", mod);
      if (rib && rib.parentElement === mod) rib.after(bar); else if (mod) mod.prepend(bar);
    } else $("app").appendChild(bar);
  },
  qatButton(c, labels) {
    const b = el("button", { class: "qb-qat-btn", dataset: { cmd: c.id }, title: this.tip(c), "aria-label": c.label }, icon(c.icon || "bolt", 16), labels ? el("span", { class: "lbl", text: c.label.replace(/…$/, "") }) : null, c.kind === "menu" ? icon("chev-down", 10) : null);
    b.addEventListener("mousedown", (e) => { if (MC.composing()) { e.preventDefault(); MC.rememberSelection(); } });
    b.onclick = (e) => { e.stopPropagation(); Commands.run(c.id, { anchor: e.currentTarget, source: "qat" }); };
    return b;
  },
  /** The drop-down checklist: the standard choices, useful extras, then the bar's own options. */
  dropdown(anchor) {
    const p = this.prefs();
    const item = (id) => { const c = this.cmd(id); return c ? { label: c.label, icon: c.icon, checked: p.items.includes(id), keepOpen: false, onClick: () => this.update({ items: Qat.toggleQatItem(this.prefs().items, id) }) } : null; };
    menu.openFor(anchor, [
      { header: "Customize Quick Access Toolbar" },
      ...Qat.STANDARD.map(item), { sep: true },
      ...Qat.EXTRAS.map(item), { sep: true },
      { label: "Add All Choices (Save, Print first, then A–Z)", icon: "sort", onClick: () => { this.update({ items: Qat.addAllQatChoices(this.prefs().items, (id) => this.cmd(id)?.label) }); toast("All choices added to the Quick Access Toolbar"); } },
      { label: "More Commands…", icon: "plus", onClick: () => this.chooser() },
      { sep: true },
      p.position !== "top" ? { label: "Show Above the Ribbon", icon: "c-qat", onClick: () => this.update({ position: "top" }) } : null,
      p.position !== "below" ? { label: "Show Below the Ribbon", icon: "c-qat", onClick: () => this.update({ position: "below" }) } : null,
      p.position !== "bottom" ? { label: "Show at the Bottom (near the keyboard)", icon: "c-qat", onClick: () => this.update({ position: "bottom" }) } : null,
      { label: p.labels ? "Hide Command Labels" : "Show Command Labels", icon: "list", onClick: () => this.update({ labels: !p.labels }) },
      { label: "Hide Quick Access Toolbar", icon: "close", onClick: () => { this.update({ hidden: true }); toast("Quick Access Toolbar hidden. Show it again from View → Show Quick Access Toolbar, or right-click any ribbon button.", { timeout: 8000 }); } },
      { label: "Customize Keyboard Shortcuts…", icon: "keyboard", onClick: () => this.keyboard() },
      { label: prefs.get("ribbonCollapsed", false) ? "Show the Ribbon" : "Collapse the Ribbon", icon: "minimize", onClick: () => Commands.run("view.ribbon") },
    ]);
  },
  /** Updates pressed / disabled states of the QAT and of the command buttons this file created. */
  refresh() {
    if (!state.token) return;
    for (const b of qsa(".qb-qat-btn[data-cmd], .qb-bar [data-cmd], .qb-cbtn[data-cmd]")) {
      const id = b.dataset.cmd, c = Commands.get(id); if (!c) continue;
      const scopeOk = c.scope !== "compose" || MC.composing();
      b.classList.toggle("qb-disabled", !scopeOk || !Commands.enabled(id, {}));
      if (c.kind === "toggle") { const on = Commands.active(id, {}); b.classList.toggle("active", on); b.setAttribute("aria-pressed", String(on)); }
    }
  },
  applyViewPrefs() {
    const p = this.prefs();
    document.documentElement.dataset.touch = p.touch ? "on" : "off";
    document.documentElement.dataset.previewLines = String(prefs.get("previewLines", prefs.get("showPreview", true) ? 1 : 0));
    $("ribbon")?.classList.toggle("qb-collapsed", !!prefs.get("ribbonCollapsed", false));
  },
  remember(id) { const r = prefs.get("recentCommands", []).filter((x) => x !== id); r.unshift(id); prefs.set("recentCommands", r.slice(0, 12)); },

  // ================================================================== keyboard
  onKey(e) {
    if (!state.token || this.capturing) return;
    if (e.defaultPrevented || e.cancelBubble || e.isComposing) return;
    const k = Commands.keyName(e); if (!k) return;
    const ids = Commands.commandsForKey(k); if (!ids.length) return;
    const t = e.target;
    const typing = !!(t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable));
    const composeOpen = MC.composing();
    const inCompose = !!(t && t.closest && t.closest("#composeDialog"));
    const modal = qsa("dialog[open]").some((d) => !d.classList.contains("docked") && d.matches(":modal"));
    const strong = /^(Ctrl|Alt)\+/.test(k) || /^(Shift\+)?F\d{1,2}$/.test(k);
    const composeModal = composeOpen && $("composeDialog").matches(":modal");
    for (const id of ids) {
      const c = this.cmd(id); if (!c) continue;
      const scope = c.scope || "mail";
      let ok;
      if (scope === "compose") ok = composeOpen && (inCompose || composeModal) && (strong || !typing) && (!modal || composeModal);
      else if (scope === "global") ok = strong ? (!modal || composeModal) : (!typing && !modal);
      else ok = !typing && !modal && state.module === "mail" && !(composeOpen && inCompose);
      if (!ok) continue;
      const ctx = { source: "key" };
      if (!Commands.enabled(id, ctx)) continue;
      e.preventDefault(); e.stopPropagation();
      Commands.run(id, ctx);
      return;
    }
  },

  // ================================================================== right-click on any command button
  onContext(e) {
    const b = e.target.closest && e.target.closest("[data-cmd], .ribbon-tabs");
    if (!b || !state.token) return;
    e.preventDefault();
    const p = this.prefs();
    if (!b.dataset.cmd) { menu.openAt(e.clientX, e.clientY, [{ label: p.hidden ? "Show Quick Access Toolbar" : "Hide Quick Access Toolbar", icon: "c-qat", onClick: () => this.update({ hidden: !p.hidden }) }, { label: "Customize Quick Access Toolbar…", icon: "settings", onClick: () => this.chooser() }, { label: prefs.get("ribbonCollapsed", false) ? "Show the Ribbon" : "Collapse the Ribbon", icon: "minimize", onClick: () => Commands.run("view.ribbon") }]); return; }
    const id = b.dataset.cmd, c = this.cmd(id); if (!c) return;
    const inQat = p.items.includes(id);
    menu.openAt(e.clientX, e.clientY, [
      { header: c.label },
      { label: inQat ? "Remove from Quick Access Toolbar" : "Add to Quick Access Toolbar", icon: inQat ? "close" : "plus", onClick: () => { this.update({ items: Qat.toggleQatItem(this.prefs().items, id), hidden: false }); toast(inQat ? "Removed from the Quick Access Toolbar" : "Added to the Quick Access Toolbar"); } },
      p.hidden ? { label: "Show Quick Access Toolbar", icon: "c-qat", onClick: () => this.update({ hidden: false }) } : null,
      { label: "Customize Quick Access Toolbar…", icon: "settings", onClick: () => this.chooser() },
      { label: "Assign Shortcut…", icon: "keyboard", onClick: () => this.keyboard(id) },
      { label: "About This Command", icon: "info", onClick: () => toast(this.tip(c) + ` · ${c.tab}${c.group ? " → " + c.group : ""}`, { timeout: 9000 }) },
    ]);
  },

  // ================================================================== "More Commands…" chooser
  categories() {
    const all = () => Commands.all().sort((a, b) => a.label.localeCompare(b.label));
    const inRibbon = new Set([...Object.values(RIBBON_BINDINGS), ...Object.values(COMPOSE_TAGS), ...EXTRA_RIBBON_TABS.flatMap((t) => t.items), ...Object.values(COMPOSE_EXTRA).flatMap((v) => (Array.isArray(v) ? v : v.items))]);
    const order = ["File", "Home", "Send / Receive", "Folder", "View", "Help", "Message", "Insert", "Options", "Format Text", "Review", "Draw", "Tools", "Macros"];
    const tabs = Commands.tabs().sort((a, b) => (order.indexOf(a) < 0 ? 99 : order.indexOf(a)) - (order.indexOf(b) < 0 ? 99 : order.indexOf(b)));
    this.registerQuickSteps();
    return [
      { id: "popular", label: "Popular Commands", list: () => all().filter((c) => c.popular) },
      { id: "notInRibbon", label: "Commands Not in the Ribbon", list: () => all().filter((c) => !inRibbon.has(c.id)) },
      { id: "all", label: `All Commands (${Commands.count()})`, list: all },
      ...tabs.filter((t) => t !== "Macros").map((t) => ({ id: "tab:" + t, label: t === "File" ? "File Tab (Backstage)" : t === "Tools" ? "Tools Tab (other mail programs and new commands)" : `${t} Tab`, list: () => all().filter((c) => c.tab === t) })),
      { id: "macros", label: "Macros (Quick Steps)", list: () => all().filter((c) => c.tab === "Macros") },
    ];
  },
  cmdList(cmds, selected, onSelect, onDbl) {
    const box = el("div", { class: "qb-cmdlist", role: "listbox", tabindex: "0" });
    cmds.forEach((c, i) => {
      const row = c === "|" ? el("div", { class: "qb-cmdrow sep" + (i === selected ? " sel" : ""), role: "option" }, el("span", { class: "ico-space" }), "<Separator>")
        : el("div", { class: "qb-cmdrow" + (i === selected ? " sel" : ""), role: "option", title: this.tip(c) }, icon(c.icon || "bolt"), el("span", { class: "grow", text: c.label }), c.kind === "menu" ? el("span", { class: "muted", text: "▸" }) : null, c.origin ? el("span", { class: "qb-origin", text: c.origin }) : null);
      row.onclick = () => onSelect(i);
      if (onDbl) row.ondblclick = () => onDbl(i);
      box.appendChild(row);
    });
    box.onkeydown = (e) => { if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); onSelect(Math.max(0, Math.min(cmds.length - 1, selected + (e.key === "ArrowDown" ? 1 : -1)))); } if (e.key === "Enter" && onDbl) onDbl(selected); };
    return box;
  },
  dialog(id, title, cls = "wide") {
    let d = $(id);
    if (!d) {
      d = el("dialog", { id, class: cls + " qb-dialog" }, el("div", { class: "dlg-head" }, el("h3", { class: "qb-dlg-title" }), el("button", { class: "icon-btn", title: "Close", onclick: () => d.close() }, icon("close"))), el("div", { class: "dlg-body" }), el("div", { class: "dlg-foot" }));
      document.body.appendChild(d);
    }
    qs(".qb-dlg-title", d).textContent = title;
    qs(".dlg-body", d).innerHTML = ""; qs(".dlg-foot", d).innerHTML = "";
    return d;
  },
  chooser() {
    const cats = this.categories();
    let cat = cats[0], filter = "", leftSel = 0, rightSel = -1;
    const p0 = this.prefs();
    let items = [...p0.items];
    const d = this.dialog("qbChooser", "Customize the Quick Access Toolbar");
    const body = qs(".dlg-body", d), foot = qs(".dlg-foot", d);
    const left = el("div", { class: "qb-col" }), mid = el("div", { class: "qb-mid" }), right = el("div", { class: "qb-col" });
    const leftItems = () => cat.list().filter((c) => !filter || `${c.label} ${c.id} ${c.keywords || ""} ${c.origin || ""}`.toLowerCase().includes(filter));
    const rightItems = () => items.map((id) => (id === "|" ? "|" : this.cmd(id))).filter(Boolean);
    const catSel = select(cats.map((c) => [c.id, c.label]), cat.id, { "aria-label": "Choose commands from" });
    catSel.onchange = () => { cat = cats.find((c) => c.id === catSel.value) || cats[0]; leftSel = 0; draw(); };
    const search = el("input", { type: "search", placeholder: "Filter commands…", "aria-label": "Filter commands" });
    search.oninput = () => { filter = search.value.toLowerCase(); leftSel = 0; draw(); };
    const count = el("div", { class: "small muted" });
    const add = () => { const c = leftItems()[leftSel]; if (!c) return; const at = rightSel >= 0 ? rightSel + 1 : items.length; items.splice(at, 0, c.id); rightSel = at; draw(); };
    const remove = () => { if (rightSel < 0) return; items.splice(rightSel, 1); rightSel = Math.min(rightSel, items.length - 1); draw(); };
    const move = (dd) => { const r = Qat.moveQatItem(items, rightSel, dd); items = r.items; rightSel = r.index; draw(); };
    const leftHost = el("div", { class: "qb-listhost" }), rightHost = el("div", { class: "qb-listhost" });
    left.append(el("label", { text: "Choose commands from:" }), catSel, search, count, leftHost);
    right.append(el("label", { text: "Quick Access Toolbar:" }), rightHost);
    const draw = () => {
      const li = leftItems();
      count.textContent = plural(li.length, "command");
      leftHost.innerHTML = ""; rightHost.innerHTML = "";
      leftHost.appendChild(this.cmdList(li, leftSel, (i) => { leftSel = i; draw(); }, (i) => { leftSel = i; add(); }));
      rightHost.appendChild(this.cmdList(rightItems(), rightSel, (i) => { rightSel = i; draw(); }, (i) => { rightSel = i; remove(); }));
      const s = leftHost.querySelector(".sel"); s && s.scrollIntoView({ block: "nearest" });
    };
    const b = (label, ic, fn) => el("button", { class: "btn", type: "button", title: label, onclick: fn }, icon(ic), el("span", { class: "qb-hide-phone", text: label }));
    mid.append(b("Add", "chev-right", add), b("Remove", "chev-left", remove), b("Up", "c-up", () => move(-1)), b("Down", "c-down", () => move(1)),
      b("Separator", "minus", () => { const at = rightSel >= 0 ? rightSel + 1 : items.length; items.splice(at, 0, "|"); rightSel = at; draw(); }),
      b("Add All A–Z", "sort", () => { items = Qat.addAllQatChoices(items, (id) => this.cmd(id)?.label); rightSel = -1; draw(); }),
      b("Reset", "undo", () => { items = [...Qat.DEFAULT]; rightSel = -1; draw(); }));
    const pos = select([["top", "Show above the ribbon"], ["below", "Show below the ribbon"], ["bottom", "Show at the bottom (near the keyboard)"]], p0.position);
    const labels = el("input", { type: "checkbox", checked: p0.labels }), hidden = el("input", { type: "checkbox", checked: p0.hidden }), touch = el("input", { type: "checkbox", checked: p0.touch });
    body.append(el("div", { class: "qb-cust" }, left, mid, right),
      el("div", { class: "qb-cust-opts" }, el("label", { class: "opt" }, "Position ", pos), el("label", { class: "check" }, labels, el("span", { text: "Show command labels" })), el("label", { class: "check" }, touch, el("span", { text: "Touch mode (more space between commands)" })), el("label", { class: "check" }, hidden, el("span", { text: "Hide the Quick Access Toolbar" }))));
    foot.append(
      el("button", { class: "btn", onclick: () => MC.saveBlob("quillpost-quick-access.json", JSON.stringify({ qat: { items, position: pos.value, labels: labels.checked, hidden: hidden.checked, touch: touch.checked }, keymap: Commands.keymap, keyScheme: Commands.scheme }, null, 2), "application/json") }, icon("download"), "Export…"),
      el("button", { class: "btn", onclick: async () => { const [f] = await pickFiles({ accept: ".json,application/json" }); if (!f) return; try { const j = JSON.parse(await readFileText(f)); const q = Qat.sanitizeQatPrefs(j.qat || j); items = q.items; pos.value = q.position; labels.checked = q.labels; hidden.checked = q.hidden; touch.checked = q.touch; if (j.keymap && typeof j.keymap === "object") { Commands.keymap = Object.fromEntries(Object.entries(j.keymap).filter(([, v]) => typeof v === "string")); prefs.set("keymap", Commands.keymap); } draw(); toast("Imported — choose OK to keep it."); } catch { toast("That file is not a Quick Access Toolbar layout.", { danger: true }); } } }, icon("upload"), "Import…"),
      el("span", { class: "grow" }),
      el("button", { class: "btn", onclick: () => d.close() }, "Cancel"),
      el("button", { class: "btn primary", onclick: () => { prefs.set("qat", Qat.sanitizeQatPrefs({ items, position: pos.value, labels: labels.checked, hidden: hidden.checked, touch: touch.checked })); d.close(); this.apply(); } }, "OK"));
    draw();
    d.showModal();
  },

  // ================================================================== command search (Alt+Q)
  palette() {
    const d = this.dialog("qbPalette", "Search commands", "qb-palette");
    const body = qs(".dlg-body", d);
    const input = el("input", { type: "search", placeholder: "What do you want to do? (e.g. redirect, tag, digest, unsubscribe)", "aria-label": "Search commands" });
    const results = el("div", { class: "qb-cmdlist qb-palette-list", role: "listbox" });
    let sel = 0, shown = [];
    const draw = () => {
      const q = input.value.trim();
      shown = q ? Commands.search(q, 50) : prefs.get("recentCommands", []).map((id) => this.cmd(id)).filter(Boolean).concat(Commands.all().filter((c) => c.popular).slice(0, 12)).filter((c, i, a) => a.indexOf(c) === i);
      sel = Math.min(sel, Math.max(0, shown.length - 1));
      results.innerHTML = "";
      if (!q) results.appendChild(el("div", { class: "small muted qb-pal-head", text: `Recent and popular — ${Commands.count()} commands in all` }));
      shown.forEach((c, i) => {
        const keys = Commands.keysFor(c.id);
        const pinned = this.prefs().items.includes(c.id);
        const row = el("div", { class: "qb-cmdrow" + (i === sel ? " sel" : "") + (Commands.enabled(c.id, {}) && (c.scope !== "compose" || MC.composing()) ? "" : " qb-disabled") },
          icon(c.icon || "bolt"), el("div", { class: "grow" }, el("div", { text: c.label }), el("div", { class: "small muted", text: `${c.tab}${c.group ? " → " + c.group : ""}${c.origin ? " · from " + c.origin : ""}` })),
          keys.length ? el("kbd", { text: keys[0] }) : null,
          el("button", { class: "icon-btn", title: pinned ? "Remove from Quick Access Toolbar" : "Add to Quick Access Toolbar", onclick: (e) => { e.stopPropagation(); this.update({ items: Qat.toggleQatItem(this.prefs().items, c.id), hidden: false }); draw(); } }, icon(pinned ? "pin-fill" : "pin")));
        row.onclick = () => go(c);
        results.appendChild(row);
      });
      if (q && !shown.length) results.appendChild(el("div", { class: "muted", text: "No command matches." }));
    };
    const go = (c) => { d.close(); setTimeout(() => Commands.run(c.id, { source: "palette" }), 30); };
    input.oninput = () => { sel = 0; draw(); };
    input.onkeydown = (e) => {
      if (e.key === "ArrowDown") { e.preventDefault(); sel = Math.min(shown.length - 1, sel + 1); draw(); qs(".sel", results)?.scrollIntoView({ block: "nearest" }); }
      else if (e.key === "ArrowUp") { e.preventDefault(); sel = Math.max(0, sel - 1); draw(); qs(".sel", results)?.scrollIntoView({ block: "nearest" }); }
      else if (e.key === "Enter") { e.preventDefault(); if (shown[sel]) go(shown[sel]); }
    };
    body.append(input, results);
    draw();
    d.showModal();
    setTimeout(() => input.focus(), 30);
  },

  // ================================================================== keyboard customisation
  keyboard(initialId) {
    const cats = this.categories();
    let cat = cats.find((c) => c.id === "all"), filter = "", sel = 0, pending = null;
    const keymap = { ...Commands.keymap };
    const d = this.dialog("qbKeys", "Customize Keyboard Shortcuts");
    const body = qs(".dlg-body", d), foot = qs(".dlg-foot", d);
    const left = el("div", { class: "qb-col" }), right = el("div", { class: "qb-col" });
    const items = () => cat.list().filter((c) => !filter || `${c.label} ${c.id}`.toLowerCase().includes(filter));
    if (initialId) { const i = items().findIndex((c) => c.id === initialId); if (i >= 0) sel = i; }
    const effective = () => { const save = Commands.keymap; Commands.keymap = keymap; const m = Commands.effectiveMap(); Commands.keymap = save; return m; };
    const keysFor = (id) => { const out = []; for (const [k, ids] of effective()) if (ids.includes(id)) out.push(k); return out; };
    const catSel = select(cats.map((c) => [c.id, c.label]), cat.id); catSel.onchange = () => { cat = cats.find((c) => c.id === catSel.value) || cats[0]; sel = 0; draw(); };
    const search = el("input", { type: "search", placeholder: "Filter commands…" }); search.oninput = () => { filter = search.value.toLowerCase(); sel = 0; draw(); };
    const scheme = select([["default", "Desktop mail (default)"], ["gmail", "Gmail-style"], ["mutt", "Mutt/Pine-style"]], Commands.scheme);
    const capture = el("input", { class: "qb-capture", placeholder: "Press the new shortcut…", readonly: true, "aria-label": "Press the new shortcut" });
    const owner = el("div", { class: "small muted", role: "status" });
    capture.onfocus = () => (this.capturing = true); capture.onblur = () => (this.capturing = false);
    capture.onkeydown = (e) => {
      const k = Commands.keyName(e);
      if (!k || k === "Tab" || k === "Shift+Tab" || k === "Escape") return;
      e.preventDefault(); e.stopPropagation();
      pending = k; capture.value = k;
      const ids = effective().get(k) || [];
      owner.textContent = ids.length ? `Currently: ${ids.map((id) => this.cmd(id)?.label || id).join(", ")}` : "Currently unassigned";
    };
    const draw = () => {
      left.innerHTML = ""; right.innerHTML = "";
      const list2 = items();
      left.append(el("label", { text: "Categories:" }), catSel, search, this.cmdList(list2, sel, (i) => { sel = i; pending = null; capture.value = ""; owner.textContent = ""; draw(); }));
      const c = list2[sel]; if (!c) return;
      const cur = keysFor(c.id);
      right.append(el("h4", { text: c.label }), el("p", { class: "small muted", text: `${c.id}${c.tip ? " — " + c.tip : ""}${c.origin ? " · from " + c.origin : ""}` }),
        el("label", { text: "Current keys:" }),
        el("div", { class: "qb-keys" }, ...(cur.length ? cur.map((k) => el("span", { class: "qb-keychip" }, el("kbd", { text: k }), el("button", { class: "icon-btn", title: "Remove", onclick: () => { keymap[k] = ""; draw(); } }, icon("close", 12)))) : [el("span", { class: "muted", text: "(none)" })])),
        el("label", { text: "Press new shortcut key:" }), capture, owner,
        el("div", { class: "btn-row", style: { marginTop: "8px" } },
          el("button", { class: "btn primary", onclick: () => { if (!pending) return toast("Click the box and press a key combination."); keymap[pending] = c.id; pending = null; capture.value = ""; owner.textContent = ""; draw(); } }, "Assign"),
          el("button", { class: "btn", onclick: () => { for (const k of Object.keys(keymap)) if (keymap[k] === c.id || (keymap[k] === "" && (c.keys || []).includes(k))) delete keymap[k]; draw(); } }, "Reset Command")),
        el("p", { class: "small muted", text: "Single keys (letters, digits) work in the message list; combinations with Ctrl or Alt work everywhere. Some combinations (Ctrl+T, Ctrl+W, Ctrl+N) belong to the browser and cannot be used." }));
    };
    body.append(el("div", { class: "qb-cust qb-cust-keys" }, left, right), el("div", { class: "qb-cust-opts" }, el("label", { class: "opt" }, "Keyboard scheme ", scheme)));
    foot.append(el("button", { class: "btn", onclick: () => { for (const k of Object.keys(keymap)) delete keymap[k]; draw(); } }, "Reset All"), el("span", { class: "grow" }),
      el("button", { class: "btn", onclick: () => d.close() }, "Cancel"),
      el("button", { class: "btn primary", onclick: () => { Commands.keymap = keymap; prefs.set("keymap", keymap); Commands.scheme = scheme.value; prefs.set("keyScheme", scheme.value); d.close(); toast("Keyboard shortcuts saved"); } }, "OK"));
    draw();
    d.showModal();
  },

  // ================================================================== reference, shortcuts, backstage
  reference() {
    const d = this.dialog("qbReference", `Command reference (${Commands.count()} commands)`);
    const body = qs(".dlg-body", d);
    const filter = el("input", { type: "search", placeholder: "Filter by name, tab or origin…" });
    const host = el("div", { class: "qb-ref" });
    const draw = () => {
      const q = filter.value.toLowerCase(); host.innerHTML = "";
      const byTab = {};
      for (const c of Commands.all()) if (!q || `${c.label} ${c.tab} ${c.group} ${c.origin || ""} ${c.id}`.toLowerCase().includes(q)) (byTab[c.tab] = byTab[c.tab] || []).push(c);
      for (const [tab, cmds] of Object.entries(byTab)) {
        host.appendChild(el("h4", { text: `${tab} (${cmds.length})` }));
        host.appendChild(el("table", { class: "table" }, el("tr", {}, el("th", { text: "Command" }), el("th", { text: "Group" }), el("th", { text: "Keys" }), el("th", { text: "Origin" }), el("th")),
          ...cmds.map((c) => el("tr", {}, el("td", {}, icon(c.icon || "bolt", 14), " ", c.label), el("td", { class: "small", text: c.group || "" }), el("td", { class: "small mono", text: Commands.keysFor(c.id).join(", ") }), el("td", { class: "small", text: c.origin || "" }), el("td", {}, el("button", { class: "btn sm", onclick: () => { d.close(); Commands.run(c.id, { source: "reference" }); } }, "Run"))))));
      }
    };
    filter.oninput = draw;
    body.append(filter, host);
    qs(".dlg-foot", d).append(el("button", { class: "btn", onclick: () => MC.saveBlob("COMMANDS.md", commandsMarkdown(), "text/markdown;charset=utf-8") }, icon("download"), "Download as Markdown"), el("span", { class: "grow" }), el("button", { class: "btn primary", onclick: () => d.close() }, "Close"));
    draw();
    d.showModal();
  },
  /** Fills the keyboard shortcuts dialog from the registry (default keys, scheme and your own). */
  shortcuts() {
    const host = qs("#shortcutsDialog .shortcuts");
    if (host) {
      host.innerHTML = "";
      const rows = [];
      for (const [k, ids] of Commands.effectiveMap()) { const c = this.cmd(ids[0]); if (c && !c.internal) rows.push([k, ids.map((id) => this.cmd(id)?.label).filter(Boolean).join(" / ")]); }
      rows.sort((a, b) => a[1].localeCompare(b[1]));
      for (const [k, l] of rows) host.append(el("kbd", { text: k }), el("span", { text: l }));
      const foot = el("div", { class: "btn-row", style: { gridColumn: "1 / -1", marginTop: "10px" } }, el("button", { class: "btn sm", onclick: () => { $("shortcutsDialog").close(); this.keyboard(); } }, icon("keyboard"), "Customize…"), el("span", { class: "small muted", text: `Scheme: ${Commands.scheme}` }));
      host.appendChild(foot);
    }
    $("shortcutsDialog").showModal();
  },
  backstage() {
    const d = this.dialog("qbBackstage", "File", "qb-backstage");
    const body = qs(".dlg-body", d);
    const groups = {};
    for (const c of Commands.all().filter((x) => x.tab === "File")) (groups[c.group || "File"] = groups[c.group || "File"] || []).push(c);
    const nav = el("nav", { class: "qb-bs-nav" }), main = el("div", { class: "qb-bs-main" });
    const show = (g) => {
      qsa("button", nav).forEach((b) => b.classList.toggle("active", b.textContent === g));
      main.innerHTML = "";
      main.append(el("h2", { text: g }));
      if (g === "Info") main.append(el("p", { class: "muted", text: `${state.account?.displayName || ""} <${state.account?.email || ""}> · ${state.account?.protocol || "IMAP"}` }));
      for (const c of groups[g]) main.append(el("button", { class: "qb-bs-item", onclick: () => { d.close(); Commands.run(c.id, { source: "backstage" }); } }, icon(c.icon || "bolt", 28), el("div", {}, el("b", { text: c.label }), c.tip ? el("div", { class: "small muted", text: c.tip }) : null)));
    };
    for (const g of Object.keys(groups)) nav.appendChild(el("button", { onclick: () => show(g) }, g));
    nav.appendChild(el("button", { class: "qb-bs-back", onclick: () => d.close() }, icon("back"), "Back"));
    body.append(el("div", { class: "qb-bs" }, nav, main));
    show(Object.keys(groups)[0]);
    d.showModal();
  },
};

/** The command reference as a Markdown table (also used to generate docs/COMMANDS.md). */
function commandsMarkdown() {
  const rows = Commands.all();
  const tabs = [...new Set(rows.map((c) => c.tab))];
  const cell = (s) => String(s || "").replace(/\|/g, "\\|");
  const out = [`# Anne\'s Post commands`, "", `${rows.length} commands. Origin "Outlook" marks the desktop mail client's own commands; other origins name the mail program a command comes from, and "Anne\'s Post" marks commands original to this client. Every one can be placed on the Quick Access Toolbar (Customize → More Commands…), found with command search (Alt+Q) and given a shortcut (Help → Customize Keyboard Shortcuts).`, ""];
  for (const t of tabs) {
    const list2 = rows.filter((c) => c.tab === t);
    out.push(`## ${t} (${list2.length})`, "", "| Command | Id | Group | Keys | Origin |", "|---|---|---|---|---|");
    for (const c of list2) out.push(`| ${cell(c.label)} | \`${c.id}\` | ${cell(c.group)} | ${cell((c.keys || []).join(", "))} | ${cell(c.origin || (c.tab === "Tools" ? "" : "Outlook"))} |`);
    out.push("");
  }
  return out.join("\n");
}

// Start once the core client has wired itself up (this file loads after app.js).
try { qatUI.init(); } catch (e) { console.error("Quick Access Toolbar failed to start", e); }
