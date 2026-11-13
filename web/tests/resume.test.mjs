// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Resume the last channel on open: the newest of the device's and the server's wins.
import test from 'node:test';
import assert from 'node:assert/strict';
import { pickLastChannel } from '../frontend/js/resume.js';

test('nothing remembered: no channel', () => {
  assert.equal(pickLastChannel(null, null), null);
  assert.equal(pickLastChannel({}, { key: '' }), null);
});
test('only one side knows a channel: that one', () => {
  assert.deepEqual(pickLastChannel({ key: 'a', at: 5 }, null), { ch: { key: 'a', at: 5 }, from: 'device' });
  assert.deepEqual(pickLastChannel(null, { key: 'b', at: 5 }), { ch: { key: 'b', at: 5 }, from: 'server' });
});
test('both: the newest wins (another device watched later -> the server one)', () => {
  assert.equal(pickLastChannel({ key: 'a', at: 10 }, { key: 'b', at: 20 }).ch.key, 'b');
  assert.equal(pickLastChannel({ key: 'a', at: 30 }, { key: 'b', at: 20 }).ch.key, 'a');
  // an old device record without a time loses to the server's
  assert.equal(pickLastChannel({ key: 'a' }, { key: 'b', at: 1 }).from, 'server');
  // same time: the device keeps its own
  assert.equal(pickLastChannel({ key: 'a', at: 7 }, { key: 'b', at: 7 }).from, 'device');
});
