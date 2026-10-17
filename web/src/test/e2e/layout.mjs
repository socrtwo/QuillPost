// Phone layout audit: opens every dialog, menu and overlay of the web client on emulated phones and
// checks that each control is inside the safe part of the screen (clear of the status bar,
// navigation bar, display cut-outs and the on-screen keyboard), that no control is covered by
// another one, and that nothing scrolls sideways.
//
// Two ways the app meets the system bars are covered:
//   shell  the Android and iOS apps pad the WebView natively (MainActivity / ContentView), so the
//          page gets a viewport that excludes the bars: emulated by a smaller viewport;
//   cover  a browser or home-screen web app with viewport-fit=cover draws under the bars and the
//          page must keep clear of env(safe-area-inset-*): emulated by setting the --sa-top,
//          --sa-right, --sa-bottom and --sa-left variables that styles.css reads (Chromium cannot
//          fake env() itself).
// The keyboard is emulated by shrinking the viewport (what the Android app and Chrome with
// interactive-widget=resizes-content do).
//
// Run against the demo server (see README "Run it"; from web/):
//   PORT=8121 DEMO_IMAP_PORT=13144 DEMO_SMTP_PORT=13026 QUILLBOX_DATA_DIR=/tmp/qbB ./gradlew demo &
//   node src/test/e2e/layout.mjs
// Environment: BASE_URL (default http://127.0.0.1:8121/), IMAP_PORT / SMTP_PORT (13144 / 13026),
// CHROMIUM (browser executable), SHOTS=<folder> to save a screenshot of every state, ONLY=<text>
// to run only the states whose name contains the text, MODES=shell or MODES=cover, DEVICE=<name>.
// Exit code 1 when a defect is found.
import fs from "node:fs";
import path from "node:path";
import { loadPlaywright, chromiumPath, demoAccount, signIn, waitSignedIn, checker } from "./common.mjs";

const BASE = process.env.BASE_URL || "http://127.0.0.1:8121/";
const account = demoAccount(Number(process.env.IMAP_PORT || 13144), Number(process.env.SMTP_PORT || 13026));
const SHOTS = process.env.SHOTS || "";
const ONLY = process.env.ONLY || "";
if (SHOTS) fs.mkdirSync(SHOTS, { recursive: true });

// Phones: the screen, the system bars (status, navigation, cut-out) and the keyboard height.
const DEVICES = [
  { name: "412x915", w: 412, h: 915, bars: { top: 32, bottom: 24, left: 0, right: 0 }, kb: 330 },
  { name: "360x640", w: 360, h: 640, bars: { top: 24, bottom: 48, left: 0, right: 0 }, kb: 260 },
  { name: "915x412-landscape", w: 915, h: 412, bars: { top: 24, bottom: 0, left: 48, right: 48 }, kb: 190 },
];

const { chromium } = loadPlaywright();
const t = checker("layout");

