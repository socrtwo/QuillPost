// Browser test of the QuillPen embedded editor (web/src/main/resources/web/embed.js) against a fake
// QuillPen (web/src/test/quillpen-fake/index.html) that implements QuillPen's side of the protocol.
//
// Needs two demo servers (in-memory mailbox, see README "Run it"): one acting as the desktop app
// (system property quillpost.desktop=true) and one as the plain web server, each with its own
// IMAP/SMTP ports and data folder. From the web/ folder:
//
//   ./gradlew testClasses
//   # desktop-mode server on 18090 (IMAP 13143, SMTP 13025) and web-mode server on 18091 (13144, 13026):
//   PORT=18090 DEMO_IMAP_PORT=13143 DEMO_SMTP_PORT=13025 QUILLBOX_DATA_DIR=/tmp/qbA \
//     JAVA_TOOL_OPTIONS=-Dquillpost.desktop=true ./gradlew demo &
//   PORT=18091 DEMO_IMAP_PORT=13144 DEMO_SMTP_PORT=13026 QUILLBOX_DATA_DIR=/tmp/qbB ./gradlew demo &
//   node src/test/e2e/embed.mjs
//
// Environment: DESKTOP_URL (default http://127.0.0.1:18090/), WEB_URL (http://127.0.0.1:18091/),
// DESKTOP_IMAP/DESKTOP_SMTP, WEB_IMAP/WEB_SMTP (ports above), CHROMIUM (browser executable).
// The script itself serves the fake QuillPen for the web-deployment and Android cases.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { loadPlaywright, chromiumPath, demoAccount, signIn, waitSignedIn, checker } from "./common.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const FAKE_DIR = path.resolve(here, "../quillpen-fake");
const FAKE_HTML = fs.readFileSync(path.join(FAKE_DIR, "index.html"), "utf8");
const DESKTOP_URL = process.env.DESKTOP_URL || "http://127.0.0.1:18090/";
const WEB_URL = process.env.WEB_URL || "http://127.0.0.1:18091/";
const desktopAccount = demoAccount(Number(process.env.DESKTOP_IMAP || 13143), Number(process.env.DESKTOP_SMTP || 13025));
const webAccount = demoAccount(Number(process.env.WEB_IMAP || 13144), Number(process.env.WEB_SMTP || 13026));

// Variants of the fake: one that never answers, one that tries to finish the edit at once.
const SILENT_HTML = "<!doctype html><title>silent</title><p>QuillPen that never says ready</p>";
const EVIL_HTML = `<!doctype html><title>evil</title><script>
  parent.postMessage({ type: "quill-embed:ready", v: 1, app: "QuillPen" }, "*");
  parent.postMessage({ type: "quill-embed:done", v: 1, html: "<b>EVIL</b>", text: "EVIL" }, "*");
</script>`;

const { chromium } = loadPlaywright();
const t = checker("embed");

function serveFake() {
  const server = http.createServer((req, res) => {
    const p = new URL(req.url, "http://x").pathname;
    const body = p.startsWith("/silent/") ? SILENT_HTML : p.startsWith("/evil/") ? EVIL_HTML : p === "/" || p === "/index.html" ? FAKE_HTML : null;
    if (body == null) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }); res.end(body);
  });
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server)));
}

const editorHtml = (page) => page.evaluate(() => document.getElementById("cEditor").innerHTML);
const penOpen = (page) => page.evaluate(() => !!document.querySelector("#composeDialog .qp-pen-frame"));
async function openCompose(page, html = "<p>Hello <b>world</b></p>") {
  await page.evaluate((html) => compose.open({ mode: "new", to: "demo@quillbox.test", subject: "Embed test", html }), html);
  await page.waitForSelector("#composeDialog[open]");
}
async function waitReady(page) {
  await page.waitForSelector("#composeDialog .qp-pen-host.ready", { timeout: 10000 });
}
const pen = (page) => page.frameLocator("#composeDialog .qp-pen-frame");

