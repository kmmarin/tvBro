// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Thin client for the tvBro server. The server owns everything: provider login, catalogue,
// EPG, and all user state (My list, resume points, reminders, history, preferences) in its
// SQLite store, so every device on the LAN sees the same thing. The browser keeps only a
// short-lived in-memory view; media URLs are signed by the server.

import { detectEnv, wantFormat, clientCaps } from './playfmt.js';

/** 'hls' on clients without Media Source Extensions (iPhone / iPad), else 'ts'. */
const FMT = wantFormat(detectEnv());
const CAPS = clientCaps(detectEnv());

export function setToken() { /* the session is an HttpOnly cookie */ }
export function hasToken() { return true; }
// CSRF token of this device's session (from /api/session or sign-in); sent with every write
let csrf = '';
export function setCsrf(t) { csrf = t || ''; }
/** The server answered 401: this device is not signed in (any more). */
function signedOut(j) { window.dispatchEvent(new CustomEvent('tvbro-signin', { detail: (j && j.auth) || 'login' })); }
// parental unlock: a server token for this tab only (sessionStorage), sent with every request
let pTok = null;
try { const j = JSON.parse(sessionStorage.getItem('tvbro.parental') || 'null'); if (j && j.exp > Date.now()) pTok = j; } catch {}
export function parentalToken() { if (pTok && pTok.exp <= Date.now()) pTok = null; return pTok ? pTok.tok : ''; }
export function setParentalToken(tok, ttlSec) {
  pTok = tok ? { tok, exp: Date.now() + (ttlSec || 14400) * 1000 - 60000 } : null;
  try { if (pTok) sessionStorage.setItem('tvbro.parental', JSON.stringify(pTok)); else sessionStorage.removeItem('tvbro.parental'); } catch {}
}

// ---- stream session (one per tab: preview, live, catch-up and VOD share it) ----
export const SID = (() => {
  const fresh = () => { try { return crypto.randomUUID().replace(/-/g, ''); } catch { const a = new Uint8Array(16); crypto.getRandomValues(a); return [...a].map((b) => b.toString(16).padStart(2, '0')).join(''); } };
  try { let s = sessionStorage.getItem('tvbro.sid'); if (!s || s.length < 32) { s = fresh(); sessionStorage.setItem('tvbro.sid', s); } return s; }
  catch { return fresh(); }
})();
function deviceLabel() {
  const ua = navigator.userAgent;
  const br = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Android/.test(ua) ? 'Android' : /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : '';
  return `${br}${os ? ' on ' + os : ''}`;
}
let hbTimer = null, streaming = false;
/** Claim this tab's stream slot (the server may stop the oldest other tab). Await before playing. */
export async function beginStream(what) {
  streaming = true;
  try {
    const r = await req('/api/stream/start', { method: 'POST', body: JSON.stringify({ sid: SID, label: deviceLabel(), what: what || '' }) });
    if (r && r.stopped) { streaming = false; window.dispatchEvent(new CustomEvent('tvbro-replaced', { detail: r.stopped })); return false; }
  } catch {}
  clearInterval(hbTimer);
  hbTimer = setInterval(heartbeat, 10000);
  return true;
}
export async function heartbeat() {
  if (!streaming) return;
  try {
    const r = await req('/api/stream/heartbeat', { method: 'POST', body: JSON.stringify({ sid: SID }) });
    if (r.stopped) { streaming = false; clearInterval(hbTimer); window.dispatchEvent(new CustomEvent('tvbro-replaced', { detail: r.stopped })); }
  } catch {}
}
/** Before reconnecting a stream that broke: is this tab's slot still ours? (false = another window
 *  took it; the 'tvbro-replaced' event is sent, and a reconnect must not take it back.) */