// ------------------------------------------------------------------ in-page audit
function audit({ safe, ignoreToasts }) {
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
    for (let n = el; n; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.visibility === "hidden" || cs.display === "none" || Number(cs.opacity) === 0) return false;
    }
    return true;
  };
  // Rect of el clipped by every ancestor that clips overflow (not by the window: a control that
  // runs off the window without a scroll container around it cannot be reached at all).
  const clipped = (el) => {
    const r = el.getBoundingClientRect();
    let top = r.top, bottom = r.bottom, left = r.left, right = r.right;
    for (let n = el.parentElement; n && n !== document.documentElement; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.overflowX !== "visible" || cs.overflowY !== "visible") {
        const p = n.getBoundingClientRect();
        if (n !== document.body) {
          if (cs.overflowY !== "visible") { top = Math.max(top, p.top); bottom = Math.min(bottom, p.bottom); }
          if (cs.overflowX !== "visible") { left = Math.max(left, p.left); right = Math.min(right, p.right); }
        }
      }
      if (cs.position === "fixed") break;
    }
    return { top, bottom, left, right, w: right - left, h: bottom - top };
  };
  const outside = (r) => r.top < safe.top - 0.5 || r.left < safe.left - 0.5 || r.bottom > safe.bottom + 0.5 || r.right > safe.right + 0.5;
  const fmt = (r) => `[${Math.round(r.left)},${Math.round(r.top)} ${Math.round(r.w ?? r.width)}x${Math.round(r.h ?? r.height)}]`;

  const se = document.scrollingElement || document.documentElement;
  if (se.scrollWidth > vw + 1) issues.push(`page scrolls sideways (${se.scrollWidth} > ${vw})`);
  if (document.body.scrollWidth > vw + 1) issues.push(`body wider than the window (${document.body.scrollWidth} > ${vw})`);

  const modal = [...document.querySelectorAll("dialog[open]")].filter((d) => d.matches(":modal")).pop() || null;
  const menuEl = document.getElementById("ctxMenu");
  const menuOpen = menuEl && !menuEl.classList.contains("hidden") && shown(menuEl);
  const root = modal || document.body;
  if (modal && modal.scrollWidth > modal.clientWidth + 1) issues.push(`dialog ${describe(modal)} scrolls sideways (${modal.scrollWidth} > ${modal.clientWidth})`);

  // Surfaces: dialogs, menus, drawers, floating buttons, toasts and scroll-cue chips.
  const surfaces = [modal, menuOpen ? menuEl : null, ...document.querySelectorAll(".toast-host .toast, .sc-chip:not(.sc-hidden), .fab, .draft-chips > *, .ac-popup, .workspace.nav-open .folders, .module.nav-open .side-pane, .bottom-nav, .topbar")]
    .filter((s) => s && shown(s) && (!ignoreToasts || !s.closest(".toast-host")));
  for (const s of surfaces) {
    if (modal && !modal.contains(s) && !(menuOpen && s === menuEl) && !s.matches(".toast")) continue; // under the modal
    const r = s.getBoundingClientRect();
    if (outside({ ...r, w: r.width, h: r.height })) issues.push(`surface ${describe(s)} ${fmt(r)} outside the safe area`);
  }

  // The layer the person is using: an open menu, else an autocomplete list, else the modal dialog,
  // else an open drawer, else the page. What lies under it is meant to be covered.
  const popup = [...document.querySelectorAll(".ac-popup")].find((x) => shown(x));
  const drawer = [...document.querySelectorAll(".workspace.nav-open .folders, .module.nav-open .side-pane, .todo-detail:not(.hidden)")].find((x) => shown(x) && /fixed|absolute/.test(getComputedStyle(x).position))
    || [...document.querySelectorAll(".workspace.with-myday .my-day")].find((x) => shown(x) && /fixed|absolute/.test(getComputedStyle(x).position));
  const layers = menuOpen ? [menuEl] : popup ? [popup] : modal ? [modal] : drawer ? [drawer, document.querySelector(".topbar")] : [document.body];
  if (!ignoreToasts) layers.push(document.getElementById("toasts"));
  const SEL = "button, a[href], input:not([type=hidden]), select, textarea, [contenteditable=true], [role=button], .msg, li[class]";
  const controls = [...new Set(layers.flatMap((l) => (l ? [...l.querySelectorAll(SEL)] : [])))]
    .filter((el) => shown(el) && !el.closest(".sc-chip, #splash" + (ignoreToasts ? ", .toast-host" : "")) && !el.disabled
      && !(layers[0] === document.body && modal && !modal.contains(el)));
  const boxes = [];
  for (const el of controls) {
    const r = clipped(el);
    if (r.w < 2 || r.h < 2) continue; // scrolled out of view
    if (outside(r)) { issues.push(`${describe(el)} ${fmt(r)} outside the safe area`); continue; }
    // Hit test at the centre of the visible part.
    const cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
    if (cx < 0 || cy < 0 || cx >= vw || cy >= vh) continue;
    const hit = document.elementFromPoint(cx, cy);
    const sameLabel = hit && el.closest("label") && el.closest("label") === hit.closest("label");
    // List rows may pass under floating buttons and toasts (they scroll); buttons must not.
    const row = el.matches(".msg, li, .msg input, li input") || !!el.closest(".msg");
    const floating = hit && hit.closest(".fab, .toast-host, .draft-chips");
    if (hit && hit !== el && !el.contains(hit) && !hit.contains(el) && !sameLabel && !hit.closest(".sc-chip") && !(ignoreToasts && hit.closest(".toast-host")) && !(row && floating)) {
      issues.push(`${describe(el)} ${fmt(r)} covered by ${describe(hit)}`);
    }
    if (!/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) && !el.isContentEditable && !el.matches(".msg, li") && !el.querySelector(SEL)) boxes.push({ el, r });
  }
  // Buttons drawn on top of each other.
  for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
    const a = boxes[i], b = boxes[j];
    if (a.el.contains(b.el) || b.el.contains(a.el)) continue;
    const w = Math.min(a.r.right, b.r.right) - Math.max(a.r.left, b.r.left), h = Math.min(a.r.bottom, b.r.bottom) - Math.max(a.r.top, b.r.top);
    if (w > 2 && h > 2 && w * h > 0.25 * Math.min(a.r.w * a.r.h, b.r.w * b.r.h)) issues.push(`${describe(a.el)} overlaps ${describe(b.el)}`);
  }
  return [...new Set(issues)];
}

