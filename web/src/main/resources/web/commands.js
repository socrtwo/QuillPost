/* QuillPost — command registry.
 *
 * Every user-facing action is a command: { id, label, icon, tab, group, tip, keys, kind, run,
 * menu, isEnabled, isActive, origin, popular, keywords, scope }. The ribbon, the Quick Access
 * Toolbar, context menus, keyboard shortcuts and the command search all run commands by id, so
 * any command can be placed anywhere. This file has no DOM dependencies at load time; it is also
 * loaded by the Node unit tests (web/src/test/js).
 */
"use strict";
(function (root) {
  const registry = new Map();
  const listeners = new Set();

  /** Keys are written "Ctrl+Shift+R", "Alt+Q", "F9", "Shift+Delete", "J", "?" — see keyName(). */
  const MOD_ORDER = ["Ctrl", "Alt", "Shift"];
  const KEY_ALIASES = { esc: "Escape", del: "Delete", ins: "Insert", up: "ArrowUp", down: "ArrowDown", left: "ArrowLeft", right: "ArrowRight", enter: "Enter", return: "Enter", cmd: "Ctrl", meta: "Ctrl", control: "Ctrl", option: "Alt" };

  /** Normalises a written shortcut ("ctrl+shift+r", "Shift-J") to canonical form. */
  function canonicalKey(s) {
    if (!s) return "";
    const raw = String(s).trim();
    const parts = raw === "+" ? ["+"] : raw.split(/(?<!^)[+-](?!$)/).map((p) => p.trim()).filter(Boolean);
    const mods = new Set();
    let key = "";
    for (const p of parts) {
      const a = KEY_ALIASES[p.toLowerCase()] || p;
      const cap = a.length === 1 ? a : a[0].toUpperCase() + a.slice(1);
      if (["Ctrl", "Alt", "Shift"].includes(cap)) mods.add(cap); else key = a;
    }
    if (key.length === 1) {
      if (/[a-z]/i.test(key)) key = key.toUpperCase();
    } else if (/^f\d{1,2}$/i.test(key)) key = key.toUpperCase();
    else if (key) key = key[0].toUpperCase() + key.slice(1);
    return [...MOD_ORDER.filter((m) => mods.has(m)), key].join("+");
  }

  /** The canonical name of a keyboard event. Shift is part of the name only for letters and named keys. */
  function keyName(e) {
    let k = e.key;
    if (!k || ["Control", "Shift", "Alt", "Meta", "AltGraph", "CapsLock", "Dead", "Unidentified"].includes(k)) return "";
    const named = k.length > 1;
    if (k === " ") k = "Space";
    const letter = k.length === 1 && /[a-z]/i.test(k);
    // With Alt on a Mac (and AltGr layouts) e.key is a composed character: use the physical key.
    if (e.altKey && e.code && /^Key[A-Z]$/.test(e.code)) k = e.code.slice(3);
    else if (e.altKey && e.code && /^Digit\d$/.test(e.code)) k = e.code.slice(5);
    const mods = [];
    if (e.ctrlKey || e.metaKey) mods.push("Ctrl");
    if (e.altKey) mods.push("Alt");
    if (e.shiftKey && (letter || named || mods.length || /^[A-Z]$/.test(k))) mods.push("Shift");
    return canonicalKey([...mods, k.length === 1 ? k.toUpperCase() === k.toLowerCase() ? k : k.toUpperCase() : k].join("+"));
  }

  /** Display form: "Ctrl+Shift+R". */
  function prettyKey(k) { return canonicalKey(k).replace(/^\s$/, "Space"); }

  const Commands = {
    /** Registers one command or an array of commands. Later definitions replace earlier ones. */
    def(c) {
      for (const cmd of Array.isArray(c) ? c : [c]) {
        if (!cmd || !cmd.id || typeof cmd.label !== "string") throw new Error("A command needs an id and a label");
        registry.set(cmd.id, { kind: "button", tab: "Other", group: "", ...cmd, keys: (cmd.keys || []).map(canonicalKey) });
      }
      for (const fn of listeners) { try { fn(); } catch {} }
    },
    get(id) { return registry.get(id); },
    has(id) { return registry.has(id); },
    /** User-visible commands (internal ones are hidden from choosers). */
    all() { return [...registry.values()].filter((c) => !c.internal); },
    count() { return this.all().length; },
    onChange(fn) { listeners.add(fn); },
    label(id) { const c = registry.get(id); return c ? c.label : undefined; },
    tabs() { return [...new Set(this.all().map((c) => c.tab))]; },

    /** Ranks commands for a free-text query (label first, then tab, group, keywords, tip, origin). */
    search(q, limit = 60) {
      const terms = String(q || "").toLowerCase().split(/\s+/).filter(Boolean);
      if (!terms.length) return [];
      const scored = [];
      for (const c of this.all()) {
        const label = c.label.toLowerCase();
        const hay = `${label} ${c.tab} ${c.group || ""} ${c.keywords || ""} ${c.tip || ""} ${c.origin || ""} ${c.id}`.toLowerCase();
        let s = 0, ok = true;
        for (const t of terms) {
          const i = hay.indexOf(t);
          if (i < 0) { ok = false; break; }
          s += label.startsWith(t) ? 10 : label.includes(" " + t) ? 8 : label.includes(t) ? 5 : 1;
        }
        if (ok) scored.push({ c, s });
      }
      scored.sort((a, b) => b.s - a.s || a.c.label.localeCompare(b.c.label));
      return scored.slice(0, limit).map((x) => x.c);
    },

    enabled(id, ctx = {}) {
      const c = registry.get(id); if (!c) return false;
      try { return c.isEnabled ? c.isEnabled(ctx) !== false : true; } catch { return false; }
    },
    active(id, ctx = {}) {
      const c = registry.get(id); if (!c || !c.isActive) return false;
      try { return !!c.isActive(ctx); } catch { return false; }
    },

    /** Hooks set by the UI: error reporting and menu display for menu commands. */
    hooks: { error: (c, e) => console.error(c.id, e), showMenu: null, before: null, after: null },
    last: null,

    /** Runs a command. ctx: { arg, items, anchor, source } — items are the messages it acts on. */
    async run(id, ctx = {}) {
      const c = registry.get(id);
      if (!c) throw new Error("Unknown command " + id);
      if (!this.enabled(id, ctx)) { if (this.hooks.disabled) this.hooks.disabled(c, ctx); return undefined; }
      try {
        if (this.hooks.before) this.hooks.before(c, ctx);
        let r;
        if (c.kind === "menu" && c.menu && !c.run) {
          const items = await c.menu(ctx);
          if (items && this.hooks.showMenu) this.hooks.showMenu(items, ctx, c);
        } else r = await c.run(ctx);
        if (!c.noRepeat) this.last = { id, ctx: { arg: ctx.arg } };
        if (this.hooks.after) this.hooks.after(c, ctx);
        return r;
      } catch (e) { this.hooks.error(c, e); return undefined; }
    },

    // ------------------------------------------------------------------ keyboard
    /** Custom bindings: key → command id ("" removes a default binding). */
    keymap: {},
    /** Alternative default schemes (Gmail, Mutt) layered on top of the command defaults. */
    schemes: {},
    scheme: "default",
    /** key → [command ids]. Several commands may share a key when they work in different places (scopes). */
    effectiveMap() {
      const map = new Map();
      const addTo = (k, id) => { const a = map.get(k) || []; if (!a.includes(id)) a.push(id); map.set(k, a); };
      for (const c of registry.values()) for (const k of c.keys || []) addTo(k, c.id);
      const sch = this.schemes[this.scheme];
      if (sch) for (const [k, id] of Object.entries(sch)) map.set(canonicalKey(k), [id]);
      for (const [k, id] of Object.entries(this.keymap || {})) { const ck = canonicalKey(k); if (id) map.set(ck, [id]); else map.delete(ck); }
      return map;
    },
    /** The commands bound to [k], most specific first (compose, then mail, then global). */
    commandsForKey(k) {
      const order = { compose: 0, mail: 1, global: 2 };
      return [...(this.effectiveMap().get(canonicalKey(k)) || [])].sort((a, b) => (order[(registry.get(a) || {}).scope || "mail"] ?? 1) - (order[(registry.get(b) || {}).scope || "mail"] ?? 1));
    },
    commandForKey(k) { return this.commandsForKey(k)[0] || null; },
    keysFor(id) { const out = []; for (const [k, ids] of this.effectiveMap()) if (ids.includes(id)) out.push(k); return out; },
    /** The commands that currently own [k]. */
    ownersOf(k) { return this.commandsForKey(k); },

    // ------------------------------------------------------------------ helpers shared with tests
    canonicalKey, keyName, prettyKey,
  };

  // ==================================================================== Quick Access Toolbar model
  /**
   * The QAT drop-down checklist for Mail, in the order the desktop mail client lists it: Send/Receive
   * All Folders, Reply, Reply All, Forward, Delete, Undo, Redo, Print and Touch/Mouse Mode. Useful
   * extras follow.
   */
  const QAT_STANDARD = ["mail.sendReceiveAll", "mail.reply", "mail.replyAll", "mail.forward", "mail.delete", "edit.undo", "edit.redo", "file.print", "view.touchMode"];
  const QAT_EXTRAS = ["file.save", "mail.newMail", "mail.archive", "mail.markRead", "mail.flag", "mail.moveTo", "mail.junk", "folder.markAllRead", "mail.updateFolder", "mail.sendAll", "view.readingPane", "help.searchCommands"];
  const QAT_CHOICES = [...QAT_STANDARD, ...QAT_EXTRAS];
  const DEFAULT_QAT = ["mail.sendReceiveAll", "edit.undo"];
  /** Kept first, in this order, by addAllQatChoices. */
  const QAT_PINNED_FIRST = ["file.save", "file.print"];
  const QAT_POSITIONS = ["top", "below", "bottom"];

  /**
   * Adds every drop-down choice to the bar: Save and Print first, then the remaining choices sorted
   * by label (case-insensitive), then anything else already on the bar in its existing order.
   */
  function addAllQatChoices(current, labelOf, choices = QAT_CHOICES) {
    const known = choices.filter((id) => labelOf(id) !== undefined);
    const first = QAT_PINNED_FIRST.filter((id) => known.includes(id));
    const rest = known.filter((id) => !first.includes(id)).sort((a, b) => labelOf(a).localeCompare(labelOf(b), undefined, { sensitivity: "base" }));
    const head = [...first, ...rest];
    return [...head, ...(current || []).filter((id) => id === "|" || !head.includes(id))];
  }

  /** Keeps only QAT preferences of the expected shape (stored, or imported from a file). */
  function sanitizeQatPrefs(raw) {
    const d = { items: [...DEFAULT_QAT], position: "top", labels: false, hidden: false, touch: false };
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return d;
    const out = { ...d };
    if (Array.isArray(raw.items)) out.items = raw.items.filter((x) => typeof x === "string" && x.length < 80).slice(0, 80);
    if (QAT_POSITIONS.includes(raw.position)) out.position = raw.position;
    for (const k of ["labels", "hidden", "touch"]) if (typeof raw[k] === "boolean") out[k] = raw[k];
    return out;
  }

  /** Toggles [id] in the bar (the drop-down checklist behaviour). */
  function toggleQatItem(items, id) { return items.includes(id) ? items.filter((x) => x !== id) : [...items, id]; }

  /** Moves the entry at [i] by [d] (−1 up, +1 down); returns the new list and index. */
  function moveQatItem(items, i, d) {
    const j = i + d;
    if (i < 0 || j < 0 || i >= items.length || j >= items.length) return { items: [...items], index: i };
    const next = [...items]; [next[i], next[j]] = [next[j], next[i]];
    return { items: next, index: j };
  }

  const Qat = { STANDARD: QAT_STANDARD, EXTRAS: QAT_EXTRAS, CHOICES: QAT_CHOICES, DEFAULT: DEFAULT_QAT, PINNED_FIRST: QAT_PINNED_FIRST, POSITIONS: QAT_POSITIONS, addAllQatChoices, sanitizeQatPrefs, toggleQatItem, moveQatItem };

  const api = { Commands, Qat };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else { root.Commands = Commands; root.Qat = Qat; }
})(typeof window !== "undefined" ? window : globalThis);
