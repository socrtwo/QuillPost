// Generates the raster and packaged icons from the suite's QuillPost icon (branding/QuillPost/,
// copied from the Quill suite icon family). Run from the repository root:
//
//   node scripts/gen-icons.mjs
//
// Needs Playwright with Chromium (CHROMIUM=/path/to/chrome to choose the browser) and Python 3
// with Pillow (for the .ico and .icns containers). Writes:
//   ios/QuillPost/Assets.xcassets/AppIcon.appiconset/icon-1024.png   full-bleed, no alpha (iOS masks it)
//   ios/QuillPost/Assets.xcassets/LaunchLogo.imageset/launch-logo.svg
//   desktop/icons/QuillPost.png / .ico / .icns                       installers (Compose nativeDistributions)
//   desktop/src/main/resources/tray.svg                              window and tray icon
//   web/src/main/resources/web/favicon.svg, apple-touch-icon.png     browser tab, home-screen icon
// The Android launcher and splash icons are vector drawables written by hand from the same SVGs
// (app/src/main/res/drawable/ic_launcher_foreground.xml, ic_splash.xml).
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { execSync } from "node:child_process";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const src = (f) => path.join(root, "branding/QuillPost", f);
const out = (f) => { const p = path.join(root, f); fs.mkdirSync(path.dirname(p), { recursive: true }); return p; };

const require = createRequire(import.meta.url);
let playwright;
try { playwright = require("playwright"); } catch { playwright = require(execSync("npm root -g").toString().trim() + "/playwright"); }

const tile = fs.readFileSync(src("icon.svg"), "utf8");
// iOS and Apple touch icons are masked by the system: draw the tile without its rounded corners.
const fullBleed = tile.replace(/(<rect width="512" height="512") rx="116"/, "$1");
if (fullBleed === tile) throw new Error("icon.svg changed shape: could not remove the corner radius");

const browser = await playwright.chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : fs.existsSync("/opt/pw-browsers/chromium") ? { executablePath: "/opt/pw-browsers/chromium" } : {});
const page = await browser.newPage({ deviceScaleFactor: 1 });
async function render(svg, size, file, { opaque = false } = {}) {
  const sized = svg.replace(/width="512" height="512"/, `width="${size}" height="${size}"`);
  await page.setViewportSize({ width: size, height: size });
  await page.setContent(`<!doctype html><html><head><style>html,body{margin:0;background:${opaque ? "#fff" : "transparent"}}svg{display:block}</style></head><body>${sized}</body></html>`);
  await page.screenshot({ path: file, omitBackground: !opaque, clip: { x: 0, y: 0, width: size, height: size } });
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "qp-icons-"));
await render(fullBleed, 1024, out("ios/QuillPost/Assets.xcassets/AppIcon.appiconset/icon-1024.png"), { opaque: true });
await render(fullBleed, 180, out("web/src/main/resources/web/apple-touch-icon.png"), { opaque: true });
await render(tile, 512, out("desktop/icons/QuillPost.png"));
for (const s of [16, 24, 32, 48, 64, 128, 256, 512, 1024]) await render(tile, s, path.join(tmp, `t${s}.png`));
await browser.close();

fs.writeFileSync(out("web/src/main/resources/web/favicon.svg"), tile);
fs.writeFileSync(out("desktop/src/main/resources/tray.svg"), tile);
fs.writeFileSync(out("ios/QuillPost/Assets.xcassets/LaunchLogo.imageset/launch-logo.svg"), tile.replace(/width="512" height="512"/, 'width="160" height="160"'));

// .ico (Windows) and .icns (macOS) from the renders; the iOS icon must not have an alpha channel.
execFileSync("python3", ["-I", "-c", `
import sys
from PIL import Image
tmp, ico, icns, ios = sys.argv[1:5]
big = Image.open(f"{tmp}/t256.png").convert("RGBA")
big.save(ico, format="ICO", sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
Image.open(f"{tmp}/t1024.png").convert("RGBA").save(icns, format="ICNS")
Image.open(ios).convert("RGB").save(ios, format="PNG", optimize=True)
`, tmp, out("desktop/icons/QuillPost.ico"), out("desktop/icons/QuillPost.icns"), out("ios/QuillPost/Assets.xcassets/AppIcon.appiconset/icon-1024.png")], { stdio: "inherit" });
fs.rmSync(tmp, { recursive: true, force: true });
console.log("icons written");
