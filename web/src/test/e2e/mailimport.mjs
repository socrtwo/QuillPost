// Importing mailboxes in the browser: a mailbox given to File ▸ Open File… (mail.openFile) is routed to
// the Import Mailbox dialog while a single message still opens in the message window; File ▸ Import
// Mailbox… into Local Archives (an mbox) and into the signed-in IMAP account (an Outlook .pst), a
// folder upload (an Outlook Express store), a dropped message, and a word-processor file handed back
// to the Open File window.
//
// Run against the demo server (from web/):
//   QUILLPOST_FIXTURE_OUT=/tmp/qp-fixtures ./gradlew test --tests '*FixtureExport*'
//   PORT=8160 DEMO_IMAP_PORT=13160 DEMO_SMTP_PORT=13161 QUILLPOST_DATA_DIR=/tmp/qpA ./gradlew demo &
//   FIXTURES=/tmp/qp-fixtures node src/test/e2e/mailimport.mjs
// Environment: BASE_URL (default http://127.0.0.1:8160/), IMAP_PORT / SMTP_PORT (13160 / 13161),
// FIXTURES (folder written by FixtureExport), CHROMIUM, SHOTS=<folder> for screenshots.
import fs from "node:fs";
import path from "node:path";
import { loadPlaywright, chromiumPath, demoAccount, signIn, waitSignedIn, checker } from "./common.mjs";

const BASE = process.env.BASE_URL || "http://127.0.0.1:8160/";
const FIX = process.env.FIXTURES || "/tmp/qp-fixtures";
const SHOTS = process.env.SHOTS || "";
const account = demoAccount(Number(process.env.IMAP_PORT || 13160), Number(process.env.SMTP_PORT || 13161));
const t = checker("mailimport");

const { chromium } = loadPlaywright();
const browser = await chromium.launch({ executablePath: chromiumPath() });
const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
await signIn(context, account);
const page = await context.newPage();
page.on("pageerror", (e) => t.ok(false, "page error: " + e.message));
await page.goto(BASE);
await waitSignedIn(page);
await page.waitForFunction("typeof mailImport !== 'undefined' && Commands.has('mail.importMailbox')");
const shot = async (name) => { console.log("step " + name); if (SHOTS) { fs.mkdirSync(SHOTS, { recursive: true }); await page.screenshot({ path: path.join(SHOTS, name + ".png") }); } };
const pickWith = async (trigger, files) => {
  const [chooser] = await Promise.all([page.waitForEvent("filechooser"), trigger()]);
  await chooser.setFiles(files);
};

// The commands exist and can go on the Quick Access Toolbar.
for (const id of ["mail.importMailbox", "mail.openFile", "mail.localArchives"]) t.ok(await page.evaluate((i) => Commands.has(i) && Commands.all().some((c) => c.id === i), id), `command ${id} registered`);

// 1. File ▸ Open File…: a single .eml opens in the message window; a mailbox goes to Import Mailbox.
await pickWith(() => page.evaluate(() => { Commands.run("mail.openFile"); }), path.join(FIX, "note.eml"));
await page.waitForSelector("#fileDialog[open]", { timeout: 15000 });
t.ok((await page.textContent("#fileDialog")).includes("Opened from a file"), "message opened in the Open File window");
await shot("1-opened-eml");
await page.evaluate(() => document.getElementById("fileDialog")?.close());
await pickWith(() => page.evaluate(() => { Commands.run("mail.openFile"); }), path.join(FIX, "takeout.mbox"));
await page.waitForSelector("#qbImport[open] .qb-import-tree", { timeout: 20000 });
t.ok((await page.textContent("#qbImport .qb-import-head")).includes("Gmail"), "Open File routes a mailbox to Import Mailbox");
t.ok(!(await page.isVisible("#fileDialog[open]")), "no Open File window for a mailbox");
await shot("1b-openfile-mailbox");
await page.click("#qbImport .dlg-foot button:has-text('Cancel')");