// ------------------------------------------------------------------ states to open
const menuOpen = (page) => page.waitForFunction(() => !document.getElementById("ctxMenu").classList.contains("hidden"), null, { timeout: 3000 });
async function closeAll(page) {
  await page.evaluate(() => {
    try { menu.close(); } catch {}
    try { if (state.compose) compose.close(true); } catch {}
    for (const d of document.querySelectorAll("dialog[open]")) d.close();
    document.querySelectorAll(".nav-open").forEach((n) => n.classList.remove("nav-open"));
    document.querySelectorAll("#toasts .toast").forEach((x) => x.remove());
    if (state.module !== "mail") shell.setModule("mail");
    if (prefs.get("navCollapsed", false)) { prefs.set("navCollapsed", false); applyTheme(); }
    if (document.getElementById("workspace").classList.contains("show-reading")) reading.close();
  });
  await page.waitForTimeout(150);
}
async function openFirstMessage(page) {
  await page.waitForSelector("#messageList .msg", { timeout: 15000 });
  await page.locator("#messageList .msg").first().click();
  await page.waitForFunction(() => !!state.openDetail, null, { timeout: 10000 });
  await page.waitForTimeout(400);
}

const SETTINGS_TABS = ["account", "signatures", "autoreply", "general", "notifications", "layout", "compose", "templates", "quicksteps", "categories", "swipe", "junk", "rules", "senders", "ai", "calendar", "importexport", "about"];

/** Each state: [name, open(page), { keyboard: focus selector to raise the keyboard }] */
const STATES = [
  ["mail list", async () => {}],
  ["folder drawer", async (p) => { await p.click("#navToggle"); await p.waitForTimeout(250); }],
  ["reading pane", async (p) => { await openFirstMessage(p); }],
  ["message menu", async (p) => { await openFirstMessage(p); await p.click("#rdMore"); await menuOpen(p); }],
  ["list context menu", async (p) => { await p.waitForSelector("#messageList .msg"); await p.locator("#messageList .msg").first().dispatchEvent("contextmenu", { clientX: 40, clientY: 200 }); await menuOpen(p); }],
  ["account menu", async (p) => { await p.click("#accountAvatar"); await menuOpen(p); }],
  ["toast", async (p) => { await p.evaluate(() => toast("A message that is long enough to wrap onto a second line on a phone screen", { action: "Undo" })); }, { toasts: true }],
  ["compose", async (p) => { await p.evaluate(() => compose.open({ mode: "new" })); await p.waitForSelector("#composeDialog[open]"); }],
  ["compose + keyboard (To)", async (p) => { await p.evaluate(() => compose.open({ mode: "new" })); await p.waitForSelector("#composeDialog[open]"); }, { keyboard: "#cTo" }],
  ["compose + keyboard (body)", async (p) => { await p.evaluate(() => compose.open({ mode: "new", html: "<p>" + "Line of text. ".repeat(80) + "</p>" })); await p.waitForSelector("#composeDialog[open]"); }, { keyboard: "#cEditor" }],
  ["compose autocomplete", async (p) => { await p.evaluate(() => compose.open({ mode: "new" })); await p.waitForSelector("#composeDialog[open]"); await p.click("#cTo"); await p.keyboard.type("j"); await p.waitForTimeout(400); }, { keyboard: "#cTo", keepFocus: true }],
  ["compose send menu", async (p) => { await p.evaluate(() => compose.open({ mode: "new" })); await p.waitForSelector("#composeDialog[open]"); await p.click("#cSendMenu"); await menuOpen(p); }],
  ["compose close menu", async (p) => { await p.evaluate(() => compose.open({ mode: "new", to: "x@example.org" })); await p.waitForSelector("#composeDialog[open]"); await p.evaluate(() => { state.compose.dirty = true; }); await p.click("#cClose"); await menuOpen(p); }],
  ["compose insert tab", async (p) => { await p.evaluate(() => compose.open({ mode: "new" })); await p.waitForSelector("#composeDialog[open]"); await p.click(".compose-tabs [data-ctab=insert]"); }],
  ["compose options tab", async (p) => { await p.evaluate(() => compose.open({ mode: "new" })); await p.waitForSelector("#composeDialog[open]"); await p.click(".compose-tabs [data-ctab=options]"); }],
  ["reply (quoted)", async (p) => { await openFirstMessage(p); await p.evaluate(() => compose.open({ mode: "reply", detail: state.openDetail })); await p.waitForSelector("#composeDialog[open]"); }],
  ...SETTINGS_TABS.map((tab) => [`settings: ${tab}`, async (p) => { await p.evaluate((tab) => settingsUI.open(tab), tab); await p.waitForSelector("#settingsDialog[open]"); await p.waitForTimeout(250); }]),
  ["settings + keyboard (signature)", async (p) => { await p.evaluate(() => settingsUI.open("account")); await p.waitForSelector("#settingsDialog[open]"); }, { keyboard: "#stBody input, #stBody textarea" }],
  ["move dialog", async (p) => { await openFirstMessage(p); await p.evaluate(() => actions.pickFolder([state.openDetail])); await p.waitForSelector("#moveDialog[open]"); }],
  ["move dialog + keyboard", async (p) => { await openFirstMessage(p); await p.evaluate(() => actions.pickFolder([state.openDetail])); await p.waitForSelector("#moveDialog[open]"); }, { keyboard: "#mvSearch" }],
  ["rule editor", async (p) => { await p.evaluate(() => settingsUI.editRule(null)); await p.waitForSelector("#ruleDialog[open]"); }],
  ["new folder form", async (p) => { p.evaluate(() => mailplus.createFolder(null)); await p.waitForSelector("#formDialog[open]"); }, { keyboard: "#formDialog input" }],
  ["shortcuts", async (p) => { await p.evaluate(() => $("shortcutsDialog").showModal()); }],
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
  ["to do side pane", async (p) => { await p.evaluate(() => shell.setModule("todo")); await p.waitForTimeout(400); await p.locator("#modTodo .mobile-only[title=Lists]").first().click(); await p.waitForTimeout(250); }],
];

