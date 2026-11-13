// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Guide grid math: one shared time scale for the ruler, the programme blocks and the "now"
// line. Pure functions (epoch seconds in, pixels out) so they can be unit-tested in Node.

export const WINDOW_MIN = 150;               // ~2.5 h visible
export const WINDOW_SEC = WINDOW_MIN * 60;
export const STEP = 1800;                    // edges shift the window by 30 min
export const AHEAD_LIMIT = 72 * 3600;        // how far right the window may go
export const CHUNK = 6 * 3600;               // guide paging unit (backend fetch granularity)

export const floor30 = (t) => Math.floor(t / 1800) * 1800;

/** The guide starts at the current half hour (05:00 at 05:17) and only goes forward: the past is
 *  never shown in the grid (catch-up is reached from the programme / channel menu). */
export const initialStart = (now) => floor30(now);

/** A window start kept inside [current half hour, now + AHEAD_LIMIT]: one behind "now" snaps to now. */
export function clampWin(winStart, now) {
  const lo = floor30(now), hi = floor30(now + AHEAD_LIMIT);
  return Math.min(hi, Math.max(lo, winStart));
}

/** Window start after a 30-minute step (never into the past). */
export function shifted(winStart, delta, now) { return clampWin(winStart + delta, now); }

/**
 * Left from a focused programme. Returns { prog, win } (focus that programme, window start win)
 * or { label: true } (focus goes to the channel name: at the window start, which is never before
 * the current half hour, Left never scrolls into the past and never traps focus).
 */
export function leftOf(progs, focus, winStart, now) {
  const lo = floor30(now);
  if (!progs || !progs.length || !focus) return { label: true };
  const pv = prevProg(progs, focus);
  if (pv && pv.stop > winStart) return { prog: pv, win: winStart };          // visible: just move
  if (winStart > lo) {                                                        // the window can still go back (not past now)
    if (pv) { const win = clampWin(Math.min(floor30(pv.start), winStart - STEP), now); if (pv.stop > win) return { prog: pv, win }; }
    // the previous one is in the past (or there is none): the window steps back towards now first,
    // the focus stays; the channel name comes once the window is at now
    return { prog: focus, win: clampWin(winStart - STEP, now) };
  }
  return { label: true };
}

/** Right from the channel name: the programme on now, with the window back at now. */
export function rightOfLabel(progs, now) {
  const win = initialStart(now);
  return { prog: progAt(progs, now) || firstVisible(progs, win, win + WINDOW_SEC), win };
}

/**
 * Window start after focus lands on a programme. One that is at least partly visible never
 * pulls the window back; one entirely outside (or starting inside and running past the end) moves it.
 */
export function reveal(pStart, pStop, winStart) {
  const winEnd = winStart + WINDOW_SEC;
  if (pStop <= winStart || pStart >= winEnd) return floor30(pStart);
  if (pStart >= winStart && pStop > winEnd) return Math.min(floor30(pStart), floor30(pStop - WINDOW_SEC + 1799));
  return winStart;
}

/** x offset and width (px) of [start, stop) clipped to the window, or null when outside. */
export function block(start, stop, winStart, winEnd, pxPerMin, gap = 2) {
  const s = Math.max(start, winStart), e = Math.min(stop, winEnd);
  if (e <= s) return null;
  return { x: ((s - winStart) / 60) * pxPerMin, w: Math.max(1, ((e - s) / 60) * pxPerMin - gap), clippedLeft: start < winStart };
}

export const tickX = (t, winStart, pxPerMin) => ((t - winStart) / 60) * pxPerMin;

/** Ruler ticks (every 30 min) inside [winStart, winEnd]. */
export function ticks(winStart, winEnd) {
  const out = [];
  for (let t = Math.ceil(winStart / 1800) * 1800; t <= winEnd; t += 1800) out.push(t);
  return out;
}

/**
 * Segments for one row: programmes clipped to the window plus "No guide data" gaps
 * (only gaps longer than minGap seconds are shown). progs must be sorted by start.
 */
export function rowSegments(progs, winStart, winEnd, minGap = 120) {
  const out = [];
  if (!progs || !progs.length) return [{ gap: true, start: winStart, stop: winEnd }];
  let cursor = winStart;
  for (const p of progs) {
    if (p.stop <= winStart || p.start >= winEnd) continue;
    const s = Math.max(p.start, winStart);
    if (s > cursor + minGap) out.push({ gap: true, start: cursor, stop: s });
    out.push({ gap: false, prog: p, start: p.start, stop: p.stop });
    cursor = Math.max(cursor, Math.min(p.stop, winEnd));
  }
  if (winEnd > cursor + minGap) out.push({ gap: true, start: cursor, stop: winEnd });
  return out;
}

/** Programme playing at time t, or null. */
export function progAt(progs, t) {
  if (!progs) return null;
  for (const p of progs) if (p.start <= t && p.stop > t) return p;
  return null;
}
export const nextProg = (progs, p) => (progs || []).find((x) => x.start >= p.stop) || null;
export function prevProg(progs, p) {
  let r = null;
  for (const x of progs || []) if (x.stop <= p.start) r = x;
  return r;
}
/** First programme visible in the window (used when focus enters a row). */
export function firstVisible(progs, winStart, winEnd) {
  return (progs || []).find((p) => p.stop > winStart && p.start < winEnd) || null;
}

/** Chunk indices (CHUNK-aligned) needed to cover [from, to). */
export function chunksFor(from, to) {
  const out = [];
  for (let c = Math.floor(from / CHUNK); c * CHUNK < to; c++) out.push(c);
  return out;
}

/** Merge programme lists from several chunks: dedupe by start, sort. */
export function mergeProgs(lists) {
  const m = new Map();
  for (const l of lists) for (const p of l || []) if (!m.has(p.start)) m.set(p.start, p);
  return [...m.values()].sort((a, b) => a.start - b.start);
}

/** State of a programme for the UI: 'now' | 'future' | 'catchup' (past, in archive) | 'past'. */
export function progState(p, now, ch) {
  if (p.start > now) return 'future';
  if (p.stop > now) return 'now';
  if (ch && ch.catchup && p.start >= now - (ch.catchupDays || 0) * 86400) return 'catchup';
  return 'past';
}

/**
 * What a search result shows for a programme: catch-up clock if past and in the archive,
 * progress + minutes left if on now, air time if future; null if past and not watchable.
 */
export function searchInfo(p, now, ch) {
  const st = progState(p, now, ch);
  if (st === 'now') { const pr = progress(p, now); return { kind: 'now', frac: pr.frac, minLeft: pr.minLeft, watch: 'live' }; }
  if (st === 'future') return { kind: 'future', at: p.start, inMin: Math.ceil((p.start - now) / 60), watch: null };
  if (st === 'catchup') return { kind: 'catchup', at: p.start, watch: 'catchup' };
  return null;
}

/** Progress (0..1) and minutes left of a programme at time now. */
export function progress(p, now) {
  const len = Math.max(1, p.stop - p.start);
  return { frac: Math.min(1, Math.max(0, (now - p.start) / len)), minLeft: Math.max(0, Math.ceil((p.stop - now) / 60)) };
}
