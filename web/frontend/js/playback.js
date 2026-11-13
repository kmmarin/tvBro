// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// What is playing: live channel / catch-up / VOD. Screens call these; the TV preview listens
// to the 'tvbro-player' event so only one stream is open at a time.

import { api, SID } from './api.js';
import { openPlayer } from './player.js';
import { toast, nowSec } from './util.js';
import { uiLoad, uiSave } from './uistate.js';
import { prefs, setPref } from './prefs.js';
import { pickLastChannel } from './resume.js';
import { ensureUnlocked, withUnlock } from './parental.js';

// playing: the current channel (full screen, or what the TV-screen preview plays); list: () => channels
// for ch up/down; the guide group / row it came from. Moving focus in the guide never changes it.
export const state = { playing: null, list: null, groupId: null, row: null };
{ const u = uiLoad(); if (u.playing && u.playing.key) { state.playing = u.playing; state.groupId = u.playingGroup || null; } }
function remember(ctx) { if (ctx.list) { state.list = ctx.list; state.groupId = ctx.group || null; state.row = ctx.row != null ? ctx.row : null; } }

function emit(open) { if (!open) uiSave({ full: false }); window.dispatchEvent(new CustomEvent('tvbro-player', { detail: { open } })); }
const slim = (c) => ({ key: c.key, name: c.name, logo: c.logo, number: c.number, group: c.group, streamType: c.streamType, catchup: c.catchup, catchupDays: c.catchupDays, locked: c.locked || undefined });

/** The last channel is kept on the server too (prefs.lastChannel, the household's state), so a new
 *  browser, a cleared one or another device opens on it; the newest of the device's and the server's wins. */
function rememberLast(ch) {
  const at = Date.now();
  uiSave({ playing: slim(ch), playingGroup: state.groupId, playingAt: at });
  const old = prefs().lastChannel;
  if (!old || old.key !== ch.key || at - (old.at || 0) > 60000) setPref('lastChannel', { ...slim(ch), group: ch.group, groupId: state.groupId, at });
}
/** At start (after the server state is loaded): resume the last channel. Returns it (or null). */
export function resumeLastChannel() {
  const u = uiLoad();
  const pick = pickLastChannel(u.playing && u.playing.key ? { ...u.playing, at: u.playingAt || 0, groupId: u.playingGroup || null } : null, prefs().lastChannel);
  if (pick) { state.playing = pick.ch; state.groupId = pick.ch.groupId || null; if (pick.from === 'server') uiSave({ playing: slim(pick.ch), playingGroup: state.groupId, playingAt: pick.ch.at || 0 }); }
  return state.playing;
}
/** The remembered channel no longer exists (playlist removed, channel gone): forget it. */
export function forgetCurrent(key) {
  if (state.playing && state.playing.key === key) { state.playing = null; uiSave({ playing: null, playingAt: 0, full: false }); }
}

/** Make ch the current channel without going full screen (Enter on another channel in the guide:
 *  the preview switches to it). */
export function setCurrent(ch, ctx = {}) {
  state.playing = ch; remember(ctx);
  rememberLast(ch);
}

/** ctx: { list?: () => channel[] (channel up/down in the player), group?, row? (the guide's group / row) } */
export async function playLive(ch, ctx = {}) {
  if (!(await ensureUnlocked(ch))) return;          // parental lock: the PIN first
  state.playing = ch; remember(ctx);
  emit(true);
  rememberLast(ch); uiSave({ full: true });
  openPlayer({ url: api.streamUrl(ch.key), title: ch.name, channel: ch, mode: ch.streamType === 'hls' ? 'live' : 'ts',
    live: true, onClose: () => emit(false), zap: (dir) => zap(dir), zapNumber });
}

/** Number keys in the player: jump to the channel with that number in the current list. */
function zapNumber(n) {
  const l = state.list ? state.list() : null;
  const c = l && l.find((x) => x && +x.number === n);
  if (c) { playLive(c); return true; }
  return false;
}

export async function playCatchup(ch, p, ctx = {}) {
  if (!(await ensureUnlocked(ch))) return;
  let r;
  try { r = await withUnlock(() => api.catchupUrl(ch.key, p.start, p.stop)); } catch (e) { if (!e.cancelled) toast(e.locked ? 'Locked: the parental PIN is needed' : 'Catch-up unavailable'); return; }
  state.playing = ch; remember(ctx);
  emit(true);
  openPlayer({ url: api.withSid(r.url), title: ch.name, subtitle: p.title, channel: ch, prog: p, mode: ch.streamType === 'hls' ? 'live' : 'ts',
    catchup: true, duration: p.stop - p.start, onClose: () => emit(false), zap: (dir) => zap(dir) });
}

/** Watch a programme: live if on now, catch-up if past and in the archive. Returns false if not playable. */
export function watchProg(ch, p, ctx) {
  const now = nowSec();
  if (!p || (p.start <= now && p.stop > now)) { playLive(ch, ctx); return true; }
  if (p.stop <= now) {
    if (ch.catchup && p.start >= now - (ch.catchupDays || 0) * 86400) { playCatchup(ch, p, ctx); return true; }
    toast('No catch-up for this programme'); return false;
  }
  return false; // future
}

/** Channel up/down from the full-screen player. */
function zap(dir) {
  const l = state.list ? state.list() : null;
  if (!l || !state.playing) return;
  let i = l.findIndex((c) => c && c.key === state.playing.key);
  if (i < 0) return;
  for (let n = 0; n < l.length; n++) {
    i = (i + dir + l.length) % l.length;
    if (l[i]) { playLive(l[i]); return; }
  }
}

export function resumeFullscreen() {
  if (state.playing) playLive(state.playing); else toast('Nothing playing');
}
