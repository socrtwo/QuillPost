/* Run with: node --test web/src/test/js */
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { load } = require("./load.js");
const L = load();
const { Commands, Qat } = L;

test("every command is well formed and unique", () => {
  assert.deepEqual(L.duplicates, [], "duplicate ids");
  assert.ok(Commands.count() >= 300, `only ${Commands.count()} commands`);
  for (const c of Commands.all()) {
    assert.match(c.id, /^[a-z]+\.[A-Za-z0-9]+$/, c.id);
    assert.ok(c.label && c.tab, c.id);
    assert.ok(L.icons.has(c.icon), `${c.id}: unknown icon ${c.icon}`);
    assert.ok(typeof c.run === "function" || (c.kind === "menu" && typeof c.menu === "function"), `${c.id} has nothing to run`);
    for (const k of c.keys) assert.equal(Commands.canonicalKey(k), k, `${c.id}: key ${k} not canonical`);
    assert.ok(!c.scope || ["mail", "compose", "global"].includes(c.scope), c.id);
  }
});

test("every referenced command exists", () => {
  const refs = new Set();
  for (const s of Object.values(L.sources)) for (const m of s.matchAll(/Commands\.(?:run|get)\("([^"]+)"/g)) refs.add(m[1]);
  const g = L.globals;
  for (const map of [g.RIBBON_BINDINGS, g.READING_BINDINGS, g.COMPOSE_TAGS]) Object.values(map).forEach((v) => refs.add(v));
  g.EXTRA_RIBBON_TABS.forEach((t) => t.items.forEach((i) => i !== "|" && refs.add(i)));
  Object.values(g.COMPOSE_EXTRA).forEach((v) => (Array.isArray(v) ? v : v.items).forEach((i) => refs.add(i)));
  Qat.CHOICES.forEach((i) => refs.add(i));
  for (const s of Object.values(Commands.schemes)) Object.values(s).forEach((i) => refs.add(i));
  assert.deepEqual([...refs].filter((r) => !Commands.get(r)), []);
});

test("the ribbon button ids exist in index.html", () => {
  for (const id of [...Object.keys(L.globals.RIBBON_BINDINGS), ...Object.keys(L.globals.READING_BINDINGS), ...Object.keys(L.globals.COMPOSE_TAGS)]) assert.ok(L.html.includes(`id="${id}"`), id);
});

test("no two commands share a key in the same place", () => {
  for (const [k, ids] of Commands.effectiveMap()) {
    const seen = {};
    for (const id of ids) { const s = Commands.get(id).scope || "mail"; assert.ok(!seen[s], `${k}: ${seen[s]} and ${id} (${s})`); seen[s] = id; }
  }
});

test("the QAT drop-down starts with the desktop client's standard choices", () => {
  assert.deepEqual(Qat.STANDARD.map((id) => Commands.label(id)), ["Send/Receive All Folders", "Reply", "Reply All", "Forward", "Delete", "Undo", "Redo", "Print", "Touch/Mouse Mode"]);
  assert.deepEqual(Qat.DEFAULT, ["mail.sendReceiveAll", "edit.undo"]);
  for (const id of Qat.CHOICES) assert.ok(Commands.get(id), id);
});

test("Add All Choices puts Save and Print first, then the rest A–Z, then the user's own", () => {
  const out = Qat.addAllQatChoices(["x.mine", "|", "mail.delete"], (id) => Commands.label(id));
  assert.deepEqual(out.slice(0, 2), ["file.save", "file.print"]);
  const rest = out.slice(2, Qat.CHOICES.length).map((id) => Commands.label(id));
  assert.deepEqual(rest, [...rest].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" })));
  assert.equal(out.length, Qat.CHOICES.length + 2);
  assert.deepEqual(out.slice(-2), ["x.mine", "|"]);
  assert.equal(out.filter((x) => x === "mail.delete").length, 1);
  // Unknown choices are skipped.
  assert.deepEqual(Qat.addAllQatChoices([], (id) => ({ "file.print": "Print", "mail.reply": "Reply" })[id]), ["file.print", "mail.reply"]);
});

test("QAT prefs are sanitised, toggled and reordered", () => {
  assert.deepEqual(Qat.sanitizeQatPrefs(null).items, Qat.DEFAULT);
  const p = Qat.sanitizeQatPrefs({ items: ["a", 3, "b"], position: "sideways", labels: true, hidden: "yes" });
  assert.deepEqual(p, { items: ["a", "b"], position: "top", labels: true, hidden: false, touch: false });
  assert.equal(Qat.sanitizeQatPrefs({ position: "bottom" }).position, "bottom");
  assert.deepEqual(Qat.toggleQatItem(["a"], "b"), ["a", "b"]);
  assert.deepEqual(Qat.toggleQatItem(["a", "b"], "a"), ["b"]);
  assert.deepEqual(Qat.moveQatItem(["a", "b", "c"], 2, -1), { items: ["a", "c", "b"], index: 1 });
  assert.deepEqual(Qat.moveQatItem(["a", "b"], 0, -1), { items: ["a", "b"], index: 0 });
});

test("registry: run, enabled, active, menus, errors", async () => {
  const calls = [];
  Commands.def([
    { id: "t.one", label: "One", tab: "Test", icon: "bolt", run: (ctx) => calls.push(["one", ctx.arg]) },
    { id: "t.off", label: "Off", tab: "Test", icon: "bolt", isEnabled: () => false, run: () => calls.push(["off"]) },
    { id: "t.menu", label: "Menu", tab: "Test", icon: "bolt", kind: "menu", menu: () => [{ label: "x" }] },
    { id: "t.bad", label: "Bad", tab: "Test", icon: "bolt", run: () => { throw new Error("boom"); } },
    { id: "t.tog", label: "Tog", tab: "Test", icon: "bolt", kind: "toggle", isActive: () => true, run: () => {} },
  ]);
  const shown = [], errs = [], disabled = [];
  const saved = { ...Commands.hooks };
  Object.assign(Commands.hooks, { showMenu: (items) => shown.push(items), error: (c, e) => errs.push(c.id + ":" + e.message), disabled: (c) => disabled.push(c.id), after: null, before: null });
  await Commands.run("t.one", { arg: 5 });
  await Commands.run("t.off");
  await Commands.run("t.menu");
  await Commands.run("t.bad");
  Object.assign(Commands.hooks, saved);
  assert.deepEqual(calls, [["one", 5]]);
  assert.deepEqual(disabled, ["t.off"]);
  assert.equal(shown.length, 1);
  assert.deepEqual(errs, ["t.bad:boom"]);
  assert.equal(Commands.active("t.tog"), true);
  assert.equal(Commands.enabled("t.off"), false);
  await assert.rejects(() => Commands.run("t.nope"));
});

test("search ranks label matches first", () => {
  assert.equal(Commands.search("redirect")[0].id, "eudora.redirect");
  assert.equal(Commands.search("send receive all")[0].id, "mail.sendReceiveAll");
  assert.ok(Commands.search("eudora").length >= 10);
  assert.deepEqual(Commands.search(""), []);
});

test("keys: names, schemes and custom bindings", () => {
  const K = (o) => Commands.keyName(o);
  assert.equal(K({ key: "j" }), "J");
  assert.equal(K({ key: "J", shiftKey: true }), "Shift+J");
  assert.equal(K({ key: "?", shiftKey: true }), "?");
  assert.equal(K({ key: "R", ctrlKey: true, shiftKey: true }), "Ctrl+Shift+R");
  assert.equal(K({ key: "r", metaKey: true }), "Ctrl+R");
  assert.equal(K({ key: "œ", altKey: true, code: "KeyQ" }), "Alt+Q");
  assert.equal(K({ key: "Delete", shiftKey: true }), "Shift+Delete");
  assert.equal(K({ key: "Shift", shiftKey: true }), "");
  assert.equal(Commands.canonicalKey("ctrl+shift+r"), "Ctrl+Shift+R");
  assert.equal(Commands.canonicalKey("f9"), "F9");
  assert.equal(Commands.canonicalKey("Cmd+K"), "Ctrl+K");
  assert.deepEqual(Commands.commandsForKey("F9"), ["mail.sendReceiveAll"]);
  assert.deepEqual(Commands.commandsForKey("Ctrl+K"), ["compose.link", "help.searchCommands"]);
  assert.equal(Commands.commandForKey("J"), "mail.junk");
  Commands.scheme = "mutt";
  assert.equal(Commands.commandForKey("J"), "mail.nextItem");
  assert.equal(Commands.commandForKey("|"), "pine.pipe");
  Commands.scheme = "gmail";
  assert.equal(Commands.commandForKey("E"), "mail.archive");
  Commands.scheme = "default";
  Commands.keymap = { "Ctrl+Shift+Y": "eudora.redirect", F9: "" };
  assert.equal(Commands.commandForKey("Ctrl+Shift+Y"), "eudora.redirect");
  assert.equal(Commands.commandForKey("F9"), null);
  assert.ok(Commands.keysFor("eudora.redirect").includes("Ctrl+Shift+Y"));
  Commands.keymap = {};
});

test("commands cover Outlook's tabs, other mail programs and new ideas", () => {
  const tabs = new Set(Commands.all().map((c) => c.tab));
  for (const t of ["File", "Home", "Send / Receive", "Folder", "View", "Help", "Message", "Insert", "Options", "Format Text", "Review", "Draw", "Tools"]) assert.ok(tabs.has(t), t);
  const origins = Commands.all().map((c) => c.origin || "").join(" ");
  for (const p of ["Eudora", "Pine", "mutt", "Pegasus", "Lotus Notes", "Thunderbird", "Outlook Express", "Gmail", "Apple Mail", "Superhuman", "Spark", "Hey", "Claris Emailer", "Opera M2", "Anne\'s Post"]) assert.ok(origins.includes(p), p);
  assert.ok(Commands.all().filter((c) => c.origin === "Anne\'s Post").length >= 8);
});

test("the generated command reference lists every command", () => {
  const md = L.commandsMarkdown();
  for (const c of Commands.all()) assert.ok(md.includes("`" + c.id + "`"), c.id);
});
