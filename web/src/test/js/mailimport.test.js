"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { load } = require("./load.js");

const L = load();

test("mail import commands are registered for the File tab, the QAT and command search", () => {
  for (const id of ["mail.openMessageFile", "mail.importMailbox", "mail.localArchives"]) {
    const c = L.Commands.get(id);
    assert.ok(c, id);
    assert.ok(L.icons.has(c.icon), `${id}: icon ${c.icon}`);
    assert.ok(L.Commands.all().some((x) => x.id === id), `${id} is user-visible`);
  }
  assert.strictEqual(L.Commands.get("mail.importMailbox").group, "Open & Export");
  assert.ok(L.Commands.search("pst").some((c) => c.id === "mail.importMailbox"));
  assert.ok(L.Commands.search("eml").some((c) => c.id === "mail.openMessageFile"));
  assert.deepStrictEqual(L.duplicates, []);
});
