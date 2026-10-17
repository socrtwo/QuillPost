/* Safe area helpers (see safearea.css). Loaded after app.js and mailplus.js.
 *
 * - --kb: where the on-screen keyboard covers the page without the browser shrinking it (iOS
 *   Safari and WKWebView, Chrome without interactive-widget support), from window.visualViewport.
 *   The Android app resizes the WebView itself, so there --kb stays 0.
 * - safeArea(): the rectangle of the window that is clear of system bars, cut-outs and keyboard.
 * - Pop-up menus are kept inside that rectangle (menu.openAt only knows the window edges).
 */
"use strict";

const safeArea = (() => {
  let probe = null;
  /** { top, right, bottom, left } in CSS pixels, measured from the --sa-* variables. */
  function insets() {
    if (!probe) {
      probe = document.createElement("div");
      probe.setAttribute("aria-hidden", "true");
      probe.style.cssText = "position:fixed;visibility:hidden;pointer-events:none;top:var(--sa-top);right:var(--sa-right);bottom:var(--sa-b);left:var(--sa-left)";
      document.body.appendChild(probe);
    }
    const r = probe.getBoundingClientRect();
    return { top: Math.max(0, r.top), left: Math.max(0, r.left), right: Math.max(0, innerWidth - r.right), bottom: Math.max(0, innerHeight - r.bottom) };
  }
  /** The usable part of the window: { top, left, right, bottom } as coordinates. */
  function rect() {
    const i = insets();
    return { top: i.top, left: i.left, right: innerWidth - i.right, bottom: innerHeight - i.bottom };
  }

  // ------------------------------------------------------------------ keyboard (visual viewport)
  const vv = window.visualViewport;
  function updateKeyboard() {
    if (!vv) return;
    const covered = Math.max(0, Math.round(innerHeight - (vv.height + vv.offsetTop)));
    // Pinch zoom also shrinks the visual viewport; only count it as a keyboard while a field has focus.
    const a = document.activeElement;
    const typing = a && (a.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName));
    document.documentElement.style.setProperty("--kb", (typing && vv.scale <= 1.01 && covered > 80 ? covered : 0) + "px");
  }
  if (vv) {
    vv.addEventListener("resize", updateKeyboard);
    vv.addEventListener("scroll", updateKeyboard);
    addEventListener("focusin", () => setTimeout(updateKeyboard, 50));
    addEventListener("focusout", () => setTimeout(updateKeyboard, 50));
  }

  // ------------------------------------------------------------------ menus
  if (typeof menu !== "undefined" && menu && typeof menu.openAt === "function") {
    const openAt = menu.openAt;
    menu.openAt = function (...args) {
      const r = openAt.apply(this, args);
      try {
        const m = this.node();
        if (!m.classList.contains("hidden")) {
          const s = rect();
          m.style.maxHeight = Math.max(120, Math.min(560, s.bottom - s.top - 16)) + "px";
          const w = m.offsetWidth, h = m.offsetHeight;
          const left = Math.min(Math.max(parseFloat(m.style.left) || 0, s.left + 8), s.right - w - 8);
          let top = parseFloat(m.style.top) || 0;
          if (top + h > s.bottom - 8) {
            // Like menu.openAt: above the anchor when it does not fit below, else as low as fits.
            const anchor = args[3];
            top = anchor && anchor.top - h - 4 >= s.top + 8 ? anchor.top - h - 4 : s.bottom - h - 8;
          }
          top = Math.max(top, s.top + 8);
          m.style.left = Math.max(s.left + 8, left) + "px"; m.style.top = top + "px";
          this.x = m.style.left; this.y = m.style.top;
        }
      } catch {}
      return r;
    };
  }

  return { insets, rect, updateKeyboard };
})();
window.safeArea = safeArea;