export async function streamStillOurs() {
  try {
    const r = await req('/api/stream/heartbeat', { method: 'POST', body: JSON.stringify({ sid: SID }) });
    if (r.stopped) { streaming = false; clearInterval(hbTimer); window.dispatchEvent(new CustomEvent('tvbro-replaced', { detail: r.stopped })); return false; }
  } catch {}
  return true;
}
/** Release the slot (pause / close). */
export function endStream() {
  if (!streaming) return;
  streaming = false; clearInterval(hbTimer);
  req('/api/stream/stop', { method: 'POST', body: JSON.stringify({ sid: SID }) }).catch(() => {});
}
window.addEventListener('pagehide', () => {
  // a keepalive fetch instead of sendBeacon: writes must carry JSON + the CSRF token
  if (streaming) try { fetch('/api/stream/stop', { method: 'POST', keepalive: true, headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify({ sid: SID }) }); } catch {}
});

async function req(path, opts = {}) {
  const headers = Object.assign({}, opts.headers);
  if (opts.body) { headers['Content-Type'] = 'application/json'; headers['X-CSRF-Token'] = csrf; }
  const pt = parentalToken(); if (pt) headers['X-Parental'] = pt;
  const r = await fetch(path, { ...opts, headers, credentials: 'same-origin' });
  if (r.status === 401) { let j = {}; try { j = await r.json(); } catch {} signedOut(j); const e = new Error('sign in first'); e.status = 401; throw e; }
  if (r.status === 423) { const e = new Error('locked: the parental PIN is needed'); e.locked = true; e.status = 423; throw e; }
  if (r.status === 403) { let m = 'forbidden'; try { m = (await r.json()).error || m; } catch {} const e = new Error(m); e.status = 403; throw e; }
  if (!r.ok) { let m = 'HTTP ' + r.status; try { m = (await r.json()).error || m; } catch {} throw new Error(m); }
  return r.json();
}
/** Sign-in endpoints (no session yet): JSON + X-TvBro, so no other site can post them. */
async function authPost(path, body) {
  const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-TvBro': '1' }, body: JSON.stringify(body), credentials: 'same-origin' });
  let j = {}; try { j = await r.json(); } catch {}
  if (!r.ok) { const e = new Error(j.error || 'HTTP ' + r.status); e.status = r.status; e.retryAfter = j.retryAfter; throw e; }
  if (j.csrf) setCsrf(j.csrf);
  return j;
}
/** Parental endpoints: a 403 here is a wrong parental PIN, not the admin PIN (settings.js asks for that one). */
async function preq(path, body) {
  const r = await fetch(path, { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, parentalToken() ? { 'X-Parental': parentalToken() } : {}), body: JSON.stringify(body) });
  let j = {}; try { j = await r.json(); } catch {}
  if (!r.ok) throw new Error(j.error || 'HTTP ' + r.status);
  return j;
}
const withSid = (u) => u + (u.includes('?') ? '&' : '?') + 'sid=' + SID;