// 2. Import Mailbox… an mbox into Local Archives.
await page.evaluate(() => Commands.run("mail.importMailbox"));
await page.waitForSelector("#qbImportSource[open]");
await shot("2-import-source");
await pickWith(() => page.click("#qbImportSource button:has-text('Choose file')"), path.join(FIX, "Thunderbird Inbox"));
await page.waitForSelector("#qbImport[open] .qb-import-tree", { timeout: 20000 });
t.ok((await page.textContent("#qbImport .qb-import-head")).includes("Thunderbird"), "detected Thunderbird mbox");
await shot("3-import-dialog-mbox");
await page.check("#qbImport input[value=archive]");
await page.click("#qbImport .dlg-foot button:has-text('Import')");
await page.waitForFunction("document.querySelector('#qbImport .qb-dlg-title')?.textContent === 'Import finished'", null, { timeout: 30000 });
await shot("4-import-finished-mbox");
await page.click("#qbImport button:has-text('Show imported mail')");
await page.waitForFunction("state.folder.startsWith('@archive/') && state.messages.length === 2", null, { timeout: 15000 });
const archived = await page.evaluate(() => state.messages.map((m) => ({ s: m.subject, read: m.read, flagged: m.flagged })));
t.ok(archived.some((m) => m.s === "Mozilla read flagged" && m.read && m.flagged), "flags kept in Local Archives: " + JSON.stringify(archived));
const hits = await page.evaluate(async () => (await api("/api/search", { query: { folder: state.folder, q: "Hello from Mozilla forwarded" } })).messages.length);
t.eq(hits, 1, "body search in a Local Archives folder");
t.ok(await page.locator("#folderTree .tree-head", { hasText: "Local Archives" }).count() === 1, "Local Archives section in the folder pane");
await page.click(".msg >> nth=0");
await page.waitForFunction("state.openDetail && state.openDetail.folder.startsWith('@archive/')");
await shot("5-archive-folder");

// 3. Import an Outlook .pst into the IMAP account under a new folder.
await page.evaluate(() => Commands.run("mail.importMailbox"));
await page.waitForSelector("#qbImportSource[open]");
await pickWith(() => page.click("#qbImportSource button:has-text('Choose file')"), path.join(FIX, "fixture.pst"));
await page.waitForSelector("#qbImport[open] .qb-import-tree", { timeout: 20000 });
t.ok((await page.textContent("#qbImport .qb-import-head")).includes("Outlook data file"), "detected PST");
t.eq(await page.locator("#qbImport .qb-import-row").count(), 6, "PST folder tree rows");
await page.check("#qbImport input[value=imap]");
const DEST = "From Outlook " + Date.now().toString(36);
await page.fill("#qbImport input[aria-label='New folder name']", DEST);
await shot("6-import-dialog-pst");
await page.click("#qbImport .dlg-foot button:has-text('Import')");
await page.waitForFunction("document.querySelector('#qbImport .qb-dlg-title')?.textContent === 'Import finished'", null, { timeout: 30000 });
await shot("7-import-finished-pst");
await page.click("#qbImport button:has-text('Close')");
await page.waitForFunction((d) => state.folders.some((f) => f.path.startsWith(d) && /.Inbox$/.test(f.path)), DEST, { timeout: 15000 });
const inboxPath = await page.evaluate((d) => state.folders.find((f) => f.path.startsWith(d) && /.Inbox$/.test(f.path)).path, DEST);
const imported = await page.evaluate(async (p) => (await api("/api/messages", { query: { folder: p, limit: 50 } })).messages, inboxPath);
t.eq(imported.length, 2, "PST Inbox copied to IMAP");
const lunch = imported.find((m) => m.subject === "Lunch menu") || {};
t.ok(lunch.read && lunch.flagged && lunch.answered, "IMAP flags from the PST: " + JSON.stringify(lunch));
t.ok(await page.evaluate((d) => state.folders.some((f) => f.path.startsWith(d) && /.Inbox.Projects$/.test(f.path)), DEST), "sub-folder hierarchy kept on IMAP");
await page.evaluate((p) => list.load(p), inboxPath);
await page.waitForFunction("state.messages.length === 2");
await page.evaluate(() => reading.open(state.messages.find((m) => m.subject === "Quarterly figures")));
await page.waitForFunction("state.openDetail && state.openDetail.subject === 'Quarterly figures'");
await page.waitForTimeout(600);
t.ok(await page.evaluate(() => state.openDetail.attachments.some((a) => a.fileName === "figures.csv")), "attachment survived the import");
await shot("8-pst-on-imap");
const pimOk = await page.evaluate(async () => { const d = await api("/api/pim"); return d.contacts.some((c) => (c.emails || []).some((e) => e.value === "frank@example.com")) && d.events.some((e) => e.title === "Team sync") && d.tasks.some((x) => x.title === "File expenses"); });
t.ok(pimOk, "PST contacts, calendar and tasks went to People, Calendar and To Do");

