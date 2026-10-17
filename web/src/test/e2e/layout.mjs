// Layout audit: opens every dialog, menu, pane and overlay of the web client — and every command in
// the command registry that opens something — on emulated phones and desktop windows, and checks
// that each control is inside the safe part of the screen (clear of the status bar, navigation bar,
// display cut-outs and the on-screen keyboard), that no control is covered by another one or cut
// off where it cannot be scrolled to, that text is not squeezed into a sliver, and that nothing
// scrolls sideways.
//
// Two ways the app meets the system bars are covered on phones:
//   shell  the Android and iOS apps pad the WebView natively (MainActivity / ContentView), so the
//          page gets a viewport that excludes the bars: emulated by a smaller viewport;
//   cover  a browser or home-screen web app with viewport-fit=cover draws under the bars and the
//          page must keep clear of env(safe-area-inset-*): emulated by setting the --sa-top,
//          --sa-right, --sa-bottom and --sa-left variables that styles.css reads (Chromium cannot
//          fake env() itself).
// The keyboard is emulated by shrinking the viewport (what the Android app and Chrome with
// interactive-widget=resizes-content do). Desktop windows (1280x860, 1500x900) run as "desktop".
//
// Needs the demo server (see README "Run it"; from web/), on any free ports:
//   PORT=8121 DEMO_IMAP_PORT=13144 DEMO_SMTP_PORT=13026 QUILLPOST_DATA_DIR=/tmp/qbB ./gradlew demo &
//   node src/test/e2e/layout.mjs
// (embed.mjs runs a second, desktop-mode server; this audit needs only the web-mode one. The script
// serves the fake QuillPen of src/test/quillpen-fake itself for the embedded-editor states.)
// Set QUILLPOST_WEB_DIR=src/main/resources/web on the server to audit edits without restarting it.
// A full run (every profile, every command) takes a while: run profiles in parallel with DEVICE.
// Environment: BASE_URL (default http://127.0.0.1:8121/), IMAP_PORT / SMTP_PORT (13144 / 13026),
// CHROMIUM (browser executable), SHOTS=<folder> to save a screenshot of every state, ONLY=<text>
// to run only the states whose name contains the text (or ONLY=/regex), MODES=shell,cover,desktop,
// DEVICE=<name prefix: 412, 360, 915, 1280 or 1500>, CMDS=0 to leave out the command sweep (or
// CMDS=<regex> to sweep only matching command ids), VERBOSE=1 to print each state as it opens.
// Exit code 1 when a defect is found.
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadPlaywright, chromiumPath, demoAccount, signIn, waitSignedIn, checker } from "./common.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE_URL || "http://127.0.0.1:8121/";
const account = demoAccount(Number(process.env.IMAP_PORT || 13144), Number(process.env.SMTP_PORT || 13026));
const SHOTS = process.env.SHOTS || "";
const ONLY = process.env.ONLY || "";
const CMDS = process.env.CMDS ?? "1";
const SAMPLES = path.join(here, "..", "resources", "fixtures", "samples");
const FAKE_PEN = fs.readFileSync(path.join(here, "..", "quillpen-fake", "index.html"), "utf8");
if (SHOTS) fs.mkdirSync(SHOTS, { recursive: true });

// Phones: the screen, the system bars (status, navigation, cut-out) and the keyboard height.
const PHONES = [
  { name: "412x915", w: 412, h: 915, bars: { top: 24, bottom: 48, left: 0, right: 0 }, kb: 330 },
  { name: "360x640", w: 360, h: 640, bars: { top: 24, bottom: 48, left: 0, right: 0 }, kb: 260 },
  { name: "915x412-landscape", w: 915, h: 412, bars: { top: 24, bottom: 0, left: 48, right: 48 }, kb: 190 },
];
// Desktop windows: no system bars inside the window, no on-screen keyboard.
const DESKTOPS = [
  { name: "1280x860", w: 1280, h: 860, bars: { top: 0, bottom: 0, left: 0, right: 0 }, desktop: true },
  { name: "1500x900", w: 1500, h: 900, bars: { top: 0, bottom: 0, left: 0, right: 0 }, desktop: true },
];

const { chromium } = loadPlaywright();
const t = checker("layout");