async function flow(page, label) {
  // Ask mode: the offer and the button are shown.
  await openCompose(page);
  await page.waitForSelector("#cQuillPen:not(.hidden)", { timeout: 10000 });
  t.ok(await page.isVisible("#cQuillPenOffer"), `${label}: "Ask" shows the offer in a new message`);
  await page.click("#cQuillPenOffer .btn.primary");
  await waitReady(page);
  t.ok(await penOpen(page), `${label}: iframe open`);
  t.ok(!(await page.isVisible("#cEditor")), `${label}: built-in editor hidden while QuillPen is open`);
  const loaded = await pen(page).locator("#doc").innerHTML();
  t.ok(/Hello <b>world<\/b>/.test(loaded), `${label}: body loaded into QuillPen (${loaded.slice(0, 60)})`);
  const meta = await pen(page).locator("#meta").textContent();
  t.ok(/Embed test/.test(meta), `${label}: subject sent with load`);
  const sandbox = await page.getAttribute("#composeDialog .qp-pen-frame", "sandbox");
  t.eq(sandbox, "allow-scripts allow-same-origin allow-popups allow-modals allow-downloads", `${label}: sandbox`);
  const src = await page.getAttribute("#composeDialog .qp-pen-frame", "src");
  t.ok(/index\.html\?embed=compose&host=quillpost&v=1$/.test(src), `${label}: iframe URL ${src}`);

  // A message that does not come from the iframe is ignored.
  await page.evaluate(() => window.postMessage({ type: "quill-embed:done", v: 1, html: "<b>forged</b>", text: "forged" }, "*"));
  await page.waitForTimeout(300);
  t.ok(await penOpen(page), `${label}: forged "done" from another window ignored`);
  t.ok(!/forged/.test(await editorHtml(page)), `${label}: forged HTML not applied`);

  // Typing → "changed" → the compose body follows (drafts, minimise).
  await pen(page).locator("#doc").click();
  await page.keyboard.press("End");
  await page.keyboard.type(" typed-in-pen");
  await page.waitForFunction(() => /typed-in-pen/.test(document.getElementById("cEditor").innerHTML), null, { timeout: 5000 }).catch(() => {});
  t.ok(/typed-in-pen/.test(await editorHtml(page)), `${label}: changes mirrored into the compose body`);
  t.ok(await page.evaluate(() => state.compose.dirty), `${label}: compose marked dirty`);

  // Done → body replaced, editor closed. Unsafe markup from the editor is cleaned.
  await pen(page).locator("#doc").evaluate((d) => { d.innerHTML += '<img src="x" onerror="window.__pwned=1"><script>window.__pwned=2<\/script><a href="javascript:alert(1)">l</a> done-text'; });
  await pen(page).locator("#done").click();
  await page.waitForFunction(() => !document.querySelector("#composeDialog .qp-pen-frame"));
  const afterDone = await editorHtml(page);
  t.ok(/done-text/.test(afterDone) && /typed-in-pen/.test(afterDone), `${label}: Done puts QuillPen's HTML into the body`);
  t.ok(!/onerror|<script|javascript:/i.test(afterDone), `${label}: event handlers, scripts and javascript: links removed`);
  t.ok(await page.isVisible("#cEditor"), `${label}: built-in editor back after Done`);
  t.ok(!(await page.evaluate(() => window.__pwned)), `${label}: nothing from the editor ran in QuillPost`);

  // Cancel → the previous body is kept.
  const before = await editorHtml(page);
  await page.click("#cQuillPen");
  await waitReady(page);
  await pen(page).locator("#doc").click();
  await page.keyboard.type(" thrown-away");
  await page.waitForTimeout(900);
  await pen(page).locator("#cancel").click();
  await page.waitForFunction(() => !document.querySelector("#composeDialog .qp-pen-frame"));
  t.eq(await editorHtml(page), before, `${label}: Cancel keeps the old body`);

  // Send while open: the content is requested first (typed just before pressing Send).
  await page.click("#cQuillPen");
  await waitReady(page);
  await pen(page).locator("#doc").click();
  await page.keyboard.type(" SENT-MARKER");
  const sendReq = page.waitForRequest((r) => r.url().includes("/api/send") && r.method() === "POST", { timeout: 10000 }).catch(() => null);
  // Transient toasts (e.g. the notifications offer) sit over the window's bottom edge; clear them.
  await page.evaluate(() => document.querySelectorAll("#toasts .toast").forEach((x) => x.remove()));
  await page.click("#cSend");
  const req = await sendReq;
  const body = req ? JSON.parse(req.postData() || "{}") : {};
  t.ok(/SENT-MARKER/.test(body.html || ""), `${label}: Send requests the content first (html has the last keystrokes)`);
  t.ok(/SENT-MARKER/.test(body.body || ""), `${label}: plain-text part made from QuillPen's content`);
  await page.waitForFunction(() => !state.compose, null, { timeout: 10000 }).catch(() => {});
  t.ok(!(await penOpen(page)), `${label}: editor gone after sending`);
  // Cancel the undo-send hold so the demo mailbox is not changed.
  await page.locator(".toast button", { hasText: "Undo" }).click().catch(() => {});
  await page.evaluate(() => compose.close(true)).catch(() => {});
}

async function settingsRow(page) {
  await page.evaluate(() => settingsUI.open("compose"));
  await page.waitForSelector("#settingsDialog[open]");
  await page.waitForTimeout(600);
  const hasMode = await page.locator("#stBody .setting-row", { hasText: "Edit messages in QuillPen" }).count();
  return hasMode;
}

