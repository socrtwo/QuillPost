/* "Open file" UI pieces (fileopen.js). Run with: node --test web/src/test/js */
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const WEB = path.join(__dirname, "..", "..", "main", "resources", "web");
const { Commands } = require(path.join(WEB, "commands.js"));
globalThis.Commands = Commands;
const { FileOpenLogic: L, FILE_COMMANDS } = require(path.join(WEB, "fileopen.js"));
const html = fs.readFileSync(path.join(WEB, "index.html"), "utf8");
const sprite = new Set([...html.matchAll(/<symbol id="i-([a-z0-9-]+)"/g)].map((m) => m[1]));

test("the two commands are registered with known icons on the File tab", () => {
  for (const id of ["mail.openFile", "mail.sendFileAsAttachment"]) {
    const c = Commands.get(id);
    assert.ok(c, id);
    assert.equal(c.tab, "File");
    assert.equal(c.group, "Open & Export");
    assert.ok(sprite.has(c.icon), `${id}: icon ${c.icon}`);
    assert.equal(typeof c.run, "function");
    assert.match(c.id, /^[a-z]+\.[A-Za-z0-9]+$/);
  }
  assert.equal(FILE_COMMANDS.length, 2);
  assert.ok(Commands.search("winmail").some((c) => c.id === "mail.openFile"), "found by format name");
  assert.ok(Commands.search("docx").some((c) => c.id === "mail.openFile"));
});

test("index.html loads the module after the command files, with its stylesheet", () => {
  const order = ["commands.js", "mailcommands.js", "mailextra.js", "fileopen.js"].map((f) => html.indexOf(`<script src="${f}">`));
  assert.ok(order.every((i) => i > 0), "all scripts present");
  assert.deepEqual([...order].sort((a, b) => a - b), order, "fileopen.js after the command files");
  assert.ok(html.includes('href="fileopen.css"'));
  for (const i of [...new Set(Object.values({ message: "mail", contacts: "people", calendar: "calendar", document: "note", spreadsheet: "table", presentation: "pane", mailbox: "inbox", other: "attach" }))]) assert.ok(sprite.has(i), i);
});

test("each kind of file gets the right suite program", () => {
  assert.equal(L.suiteApp("document").label, "Anne's Pen");
  assert.equal(L.suiteApp("spreadsheet").pkg, "io.github.socrtwo.quillgrid");
  assert.equal(L.suiteApp("presentation").pkg, "io.github.socrtwo.quillstage");
  assert.equal(L.suiteApp("message"), null);
  for (const k of ["message", "contacts", "calendar", "document", "spreadsheet", "presentation", "mailbox", "other", "nonsense"]) assert.ok(sprite.has(L.iconFor(k)), k);
});

test("Open in Anne's Pen / Anne's Grid / Anne's Stage: Android intent, desktop handler or download", () => {
  const doc = { kind: "spreadsheet", nativeOpen: false };
  assert.equal(L.openPlan(doc, { android: true }).method, "android");
  assert.equal(L.openPlan({ ...doc, nativeOpen: true }, {}).method, "desktop");
  assert.equal(L.openPlan(doc, {}).method, "web");
  assert.equal(L.openPlan(doc, {}).label, "Open in Anne's Grid");
  assert.equal(L.openPlan({ kind: "message" }, {}), null);
});

test("actions offered per kind", () => {
  const msg = { kind: "message", message: { template: false }, events: [], contacts: [] };
  assert.deepEqual(L.actions(msg, {}), ["reply", "replyAll", "forward", "saveToFolder", "downloadEml", "headers", "sendAsAttachment"]);
  assert.deepEqual(L.actions(msg, { signedIn: false }), ["downloadEml", "headers", "sendAsAttachment"]);
  assert.ok(!L.actions(msg, { canSave: false }).includes("saveToFolder"), "POP3 accounts cannot save to folders");
  assert.equal(L.actions({ ...msg, message: { template: true } }, {})[0], "useTemplate");
  assert.ok(L.actions({ ...msg, events: [{}] }, {}).includes("addToCalendar"), "meeting invitation");
  assert.deepEqual(L.actions({ kind: "document" }, {}), ["openInApp", "sendAsAttachment", "download"]);
  assert.deepEqual(L.actions({ kind: "contacts", contacts: [{}] }, {}), ["addToPeople", "sendAsAttachment", "download"]);
  assert.deepEqual(L.actions({ kind: "calendar", events: [] }, {}), ["sendAsAttachment", "download"]);
  assert.deepEqual(L.actions({ kind: "mailbox" }, {}), ["importMailbox", "sendAsAttachment", "download"]);
  assert.deepEqual(L.actions({ kind: "other" }, {}), ["sendAsAttachment", "download"]);
  assert.deepEqual(L.actions(null), []);
});

test("cid references become data URLs for replies and forwards", () => {
  const map = { "logo@x": "data:image/png;base64,AAA" };
  assert.equal(L.inlineCids('<img src="cid:logo@x"><div style="background:url(cid:logo@x)">', map), '<img src="data:image/png;base64,AAA"><div style="background:url(data:image/png;base64,AAA)">');
  assert.equal(L.inlineCids('<img src="cid:other">', map), '<img src="cid:other">', "unknown cid left alone");
  assert.equal(L.inlineCids(null, map), null);
});

test("event and contact summaries", () => {
  assert.equal(L.eventLine({ start: "2026-11-03T10:00", end: "2026-11-03T11:30", location: "Room 4" }), "2026-11-03 10:00 – 11:30 · Room 4");
  assert.equal(L.eventLine({ allDay: true, start: "2026-12-24", end: "2026-12-26" }), "2026-12-24 – 2026-12-26 (all day)");
  const c = L.contactLine({ firstName: "Ada", lastName: "Lovelace", emails: [{ value: "ada@example.org" }], phones: [], company: "AE", jobTitle: "Programmer" });
  assert.equal(c.name, "Ada Lovelace");
  assert.equal(c.detail, "ada@example.org · Programmer, AE");
  assert.equal(L.contactLine({ emails: [{ value: "x@y.z" }], phones: [] }).name, "x@y.z");
  assert.equal(L.people(["A <a@x>", "", "b@x"]), "A <a@x>; b@x");
});

test("only file drops are taken, not dragged messages", () => {
  const f = { name: "a.eml" };
  assert.deepEqual(L.droppedFiles({ types: ["Files"], files: [f] }), [f]);
  assert.deepEqual(L.droppedFiles({ types: ["Files", "application/x-quillpost"], files: [f] }), []);
  assert.deepEqual(L.droppedFiles({ types: ["text/plain"], files: [] }), []);
  assert.deepEqual(L.droppedFiles(null), []);
});