// ------------------------------------------------------------------ in-page audit
function audit({ safe, ignoreToasts, screenW }) {
  const issues = [];
  const vw = innerWidth, vh = innerHeight;
  const describe = (el) => {
    if (!el) return "?";
    const id = el.id ? "#" + el.id : "";
    const cls = typeof el.className === "string" && el.className.trim() ? "." + el.className.trim().split(/\s+/).slice(0, 2).join(".") : "";
    const txt = (el.getAttribute("aria-label") || el.title || el.textContent || el.value || el.placeholder || "").trim().replace(/\s+/g, " ").slice(0, 28);
    return `${el.tagName.toLowerCase()}${id}${cls}${txt ? ` "${txt}"` : ""}`;
  };
  const shown = (el) => {
    if (!el.isConnected || !el.getClientRects().length) return false;
    // Content of a closed <details> keeps a box (content-visibility) but is not drawn.
    if (el.checkVisibility && !el.checkVisibility({ contentVisibilityAuto: true })) return false;
    for (let n = el, prev = null; n; prev = n, n = n.parentElement) if (n.tagName === "DETAILS" && !n.open && !(prev && prev.tagName === "SUMMARY" && prev.parentElement === n)) return false;
    for (let n = el; n; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.visibility === "hidden" || cs.display === "none" || Number(cs.opacity) === 0) return false;
    }
    return true;
  };
  // Rect of el clipped by every ancestor that clips overflow (not by the window: a control that
  // runs off the window without a scroll container around it cannot be reached at all). `cut` is
  // set when a clipping ancestor that cannot scroll (overflow hidden / clip) hides the control.
  const clipped = (el) => {
    const r = el.getBoundingClientRect();
    let top = r.top, bottom = r.bottom, left = r.left, right = r.right, cut = false, scrolls = false;
    for (let n = el.parentElement; n && n !== document.documentElement; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.overflowX !== "visible" || cs.overflowY !== "visible") {
        const p = n.getBoundingClientRect();
        if (n !== document.body) {
          // Only the nearest box that hides the control matters: a scrolling one can bring it back.
          const outY = r.bottom <= p.top + 1 || r.top >= p.bottom - 1, outX = r.right <= p.left + 1 || r.left >= p.right - 1;
          if (!scrolls && !cut && p.height > 4 && p.width > 4) {
            if (cs.overflowY !== "visible" && outY) { if (/hidden|clip/.test(cs.overflowY)) cut = n; else scrolls = true; }
            else if (cs.overflowX !== "visible" && outX) { if (/hidden|clip/.test(cs.overflowX)) cut = n; else scrolls = true; }
          }
          if (cs.overflowY !== "visible") { top = Math.max(top, p.top); bottom = Math.min(bottom, p.bottom); }
          if (cs.overflowX !== "visible") { left = Math.max(left, p.left); right = Math.min(right, p.right); }
        }
      }
      if (cs.position === "fixed") break;
    }
    return { top, bottom, left, right, w: right - left, h: bottom - top, cut };
  };
  const outside = (r) => r.top < safe.top - 0.5 || r.left < safe.left - 0.5 || r.bottom > safe.bottom + 0.5 || r.right > safe.right + 0.5;
  const fmt = (r) => `[${Math.round(r.left)},${Math.round(r.top)} ${Math.round(r.w ?? r.width)}x${Math.round(r.h ?? r.height)}]`;

  const se = document.scrollingElement || document.documentElement;
  // On a phone a page wider than the screen widens the layout viewport (innerWidth) instead of
  // scrolling, so compare with the screen width too.
  if (vw > screenW + 1) issues.push(`page wider than the screen (${vw} > ${screenW})`);
  else if (se.scrollWidth > vw + 1) issues.push(`page scrolls sideways (${se.scrollWidth} > ${vw})`);
  if (document.body.scrollWidth > Math.min(vw, screenW) + 1) issues.push(`body wider than the window (${document.body.scrollWidth} > ${Math.min(vw, screenW)})`);

  // The modal dialog on top (top-layer order is the order they were opened in, not DOM order):
  // the one that is hit at its own centre.
  const modals = [...document.querySelectorAll("dialog[open]")].filter((d) => d.matches(":modal") && shown(d));
  const modal = modals.find((d) => { const r = d.getBoundingClientRect(); const h = document.elementFromPoint(r.left + r.width / 2, r.top + Math.min(r.height / 2, 30)); return h && d.contains(h); }) || modals.pop() || null;
  const menuEl = document.getElementById("ctxMenu");
  const menuOpen = menuEl && !menuEl.classList.contains("hidden") && shown(menuEl);
  if (modal && modal.scrollWidth > modal.clientWidth + 1) issues.push(`dialog ${describe(modal)} scrolls sideways (${modal.scrollWidth} > ${modal.clientWidth})`);

  // Surfaces: dialogs, menus, drawers, panes, floating buttons, toolbars, toasts and scroll-cue chips.
  const SURF = ".toast-host .toast, .sc-chip:not(.sc-hidden), .fab, .draft-chips > *, .ac-popup, .workspace.nav-open .folders, .module.nav-open .side-pane, .bottom-nav, .topbar, .qb-qat, .ai-pane, .ai-busy, .qp-pen-frame";
  const surfaces = [modal, menuOpen ? menuEl : null, ...document.querySelectorAll(SURF)]
    .filter((s) => s && shown(s) && (!ignoreToasts || !s.closest(".toast-host")));
  for (const s of surfaces) {
    if (modal && !modal.contains(s) && !(menuOpen && s === menuEl) && !s.matches(".toast")) continue; // under the modal
    const r = s.getBoundingClientRect();
    if (outside({ ...r, w: r.width, h: r.height })) issues.push(`surface ${describe(s)} ${fmt(r)} outside the safe area`);
    if (s.matches(".qp-pen-frame") && clipped(s).h < 120) issues.push(`editor ${describe(s)} only ${Math.round(clipped(s).h)} px tall`);
  }

  // The layer the person is using: an open menu, else an autocomplete list, else the modal dialog,
  // else an open drawer or pane, else the page. What lies under it is meant to be covered.
  const popup = [...document.querySelectorAll(".ac-popup")].find((x) => shown(x));
  const drawer = [...document.querySelectorAll(".workspace.nav-open .folders, .module.nav-open .side-pane, .todo-detail:not(.hidden)")].find((x) => shown(x) && /fixed|absolute/.test(getComputedStyle(x).position))
    || [...document.querySelectorAll(".workspace.with-myday .my-day")].find((x) => shown(x) && /fixed|absolute/.test(getComputedStyle(x).position));
  // The AI pane floats over the page on phones (and next to it on wide screens, where it is part of the page).
  const aiPane = [...document.querySelectorAll(".ai-pane")].find((x) => shown(x));
  // It is a layer of its own when it floats over the page; on wide screens the page makes room for it.
  const shellBox = [...document.querySelectorAll("#app .shell > .module")].find((m) => m.getClientRects().length);
  const aiCovers = aiPane && !(shellBox && shellBox.getBoundingClientRect().right <= aiPane.getBoundingClientRect().left + 1);
  const layers = menuOpen ? [menuEl] : popup ? [popup] : modal ? [modal] : drawer ? [drawer, document.querySelector(".topbar")] : aiCovers ? (aiPane.getBoundingClientRect().top >= document.querySelector(".topbar").getBoundingClientRect().bottom - 1 ? [aiPane, document.querySelector(".topbar")] : [aiPane]) : [document.body];
  if (!ignoreToasts) layers.push(document.getElementById("toasts"));
  if (!menuOpen && !popup && !modal) for (const x of document.querySelectorAll(".ai-busy, .qb-qat-bottom")) if (shown(x) && !layers.includes(x)) layers.push(x);
  const SEL = "button, a[href], input:not([type=hidden]), select, textarea, [contenteditable=true], [role=button], .msg, li[class], summary";
  // A reply docked in the reading pane covers the message it answers (by design).
  const docked = !modal && [...document.querySelectorAll("#composeDialog.docked[open]")].find((x) => shown(x));
  const controls = [...new Set(layers.flatMap((l) => (l ? [...l.querySelectorAll(SEL)] : [])))]
    .filter((el) => shown(el) && !el.closest(".sc-chip, #splash" + (ignoreToasts ? ", .toast-host" : "")) && !el.disabled
      && !(layers[0] === document.body && modal && !modal.contains(el))
      && !(docked && !docked.contains(el) && el.closest("#readingPane")));
  const boxes = [];
  for (const el of controls) {
    const r = clipped(el);
    if (r.w < 2 || r.h < 2) {
      // Hidden by a clipping box that cannot scroll: the control can never be reached.
      if (r.cut && !el.closest(".msg, .ai-ens-text, .qb-swatches, .tg-body") && el.getBoundingClientRect().width > 4) issues.push(`${describe(el)} cut off by ${describe(r.cut)} (cannot be scrolled to)`);
      continue; // scrolled out of view
    }
    if (outside(r)) { issues.push(`${describe(el)} ${fmt(r)} outside the safe area`); continue; }
    // Hit test at the centre of the visible part.
    const cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
    if (cx < 0 || cy < 0 || cx >= vw || cy >= vh) continue;
    const hit = document.elementFromPoint(cx, cy);
    const sameLabel = hit && el.closest("label") && el.closest("label") === hit.closest("label");
    // List rows may pass under floating buttons and toasts (they scroll); buttons must not.
    const row = el.matches(".msg, li, .msg input, li input") || !!el.closest(".msg");
    const floating = hit && hit.closest(".fab, .toast-host, .draft-chips, .qb-qat-bottom");
    if (hit && hit !== el && !el.contains(hit) && !hit.contains(el) && !sameLabel && !hit.closest(".sc-chip") && !(ignoreToasts && hit.closest(".toast-host")) && !(row && floating)) {
      issues.push(`${describe(el)} ${fmt(r)} covered by ${describe(hit)}`);
    }
    if (!/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) && !el.isContentEditable && !el.matches(".msg, li, summary") && !el.querySelector(SEL)) boxes.push({ el, r });
  }
  // Buttons drawn on top of each other.
  for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
    const a = boxes[i], b = boxes[j];
    if (a.el.contains(b.el) || b.el.contains(a.el)) continue;
    const w = Math.min(a.r.right, b.r.right) - Math.max(a.r.left, b.r.left), h = Math.min(a.r.bottom, b.r.bottom) - Math.max(a.r.top, b.r.top);
    if (w > 2 && h > 2 && w * h > 0.25 * Math.min(a.r.w * a.r.h, b.r.w * b.r.h)) issues.push(`${describe(a.el)} overlaps ${describe(b.el)}`);
  }
  // Text squeezed into a sliver (a heading or label wrapping a letter or two per line).
  const scope = layers.filter(Boolean);
  for (const l of scope) for (const n of l.querySelectorAll("h1, h2, h3, h4, p, span, b, label, button, td, th, summary, .rd-subject, .msg-subject, div")) {
    const own = [...n.childNodes].filter((c) => c.nodeType === 3).map((c) => c.textContent).join("").trim();
    if (own.length < 6 || !/[A-Za-z]{4}/.test(own)) continue;
    const r = n.getBoundingClientRect();
    if (!r.height) continue;
    const fs = parseFloat(getComputedStyle(n).fontSize) || 14;
    const tall = Math.max(r.height, n.scrollHeight);
    if (r.width < fs * 3.2 && tall > fs * 3.5 && shown(n)) {
      const c = clipped(n);
      if (c.h > 1 && c.right >= c.left) issues.push(`text ${describe(n)} squeezed into ${Math.round(r.width)} px (${Math.round(tall)} px tall)`);
    }
  }
  return [...new Set(issues)];
}

