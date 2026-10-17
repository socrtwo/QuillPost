// "Open file" in the real UI: opens the sample files in src/test/resources/fixtures/samples
// (an .eml with an inline image and a tracking pixel, an Outlook .msg with an RTF-only body, an
// .oft template, a winmail.dat, a vCard file, an iCalendar file, a vCalendar 1.0 file, a .docx and
// an Outlook item with no extension) by the file chooser (mail.openFile) and by drag and drop, and
// checks the reader window, Reply, Save to folder, Add to People / Add to calendar, opening an
// attached message, the template draft and "Send as attachment".
//
// Run against the demo server (see README "Run it"; from web/):
//   PORT=8170 DEMO_IMAP_PORT=13170 DEMO_SMTP_PORT=13171 QUILLPOST_DATA_DIR=/tmp/qpOpen ./gradlew demo &
//   node src/test/e2e/openfile.mjs
// Environment: BASE_URL (default http://127.0.0.1:8170/), IMAP_PORT / SMTP_PORT (13170 / 13171),
// CHROMIUM (browser executable), SHOTS=<folder> to save a screenshot of each opened file.
// Exit code 1 when a check fails.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadPlaywright, chromiumPath, demoAccount, signIn, waitSignedIn, checker } from "./common.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const SAMPLES = path.join(here, "..", "resources", "fixtures", "samples");
const BASE = process.env.BASE_URL || "http://127.0.0.1:8170/";
const account = demoAccount(Number(process.env.IMAP_PORT || 13170), Number(process.env.SMTP_PORT || 13171));
const SHOTS = process.env.SHOTS || "";
if (SHOTS) fs.mkdirSync(SHOTS, { recursive: true });