// 4. A folder upload (Outlook Express store) via drag-and-drop with relative paths.
const oeFiles = fs.readdirSync(path.join(FIX, "OE Store")).map((n) => ({ name: n, data: fs.readFileSync(path.join(FIX, "OE Store", n)).toString("base64") }));
await page.evaluate(async (files) => {
  const entries = files.map((f) => ({ file: new File([Uint8Array.from(atob(f.data), (c) => c.charCodeAt(0))], f.name), path: "OE Store/" + f.name }));
  mailImport.open(entries, { mailboxOnly: true });
}, oeFiles);
await page.waitForSelector("#qbImport[open] .qb-import-tree", { timeout: 20000 });
const oeRows = await page.locator("#qbImport .qb-import-row").allTextContents();
t.ok(oeRows.some((r) => r.includes("Clients")) && oeRows.some((r) => r.includes("Sent Items")), "Outlook Express folder tree: " + JSON.stringify(oeRows));
await shot("9-import-dialog-oe");
await page.click("#qbImport .dlg-foot button:has-text('Cancel')");

// 5. Dropping a message on the window opens it in the Open File window (fileopen.js owns file drops).
await page.evaluate(async () => {
  const dt = new DataTransfer();
  dt.items.add(new File(["From: Zed <zed@example.com>\r\nTo: you@example.org\r\nSubject: Dropped message\r\nDate: Thu, 24 Sep 2026 09:12:00 +0000\r\n\r\nDropped on the window.\r\n"], "dropped.eml", { type: "message/rfc822" }));
  const target = document.querySelector("#messageList");
  for (const type of ["dragenter", "dragover", "drop"]) target.dispatchEvent(new DragEvent(type, { dataTransfer: dt, bubbles: true, cancelable: true }));
});
await page.waitForFunction("document.querySelector('#fileDialog[open]')?.textContent.includes('Dropped message')", null, { timeout: 15000 });
t.ok(true, "dropped .eml opened");
await page.evaluate(() => document.getElementById("fileDialog")?.close());

// 6. A word-processor file given to Import Mailbox is handed back to the Open File window (Anne's Pen).
const doc = fs.readFileSync(path.join(FIX, "letter.doc")).toString("base64");
await page.evaluate((b64) => {
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  mailImport.open([{ file: new File([bytes], "letter.dat"), path: "letter.dat" }]);
}, doc);
await page.waitForSelector("#fileDialog[open]", { timeout: 15000 });
t.ok((await page.textContent("#fileDialog")).includes("Anne's Pen"), "Word file handed to Anne's Pen");
await shot("10-word-handoff");
await page.evaluate(() => document.getElementById("fileDialog")?.close());

// 7. Local Archives command and phone width.
await page.evaluate(() => Commands.run("mail.localArchives"));
await page.waitForFunction("state.folder.startsWith('@archive/')");
await page.setViewportSize({ width: 412, height: 915 });
await page.evaluate(() => Commands.run("mail.importMailbox"));
await page.waitForSelector("#qbImportSource[open]");
const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
t.ok(!overflow, "no sideways scrolling on a phone");
await shot("12-phone-import-source");

await browser.close();
t.done();
