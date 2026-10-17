// Opening mail files and importing mailboxes in the browser: File ▸ Open File… (a single .eml opens in
// the reading pane from Local Archives ▸ Opened files), File ▸ Import Mailbox… into Local Archives (an
// mbox) and into the signed-in IMAP account (an Outlook .pst), a folder upload (an Outlook Express
// store), drag-and-drop, and the hand-off of a word-processor file.
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
for (const id of ["mail.importMailbox", "mail.openMessageFile", "mail.localArchives"]) t.ok(await page.evaluate((i) => Commands.has(i) && Commands.all().some((c) => c.id === i), id), `command ${id} registered`);

// 1. File ▸ Open File… with a single .eml: it opens in the reading pane.
await pickWith(() => page.evaluate(() => { Commands.run("mail.openMessageFile"); }), path.join(FIX, "note.eml"));
await page.waitForFunction("state.openDetail && state.openDetail.subject === 'Opened from a file'", null, { timeout: 15000 });
t.ok(await page.evaluate(() => state.folder === "@archive/opened/files"), "opened message lives in Local Archives ▸ Opened files");
t.ok(await page.locator("#folderTree .tree-head", { hasText: "Local Archives" }).count() === 1, "Local Archives section in the folder pane");
await shot("1-opened-eml");

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
await page.fill("#qbImport input[aria-label='New folder name']", "From Outlook");
await shot("6-import-dialog-pst");
await page.click("#qbImport .dlg-foot button:has-text('Import')");
await page.waitForFunction("document.querySelector('#qbImport .qb-dlg-title')?.textContent === 'Import finished'", null, { timeout: 30000 });
await shot("7-import-finished-pst");
await page.click("#qbImport button:has-text('Close')");
await page.waitForFunction("state.folders.some((f) => /From Outlook.Inbox$/.test(f.path))", null, { timeout: 15000 });
const inboxPath = await page.evaluate(() => state.folders.find((f) => /From Outlook.Inbox$/.test(f.path)).path);
const imported = await page.evaluate(async (p) => (await api("/api/messages", { query: { folder: p, limit: 50 } })).messages, inboxPath);
t.eq(imported.length, 2, "PST Inbox copied to IMAP");
const lunch = imported.find((m) => m.subject === "Lunch menu") || {};
t.ok(lunch.read && lunch.flagged && lunch.answered, "IMAP flags from the PST: " + JSON.stringify(lunch));
t.ok(await page.evaluate(() => state.folders.some((f) => /From Outlook.Inbox.Projects$/.test(f.path))), "sub-folder hierarchy kept on IMAP");
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

// 5. Dropping a file on the window opens it.
await page.evaluate(async () => {
  const dt = new DataTransfer();
  dt.items.add(new File(["From: Zed <zed@example.com>\r\nTo: you@example.org\r\nSubject: Dropped message\r\nDate: Thu, 24 Sep 2026 09:12:00 +0000\r\n\r\nDropped on the window.\r\n"], "dropped.eml", { type: "message/rfc822" }));
  window.dispatchEvent(new DragEvent("dragover", { dataTransfer: dt, bubbles: true, cancelable: true }));
  document.querySelector("#messageList").dispatchEvent(new DragEvent("drop", { dataTransfer: dt, bubbles: true, cancelable: true }));
});
await page.waitForFunction("state.openDetail && state.openDetail.subject === 'Dropped message'", null, { timeout: 15000 });
t.ok(true, "dropped .eml opened");

// 6. A word-processor file is recognised and handed off to QuillPen (or another app), not imported.
const doc = fs.readFileSync(path.join(FIX, "letter.doc")).toString("base64");
await page.evaluate((b64) => {
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  mailImport.open([{ file: new File([bytes], "letter.dat"), path: "letter.dat" }]);
}, doc);
await page.waitForFunction("document.querySelector('#qbImport[open] .dlg-body')?.textContent.includes('Word 97')", null, { timeout: 15000 });
t.ok((await page.textContent("#qbImport .dlg-body")).includes("QuillPen"), "Word file handed to QuillPen");
t.ok(await page.locator("#qbImport button:has-text('Attach to new email')").count() === 1, "offer to attach the document");
await shot("10-word-handoff");
await page.click("#qbImport .dlg-foot button:has-text('Close')");

// 7. Local Archives command and phone width.
await page.evaluate(() => Commands.run("mail.localArchives"));
await page.waitForFunction("state.folder.startsWith('@archive/')");
await page.setViewportSize({ width: 412, height: 915 });
await page.evaluate(() => Commands.run("mail.importMailbox"));
await page.waitForSelector("#qbImportSource[open]");
const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
t.ok(!overflow, "no sideways scrolling on a phone");
await shot("11-phone-import-source");

await browser.close();
t.done();