export const api = {
  health: () => req('/api/health'),
  /** {auth: 'setup'|'login'|'ok', role?, csrf?, signedIn, …}; remembers the CSRF token. */
  session: () => req('/api/session').then((s) => { if (s && s.csrf) setCsrf(s.csrf); return s; }),
  // ---- the optional password (auth.py; off by default) ----
  /** Admin: require a password (enabled, new admin password or the current one) or not (current). */
  authRequire: (enabled, password, current) => req('/api/auth/require', { method: 'POST', body: JSON.stringify({ enabled, password, current }) }),
  authLogin: (password) => authPost('/api/auth/login', { password }),
  authLogout: () => req('/api/auth/logout', { method: 'POST', body: '{}' }),
  /** Admin: change the admin password (current + new). */
  authPassword: (current, password) => req('/api/auth/password', { method: 'POST', body: JSON.stringify({ current, password }) }),
  /** Admin: the viewer password ('' removes it): watch-only sign-in for the household. */
  authViewer: (password) => req('/api/auth/viewer', { method: 'POST', body: JSON.stringify({ password }) }),
  signin: (p) => req('/api/signin', { method: 'POST', body: JSON.stringify(p) }),
  signout: () => req('/api/signout', { method: 'POST', body: '{}' }),
  // several playlists side by side (admin): rename / enable / disable, sync, remove
  sourceUpdate: (id, upd) => req('/api/sources/' + encodeURIComponent(id) + '/update', { method: 'POST', body: JSON.stringify(upd) }),
  sourceSync: (id) => req('/api/sources/' + encodeURIComponent(id) + '/sync', { method: 'POST', body: '{}' }),
  sourceDelete: (id) => req('/api/sources/' + encodeURIComponent(id) + '/delete', { method: 'POST', body: '{}' }),
  // ---- manual channel-to-guide mapping ----
  epgChannels: (key, q) => req(`/api/epg/channels?key=${encodeURIComponent(key)}&q=${encodeURIComponent(q || '')}`),
  /** guideId: an XMLTV channel id, '__none' (no guide) or null (automatic). */
  epgMap: (key, guideId) => req('/api/epg/map', { method: 'POST', body: JSON.stringify({ key, guideId: guideId || null }) }),
  // ---- backup / restore (one JSON file, no logins or PINs) ----
  backupUrl: '/api/backup',
  /** text: the backup file's content. check: only validate and say what is in it. */
  restore: (text, check) => req('/api/restore' + (check ? '?check=1' : ''), { method: 'POST', body: text }),
  // ---- parental PIN (locked groups / channels / VOD categories) ----
  parental: () => req('/api/parental'),
  parentalUnlock: (pin) => preq('/api/parental/unlock', { pin }),
  /** pin: the current PIN ('' when this tab is unlocked or none is set); newPin '' removes it. */
  parentalPin: (pin, newPin) => preq('/api/parental/pin', { pin, newPin }),
  parentalLock: (kind, id, locked) => preq('/api/parental/lock', { kind, id, locked }),
  refresh: () => req('/api/refresh', { method: 'POST', body: '{}' }),
  groups: () => req('/api/groups'),
  channels: (group, page = 1, q = '', size = 200) => req(`/api/channels?group=${encodeURIComponent(group || '')}&page=${page}&size=${size}&q=${encodeURIComponent(q)}`),
  channelsByKeys: (keys) => req('/api/channels?size=500&keys=' + keys.map(encodeURIComponent).join(',')),
  nownext: (keys) => req('/api/nownext?keys=' + keys.map(encodeURIComponent).join(',')),
  guide: (keys, from, to) => req(`/api/guide?keys=${keys.map(encodeURIComponent).join(',')}&from=${from}&to=${to}`),
  vodCategories: (kind) => req('/api/vod/categories?kind=' + kind),
  vodList: (kind, cat, page = 1, q = '') => req(`/api/vod/list?kind=${kind}&cat=${encodeURIComponent(cat || '')}&page=${page}&size=60&q=${encodeURIComponent(q)}`),
  vodInfo: (kind, id) => req(`/api/vod/info?kind=${kind}&id=${encodeURIComponent(id)}`),
  syncStatus: () => req('/api/sync/status'),
  syncNow: (kind) => req('/api/sync/' + kind, { method: 'POST', body: '{}' }),
  saveSettings: (s) => req('/api/settings', { method: 'POST', body: JSON.stringify(s) }),
  /** Search (server FTS index). signal: an AbortSignal, so a newer query cancels the older one. */
  search: (q, signal) => req('/api/search?q=' + encodeURIComponent(q), { signal }),
  /** One programme in full (description) for the search result card. */
  prog: (key, start) => req(`/api/prog?key=${encodeURIComponent(key)}&start=${start}`),
  /** Other entries of the same channel (same tvg-id / name), tried when it stays down. */
  alternates: (key) => req('/api/alternates?key=' + encodeURIComponent(key)).then((r) => r.items || []),
  catchupUrl: (key, start, end) => req(`/api/catchup?key=${encodeURIComponent(key)}&start=${start}&end=${end}&fmt=${FMT}`),
  /** Images come from the server as signed /img URLs (never a raw provider URL). */
  img: (u) => (typeof u === 'string' && (u.startsWith('/img?') || u.startsWith('data:'))) ? u : '',
  /** Server-signed live stream URL (Promise). */
  streamUrl: (key) => req(`/api/play?kind=live&fmt=${FMT}&key=` + encodeURIComponent(key)).then((r) => withSid(r.url)),
  /** Live TV for an external player: the signed URL in the channel's own format (no browser hint). */
  liveExternal: (key) => req('/api/play?kind=live&key=' + encodeURIComponent(key)),
  /** Server-signed movie / episode URL (Promise). */
  vodStreamUrl: (kind, id, ext) => req(`/api/play?kind=${kind}&id=${encodeURIComponent(id)}&ext=${encodeURIComponent(ext || 'mp4')}`).then((r) => withSid(r.url)),
  /** Movie / episode: {fmt: 'file'|'hls'|'external', url, file, remux?, why?}. A container this
   *  browser cannot play is remuxed by the server to HLS (opts: ss = start second, a = audio
   *  track, remux = 1 to force it). Every returned URL already carries this tab's sid. */
  vodPlay: async (kind, id, ext, opts = {}) => {
    let q = `/api/play?kind=${kind}&id=${encodeURIComponent(id)}&ext=${encodeURIComponent(ext || 'mp4')}&sid=${SID}&client=${CAPS.client}&caps=${CAPS.caps.join(',')}`;
    if (opts.series) q += '&series=' + encodeURIComponent(opts.series);   // an episode's show (parental lock)
    if (opts.remux) q += '&remux=1';
    if (opts.ss) q += '&ss=' + Math.floor(opts.ss);
    if (opts.a != null) q += '&a=' + opts.a;
    const r = await req(q);
    if (r.url) r.url = withSid(r.url);
    if (r.file) r.file = withSid(r.file);
    if (r.remux) { r.remux.subs.forEach((s) => { s.url = withSid(s.url); }); r.remux.info = withSid(r.remux.info); }
    return r;
  },
  withSid,
  // ---- shows: progress per series on the server (Continue watching, next episode, marks, captions) ----
  seriesContinue: () => req('/api/series/continue'),
  seriesState: (id) => req('/api/series/state?id=' + encodeURIComponent(id)),
  seriesProgress: (p) => req('/api/series/progress', { method: 'POST', body: JSON.stringify(p) }),
  /** {seriesId, watched, season?, epId?} */
  seriesMark: (p) => req('/api/series/mark', { method: 'POST', body: JSON.stringify(p) }),
  seriesPrefs: (p) => req('/api/series/prefs', { method: 'POST', body: JSON.stringify(p) }),
  seriesHide: (id) => req('/api/series/hide', { method: 'POST', body: JSON.stringify({ seriesId: id }) }),
  // ---- the household's channel layout (custom groups, rename / hide / move) ----
  groupsAll: () => req('/api/groups?all=1'),
  channelsAll: (group, page = 1) => req(`/api/channels?all=1&size=500&page=${page}&group=${encodeURIComponent(group)}`),
  layoutEdit: (op, d) => req('/api/layout', { method: 'POST', body: JSON.stringify({ op, ...d }) }),
  // ---- server-side recordings ----
  recordings: () => req('/api/recordings'),
  /** {key, title?, start?, stop?}: no start = record now (until the programme / two hours end). */
  record: (p) => req('/api/recordings', { method: 'POST', body: JSON.stringify(p) }),
  recordStop: (id) => req('/api/recordings/stop', { method: 'POST', body: JSON.stringify({ id }) }),
  recordDelete: (id) => req('/api/recordings/delete', { method: 'POST', body: JSON.stringify({ id }) }),
  /** Series rule: keep recording upcoming airings of `title` on channel `key` (from the guide). */
  recordSeries: (key, title) => req('/api/recordings/series', { method: 'POST', body: JSON.stringify({ key, title }) }),
  recordSeriesDelete: (id) => req('/api/recordings/series/delete', { method: 'POST', body: JSON.stringify({ id }) }),
  recDirs: (path) => req('/api/recordings/dirs?path=' + encodeURIComponent(path || '')),
  recDirTest: (path) => req('/api/recordings/storage/test', { method: 'POST', body: JSON.stringify({ path }) }),
  recDirSet: (path) => req('/api/recordings/storage', { method: 'POST', body: JSON.stringify({ path }) }),
  recShare: (p) => req('/api/recordings/share', { method: 'POST', body: JSON.stringify(p) }),
  recShareSteps: () => req('/api/recordings/share/steps'),
};