// ------------------------------------------------------------------ states to open
const menuOpen = (page) => page.waitForFunction(() => !document.getElementById("ctxMenu").classList.contains("hidden"), null, { timeout: 3000 });
async function closeAll(page) {
  await page.evaluate(() => {
    try { menu.close(); } catch {}
    try { if (state.compose) compose.close(true); } catch {}
    for (const d of document.querySelectorAll("dialog[open]")) { d.close(); if (d.classList.contains("ai-dialog")) d.remove(); }
    try { qbAi.pane.close(); qbAi.stop(); } catch {}
    document.getElementById("aiBusy")?.remove(); document.body.classList.remove("ai-running");
    document.querySelectorAll(".qb-drop-overlay, .qp-drop-overlay").forEach((n) => n.remove());
    document.querySelectorAll(".nav-open").forEach((n) => n.classList.remove("nav-open"));
    document.querySelectorAll("#toasts .toast").forEach((x) => x.remove());
    const toasts = document.getElementById("toasts"); if (toasts && toasts.parentNode !== document.body) document.body.appendChild(toasts);
    if (!document.getElementById("setup")?.classList.contains("hidden")) document.getElementById("suCancelAdd")?.click();
    if (state.module !== "mail") shell.setModule("mail");
    if (prefs.get("navCollapsed", false)) { prefs.set("navCollapsed", false); applyTheme(); }
    if (prefs.get("ribbonCollapsed", false)) prefs.set("ribbonCollapsed", false);
    if (prefs.get("qat", null) !== null || prefs.get("quillpenUrl", "")) { prefs.set("qat", null); prefs.set("quillpenUrl", ""); try { quillPen.detect(true); } catch {} }
    try { qatUI.apply(); } catch {}
    const home = document.querySelector("#ribbon [data-rtab=home]"); if (home && !home.classList.contains("active")) home.click();
    document.getElementById("ribbon")?.classList.remove("qb-peek");
    if (document.getElementById("workspace").classList.contains("show-reading")) reading.close();
    if (document.getElementById("workspace").classList.contains("with-myday")) try { mailplus.toggleMyDay(); } catch {}
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  });
  // The message list as it started: the Inbox, Focused, no search or filter.
  await page.evaluate(async () => {
    try { if (typeof mailExtras !== "undefined") mailExtras.qfilter = null; } catch {}
    const reload = !!state.search || state.folder !== "INBOX";
    state.filter = "all"; state.focus = "focused"; state.selected.clear();
    document.querySelectorAll("#focusTabs button").forEach((b) => b.classList.toggle("active", b.dataset.focus === "focused"));
    if (reload) { $("searchInput").value = ""; $("searchClear").classList.add("hidden"); state.search = null; state.searchOpts = null; await list.load("INBOX"); } else list.render();
  }).catch(() => {});
  await page.waitForTimeout(150);
}
async function openFirstMessage(page) {
  // The list can re-render under the click (a refresh), or come late while the server is still busy
  // with a command that read every folder: wait, and try again a couple of times.
  for (let i = 0; ; i++) {
    await page.waitForSelector("#messageList .msg", { timeout: 45000 });
    await page.locator("#messageList .msg").first().click({ timeout: 10000 }).catch(() => {});
    if (await page.waitForFunction(() => !!state.openDetail, null, { timeout: 8000 }).then(() => true, () => false)) break;
    if (i === 2) throw new Error("the first message did not open");
  }
  await page.waitForTimeout(400);
}
async function openCompose(page, html = "<p>Hello, here is the text of the message.</p>") {
  await page.evaluate((html) => compose.open({ mode: "new", to: "someone@example.org", subject: "Layout check", html }), html);
  await page.waitForSelector("#composeDialog[open]");
}
async function setQat(page, patch) {
  await page.evaluate((patch) => {
    const items = patch.all ? Qat.addAllQatChoices(Qat.DEFAULT, (id) => qatUI.cmd(id)?.label) : patch.items || Qat.DEFAULT;
    prefs.set("qat", Qat.sanitizeQatPrefs({ items, position: patch.position || "top", labels: !!patch.labels }));
    qatUI.apply();
  }, patch);
  await page.waitForTimeout(150);
}
const visible = (page, sel) => page.evaluate((sel) => { const n = document.querySelector(sel); return !!n && n.getClientRects().length > 0 && getComputedStyle(n).visibility !== "hidden"; }, sel);
/** Two AI services set up for "Use several AIs" (no keys: nothing is ever sent by these states). */
async function setUpAis(page) {
  await page.evaluate(() => {
    const ps = QbAiProviders.PROVIDERS.filter((p) => p.models && p.models.quality && !QbAiProviders.isOnDevice(p)).slice(0, 2);
    QbAiStore.saveSettings((x) => {
      for (const p of ps) x.providers[p.id] = { model: p.models.quality };
      x.active = ps[0].id;
      x.ensemble.members = ps.map((p) => ({ provider: p.id, model: p.models.quality }));
      x.ensemble.mode = "consensus"; x.ensemble.master = ps[0].id; x.ensemble.showDeliberation = true;
    });
  });
}
/** An answer from several AIs with a discussion, as the AI pane shows it. */
function fakeEnsemble() {
  const ps = QbAiProviders.PROVIDERS.filter((p) => p.models && p.models.quality && !QbAiProviders.isOnDevice(p)).slice(0, 3);
  const long = "The sender asks for the quarterly figures by Friday and wants confirmation that the macro problem is fixed. ".repeat(3);
  return {
    mode: "consensus", outcome: "no-unity",
    members: ps.map((p, i) => ({ provider: p.id, model: p.models.quality, ms: 1200 + i * 800, text: long, error: i === 2 ? "The service did not answer within the time limit (timeout)" : null })),
    rounds: [1, 2].map(() => ({ minute: long, responses: ps.slice(0, 2).map((p, i) => ({ provider: p.id, stance: i ? "block" : "unite", reason: i ? "The draft leaves out the deadline that the sender gave in the second paragraph." : "", suggestedChange: i ? "Mention Friday as the deadline." : "" })), missing: [{ provider: ps[2].id, error: "timeout" }] })),
    blocks: [{ provider: ps[1].id, reason: "The draft leaves out the deadline that the sender gave in the second paragraph." }],
    note: "One AI was left out because it did not answer in time.",
  };
}
async function aiAnswer(page) {
  await page.evaluate((fake) => {
    const ens = new Function("return (" + fake + ")()")();
    qbAi.pane.open({ include: "message" });
    qbAi.pane.user("Summarize this message in two sentences");
    qbAi.pane.result("Summary").done("The sender asks for the **quarterly figures** by Friday.\n\n- Confirm the macro fix\n- Send the figures", {
      ensemble: ens, actions: [{ label: "Reply with this", icon: "reply", run() {} }, { label: "Copy to a new task", icon: "todo", run() {} }],
      sent: { messages: [{ role: "system", content: "You are an email assistant." }, { role: "user", content: "Message text ".repeat(40) }], providerName: "AI service", model: "m", endpoint: "https://ai.example.org/v1", route: "direct" },
    });
    document.querySelectorAll("#aiPane details").forEach((d) => (d.open = true));
  }, fakeEnsemble.toString());
  await page.waitForTimeout(200);
}
/** A small mbox with long folder and file names, imported through the real Import Mailbox dialog. */
const MBOX = Array.from({ length: 6 }, (_, i) => `From sender${i}@example.org Mon Jan  ${i + 1} 10:00:00 2024\nFrom: Sender ${i} <sender${i}@example.org>\nTo: demo@quillpost.test\nSubject: Archived message number ${i} with a fairly long subject line\nDate: Mon, ${i + 1} Jan 2024 10:00:00 +0000\nMessage-ID: <arch${i}@example.org>\n\nBody of archived message ${i}.\n`).join("\n");
async function importOptions(page) {
  await page.evaluate((mbox) => {
    const f = new File([mbox], "Messages exported from an old mail program with a long name.mbox", { type: "application/mbox" });
    mailImport.open([{ file: f, path: f.name }], { mailboxOnly: true });
  }, MBOX);
  await page.waitForFunction(() => /Import Mailbox/.test(document.querySelector("#qbImport .qb-dlg-title")?.textContent || "") && !!document.querySelector("#qbImport .qb-import-tree"), null, { timeout: 15000 });
}
/** Local Archives with one imported mailbox (imported once per demo server). */
async function ensureArchive(page) {
  if (await page.evaluate(async () => { await mailImport.loadArchives(); return mailImport.archives.length > 0; })) return;
  await importOptions(page);
  await page.click("#qbImport .dlg-foot .btn.primary");
  await page.waitForFunction(() => /finished|failed|cancelled/i.test(document.querySelector("#qbImport .qb-dlg-title")?.textContent || ""), null, { timeout: 30000 });
  await page.evaluate(() => document.getElementById("qbImport").close());
  await page.evaluate(() => folders.load());
}
const IMPORT_FAKE = { name: "Messages exported from an old mail program with a long name.mbox", upload: "none", totalMessages: 1200, totalItems: 30,
  detection: { label: "Outlook data file (.pst)", program: "Microsoft Outlook 2010 and later", category: "mailbox", supported: true, note: "Unicode PST; read with the built-in reader." },
  warnings: ["Two items could not be read and will be skipped."],
  folders: [["Top of Personal Folders", 0, 0], ["Inbox", 1, 640], ["Projects and other things with a very long folder name that goes on", 2, 120], ["2019", 3, 40], ["Sent Items", 1, 300], ["Contacts", 1, 30, "contacts"], ["Calendar", 1, 12, "calendar"]]
    .map(([name, depth, count, kind], i) => ({ id: "f" + i, name, depth, count, unread: i === 1 ? 12 : 0, kind: kind || "mail", path: Array.from({ length: depth + 1 }, (_, k) => "p" + k).join("/") + "/" + name })) };

