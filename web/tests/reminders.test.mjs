// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { dueReminders, prune, nextDueIn, GRACE } from '../frontend/js/reminders.js';

const r = (start, extra = {}) => ({ key: 'k', start, stop: start + 1800, ...extra });

test('due exactly at start, not before', () => {
  assert.equal(dueReminders([r(1000)], 999).length, 0);
  assert.equal(dueReminders([r(1000)], 1000).length, 1);
});
test('late wake-up still fires within the grace window, not after', () => {
  assert.equal(dueReminders([r(1000)], 1000 + GRACE - 1).length, 1);
  assert.equal(dueReminders([r(1000)], 1000 + GRACE).length, 0);
});
test('fired reminders do not fire twice', () => {
  assert.equal(dueReminders([r(1000, { fired: true })], 1001).length, 0);
});
test('prune drops finished programmes and old fired ones', () => {
  assert.equal(prune([r(1000)], 2900).length, 0);                       // programme over
  assert.equal(prune([r(1000, { fired: true })], 1000 + GRACE + 1).length, 0);
  assert.equal(prune([r(1000)], 1200).length, 1);
});
test('next due delay', () => {
  assert.equal(nextDueIn([r(1000), r(500)], 400), 100);
  assert.equal(nextDueIn([r(1000, { fired: true })], 400), null);
  assert.equal(nextDueIn([r(300)], 400), 0);
});