// ------------------------------------------------------------------ runner
async function run(browser, device, mode) {
  const { w, h, bars, kb } = device;
  const shell = mode === "shell";
  const vp = shell ? { width: w - bars.left - bars.right, height: h - bars.top - bars.bottom } : { width: w, height: h };
  const insets = shell ? { top: 0, right: 0, bottom: 0, left: 0 } : bars;
  const ctx = await browser.newContext({ viewport: vp, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  await signIn(ctx, account, { notifAsked: true });
  await ctx.addInitScript((i) => {
    if (!i.top && !i.right && !i.bottom && !i.left) return;
    const css = `:root{--sa-top:${i.top}px!important;--sa-right:${i.right}px!important;--sa-bottom:${i.bottom}px!important;--sa-left:${i.left}px!important}`;
    const add = () => { const s = document.createElement("style"); s.id = "sa-sim"; s.textContent = css; (document.head || document.documentElement).appendChild(s); };
    if (document.head) add(); else document.addEventListener("DOMContentLoaded", add);
  }, insets);
  const page = await ctx.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("dialog", (d) => d.dismiss().catch(() => {}));
  await page.goto(BASE);
  await waitSignedIn(page);
  await page.waitForTimeout(500);
  const label = `${device.name} ${mode}`;
  for (const [name, open, opts = {}] of STATES) {
    if (ONLY && !name.includes(ONLY)) continue;
    await closeAll(page);
    await page.setViewportSize(vp);
    await page.evaluate(() => document.getElementById("sa-kb")?.remove());
    let failed = null;
    try { await open(page); } catch (e) { failed = e.message.split("\n")[0]; }
    if (failed) { t.ok(false, `${label} / ${name}: could not open (${failed})`); continue; }
    let safeInsets = { ...insets };
    if (opts.keyboard) {
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
    const issues = await page.evaluate(audit, { safe, ignoreToasts: !opts.toasts });
    for (const i of issues) t.ok(false, `${label} / ${name}: ${i}`);
    if (!issues.length) t.ok(true, `${label} / ${name}`);
    if (SHOTS) await page.screenshot({ path: path.join(SHOTS, `${device.name}-${mode}-${name.replace(/[^a-z0-9]+/gi, "_")}.png`) });
  }
  t.ok(errors.length === 0, `${label}: page errors: ${errors.join(" | ")}`);
  await ctx.close();
}

const browser = await chromium.launch({ executablePath: chromiumPath() });
try {
  const modes = (process.env.MODES || "shell,cover").split(",");
  for (const d of DEVICES) if (!process.env.DEVICE || d.name.includes(process.env.DEVICE)) for (const mode of modes) await run(browser, d, mode);
} finally {
  await browser.close();
  t.done();
}