async function openFile(page, name, content) {
  await page.evaluate(async ({ name, b64 }) => {
    const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    await FileOpen.openFiles([new File([bin], name)]);
  }, { name, b64: Buffer.from(content).toString("base64") });
  await page.waitForSelector("#fileDialog[open]", { timeout: 15000 });
  await page.waitForTimeout(500);
}
const sample = (f) => fs.readFileSync(path.join(SAMPLES, f));
const LONG_EML = `From: "A sender with a rather long display name" <long.sender.address@subdomain.example.org>\r\nTo: demo@quillpost.test, someone.else.with.a.long.address@example.org\r\nSubject: ${"A very long subject line that keeps going ".repeat(4)}\r\nDate: Tue, 2 Jan 2024 10:00:00 +0000\r\nMessage-ID: <long@example.org>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n${"Text of the message with an unbreakable https://example.org/a/very/long/link/that/does/not/break/anywhere/at/all/because/it/is/one/word ".repeat(3)}\r\n`;

const SETTINGS_TABS = ["account", "signatures", "autoreply", "general", "notifications", "layout", "compose", "templates", "quicksteps", "categories", "swipe", "junk", "rules", "senders", "ai", "calendar", "importexport", "about"];
const RIBBON_TABS = ["home", "sendreceive", "folder", "view", "ai", "help", "tools"];
const COMPOSE_TABS = ["message", "insert", "format", "options", "review", "draw", "ai"];