const { chromium } = loadPlaywright();
const browser = await chromium.launch({ executablePath: chromiumPath(), headless: true });
const t = checker("openfile");
try {
  const context = await browser.newContext({ viewport: { width: 1366, height: 900 }, acceptDownloads: true });
  await signIn(context, account);
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(BASE);
  console.log("signing in…");
  await waitSignedIn(page);
  console.log("signed in");
  await page.waitForTimeout(800);

  const shot = async (name) => { if (SHOTS) await page.screenshot({ path: path.join(SHOTS, name + ".png") }); };
  const dialogOpen = () => page.waitForSelector("#fileDialog[open]", { timeout: 15000 });
  const closeDialog = () => page.evaluate(() => document.getElementById("fileDialog")?.close());
  const title = () => page.textContent("#fileTitle");
  const badge = () => page.textContent("#fileFormat");
  const barButtons = () => page.$$eval("#fileBar button", (bs) => bs.map((b) => b.textContent.trim()));
  /** Waits for a toast containing [text]; returns its text or "". */
  const toastText = (text) => page.waitForFunction((x) => [...document.querySelectorAll(".toast")].map((e) => e.textContent).find((s) => s.includes(x)) || "", text, { timeout: 8000 }).then((h) => h.jsonValue()).catch(() => "");

  /** Opens [name] through the File > Open File… command and the browser's file chooser. */
  async function openByChooser(name) {
    const [chooser] = await Promise.all([page.waitForEvent("filechooser"), page.evaluate(() => { Commands.run("mail.openFile"); })]);
    await chooser.setFiles(path.join(SAMPLES, name));
  }
  /** Opens [name] by dropping it on the window. */
  async function openByDrop(name) {
    const b64 = fs.readFileSync(path.join(SAMPLES, name)).toString("base64");
    await page.evaluate(({ name, b64 }) => {
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const dt = new DataTransfer();
      dt.items.add(new File([bytes], name));
      const target = document.querySelector("#workspace") || document.body;
      target.dispatchEvent(new DragEvent("dragenter", { bubbles: true, dataTransfer: dt }));
      target.dispatchEvent(new DragEvent("dragover", { bubbles: true, cancelable: true, dataTransfer: dt }));
      target.dispatchEvent(new DragEvent("drop", { bubbles: true, cancelable: true, dataTransfer: dt }));
    }, { name, b64 });
  }
  const frameText = async () => {
    const h = await page.waitForSelector("#fileDialog iframe.body-frame:not(.hidden)", { timeout: 8000 });
    const f = await h.contentFrame();
    await f.waitForLoadState();
    return { frame: f, text: await f.evaluate(() => document.body.innerText) };
  };

  // ---------------------------------------------------------------- .eml (file chooser)
  await openByChooser("Welcome.eml");
  await dialogOpen();
  t.eq(await title(), "Welcome to QuillPost ✓", "eml: subject decoded from RFC 2047");
  t.ok((await badge()).includes(".eml"), "eml: format badge");
  {
    const { frame, text } = await frameText();
    t.ok(text.includes("Hello from a saved .eml file"), "eml: HTML body shown");
    await page.waitForTimeout(500);
    const imgs = await frame.$$eval("img", (is) => is.map((i) => ({ src: i.getAttribute("src"), blocked: i.getAttribute("data-blocked-src"), w: i.naturalWidth })));
    t.ok(imgs.some((i) => /\/api\/file\/attachment/.test(i.src) && i.w === 32), "eml: inline cid image loads from the opened file");
    t.ok(imgs.some((i) => i.blocked === "https://tracker.example/pixel.gif"), "eml: tracking pixel blocked");
    t.ok(await page.isVisible("#fileDialog .images-banner:not(.hidden)"), "eml: blocked-images banner");
    const chips = await page.$$eval("#fileDialog .attachments .att", (cs) => cs.map((c) => c.textContent));
    t.ok(chips.some((c) => c.includes("notes ✓.txt")), "eml: RFC 2231 attachment name");
    t.ok((await barButtons()).join("|").includes("Reply all"), "eml: Reply all offered");
  }
  await shot("01-eml");
  await closeDialog();

  // ---------------------------------------------------------------- .msg (drop): RTF body, attachments, embedded message
  await openByDrop("Quarterly figures.msg");
  await dialogOpen();
  t.eq(await title(), "Quarterly figures", "msg: subject");
  t.ok((await badge()).includes("Outlook item"), "msg: recognised as an Outlook item");
  {
    const { text } = await frameText();
    t.ok(/quarterly figures/i.test(text) && text.includes("North") && text.includes("1,204"), "msg: RTF body rendered with its table");
    t.ok(await page.isVisible("#fileDialog .badge.danger"), "msg: high importance shown");
    const chips = await page.$$eval("#fileDialog .attachments .att", (cs) => cs.map((c) => c.textContent));
    t.ok(chips.some((c) => c.includes("figures.csv")) && chips.some((c) => c.includes("Earlier thread")), "msg: file and attached message listed");
  }
  await shot("02-msg");
  // Reply opens the composer addressed to the sender.
  await page.click("#fileBar button:has-text('Reply')");
  await page.waitForSelector("#composeDialog[open]", { timeout: 8000 });
  t.eq(await page.inputValue("#cSubject"), "RE: Quarterly figures", "msg: reply subject");
  t.ok((await page.inputValue("#cTo")).includes("alice@example.com"), "msg: reply goes to the sender");
  t.ok((await page.innerText("#cEditor")).includes("North"), "msg: original quoted in the reply");
  await shot("03-msg-reply");
  await page.evaluate(() => compose.close());
  // Open again, then open the attached message and save the original to the Inbox.
  await openByDrop("Quarterly figures.msg");
  await dialogOpen();
  await page.click("#fileDialog .attachments .att:has-text('Earlier thread')");
  await page.waitForFunction(() => document.getElementById("fileTitle").textContent === "Earlier thread", null, { timeout: 8000 }).catch(() => {});
  t.eq(await title(), "Earlier thread", "msg: attached message opens in the reader");
  t.ok(await page.isVisible("#fileHistory"), "msg: opened-files switcher appears");
  await page.selectOption("#fileHistory", { label: "Quarterly figures" });
  await page.click("#fileBar button:has-text('Save to folder')");
  await page.click("#ctxMenu button:has-text('Inbox')");
  t.ok((await toastText("Saved to Inbox")).includes("Saved to Inbox"), "msg: saved to the Inbox");
  const inbox = await page.evaluate(() => api("/api/messages", { query: { folder: "INBOX", limit: 100, rescan: "true" } }));
  t.ok(inbox.messages.some((m) => m.subject === "Quarterly figures" && m.fromAddress === "alice@example.com"), "msg: the saved copy is in the Inbox (converted to MIME)");
  await closeDialog();

  // ---------------------------------------------------------------- winmail.dat
  await openByChooser("winmail.dat");
  await dialogOpen();
  t.eq(await title(), "Budget 2027", "tnef: subject from MAPI properties");
  {
    const { text } = await frameText();
    t.ok(text.includes("budget"), "tnef: RTF body");
    const chips = await page.$$eval("#fileDialog .attachments .att", (cs) => cs.map((c) => c.textContent));
    t.ok(chips.some((c) => c.includes("Budget 2027.csv")), "tnef: attachment with its long file name");
  }
  await shot("04-winmail");
  await closeDialog();

  // ---------------------------------------------------------------- vCard
  await openByDrop("Ada Lovelace.vcf");
  await dialogOpen();
  const rows = await page.$$eval("#fileDialog .qp-file-row", (rs) => rs.map((r) => r.textContent));
  t.ok(rows.length === 2 && rows[0].includes("Ada Lovelace") && rows[0].includes("ada@example.org"), "vcf: both contacts listed");
  await shot("05-vcf");
  await page.click("#fileBar button:has-text('Add 2 contacts to People')");
  t.ok(/Added 2 contacts|already in People/.test(await toastText("People")), "vcf: added to People");
  const contacts = await page.evaluate(() => pim.data.contacts.map((c) => c.displayName || c.firstName + " " + c.lastName));
  t.ok(contacts.includes("Ada Lovelace") && contacts.includes("Charles Babbage"), "vcf: contacts are in People");
  await closeDialog();

  // ---------------------------------------------------------------- iCalendar and vCalendar
  await openByChooser("Design review.ics");
  await dialogOpen();
  t.ok((await page.textContent("#fileDialog .qp-file-row")).includes("Room 4"), "ics: event listed");
  await shot("06-ics");
  await page.click("#fileBar button:has-text('Add to calendar')");
  const calBtn = await page.$("#ctxMenu:not(.hidden) button");
  if (calBtn) await calBtn.click();
  t.ok((await toastText("to your calendar")).includes("to your calendar"), "ics: added to the calendar");
  t.ok(await page.evaluate(() => pim.data.events.some((e) => e.title === "Design review")), "ics: event is in the calendar");
  await closeDialog();
  await openByDrop("Old phone.vcs");
  await dialogOpen();
  t.ok((await page.textContent("#fileDialog .qp-file-row")).includes("Stand-up"), "vcs: vCalendar 1.0 event with quoted-printable title");
  await closeDialog();

  // ---------------------------------------------------------------- .docx
  await openByDrop("Report.docx");
  await dialogOpen();
  t.ok((await badge()).includes("Word document"), "docx: recognised as a Word document");
  const docButtons = (await barButtons()).join("|");
  t.ok(docButtons.includes("Open in QuillPen") && docButtons.includes("Send as attachment"), "docx: Open in QuillPen and Send as attachment offered");
  await shot("07-docx");
  await page.click("#fileBar button:has-text('Send as attachment')");
  await page.waitForSelector("#composeDialog[open]", { timeout: 8000 });
  t.ok((await page.textContent("#cAtts")).includes("Report.docx"), "docx: new message with the file attached");
  await shot("08-docx-send");
  await page.evaluate(() => compose.close());

  // ---------------------------------------------------------------- .oft template and an extension-less Outlook item
  await openByChooser("Weekly status.oft");
  await page.waitForSelector("#composeDialog[open]", { timeout: 8000 });
  t.eq(await page.inputValue("#cSubject"), "Weekly status", "oft: template opens as a new draft");
  t.ok((await page.innerText("#cEditor")).includes("Done:"), "oft: template body in the draft");
  await shot("09-oft");
  await page.evaluate(() => compose.close());
  await openByDrop("no-extension-message");
  await dialogOpen();
  t.eq(await title(), "Quarterly figures", "no extension: recognised by content");
  await closeDialog();

  // ---------------------------------------------------------------- phone width
  await page.setViewportSize({ width: 412, height: 860 });
  await openByDrop("Welcome.eml");
  await dialogOpen();
  const overflow = await page.evaluate(() => { const d = document.getElementById("fileDialog"); return d.scrollWidth - d.clientWidth; });
  t.ok(overflow <= 1, "phone: the reader window does not scroll sideways");
  await shot("10-phone");
  await closeDialog();

  t.eq(errors.join("; "), "", "no page errors");
} finally {
  await browser.close();
}
t.done();
