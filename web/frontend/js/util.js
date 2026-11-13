// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Small DOM + formatting helpers shared by the screens.

const BLANK = 'data:image/gif;base64,R0lGODlhAQABAAAAACw=';
export function h(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  if (tag === 'img' && attrs && !attrs.src) { attrs = { ...attrs, src: BLANK }; }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'class') e.className = v;
    else if (k === 'html') e.innerHTML = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
    else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined && v !== false) e.setAttribute(k, v === true ? '' : v);
  }
  if (tag === 'img' && e.getAttribute('src') === BLANK) e.classList.add('noimg');
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : document.createTextNode(String(k)));
  return e;
}
export function toast(msg) {
  const t = document.getElementById('toast'); if (!t) return;
  t.textContent = msg; t.classList.add('show');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.remove('show'), 2200);
}
const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
export const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ESC[c]);
/** Clock time in the household's format (Settings > General > Time format, server-side):
 *  '12' = 4:30 PM (default), '24' = 16:30, 'auto' = the browser's own. */
export function timeOpts(fmt) {
  if (fmt === '24') return { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' };
  if (fmt === 'auto') return { hour: '2-digit', minute: '2-digit' };
  return { hour: 'numeric', minute: '2-digit', hour12: true };
}
export const fmtTime = (sec) => new Date(sec * 1000).toLocaleTimeString(fmtTime.fmt === 'auto' ? [] : (fmtTime.fmt === '24' ? [] : 'en-US'), timeOpts(fmtTime.fmt));
fmtTime.fmt = '12';           // set from the server's preference (app.js)
export const fmtDate = (sec) => new Date(sec * 1000).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
export function fmtDur(s) {
  s = Math.max(0, Math.floor(s || 0));
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  return (hh ? hh + ':' + String(mm).padStart(2, '0') : String(mm)) + ':' + String(ss).padStart(2, '0');
}
export const nowSec = () => Math.floor(Date.now() / 1000);
export const clear = (n) => { n.innerHTML = ''; return n; };
export const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

/**
 * Spatial focus for keyboard / remote: move to the nearest .focusable in `root` in the
 * arrow's direction. Returns false when there is nothing that way (caller may go to the nav rail).
 */
export function spatialMove(root, key) {
  const dir = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[key];
  if (!dir) return false;
  const all = [...root.querySelectorAll('.focusable')].filter((e) => !e.disabled && e.offsetParent !== null);
  const ae = document.activeElement;
  if (!ae || !root.contains(ae) || !ae.classList.contains('focusable')) { if (all[0]) { all[0].focus(); return true; } return false; }
  const a = ae.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  let best = null, bestD = Infinity;
  for (const e of all) {
    if (e === ae) continue;
    const r = e.getBoundingClientRect(); if (!r.width && !r.height) continue;
    const ex = r.left + r.width / 2, ey = r.top + r.height / 2;
    const dx = ex - ax, dy = ey - ay;
    const primary = dir[0] ? dx * dir[0] : dy * dir[1];
    if (primary <= 2) continue;
    // stay in the same row/column when possible: overlap on the cross axis is free
    const overlap = dir[0] ? (r.bottom > a.top && r.top < a.bottom) : (r.right > a.left && r.left < a.right);
    const cross = overlap ? 0 : Math.abs(dir[0] ? dy : dx);
    const d = primary + cross * 3;
    if (d < bestD) { bestD = d; best = e; }
  }
  if (!best) return false;
  best.focus({ preventScroll: false }); best.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  return true;
}

/** Item menu on right-click and on a long press (touch); the click after a long press is
 *  swallowed so the item does not also open. */
export function onItemMenu(el, fn) {
  let t = null, fired = 0, x0 = 0, y0 = 0;
  const fire = () => { if (Date.now() - fired < 800) return; fired = Date.now(); fn(); };
  el.addEventListener('contextmenu', (e) => { e.preventDefault(); fire(); });
  el.addEventListener('touchstart', (e) => {
    const p = e.touches[0]; x0 = p.clientX; y0 = p.clientY;
    clearTimeout(t); t = setTimeout(() => { t = null; fire(); }, 550);
  }, { passive: true });
  el.addEventListener('touchmove', (e) => { const p = e.touches[0]; if (t && Math.hypot(p.clientX - x0, p.clientY - y0) > 10) { clearTimeout(t); t = null; } }, { passive: true });
  el.addEventListener('touchend', () => { clearTimeout(t); t = null; });
  el.addEventListener('click', (e) => { if (Date.now() - fired < 800) { e.preventDefault(); e.stopImmediatePropagation(); } }, true);
  el.classList.add('has-menu');
  // and a visible "⋯" button (mouse / touch users find the menu without knowing the gestures)
  const more = document.createElement('span');
  more.className = 'item-more'; more.setAttribute('role', 'button'); more.setAttribute('aria-label', 'Menu'); more.title = 'Menu'; more.textContent = '⋯';
  more.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); fired = 0; fire(); });
  el.append(more);
}

/** Simple keyboard-navigable modal menu. items: [{label, action}] */
export function menu(title, items) {
  const back = h('div', { class: 'modal-back' });
  const box = h('div', { class: 'modal', role: 'dialog' }, h('div', { class: 'modal-title' }, title));
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey, true); if (prev && prev.focus) prev.focus(); };
  const btns = items.map((it) => h('button', { class: 'btn secondary focusable menu-item', onclick: () => { close(); it.action(); } }, it.label));
  btns.push(h('button', { class: 'btn secondary focusable menu-item', onclick: close }, 'Close'));
  box.append(...btns); back.append(box); document.body.append(back);
  const prev = document.activeElement;
  back.addEventListener('click', (e) => { if (e.target === back) close(); });
  let i = 0; btns[0].focus();
  function onKey(e) {
    e.stopPropagation();
    if (e.key === 'Escape' || e.key === 'Backspace') { e.preventDefault(); close(); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); i = (i + 1) % btns.length; btns[i].focus(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); i = (i - 1 + btns.length) % btns.length; btns[i].focus(); }
    else if (e.key === 'Enter') { e.preventDefault(); document.activeElement.click(); }
  }
  document.addEventListener('keydown', onKey, true);
  return close;
}