// --- short-lived in-memory cache (the server caches the catalogue and EPG) ---
const mem = new Map();
export async function cacheGet(key) { const e = mem.get(key); return e && Date.now() - e.at < 5 * 60000 ? e.v : null; }
export function cacheClear() { mem.clear(); }
export async function cacheSet(key, value) { mem.set(key, { v: value, at: Date.now() }); if (mem.size > 200) mem.delete(mem.keys().next().value); }

// --- shared user state (server SQLite) ---
let S = { mylist: [], resume: {}, reminders: [], history: [], prefs: {} };
export function state() { return S; }
export async function loadState() {
  try { S = await req('/api/state'); window.dispatchEvent(new Event('tvbro-state')); } catch {}
  return S;
}
let lastLoad = Date.now();
document.addEventListener('visibilitychange', () => { if (!document.hidden && Date.now() - lastLoad > 15000) { lastLoad = Date.now(); loadState(); } });
setInterval(() => { if (!document.hidden) { lastLoad = Date.now(); loadState(); } }, 60000);
export function statePut(scope, key, value) { return req('/api/state/' + scope, { method: 'POST', body: JSON.stringify({ op: 'put', key, value }) }).catch(() => {}); }
export function stateDel(scope, key) { return req('/api/state/' + scope, { method: 'POST', body: JSON.stringify({ op: 'del', key }) }).catch(() => {}); }
export function stateClear(scope) { return req('/api/state/' + scope, { method: 'POST', body: JSON.stringify({ op: 'clear' }) }).catch(() => {}); }