/** Each state: [name, open(page, profile), { keyboard: focus selector to raise the keyboard, toasts, after }] */
const STATES = [
  ["mail list", async () => {}],
  ["folder drawer", async (p) => { await p.click("#navToggle"); await p.waitForTimeout(250); }],
  ["reading pane", async (p) => { await openFirstMessage(p); }],
  ["message menu", async (p) => { await openFirstMessage(p); await p.click("#rdMore"); await menuOpen(p); }],
  ["list context menu", async (p) => { await p.waitForSelector("#messageList .msg"); await p.locator("#messageList .msg").first().dispatchEvent("contextmenu", { clientX: 40, clientY: 200 }); await menuOpen(p); }],
  ["account menu", async (p) => { await p.click("#accountAvatar"); await menuOpen(p); }],
  ["toast", async (p) => { await p.evaluate(() => toast("A message that is long enough to wrap onto a second line on a phone screen", { action: "Undo" })); }, { toasts: true }],
  ["toast over a dialog", async (p) => { await p.evaluate(() => settingsUI.open("general")); await p.waitForSelector("#settingsDialog[open]"); await p.evaluate(() => toast("Settings saved — a message long enough to wrap onto a second line", { action: "Undo" })); }, { toasts: true }],
  ["compose", async (p) => { await openCompose(p); }],
  ["compose + keyboard (To)", async (p) => { await p.evaluate(() => compose.open({ mode: "new" })); await p.waitForSelector("#composeDialog[open]"); }, { keyboard: "#cTo" }],
  ["compose + keyboard (body)", async (p) => { await p.evaluate(() => compose.open({ mode: "new", html: "<p>" + "Line of text. ".repeat(80) + "</p>" })); await p.waitForSelector("#composeDialog[open]"); }, { keyboard: "#cEditor" }],
  ["compose autocomplete", async (p) => { await p.evaluate(() => compose.open({ mode: "new" })); await p.waitForSelector("#composeDialog[open]"); await p.click("#cTo"); await p.keyboard.type("j"); await p.waitForTimeout(400); }, { keyboard: "#cTo", keepFocus: true }],
  ["compose send menu", async (p) => { await openCompose(p); await p.click("#cSendMenu"); await menuOpen(p); }],
  ["compose close menu", async (p) => { await p.evaluate(() => compose.open({ mode: "new", to: "x@example.org" })); await p.waitForSelector("#composeDialog[open]"); await p.evaluate(() => { state.compose.dirty = true; }); await p.click("#cClose"); await menuOpen(p); }],
  ...COMPOSE_TABS.map((tab) => [`compose tab: ${tab}`, async (p) => { await openCompose(p); const b = p.locator(`#composeDialog .compose-tabs [data-ctab=${tab}]`); if (await b.count()) await b.first().click(); else throw new Error("no compose tab " + tab); await p.waitForTimeout(150); }]),
  ["reply (quoted)", async (p) => { await openFirstMessage(p); await p.evaluate(() => compose.open({ mode: "reply", detail: state.openDetail })); await p.waitForSelector("#composeDialog[open]"); }],
  ...SETTINGS_TABS.map((tab) => [`settings: ${tab}`, async (p) => { await p.evaluate((tab) => settingsUI.open(tab), tab); await p.waitForSelector("#settingsDialog[open]"); await p.waitForTimeout(250); }]),
  ["settings + keyboard (signature)", async (p) => { await p.evaluate(() => settingsUI.open("account")); await p.waitForSelector("#settingsDialog[open]"); }, { keyboard: "#stBody input, #stBody textarea" }],
  ["move dialog", async (p) => { await openFirstMessage(p); await p.evaluate(() => actions.pickFolder([state.openDetail])); await p.waitForSelector("#moveDialog[open]"); }],
  ["move dialog + keyboard", async (p) => { await openFirstMessage(p); await p.evaluate(() => actions.pickFolder([state.openDetail])); await p.waitForSelector("#moveDialog[open]"); }, { keyboard: "#mvSearch" }],
  ["rule editor", async (p) => { await p.evaluate(() => settingsUI.editRule(null)); await p.waitForSelector("#ruleDialog[open]"); }],
  ["new folder form", async (p) => { p.evaluate(() => mailplus.createFolder(null)).catch(() => {}); await p.waitForSelector("#formDialog[open]"); }, { keyboard: "#formDialog input" }],
  ["shortcuts", async (p) => { await p.evaluate(() => qatUI.shortcuts()); await p.waitForSelector("#shortcutsDialog[open]"); }],
  ["popup", async (p) => { await p.evaluate(() => popup("Message source", { node: el("pre", { class: "body-text mono", text: "Header: value\n".repeat(120) }) })); await p.waitForSelector("#popupDialog[open]"); }],
  ["immersive reader", async (p) => { await openFirstMessage(p); await p.evaluate(() => mailplus.reader(state.openDetail)); await p.waitForSelector("#readerDialog[open]"); }],
  ["analysis", async (p) => { await openFirstMessage(p); await p.evaluate(() => { analysis.open && analysis.open(state.openDetail); }); await p.waitForSelector("#analysisDialog[open]", { timeout: 15000 }); await p.waitForTimeout(1500); }],
  ["my day", async (p) => { await p.evaluate(() => $("btnMyDay").click()); await p.waitForTimeout(400); }],
  ["calendar", async (p) => { await p.evaluate(() => shell.setModule("calendar")); await p.waitForTimeout(600); }],
  ["calendar side pane", async (p) => { await p.evaluate(() => shell.setModule("calendar")); await p.waitForTimeout(400); await p.click("#navToggle"); await p.waitForTimeout(250); }],
  ["new event", async (p) => { await p.evaluate(() => { shell.setModule("calendar"); calendarUI.newEvent(); }); await p.waitForSelector("dialog[open]"); }],
  ["new event + keyboard", async (p) => { await p.evaluate(() => { shell.setModule("calendar"); calendarUI.newEvent(); }); await p.waitForSelector("dialog[open]"); }, { keyboard: "dialog[open] input[type=text]" }],
  ["people", async (p) => { await p.evaluate(() => shell.setModule("people")); await p.waitForTimeout(600); }],
  ["new contact", async (p) => { await p.evaluate(() => { shell.setModule("people"); peopleUI.edit(null); }); await p.waitForSelector("dialog[open]"); }],
  ["new contact + keyboard", async (p) => { await p.evaluate(() => { shell.setModule("people"); peopleUI.edit(null); }); await p.waitForSelector("dialog[open]"); }, { keyboard: "dialog[open] input" }],
  ["to do", async (p) => { await p.evaluate(() => shell.setModule("todo")); await p.waitForTimeout(600); }],
  ["to do side pane", async (p) => { await p.evaluate(() => shell.setModule("todo")); await p.waitForTimeout(400); await p.locator("#modTodo .mobile-only[title=Lists]").first().click(); await p.waitForTimeout(250); }, { phone: true }],

  // ---------------------------------------------------------------- Quick Access Toolbar
  ...["top", "below", "bottom"].flatMap((position) => [false, true].flatMap((labels) => [
    [`qat ${position}${labels ? " + labels" : ""}`, async (p) => { await setQat(p, { position, labels }); }],
    [`qat ${position}${labels ? " + labels" : ""} (all choices)`, async (p) => { await setQat(p, { position, labels, all: true }); }],
  ])),
  ["qat below + reading pane (all choices)", async (p) => { await setQat(p, { position: "below", labels: true, all: true }); await openFirstMessage(p); }],
  ["qat bottom + keyboard (search)", async (p) => { await setQat(p, { position: "bottom", labels: true, all: true }); }, { keyboard: "#searchInput" }],
  ["qat bottom + keyboard (compose)", async (p) => { await setQat(p, { position: "bottom", labels: false, all: true }); await openCompose(p, "<p>" + "Line of text. ".repeat(60) + "</p>"); }, { keyboard: "#cEditor" }],
  ["qat in other modules", async (p) => { await setQat(p, { position: "below", labels: true, all: true }); await p.evaluate(() => shell.setModule("calendar")); await p.waitForTimeout(500); }],
  ["qat drop-down", async (p) => { await p.click(".qb-qat-more"); await menuOpen(p); }],
  ["qat drop-down (bottom)", async (p) => { await setQat(p, { position: "bottom" }); await p.click(".qb-qat-more"); await menuOpen(p); }],
  ["more commands chooser", async (p) => { await p.evaluate(() => qatUI.chooser()); await p.waitForSelector("#qbChooser[open]"); }],
  ["more commands chooser (all choices)", async (p) => { await setQat(p, { position: "top", all: true }); await p.evaluate(() => qatUI.chooser()); await p.waitForSelector("#qbChooser[open]"); }],
  ["more commands chooser + keyboard", async (p) => { await p.evaluate(() => qatUI.chooser()); await p.waitForSelector("#qbChooser[open]"); }, { keyboard: "#qbChooser input[type=search]" }],
  ["keyboard shortcuts dialog", async (p) => { await p.evaluate(() => qatUI.keyboard("mail.reply")); await p.waitForSelector("#qbKeys[open]"); }],
  ["keyboard shortcuts dialog + keyboard", async (p) => { await p.evaluate(() => qatUI.keyboard()); await p.waitForSelector("#qbKeys[open]"); }, { keyboard: "#qbKeys input[type=search]" }],
  ["command search", async (p) => { await p.evaluate(() => qatUI.palette()); await p.waitForSelector("#qbPalette[open]"); await p.waitForTimeout(100); }, { keyboard: "#qbPalette input", keepFocus: true }],
  ["command search results", async (p) => { await p.evaluate(() => qatUI.palette()); await p.waitForSelector("#qbPalette[open]"); await p.fill("#qbPalette input", "mark"); }],
  ["command reference", async (p) => { await p.evaluate(() => qatUI.reference()); await p.waitForSelector("#qbReference[open]"); }],
  ["file backstage", async (p) => { await p.evaluate(() => qatUI.backstage()); await p.waitForSelector("#qbBackstage[open]"); }],
  ["file backstage: last page", async (p) => { await p.evaluate(() => qatUI.backstage()); await p.waitForSelector("#qbBackstage[open]"); await p.locator("#qbBackstage .qb-bs-nav button:not(.qb-bs-back)").last().click(); }],

  // ---------------------------------------------------------------- ribbon tabs (hidden on phones)
  ...RIBBON_TABS.map((tab) => [`ribbon: ${tab}`, async (p) => { if (await visible(p, `#ribbon [data-rtab=${tab}]`)) await p.click(`#ribbon [data-rtab=${tab}]`); await p.waitForTimeout(150); }]),
  ["ribbon: ai + reading pane", async (p) => { await openFirstMessage(p); if (await visible(p, "#ribbon [data-rtab=ai]")) await p.click("#ribbon [data-rtab=ai]"); }],
  ["ribbon collapsed", async (p) => { await p.evaluate(() => { prefs.set("ribbonCollapsed", true); qatUI.apply(); }); }],

  // ---------------------------------------------------------------- AI
  ["ai pane", async (p) => { await p.evaluate(() => qbAi.pane.open()); await p.waitForTimeout(150); }],
  ["ai pane + reading pane", async (p) => { await openFirstMessage(p); await p.evaluate(() => qbAi.pane.open()); await p.waitForTimeout(250); }],
  ["ai pane + keyboard", async (p) => { await p.evaluate(() => qbAi.pane.open()); }, { keyboard: "#aiPane textarea" }],
  ["ai pane: how the AIs answered", async (p) => { await setUpAis(p); await openFirstMessage(p); await aiAnswer(p); }],
  ["ai pane: how the AIs answered + keyboard", async (p) => { await setUpAis(p); await aiAnswer(p); }, { keyboard: "#aiPane textarea" }],
  ["ai busy chip", async (p) => { await openFirstMessage(p); await p.evaluate(() => qbAi.busy("Summarizing the conversation with several AIs — round 2 of 3")); }],
  ["ai reading-pane menu", async (p) => { await openFirstMessage(p); await p.click("#rdAi"); await menuOpen(p); }],
  ["ai setup wizard", async (p) => { p.evaluate(() => qbAi.setup()).catch(() => {}); await p.waitForSelector("dialog.ai-dialog[open]"); await p.waitForTimeout(300); }],
  ["ai providers", async (p) => { await setUpAis(p); p.evaluate(() => qbAi.providers()).catch(() => {}); await p.waitForSelector("dialog.ai-dialog[open]"); await p.waitForTimeout(300); }],
  ["ai settings", async (p) => { p.evaluate(() => qbAi.settings()).catch(() => {}); await p.waitForSelector("dialog.ai-dialog[open]"); await p.waitForTimeout(200); }],
  ["ai settings: use several AIs", async (p) => { await setUpAis(p); p.evaluate(() => qbAi.settings({ section: "ensemble" })).catch(() => {}); await p.waitForSelector("dialog.ai-dialog[open]"); await p.waitForTimeout(300); }],
  ["ai privacy", async (p) => { await setUpAis(p); p.evaluate(() => qbAi.privacy()).catch(() => {}); await p.waitForSelector("dialog.ai-dialog[open]"); await p.waitForTimeout(200); }],
  ["ai preview: how the AIs answered", async (p) => {
    await setUpAis(p);
    await p.evaluate((fake) => { const ens = new Function("return (" + fake + ")()")(); qbAi.preview({ title: "Draft a reply", original: "Original text ".repeat(30), generate: async () => ({ text: "Dear Paul,\n\nThanks — the figures follow on Friday.\n\nBest", ensemble: ens }), actions: [{ label: "Insert", primary: true, run() {} }, { label: "Copy", keepOpen: true, run() {} }] }); }, fakeEnsemble.toString());
    await p.waitForSelector("dialog.ai-dialog[open] .ai-ens"); await p.evaluate(() => document.querySelectorAll("dialog.ai-dialog[open] details").forEach((d) => (d.open = true)));
  }],

  // ---------------------------------------------------------------- import and archives
  ["import mailbox: start", async (p) => { await p.evaluate(() => mailImport.chooseSource()); await p.waitForSelector("#qbImportSource[open]"); }],
  ["import mailbox: options", async (p) => { await p.evaluate((a) => { const d = mailImport.dialog("qbImport", "Import Mailbox"); mailImport.importDialog(a, d); }, IMPORT_FAKE); await p.waitForSelector("#qbImport[open]"); }],
  ["import mailbox: options + keyboard", async (p) => { await p.evaluate((a) => { const d = mailImport.dialog("qbImport", "Import Mailbox"); mailImport.importDialog(a, d); }, IMPORT_FAKE); await p.waitForSelector("#qbImport[open]"); }, { keyboard: "#qbImport .qb-import-dest input[type=text]" }],
  ["import mailbox: progress", async (p) => {
    await p.route("**/api/mailbox/job?**", (r) => r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ state: "running", total: 1200, done: 480, imported: 470, skipped: 10, current: "Projects and other things with a very long folder name/2019/Some message subject" }) }));
    await p.evaluate((a) => { const d = mailImport.dialog("qbImport", "Import Mailbox"); d.showModal(); mailImport.progress({ id: "x", total: 1200 }, d, a, "archive"); }, IMPORT_FAKE);
    await p.waitForTimeout(900);
  }, { after: (p) => p.unroute("**/api/mailbox/job?**") }],
  ["import mailbox: report", async (p) => {
    await p.evaluate((a) => { const d = mailImport.dialog("qbImport", "Import finished"); d.showModal(); mailImport.finished({ state: "done", imported: 1190, skipped: 10, firstFolder: "INBOX", report: Array.from({ length: 40 }, (_, i) => `Skipped item ${i} in Projects and other things with a very long folder name: the attachment could not be decoded (unknown encoding x-uue-something-long)`) }, d, a, "archive"); document.querySelectorAll("#qbImport details").forEach((x) => (x.open = true)); }, IMPORT_FAKE);
  }],
  ["import mailbox: real dialog", async (p) => { await importOptions(p); }],
  ["local archives", async (p) => { await ensureArchive(p); await p.evaluate(() => Commands.run("mail.localArchives")); await p.waitForTimeout(800); }],
  ["local archives in folder drawer", async (p) => { await ensureArchive(p); await p.evaluate(() => Commands.run("mail.localArchives")); await p.waitForTimeout(600); if (await visible(p, "#navToggle")) { await p.click("#navToggle"); await p.waitForTimeout(250); } await p.evaluate(() => document.getElementById("archiveList")?.scrollIntoView({ block: "nearest" })); }, { phone: true }],

  // ---------------------------------------------------------------- file-open window
  ["open file: message", async (p) => { await openFile(p, "Welcome.eml", sample("Welcome.eml")); }],
  ["open file: long message", async (p) => { await openFile(p, "A message saved with a very long file name from another program.eml", LONG_EML); }],
  ["open file: Outlook message + history", async (p) => { await openFile(p, "Welcome.eml", sample("Welcome.eml")); await openFile(p, "Quarterly figures.msg", sample("Quarterly figures.msg")); await p.evaluate(() => FileOpen.show(FileOpen.history[FileOpen.history.length - 1])); }],
  ["open file: contacts", async (p) => { await openFile(p, "Ada Lovelace.vcf", sample("Ada Lovelace.vcf")); }],
  ["open file: calendar", async (p) => { await openFile(p, "Design review.ics", sample("Design review.ics")); }],
  ["open file: document", async (p) => { await openFile(p, "Report.docx", sample("Report.docx")); }],
  ["open file: winmail.dat", async (p) => { await openFile(p, "winmail.dat", sample("winmail.dat")); }],

  // ---------------------------------------------------------------- embedded QuillPen editor
  ["quillpen offer", async (p) => { await p.evaluate((u) => { prefs.set("quillpenUrl", u); prefs.set("quillpenMode", "ask"); return quillPen.detect(true); }, PEN_URL); await openCompose(p); await p.waitForSelector("#cQuillPenOffer:not(.hidden)", { timeout: 5000 }).catch(() => {}); }],
  ["quillpen open", async (p) => {
    await p.evaluate((u) => { prefs.set("quillpenUrl", u); prefs.set("quillpenMode", "ask"); return quillPen.detect(true); }, PEN_URL);
    await openCompose(p); await p.evaluate(() => openInQuillPen()); await p.waitForSelector("#composeDialog .qp-pen-host.ready", { timeout: 10000 });
  }],
];