async function main() {
  const browser = await chromium.launch({ executablePath: chromiumPath() });
  const fake = await serveFake();
  const fakePort = fake.address().port;
  const fakeBase = `http://127.0.0.1:${fakePort}/`;
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "qp-embed-"));
  const silentDir = path.join(tmp, "silent"); fs.mkdirSync(silentDir); fs.writeFileSync(path.join(silentDir, "index.html"), SILENT_HTML);
  const errors = [];
  const watch = (page, label) => page.on("pageerror", (e) => errors.push(`${label}: ${e.message}`));

  try {
    // ---------------------------------------------------------------- desktop (local server)
    {
      const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } });
      await signIn(ctx, desktopAccount, { quillpenMode: "ask", undoSend: 10 });
      const page = await ctx.newPage(); watch(page, "desktop");
      page.on("dialog", (d) => { console.log("  dialog: " + d.message()); d.dismiss(); });
      page.on("console", (m) => { if (m.type() === "error") console.log("  console: " + m.text()); });
      await page.goto(DESKTOP_URL);
      await waitSignedIn(page);
      // Not installed: no button, no mode row, but the folder field.
      await page.evaluate(() => api("/api/quillpen/folder", { method: "POST", body: { folder: null } }));
      await page.evaluate(() => quillPen.detect(true));
      t.eq(await settingsRow(page), 0, "desktop: mode hidden while QuillPen is not found");
      t.ok(await page.locator("#stBody .setting-row", { hasText: "QuillPen folder" }).count() === 1, "desktop: folder field shown");
      await page.fill("#stBody .qp-pen-field input", "/etc");
      await page.click("#stBody .qp-pen-field .btn >> text=Use folder");
      await page.waitForTimeout(400);
      t.ok(/No QuillPen editor/.test(await page.textContent("#stBody .qp-pen-status")), "desktop: a folder without index.html is refused");
      await page.fill("#stBody .qp-pen-field input", FAKE_DIR);
      await page.click("#stBody .qp-pen-field .btn >> text=Use folder");
      await page.waitForSelector("#stBody .setting-row >> text=Edit messages in QuillPen", { timeout: 5000 });
      t.ok(true, "desktop: choosing the folder detects QuillPen");
      await page.evaluate(() => $("settingsDialog").close());
      const info = await page.evaluate(() => quillPen.detect());
      t.eq(info.base, new URL("/quillpen-embed/", DESKTOP_URL).toString(), "desktop: served from /quillpen-embed/");
      await flow(page, "desktop");

      // Timeout: a QuillPen that never answers → error and built-in editor after 8 s.
      await page.evaluate((f) => api("/api/quillpen/folder", { method: "POST", body: { folder: f } }), silentDir);
      await page.evaluate(() => quillPen.detect(true));
      await openCompose(page, "<p>keep me</p>");
      const t0 = Date.now();
      await page.evaluate(() => openInQuillPen());
      t.ok(await penOpen(page), "desktop: silent editor iframe created");
      await page.waitForFunction(() => !document.querySelector("#composeDialog .qp-pen-frame"), null, { timeout: 12000 }).catch(() => {});
      const waited = Date.now() - t0;
      t.ok(waited >= 7500 && waited < 11000, `desktop: falls back after ~8 s (${waited} ms)`);
      t.ok(await page.locator(".toast", { hasText: "QuillPen did not start" }).count() > 0, "desktop: timeout error shown");
      t.ok(/keep me/.test(await editorHtml(page)) && (await page.isVisible("#cEditor")), "desktop: built-in editor with the body after the timeout");
      await page.evaluate(() => compose.close(true));

      // Always / Off.
      await page.evaluate((f) => api("/api/quillpen/folder", { method: "POST", body: { folder: f } }), FAKE_DIR);
      await page.evaluate(() => { prefs.set("quillpenMode", "always"); return quillPen.detect(true); });
      await openCompose(page);
      await waitReady(page).catch(() => {});
      t.ok(await penOpen(page), "desktop: Always opens QuillPen straight away");
      // Moving the window (dock ⇄ pop out) reloads the frame; the body comes back.
      await pen(page).locator("#doc").click(); await page.keyboard.type(" moved-text"); await page.waitForTimeout(1200);
      await page.evaluate(() => compose.popOut());
      await waitReady(page).catch(() => {});
      t.ok(/moved-text/.test(await pen(page).locator("#doc").innerHTML()), "desktop: content survives moving the compose window");
      await page.evaluate(() => compose.close(true));
      await page.evaluate(() => { prefs.set("quillpenMode", "off"); });
      await openCompose(page);
      await page.waitForTimeout(500);
      t.ok(!(await page.isVisible("#cQuillPen")) && !(await penOpen(page)) && !(await page.isVisible("#cQuillPenOffer")), "desktop: Off hides the button and the offer");
      t.ok(await page.evaluate(() => openInQuillPen()), "desktop: openInQuillPen() still works as a command when Off");
      await waitReady(page);
      await page.evaluate(() => compose.close(true));
      await ctx.close();
    }

    // ---------------------------------------------------------------- web (a QuillPen deployment URL)
    {
      const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
      await signIn(ctx, webAccount, { quillpenMode: "ask" });
      const page = await ctx.newPage(); watch(page, "web");
      await page.goto(WEB_URL);
      await waitSignedIn(page);
      t.eq(await settingsRow(page), 0, "web: mode hidden until a URL is set");
      t.ok(await page.locator("#stBody .setting-row", { hasText: "QuillPen web address" }).count() === 1, "web: URL field shown");
      await page.fill("#stBody .qp-pen-field input", fakeBase + "index.html");
      await page.click("#stBody .qp-pen-field .btn >> text=Save");
      await page.waitForSelector("#stBody .setting-row >> text=Edit messages in QuillPen", { timeout: 5000 });
      t.eq(await page.evaluate(() => prefs.get("quillpenUrl")), fakeBase, "web: URL stored without index.html");
      await page.evaluate(() => $("settingsDialog").close());
      await flow(page, "web");

      // A page from another origin inside the same iframe cannot finish the edit.
      await openCompose(page, "<p>original</p>");
      await page.click("#cQuillPen"); await waitReady(page);
      await page.evaluate((u) => { document.querySelector(".qp-pen-frame").src = u; }, `http://localhost:${fakePort}/evil/index.html`);
      await page.waitForTimeout(800);
      t.ok(!/EVIL/.test(await editorHtml(page)) && (await penOpen(page)), "web: messages from another origin are ignored");
      await page.evaluate(() => compose.close(true));
      await ctx.close();
    }

    // ---------------------------------------------------------------- Android shell (simulated)
    {
      const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, isMobile: true, hasTouch: true });
      await signIn(ctx, webAccount, { quillpenMode: "ask" });
      await ctx.addInitScript(() => { window.QuillboxAndroid = { platform: () => "android", version: () => "test", openExternal() {}, print() {}, toast() {}, quillPenAvailable: () => true }; });
      // What MainActivity.shouldInterceptRequest does with QuillPen's ContentProvider.
      await ctx.route("https://quillpen.embed/**", (route) => {
        const p = new URL(route.request().url()).pathname;
        if (p === "/" || p === "/index.html") route.fulfill({ status: 200, contentType: "text/html", body: FAKE_HTML });
        else route.fulfill({ status: 404, body: "" });
      });
      const page = await ctx.newPage(); watch(page, "android");
      await page.goto(WEB_URL);
      await waitSignedIn(page);
      t.eq(await settingsRow(page), 1, "android: mode shown when QuillPen is installed");
      t.eq(await page.locator("#stBody .qp-pen-field").count(), 0, "android: no folder/URL field");
      await page.evaluate(() => $("settingsDialog").close());
      await openCompose(page);
      await page.click("#cQuillPen"); await waitReady(page);
      const box = await page.locator("#composeDialog .qp-pen-frame").boundingBox();
      t.ok(box && box.height > 300 && box.width >= 400, `android: editor fills the compose body (${box && Math.round(box.width)}x${box && Math.round(box.height)})`);
      await pen(page).locator("#done").click();
      await page.waitForFunction(() => !document.querySelector("#composeDialog .qp-pen-frame"));
      t.ok(true, "android: Done closes the editor");
      await page.evaluate(() => compose.close(true));
      await ctx.close();
    }

    // ---------------------------------------------------------------- iOS shell (simulated)
    {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, userAgent: "Mozilla/5.0 (iPhone) AppleWebKit/605.1.15 QuillboxApp/1.4.0 (iOS)" });
      await signIn(ctx, webAccount, { quillpenMode: "always", quillpenUrl: fakeBase });
      const page = await ctx.newPage(); watch(page, "ios");
      await page.goto(WEB_URL);
      await waitSignedIn(page);
      await openCompose(page);
      await page.waitForTimeout(700);
      t.ok(!(await page.isVisible("#cQuillPen")) && !(await penOpen(page)), "ios: no QuillPen button, never opened");
      t.eq(await settingsRow(page), 0, "ios: option hidden in settings");
      t.eq(await page.locator("#stBody .qp-pen-settings h4").count(), 0, "ios: no QuillPen section");
      await ctx.close();
    }
  } finally {
    t.ok(errors.length === 0, "no page errors: " + errors.join(" | "));
    await browser.close();
    fake.close();
    fs.rmSync(tmp, { recursive: true, force: true });
    t.done();
  }
}

main().catch((e) => { console.error(e); process.exitCode = 1; });
