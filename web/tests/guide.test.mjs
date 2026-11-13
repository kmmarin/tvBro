// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Unit tests for the guide layout math. Run: node --test web/tests
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../frontend/js/guide.js';

const T0 = 1794409200; // a :00 boundary (divisible by 1800)

test('floor30 and initialStart hide the past', () => {
  assert.equal(T0 % 1800, 0);
  assert.equal(G.floor30(T0 + 17 * 60), T0);
  assert.equal(G.initialStart(T0 + 29 * 60 + 59), T0);
  assert.equal(G.initialStart(T0 + 30 * 60), T0 + 1800);
});

test('block x/width on one scale, clipped at the edges', () => {
  const ppm = 4, ws = T0, we = T0 + G.WINDOW_SEC;
  // programme 30 min after window start, 60 min long
  assert.deepEqual(G.block(T0 + 1800, T0 + 5400, ws, we, ppm), { x: 120, w: 238, clippedLeft: false });
  // started 20 min before the window: drawn from the left edge, flagged
  const b = G.block(T0 - 1200, T0 + 600, ws, we, ppm);
  assert.equal(b.x, 0); assert.equal(b.w, 10 * 4 - 2); assert.equal(b.clippedLeft, true);
  // runs past the right edge: clipped to the window end
  const r = G.block(T0 + 9000 - 600, T0 + 9000 + 3600, ws, we, ppm);
  assert.equal(r.x + r.w + 2, G.WINDOW_MIN * ppm);
  // outside
  assert.equal(G.block(T0 - 3600, T0, ws, we, ppm), null);
  assert.equal(G.block(we, we + 60, ws, we, ppm), null);
  // ruler tick and block share the scale
  assert.equal(G.tickX(T0 + 1800, ws, ppm), G.block(T0 + 1800, T0 + 3600, ws, we, ppm).x);
});

test('ticks every 30 min across the 2.5 h window', () => {
  const t = G.ticks(T0, T0 + G.WINDOW_SEC);
  assert.equal(t.length, 6);
  assert.equal(t[1] - t[0], 1800);
});

test('shifted clamps to the paging limits', () => {
  const now = T0 + 600;
  assert.equal(G.shifted(T0, 1800, now), T0 + 1800);
  assert.equal(G.shifted(T0, -1800, now), G.floor30(now));          // never before the current half hour
  assert.equal(G.shifted(T0 - 24 * 3600, -1800, now), G.floor30(now));
  assert.equal(G.shifted(T0 + 72 * 3600, 1800, now), G.floor30(now + G.AHEAD_LIMIT));
});

test('the window never shows the past: behind now snaps to the current half hour', () => {
  const now = T0 + 17 * 60;
  assert.equal(G.clampWin(T0 - 6 * 3600, now), T0);
  assert.equal(G.clampWin(T0 + 3600, now), T0 + 3600);
  assert.equal(G.clampWin(T0 + 99 * 3600, now), G.floor30(now + G.AHEAD_LIMIT));
});

test('Left at the window start goes to the channel name; never into the past', () => {
  const now = T0 + 17 * 60;
  const p = [{ start: T0 - 3600, stop: T0 - 1800 }, { start: T0 - 1800, stop: T0 + 600 }, { start: T0 + 600, stop: T0 + 2400 },
    { start: T0 + 2400, stop: T0 + 9000 }, { start: T0 + 9000, stop: T0 + 12600 }];
  // on now (started 20 min before the window): Left = channel name, not the earlier programme
  assert.deepEqual(G.leftOf(p, p[1], T0, now), { label: true });
  // a programme after it, both visible: plain move
  assert.deepEqual(G.leftOf(p, p[2], T0, now), { prog: p[1], win: T0 });
  // window moved forward by the user: Left brings it back (not before now) and focuses the previous one
  const r = G.leftOf(p, p[4], T0 + 9000, now);
  assert.equal(r.prog, p[3]); assert.ok(r.win >= T0 && r.win < T0 + 9000);
  // a programme that started before a later window: the window steps back, focus stays
  const q = G.leftOf(p, p[3], T0 + 3600, now);
  assert.equal(q.prog, p[2]); assert.equal(q.win, T0);
  // on now (started before the half hour), window ahead of now: the window comes back first, then the name
  const w = G.leftOf(p, p[1], T0 + 1800, now);
  assert.deepEqual(w, { prog: p[1], win: T0 });
  assert.deepEqual(G.leftOf(p, p[1], w.win, now), { label: true });
  // no guide data / nothing focused
  assert.deepEqual(G.leftOf([], null, T0, now), { label: true });
  assert.deepEqual(G.leftOf(p, null, T0, now), { label: true });
});

test('Right from the channel name lands on the programme on now, window at now', () => {
  const now = T0 + 17 * 60;
  const p = [{ start: T0 - 1800, stop: T0 + 600 }, { start: T0 + 600, stop: T0 + 2400 }];
  assert.deepEqual(G.rightOfLabel(p, now), { prog: p[1], win: T0 });
  assert.deepEqual(G.rightOfLabel(p, T0 + 60), { prog: p[0], win: T0 });
  assert.deepEqual(G.rightOfLabel([], now), { prog: null, win: T0 });
});

