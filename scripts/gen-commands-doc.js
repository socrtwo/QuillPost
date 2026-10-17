#!/usr/bin/env node
/* Regenerates docs/COMMANDS.md from the command registry: node scripts/gen-commands-doc.js */
"use strict";
const fs = require("fs");
const path = require("path");
const { load } = require(path.join(__dirname, "..", "web", "src", "test", "js", "load.js"));
const L = load();
const out = path.join(__dirname, "..", "docs", "COMMANDS.md");
const intro = fs.existsSync(out) ? fs.readFileSync(out, "utf8").split("<!-- generated -->")[0] : "";
fs.writeFileSync(out, (intro || "") + "<!-- generated -->\n" + L.commandsMarkdown().replace(/^# Quillbox commands\n\n/, "## Command table\n\n").replace(/^## /gm, "### ").replace(/^### Command table/m, "## Command table") + "\n");
console.log(`Wrote ${out} (${L.Commands.count()} commands)`);