// --- my list ---
export function myList() { return S.mylist; }
export function inMyList(key) { return S.mylist.some((x) => x.key === key); }
export function toggleMyList(item) {
  const i = S.mylist.findIndex((x) => x.key === item.key);
  if (i >= 0) { S.mylist.splice(i, 1); stateDel('mylist', item.key); }
  else { S.mylist.unshift(item); statePut('mylist', item.key, item); }
  return i < 0;
}
// --- resume positions / watched ---
export function resumeMap() { return S.resume; }
/** meta (optional): { title, poster, kind, id, ext, sub?, seriesId? } for "Continue watching". */
export function setResume(key, secs, dur, meta) {
  const m = S.resume, old = m[key];
  let v = null;
  if (secs > 10 && (!dur || !isFinite(dur) || secs < dur - 30)) v = { secs: Math.round(secs), dur: isFinite(dur) ? Math.round(dur) : 0, at: Date.now(), meta: meta || (old && old.meta) || null };
  else if (dur && isFinite(dur) && secs >= dur - 30) v = { secs: Math.round(dur), dur: Math.round(dur), at: Date.now(), done: true, meta: meta || (old && old.meta) || null };
  if (v) { m[key] = v; statePut('resume', key, v); } else if (old) { delete m[key]; stateDel('resume', key); }
}
export function clearResume(key) { delete S.resume[key]; stateDel('resume', key); }
export function isWatched(key) { const r = S.resume[key]; return !!(r && r.done); }
export function getResume(key) { return S.resume[key]; }
// --- search history ---
export function searchHistory() { return S.history.map((x) => x.q); }
export function addSearchHistory(q) {
  S.history = [{ q, at: Date.now() }, ...S.history.filter((x) => x.q.toLowerCase() !== q.toLowerCase())].slice(0, 20);
  statePut('history', q.toLowerCase(), { q, at: Date.now() });
}
export function clearSearchHistory() { S.history = []; stateClear('history'); }