let PEN_URL = "";
function serveFakePen() {
  const server = http.createServer((req, res) => { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }); res.end(FAKE_PEN); });
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server)));
}

// ------------------------------------------------------------------ the command sweep
// Commands that would change the demo mailbox for the states after them, end the session, print,
// leave the page or ask the system for a screen to capture (its picker is not part of the page). Everything else that a person can run is opened and audited.
const SKIP_CMDS = new Set(["file.signOut", "file.print", "file.printPreview", "file.quickPrint", "gmail.printAll", "compose.send", "compose.discard", "mail.sendAll", "eudora.sendQueued",
  "mail.archive", "mail.junk", "mail.notJunk", "mail.phish", "mail.ignore", "mail.cleanUpConversation", "mail.blockSender", "folder.emptyTrash", "folder.compact", "folder.deleteAll", "folder.cleanUp",
  "tb.archiveYear", "tb.archiveMonth", "tb.stripAttachments", "tb.deleteJunkInFolder", "pine.markDeleted", "mutt.deleteThread", "hey.moveToPaperTrail", "gmail.mute", "mail.unsubscribe",
  "new.unsubscribeEverywhere", "view.fullScreen", "mail.workOffline", "folder.delete", "mail.delete", "mail.deletePermanent", "ai.clearModels", "file.addAccount", "edit.undo", "edit.redo",
  "compose.screenshot", "mail.rescan", "folder.runRules", "folder.archiveOld", "folder.recoverDeleted", "folder.markReadByDate", "mail.nextItem", "mail.prevItem", "mutt.nextUnread", "mutt.prevUnread", "mutt.nextFlagged", "mutt.nextUnreadThread", "tb.goBack", "tb.goForward", "view.reset"]);
