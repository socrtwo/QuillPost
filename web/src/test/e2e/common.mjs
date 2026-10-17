// Shared helpers for the browser tests in this folder (embed.mjs, layout.mjs).
import { createRequire } from "node:module";
import { execSync } from "node:child_process";

const require = createRequire(import.meta.url);

/** Playwright from the project, or else from the global npm modules. */
export function loadPlaywright() {
  try { return require("playwright"); } catch {}
  const root = execSync("npm root -g").toString().trim();
  return require(root + "/playwright");
}

/** Chromium: $CHROMIUM, else the pre-installed Playwright browser, else Playwright's default. */
export function chromiumPath() {
  return process.env.CHROMIUM || "/opt/pw-browsers/chromium";
}

/** The demo mailbox account (`./gradlew demo`, see README); ports from DEMO_IMAP_PORT / DEMO_SMTP_PORT. */
export function demoAccount(imapPort = Number(process.env.DEMO_IMAP_PORT || 3143), smtpPort = Number(process.env.DEMO_SMTP_PORT || 3025)) {
  return {
    displayName: "Demo User", email: "demo@quillbox.test",
    incomingHost: "127.0.0.1", incomingPort: imapPort, protocol: "IMAP", incomingSecurity: "NONE",
    smtpHost: "127.0.0.1", smtpPort, smtpSecurity: "NONE",
    username: "demo@quillbox.test", password: "demo",
  };
}

/** Adds an init script to [context]: a remembered demo account (signs in on load) plus optional prefs ("qb." keys). */
export async function signIn(context, account, prefs = {}) {
  await context.addInitScript(({ account, prefs }) => {
    try {
      if (!localStorage.getItem("qb.accounts")) {
        localStorage.setItem("qb.accounts", JSON.stringify([{ account, remember: true }]));
        localStorage.setItem("qb.lastActive", account.email);
        for (const [k, v] of Object.entries(prefs)) localStorage.setItem("qb." + k, JSON.stringify(v));
      }
    } catch {}
  }, { account, prefs });
}

export async function waitSignedIn(page) {
  await page.waitForFunction("typeof state !== 'undefined' && !!state.token && !!state.settings", null, { timeout: 30000 });
  await page.waitForFunction("!document.getElementById('splash')", null, { timeout: 10000 }).catch(() => {});
}

/** Collects failures instead of stopping at the first one. */
export function checker(name) {
  const failures = [];
  let passed = 0;
  return {
    ok(cond, msg) { if (cond) passed++; else { failures.push(msg); console.log("  FAIL " + msg); } },
    eq(a, b, msg) { this.ok(a === b, `${msg} (expected ${JSON.stringify(b)}, got ${JSON.stringify(a)})`); },
    done() {
      console.log(`${name}: ${passed} passed, ${failures.length} failed`);
      if (failures.length) process.exitCode = 1;
      return failures;
    },
  };
}
