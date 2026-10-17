/* Loads the command files the way the browser does (classic scripts sharing one global scope),
 * without a DOM. Used by the unit tests and by scripts/gen-commands-doc.js. */
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const WEB = path.join(__dirname, "..", "..", "main", "resources", "web");

function load() {
  const { Commands, Qat } = require(path.join(WEB, "commands.js"));
  const MailTools = require(path.join(WEB, "mailtools.js"));
  const duplicates = [];
  const def = Commands.def.bind(Commands);
  Commands.def = (c) => { for (const x of Array.isArray(c) ? c : [c]) if (Commands.get(x.id)) duplicates.push(x.id); def(c); };
  Object.assign(globalThis, { Commands, Qat, MailTools });
  for (const f of ["mailcommands.js", "mailextra.js", "fileopen.js", "mailimport.js"]) vm.runInThisContext(fs.readFileSync(path.join(WEB, f), "utf8"), { filename: f });
  const qat = fs.readFileSync(path.join(WEB, "qat.js"), "utf8");
  const extraIcons = [...qat.matchAll(/^\s+"(c-[a-z0-9-]+)":/gm)].map((m) => m[1]);
  const html = fs.readFileSync(path.join(WEB, "index.html"), "utf8");
  const spriteIcons = [...html.matchAll(/<symbol id="i-([a-z0-9-]+)"/g)].map((m) => m[1]);
  // Build commandsMarkdown() from qat.js without running its UI start-up.
  const md = qat.slice(qat.indexOf("function commandsMarkdown"), qat.indexOf("// Start once the core client"));
  const commandsMarkdown = vm.runInThisContext(`(${md.replace(/^function commandsMarkdown/, "function")})`);
  return { Commands, Qat, MailTools, duplicates, icons: new Set([...spriteIcons, ...extraIcons]), commandsMarkdown, WEB, html,
    sources: Object.fromEntries(["mailcommands.js", "mailextra.js", "qat.js"].map((f) => [f, fs.readFileSync(path.join(WEB, f), "utf8")])),
    globals: { RIBBON_BINDINGS: vm.runInThisContext("RIBBON_BINDINGS"), READING_BINDINGS: vm.runInThisContext("READING_BINDINGS"), COMPOSE_TAGS: vm.runInThisContext("COMPOSE_TAGS"), EXTRA_RIBBON_TABS: vm.runInThisContext("EXTRA_RIBBON_TABS"), COMPOSE_EXTRA: vm.runInThisContext("COMPOSE_EXTRA") } };
}
module.exports = { load };
