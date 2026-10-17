/**
 * Quill suite scroll cues (see scrollcue.css). Framework-free; one instance per page.
 *
 *   import { installScrollCues } from './scrollcue.js';
 *   installScrollCues({ selector: '.menu, .dialog-body, .list', label: 'More' });
 *
 * Every element matching `selector` gets the .sc-scroll class (thin bar + edge shadows). While such an
 * element is visible and can scroll further down, a "More" chip floats at its bottom edge; activating it
 * scrolls by most of a page. The chip lives in <body> (position: fixed), so container layouts are never
 * changed; for a container inside a modal <dialog> or an open popover the chip lives in that element
 * instead, because anything outside the top layer is drawn under it and made inert. Containers are
 * found again whenever the DOM changes.
 */
const ARROW = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7.4 8.6 12 13.2l4.6-4.6L18 10l-6 6-6-6z"/></svg>';

export function installScrollCues(opts = {}) {
  const selector = opts.selector || '[data-scrollcue]';
  const label = opts.label || 'More';
  const minHeight = opts.minHeight ?? 64;
  const chips = new Map(); // container -> chip
  let queued = false;

  // Only a container that can actually scroll (overflow-y auto/scroll) gets a chip: an element with
  // overflow hidden or visible may report a larger scrollHeight, but scrolling it does nothing.
  const scrollable = (el) => el.scrollHeight - el.clientHeight > 6 && /(auto|scroll|overlay)/.test(getComputedStyle(el).overflowY);
  const atBottom = (el) => el.scrollTop + el.clientHeight >= el.scrollHeight - 4;
  const visible = (el) => el.isConnected && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';

  function chipFor(el) {
    let chip = chips.get(el);
    if (chip) return chip;
    chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'sc-chip sc-hidden';
    chip.tabIndex = -1; // keyboard users scroll with the keys; the chip is a pointer/touch affordance
    chip.setAttribute('aria-hidden', 'true');
    chip.innerHTML = `${ARROW}<span>${label}</span>`;
    // Keep menus and dialogs open: the chip is not "outside" them.
    for (const ev of ['mousedown', 'pointerdown', 'touchstart']) chip.addEventListener(ev, (e) => e.stopPropagation(), { passive: ev === 'touchstart' });
    chip.addEventListener('click', (e) => {
      e.preventDefault(); e.stopPropagation();
      el.scrollBy({ top: Math.max(40, el.clientHeight * 0.8), behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    });
    document.body.appendChild(chip);
    chips.set(el, chip);
    return chip;
  }

  // The top-layer element (modal dialog, open popover) that contains el, else <body>.
  function hostFor(el) {
    try { return el.closest('dialog:modal, :popover-open') || document.body; }
    catch { try { return el.closest('dialog[open]') || document.body; } catch { return document.body; } }
  }

  // The part of el that is really on screen: clipped by every ancestor that clips its overflow
  // (a scrolled dialog, a pane) and by the window.
  function visibleRect(el) {
    const r = el.getBoundingClientRect();
    let top = Math.max(r.top, 0), bottom = Math.min(r.bottom, innerHeight);
    let left = Math.max(r.left, 0), right = Math.min(r.right, innerWidth);
    for (let n = el.parentElement; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.overflowY !== 'visible' || cs.overflowX !== 'visible') {
        const p = n.getBoundingClientRect();
        top = Math.max(top, p.top); bottom = Math.min(bottom, p.bottom);
        left = Math.max(left, p.left); right = Math.min(right, p.right);
      }
      if (cs.position === 'fixed') break; // its ancestors do not clip it
    }
    return { top, bottom, left, right, height: bottom - top, width: right - left };
  }

  function place(el) {
    const chip = chipFor(el);
    const host = hostFor(el);
    if (chip.parentNode !== host) host.appendChild(chip);
    const r = visibleRect(el);
    const show = visible(el) && el.getBoundingClientRect().height >= minHeight && r.height >= 40 && r.width > 0
      && scrollable(el) && !atBottom(el);
    chip.classList.toggle('sc-hidden', !show);
    if (!show) return;
    // Stay inside both the visible part of the container and the window (system bars are handled
    // by the app's own layout).
    const bottom = r.bottom - 8;
    chip.style.left = `${Math.round(r.left + r.width / 2)}px`;
    chip.style.top = `${Math.round(bottom - chip.offsetHeight)}px`;
    // Stacking: just above the container's own stacking level.
    chip.style.zIndex = String(Math.max(1, zOf(el) + 1));
  }

  function zOf(el) {
    let z = 0;
    for (let n = el; n && n !== document.body; n = n.parentElement) {
      const v = parseInt(getComputedStyle(n).zIndex, 10);
      if (!Number.isNaN(v)) z = Math.max(z, v);
    }
    return z;
  }

  function scan() {
    queued = false;
    const found = new Set(document.querySelectorAll(selector));
    for (const el of found) {
      if (!el.classList.contains('sc-scroll')) {
        el.classList.add('sc-scroll');
        el.addEventListener('scroll', schedule, { passive: true });
        resize.observe(el);
      }
      place(el);
    }
    for (const [el, chip] of chips) {
      if (!found.has(el) || !el.isConnected) { chip.remove(); chips.delete(el); resize.unobserve(el); }
    }
  }
  function schedule() { if (!queued) { queued = true; requestAnimationFrame(scan); } }

  const resize = new ResizeObserver(schedule);
  // Ignore the chips' own position/visibility updates, or every placement would schedule another.
  const isChip = (n) => n instanceof Element && n.classList.contains('sc-chip');
  const relevant = (m) => (m.type === 'attributes' ? !isChip(m.target) : [...m.addedNodes, ...m.removedNodes].some((n) => !isChip(n)));
  new MutationObserver((list) => { if (list.some(relevant)) schedule(); })
    .observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'hidden', 'open'] });
  addEventListener('resize', schedule, { passive: true });
  addEventListener('scroll', schedule, { passive: true, capture: true });
  // Wider scrollbar while dragging it (desktop).
  addEventListener('pointerdown', (e) => { const el = e.target instanceof Element && e.target.closest('.sc-scroll'); if (el) { el.classList.add('sc-dragging'); addEventListener('pointerup', () => el.classList.remove('sc-dragging'), { once: true }); } }, { capture: true });
  schedule();
  return { refresh: schedule };
}