async function commandStates(page) {
  if (CMDS === "0") return [];
  const re = CMDS === "1" ? null : new RegExp(CMDS);
  const cmds = await page.evaluate(() => Commands.all().map((c) => ({ id: c.id, scope: c.scope || "mail", kind: c.kind })));
  return cmds.filter((c) => !SKIP_CMDS.has(c.id) && (!re || re.test(c.id))).map((c) => {
    const run = (p) => p.evaluate((id) => {
      const vis = (n) => n && n.getClientRects().length && getComputedStyle(n).visibility !== "hidden";
      const anchor = ["#rdMore", "#cSendMenu", ".qb-qat-more", "#btnSettings"].map((s) => document.querySelector(s)).find(vis) || null;
      window.__lastRun = Commands.run(id, { anchor, source: "ribbon", items: state.openDetail ? [state.messages.find((m) => mkey(m) === mkey(state.openDetail)) || state.openDetail] : [] });
    }, c.id);
    return [`cmd: ${c.id}`, async (p) => {
      if (c.scope === "compose" || /^(compose|eudora\.(personalities|priority|queue|pasteQuotation)|pegasus\.(glossary|copyToSelf)|notes\.moodStamp|snip\.insert|sh\.remindNoReply|new\.(insertTimeSlots|plainPreview|presend))/.test(c.id)) {
        await openCompose(p, "<p>Hello, here is the text of the message.</p><p>Second paragraph.</p>");
        await p.evaluate(() => { const e = document.getElementById("cEditor"); e.focus(); const r = document.createRange(); r.selectNodeContents(e.firstChild); getSelection().removeAllRanges(); getSelection().addRange(r); });
      } else await openFirstMessage(p);
      await run(p);
      await p.waitForTimeout(600);
    }, { after: c.kind === "toggle" ? async (p) => { await closeAll(p); await run(p); await p.waitForTimeout(150); } : null, command: true }];
  });
}