test('reveal keeps partly visible programmes, moves for hidden ones', () => {
  const ws = T0;
  assert.equal(G.reveal(T0 - 600, T0 + 600, ws), ws);              // partly visible on the left
  assert.equal(G.reveal(T0 - 7200, T0 - 3600, ws), T0 - 7200);       // entirely before
  assert.equal(G.reveal(T0 + 10000, T0 + 12000, ws), G.floor30(T0 + 10000)); // entirely after
  // starts inside, ends after: move just enough (in 30-min steps) without hiding its start
  const n = G.reveal(T0 + 7200, T0 + 10800, ws);
  assert.ok(n > ws && n <= T0 + 7200);
});

test('rowSegments fills "No guide data" gaps of the right width', () => {
  const ws = T0, we = T0 + G.WINDOW_SEC;
  const progs = [{ start: T0 - 600, stop: T0 + 1800, title: 'a' }, { start: T0 + 3600, stop: T0 + 5400, title: 'b' }];
  const seg = G.rowSegments(progs, ws, we);
  assert.deepEqual(seg.map((s) => s.gap ? 'gap' : s.prog.title), ['a', 'gap', 'b', 'gap']);
  assert.equal(seg[1].start, T0 + 1800); assert.equal(seg[1].stop, T0 + 3600);
  assert.equal(seg[3].stop, we);
  assert.deepEqual(G.rowSegments([], ws, we), [{ gap: true, start: ws, stop: we }]);
  // tiny gaps (< 2 min) are not drawn
  const s2 = G.rowSegments([{ start: T0, stop: T0 + 1800 }, { start: T0 + 1860, stop: we }], ws, we);
  assert.equal(s2.filter((s) => s.gap).length, 0);
});

test('prev/next/progAt navigation', () => {
  const p = [{ start: T0, stop: T0 + 1800 }, { start: T0 + 1800, stop: T0 + 3600 }, { start: T0 + 3600, stop: T0 + 7200 }];
  assert.equal(G.progAt(p, T0 + 1900), p[1]);
  assert.equal(G.nextProg(p, p[0]), p[1]);
  assert.equal(G.prevProg(p, p[2]), p[1]);
  assert.equal(G.prevProg(p, p[0]), null);
  assert.equal(G.firstVisible(p, T0 + 2000, T0 + 4000), p[1]);
});

test('paging chunks and merge', () => {
  const c = G.chunksFor(T0, T0 + G.WINDOW_SEC);
  assert.ok(c.length >= 1 && c.length <= 2);
  assert.ok(c[0] * G.CHUNK <= T0 && (c[c.length - 1] + 1) * G.CHUNK >= T0 + G.WINDOW_SEC);
  const m = G.mergeProgs([[{ start: 2 }, { start: 1 }], [{ start: 2 }, { start: 3 }]]);
  assert.deepEqual(m.map((x) => x.start), [1, 2, 3]);
});

test('programme state: now / future / catch-up / past', () => {
  const now = T0 + 5000, ch = { catchup: true, catchupDays: 1 };
  assert.equal(G.progState({ start: T0, stop: T0 + 7200 }, now, ch), 'now');
  assert.equal(G.progState({ start: now + 60, stop: now + 600 }, now, ch), 'future');
  assert.equal(G.progState({ start: T0, stop: T0 + 1800 }, now, ch), 'catchup');
  assert.equal(G.progState({ start: now - 3 * 86400, stop: now - 3 * 86400 + 1800 }, now, ch), 'past');
  assert.equal(G.progState({ start: T0, stop: T0 + 1800 }, now, { catchup: false }), 'past');
  const pr = G.progress({ start: T0, stop: T0 + 6000 }, T0 + 3000);
  assert.equal(pr.frac, 0.5); assert.equal(pr.minLeft, 50);
});

test('search classification: catch-up / now / future / not watchable', () => {
  const now = T0 + 5000, ch = { catchup: true, catchupDays: 1 };
  assert.deepEqual(G.searchInfo({ start: T0, stop: T0 + 1800 }, now, ch), { kind: 'catchup', at: T0, watch: 'catchup' });
  const n = G.searchInfo({ start: T0, stop: T0 + 7200 }, now, ch);
  assert.equal(n.kind, 'now'); assert.equal(n.minLeft, Math.ceil((T0 + 7200 - now) / 60)); assert.equal(n.watch, 'live');
  const f = G.searchInfo({ start: now + 600, stop: now + 1200 }, now, ch);
  assert.equal(f.kind, 'future'); assert.equal(f.inMin, 10); assert.equal(f.watch, null);
  assert.equal(G.searchInfo({ start: T0, stop: T0 + 1800 }, now, { catchup: false }), null);
  assert.equal(G.searchInfo({ start: now - 3 * 86400, stop: now - 3 * 86400 + 60 }, now, ch), null);
});
