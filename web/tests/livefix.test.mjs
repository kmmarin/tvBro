// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Live recovery policy: reconnects with back-off, then alternates, then a rest and start over.
import test, { mock } from 'node:test';
import assert from 'node:assert/strict';
import { LiveRecovery, RETRY_DELAYS, REST_MS, watchStall, STALL_MS, HEALTHY_MS } from '../frontend/js/livefix.js';

test('watchStall: a stream that stops moving is reported once; a paused one never; steady playback is healthy', () => {
  mock.timers.enable({ apis: ['setInterval', 'Date'] });
  try {
    const v = { currentTime: 0, paused: false, readyState: 4 };
    let stalls = 0, healthy = 0, ours = true;
    const w = watchStall(v, { onStall: () => stalls++, onHealthy: () => healthy++, ok: () => ours });
    const run = (ms) => { for (let t = 0; t < ms; t += 1000) mock.timers.tick(1000); };
    for (let i = 0; i < (HEALTHY_MS + 2000) / 1000; i++) { v.currentTime += 1; mock.timers.tick(1000); }
    assert.equal(healthy, 1, 'steady playback counts as healthy once');
    run(STALL_MS + 2000);
    assert.equal(stalls, 1, 'no progress for STALL_MS = one stall');
    run(STALL_MS * 2);
    assert.equal(stalls, 1, 'reported once until reset');
    w.reset(); v.paused = true; run(STALL_MS * 2);
    assert.equal(stalls, 1, 'paused by the viewer is not a stall');
    v.paused = false; ours = false; run(STALL_MS * 2);
    assert.equal(stalls, 1, 'not watched while ok() is false (another layer / window)');
    ours = true; run(STALL_MS + 1000);
    assert.equal(stalls, 2);
    // a stream that never starts (paused, nothing loaded yet) is a stall too
    w.reset(); v.paused = true; v.readyState = 0; run(STALL_MS + 1000);
    assert.equal(stalls, 3);
    w.stop();
  } finally { mock.timers.reset(); }
});

test('the channel itself is reconnected first, with growing waits', () => {
  const r = new LiveRecovery('a');
  const steps = RETRY_DELAYS.map(() => r.failed());
  assert.deepEqual(steps.map((s) => s.action), RETRY_DELAYS.map(() => 'retry'));
  assert.deepEqual(steps.map((s) => s.delay), RETRY_DELAYS);
  assert.deepEqual(steps.map((s) => s.key), ['a', 'a', 'a']);
  assert.equal(LiveRecovery.notice(steps[1]), `Reconnecting… (2/${RETRY_DELAYS.length})`);
  assert.equal(r.failed().action, 'alts', 'then the alternates are asked for');
});

test('alternates in order, one reconnect each, then a rest and the channel again', () => {
  const r = new LiveRecovery('a');
  RETRY_DELAYS.forEach(() => r.failed()); r.failed();
  r.setAlts([{ key: 'b', name: 'B HD' }, { key: 'a', name: 'self' }, { key: 'c', name: 'C' }]);
  let s = r.failed(); assert.equal(s.action, 'alt'); assert.equal(s.key, 'b');
  assert.equal(LiveRecovery.notice(s), 'Trying another source: B HD');
  s = r.failed(); assert.equal(s.action, 'retry'); assert.equal(s.key, 'b');
  s = r.failed(); assert.equal(s.action, 'alt'); assert.equal(s.key, 'c', 'the channel itself is never its own alternate');
  r.failed();
  s = r.failed(); assert.equal(s.action, 'rest'); assert.equal(s.key, 'a'); assert.equal(s.delay, REST_MS);
  assert.match(LiveRecovery.notice(s, 'Kilo'), /^Kilo is not available/);
  // after the rest: the channel gets its reconnects again, the alternates are already known
  assert.equal(r.failed().action, 'retry');
});

test('no alternates: reconnects, then rest', () => {
  const r = new LiveRecovery('a');
  RETRY_DELAYS.forEach(() => r.failed());
  assert.equal(r.failed().action, 'alts'); r.setAlts([]);
  assert.equal(r.failed().action, 'rest');
});

test('playing fine again resets the reconnect count (and stays on the source that works)', () => {
  const r = new LiveRecovery('a');
  r.failed(); r.failed(); r.healthy();
  const s = r.failed(); assert.equal(s.attempt, 1);
  RETRY_DELAYS.forEach(() => r.failed()); r.failed(); r.setAlts([{ key: 'b' }]);
  r.failed();                       // on b now
  r.healthy();
  const t = r.failed(); assert.equal(t.action, 'retry'); assert.equal(t.key, 'b');
});
