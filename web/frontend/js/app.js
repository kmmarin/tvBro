// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// tvBro web SPA: left nav + screens (TV, Movies, Shows, My list, Search, Settings).
// Matches the Android app's structure as the web allows. Dark theme only.

import { api, hasToken, setToken, cacheGet, cacheSet, myList, inMyList, toggleMyList, getResume } from './api.js';
import { openPlayer, playerIsOpen } from './player.js';
import { h, toast, fmtTime, nowSec as now, clear } from './util.js';
import { tvScreen } from './tv.js';
import { playLive, watchProg, resumeLastChannel, forgetCurrent } from './playback.js';
import { prefs } from './prefs.js';
import { vodBrowse, vodDetail, rememberItem } from './vod.js';
import { settingsScreen, applyCaptionPrefs } from './settings.js';
import { myListScreen } from './mylist.js';
import { searchScreen } from './search.js';
import { recordingsScreen } from './recordings.js';
import { editorScreen } from './editor.js';
import { startReminders } from './remind.js';
import * as apiMod from './api.js';
import { useStore } from './reminders.js';
import { uiLoad, uiSave } from './uistate.js';
import { ensureSignedIn } from './signin.js';


const main = document.getElementById('main');
main.addEventListener('scroll', () => {
  const hs = location.hash.replace(/^#/, '') || 'live';
  const sc = Object.assign({}, uiLoad().scroll || {}); sc[hs] = main.scrollTop;
  const keys = Object.keys(sc); if (keys.length > 30) delete sc[keys[0]];
  uiSave({ scroll: sc });
}, { passive: true });
let signedIn = false;
let active = null;   // current screen controller { onKey?, onKeyUp?, focusIn?, destroy? }

// ---------- router ----------
const screens = {};
function setScreen(name, arg) {
  for (const b of document.querySelectorAll('.nav-item')) b.classList.toggle('active', b.dataset.screen === name);
  location.hash = name + (arg ? ':' + arg : '');
}
window.addEventListener('hashchange', route);
let restoreTimer = null;
function route() {
  const hs = location.hash.replace(/^#/, '') || 'live'; const ci = hs.indexOf(':');
  uiSave({ hash: hs });
  // the page scroll of each section comes back too (best effort: once its content is there)
  clearTimeout(restoreTimer);
  const sy = (uiLoad().scroll || {})[hs];
  if (sy) restoreTimer = setTimeout(() => { if ((location.hash.replace(/^#/, '') || 'live') === hs) main.scrollTop = sy; }, 700);
  const name = ci < 0 ? hs : hs.slice(0, ci), arg = ci < 0 ? '' : hs.slice(ci + 1);
  for (const b of document.querySelectorAll('.nav-item')) b.classList.toggle('active', b.dataset.screen === (name === 'detail' ? (arg.startsWith('series') ? 'series' : 'movies') : name === 'play' || name === 'editor' ? 'live' : name));
  if (active && active.destroy) { try { active.destroy(); } catch {} }
  active = null; lastFocus = null;
  if (!signedIn && name !== 'settings') { active = screens.settings(); return; }
  const r = (screens[name] || screens.live)(arg);
  if (r && typeof r.then !== 'function') active = r;
}
document.querySelectorAll('.nav-item').forEach((b) => b.addEventListener('click', () => setScreen(b.dataset.screen)));

// left nav rail: Up/Down move, Right enters the screen
const nav = {
  focusActive() { const a = document.querySelector('.nav-item.active') || document.querySelector('.nav-item'); if (a) a.focus(); },
};
// the last item focused in the current screen: Right from the nav rail (or an arrow after the focus
// was lost) comes back to it instead of jumping to the top
let lastFocus = null;
main.addEventListener('focusin', (e) => { if (!(active && active.virtualFocus)) lastFocus = e.target; });
const usable = (el) => el && el.isConnected && main.contains(el) && el.offsetParent !== null && !el.disabled;
function enterScreen() {
  if (!active) return;
  if (!active.virtualFocus && usable(lastFocus)) { lastFocus.focus({ preventScroll: true }); lastFocus.scrollIntoView({ block: 'nearest', inline: 'nearest' }); return; }
  if (active.focusIn) active.focusIn();
  // an empty screen (nothing to focus): the focus stays in the side menu instead of getting lost
  if (!active.virtualFocus && !main.contains(document.activeElement)) nav.focusActive();
}
document.addEventListener('keydown', (e) => {
  if (playerIsOpen() || document.querySelector('.modal-back')) return;
  const ae = document.activeElement;
  if (ae && ae.classList && ae.classList.contains('nav-item')) {
    const items = [...document.querySelectorAll('.nav-item')]; const i = items.indexOf(ae);
    if (e.key === 'ArrowDown') { e.preventDefault(); items[Math.min(items.length - 1, i + 1)].focus(); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); items[Math.max(0, i - 1)].focus(); return; }
    if (e.key === 'ArrowRight' || e.key === 'Enter') { e.preventDefault(); if (!ae.classList.contains('active')) ae.click();
      else { ae.blur(); enterScreen(); } return; }
    return;
  }
  // nothing focused (a click on empty space, a closed dialog): an arrow brings the focus back where it was
  if ((!ae || ae === document.body) && e.key.startsWith('Arrow') && active && !active.virtualFocus && usable(lastFocus)) {
    e.preventDefault(); lastFocus.focus(); return;
  }
  if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA')) {
    // a text field keeps its keys, except the ones that leave it (no trap on a remote): Up / Down,
    // Left at the start of the text, Escape
    if (e.defaultPrevented) return;
    let atStart = false; try { atStart = ae.selectionStart === 0 && ae.selectionEnd === 0; } catch {}
    const leave = e.key === 'Escape' || (ae.tagName === 'INPUT' && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) || (e.key === 'ArrowLeft' && atStart);
    if (!leave) return;
  }
  // a <select>: Enter / Space open it (the browser's own picker); arrows move the focus like everywhere else
  // (Up / Down would otherwise change its value and never leave it)
  if (ae && ae.tagName === 'SELECT' && (e.key === 'Enter' || e.key === ' ')) return;
  if (active && active.onKey && active.onKey(e)) e.preventDefault();
});
document.addEventListener('keyup', (e) => { if (active && active.onKeyUp) active.onKeyUp(e); });

// ---------- TV / live ----------
screens.live = function () { return tvScreen(main, nav); };
// #play:<channel key> deep link: TV screen underneath, channel full screen on top
screens.play = function (key) {
  const tv = tvScreen(main, nav);
  api.channelsByKeys([decodeURIComponent(key || '')]).then((r) => { if (r.items[0]) playLive(r.items[0]); else toast('Channel not found'); }).catch(() => {});
  return tv;
};
function play(c) { playLive(c); }

// ---------- VOD (movies / series) ----------
screens.movies = () => vodBrowse(main, 'movie', nav);
screens.series = () => vodBrowse(main, 'series', nav);
screens.detail = (arg) => { const [kind, id] = (arg || '').split(':'); return vodDetail(main, kind === 'series' ? 'series' : 'movie', id, nav); };
function openDetail(kind, it) { rememberItem(kind, it); location.hash = `detail:${kind}:${it.id}`; }

// ---------- My list / Search ----------
screens.mylist = () => myListScreen(main, nav);
screens.search = (arg) => searchScreen(main, nav, arg ? decodeURIComponent(arg) : '');
screens.recordings = () => recordingsScreen(main, nav);
screens.editor = (arg) => editorScreen(main, nav, arg ? decodeURIComponent(arg) : null);

// ---------- Settings ----------
screens.settings = () => settingsScreen(main, nav, { setSignedIn(v) { signedIn = v; refreshNav(); } });

function refreshNav() { document.querySelectorAll('.nav-item').forEach((b) => { if (b.dataset.screen !== 'settings') b.style.opacity = signedIn ? '1' : '.4'; }); }

// ---------- boot ----------
// a channel logo that does not load is hidden (no inline handlers: the CSP forbids them)
document.addEventListener('error', (e) => { const t = e.target; if (t && t.tagName === 'IMG' && t.classList.contains('lg')) t.style.visibility = 'hidden'; }, true);

(async function boot() {
  const sess = await ensureSignedIn();          // this device's session (sign-in page otherwise)
  if (!sess) return;
  document.documentElement.dataset.role = sess.role;
  signedIn = sess.signedIn;
  useStore(apiMod);
  await apiMod.loadState();
  document.documentElement.classList.toggle('solid', !!prefs().solid);
  fmtTime.fmt = prefs().timeFormat || '12';
  applyCaptionPrefs();
  startReminders();
  window.addEventListener('tvbro-state', () => { document.documentElement.classList.toggle('solid', !!prefs().solid); fmtTime.fmt = prefs().timeFormat || '12'; applyCaptionPrefs(); });
  refreshNav();
  // the last channel (the newest of this device's and the server's) is the current one again; one that
  // no longer exists is forgotten, so the guide's first channel takes over
  const last = signedIn ? resumeLastChannel() : null;
  if (last) api.channelsByKeys([last.key]).then((r) => { if (!r.items.length) forgetCurrent(last.key); }).catch(() => {});
  // back where this device left off: section, guide position, full screen + channel
  const ui = uiLoad();
  if (!location.hash) location.hash = signedIn ? (ui.hash && !/^(play|editor)/.test(ui.hash) ? ui.hash : 'live') : 'settings';
  route();
  if (signedIn && ui.full && last && (location.hash.replace(/^#/, '') || 'live') === 'live') {
    playLive(last, { group: last.groupId || ui.playingGroup || null });
  }
})();