// ------------------------------------------------------------------ runner
/** Rejects when [p] takes longer than [ms] (a state that hangs the page must not stop the audit). */
const within = (p, ms, what) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error(`${what} did not finish within ${ms / 1000} s`)), ms))]);
const VERBOSE = !!process.env.VERBOSE;
async function run(browser, device, mode) {
  const { w, h, bars, kb } = device;
  const shell = mode === "shell";
  const vp = shell ? { width: w - bars.left - bars.right, height: h - bars.top - bars.bottom } : { width: w, height: h };
  const insets = shell || device.desktop ? { top: 0, right: 0, bottom: 0, left: 0 } : bars;
  const ctx = await browser.newContext(device.desktop ? { viewport: vp, deviceScaleFactor: 1 } : { viewport: vp, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  await signIn(ctx, account, { notifAsked: true });
  await ctx.addInitScript((i) => {
    if (!i.top && !i.right && !i.bottom && !i.left) return;
    const css = `:root{--sa-top:${i.top}px!important;--sa-right:${i.right}px!important;--sa-bottom:${i.bottom}px!important;--sa-left:${i.left}px!important}`;
    const add = () => { const s = document.createElement("style"); s.id = "sa-sim"; s.textContent = css; (document.head || document.documentElement).appendChild(s); };
    if (document.head) add(); else document.addEventListener("DOMContentLoaded", add);
  }, insets);
  let page = null;
  const label = `${device.name} ${mode}`;
  const errors = [];
  let crashed = false;
  // Pop-up windows a command opens are closed; the audited page is replaced if it ever goes away.
  ctx.on("page", (p) => { if (page && p !== page) p.close().catch(() => {}); });
  const newPage = async () => {
    page = await ctx.newPage();
    page.on("pageerror", (e) => errors.push(e.message));
    page.on("dialog", (d) => d.dismiss().catch(() => {}));
    page.on("filechooser", () => {});
    page.on("crash", () => { crashed = true; console.log(`  NOTE ${label}: the page crashed; continuing in a new page`); });
  };
  await newPage();
  const start = async () => {
    if (page.isClosed() || crashed) { await page.close().catch(() => {}); crashed = false; await newPage(); }
    await page.goto(BASE); await waitSignedIn(page); await page.waitForSelector("#messageList .msg", { timeout: 20000 }).catch(() => {}); await page.waitForTimeout(500);
  };
  await start();
  const states = [...STATES, ...(await commandStates(page))];
  for (const [name, open, opts = {}] of states) {
    if (ONLY && !(ONLY.startsWith("/") ? new RegExp(ONLY.slice(1)).test(name) : name.includes(ONLY))) continue;
    if (opts.phone && device.desktop) continue;
    if (VERBOSE) console.log(`  … ${label} / ${name}`);
    try {
      if (page.isClosed() || crashed) { console.log(`  NOTE ${label}: the page was closed; continuing in a new page`); await within(start(), 60000, "sign-in"); }
      if (!(await within(page.evaluate(() => typeof state !== "undefined" && !!state.token && !!document.getElementById("app") && !document.getElementById("app").classList.contains("hidden")), 10000, "check").catch(() => false))) await within(start(), 60000, "sign-in");
      await page.setViewportSize(vp);
      await within(closeAll(page), 30000, "closing the previous state");
      await page.evaluate(() => document.getElementById("sa-kb")?.remove());
    } catch (e) { t.ok(false, `${label} / ${name}: page stuck (${e.message.split("\n")[0]})`); await within(start(), 60000, "sign-in").catch(() => {}); continue; }
    let failed = null;
    try { await within(open(page, device), 60000, "opening"); } catch (e) { failed = e.message.split("\n")[0]; }
    if (failed) { t.ok(false, `${label} / ${name}: could not open (${failed})`); if (opts.after) await within(opts.after(page), 20000, "after").catch(() => {}); continue; }
    let safeInsets = { ...insets };
    if (opts.keyboard && !device.desktop) {
      // The keyboard comes up: the field gets focus, the viewport shrinks by the keyboard height
      // (and covers the bottom inset).
      if (!opts.keepFocus) await page.locator(opts.keyboard).first().focus().catch(() => {});
      await page.setViewportSize({ width: vp.width, height: vp.height - kb + (shell ? 0 : insets.bottom) });
      safeInsets = { ...insets, bottom: 0 };
      await page.evaluate(() => { const s = document.createElement("style"); s.id = "sa-kb"; s.textContent = ":root{--sa-bottom:0px!important}"; document.head.appendChild(s); });
      await page.waitForTimeout(450);
      // Where typing happens: the caret line of an editor, or the whole field.
      const caret = () => page.evaluate(() => {
        const a = document.activeElement; if (!a || a === document.body) return null;
        let r = a.getBoundingClientRect();
        if (a.isContentEditable) {
          const sel = getSelection(); const rr = sel.rangeCount ? sel.getRangeAt(0).getClientRects()[0] : null;
          r = rr && rr.height ? rr : { top: r.top, bottom: Math.min(r.bottom, r.top + 24) };
        }
        return { top: r.top, bottom: r.bottom, h: innerHeight };
      });
      const focused = await caret();
      if (focused && (focused.bottom > focused.h || focused.top < 0)) {
        // Browsers scroll the focused field (the caret) into view; the app must make that possible.
        await page.evaluate(() => { const a = document.activeElement; const sel = getSelection(); const n = a.isContentEditable && sel.rangeCount ? sel.getRangeAt(0).startContainer : a; (n.nodeType === 1 ? n : n.parentElement).scrollIntoView({ block: "nearest" }); });
        const again = await caret();
        t.ok(again.bottom <= again.h + 0.5 && again.top >= -0.5, `${label} / ${name}: focused field cannot be brought above the keyboard`);
      }
    }
    await page.waitForTimeout(250);
    const cur = page.viewportSize();
    const safe = { top: safeInsets.top, left: safeInsets.left, right: cur.width - safeInsets.right, bottom: cur.height - safeInsets.bottom };
    const issues = await within(page.evaluate(audit, { safe, ignoreToasts: !opts.toasts, screenW: cur.width }), 30000, "audit").catch((e) => [`audit failed: ${e.message.split("\n")[0]}`]);
    for (const i of issues) t.ok(false, `${label} / ${name}: ${i}`);
    if (!issues.length) t.ok(true, `${label} / ${name}`);
    if (SHOTS) await within(page.screenshot({ path: path.join(SHOTS, `${device.name}-${mode}-${name.replace(/[^a-z0-9]+/gi, "_")}.png`) }), 20000, "screenshot").catch(() => {});
    if (opts.after) { await page.setViewportSize(vp); await within(opts.after(page), 20000, "after").catch(() => {}); }
  }
  t.ok(errors.length === 0, `${label}: page errors: ${errors.join(" | ")}`);
  await ctx.close();
}

const browser = await chromium.launch({ executablePath: chromiumPath() });
const pen = await serveFakePen();
PEN_URL = `http://127.0.0.1:${pen.address().port}/`;
try {
  const modes = (process.env.MODES || "shell,cover,desktop").split(",");
  for (const d of [...PHONES, ...DESKTOPS]) {
    if (process.env.DEVICE && !d.name.startsWith(process.env.DEVICE)) continue;
    for (const mode of d.desktop ? ["desktop"] : ["shell", "cover"]) if (modes.includes(mode)) await run(browser, d, mode);
  }
} finally {
  await browser.close();
  pen.close();
  t.done();
}
