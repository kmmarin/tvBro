// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanEpisodeTitle, seasonsOf, nextEpisode, fmtRuntime, isWatchedPos, episodeAfter, upNextDue, pickTrack, epCode } from '../frontend/js/vodutil.js';

test('episode titles lose series name, year and SxxEyy prefix', () => {
  assert.equal(cleanEpisodeTitle('The Show', 'The Show (2019) - S01E03 - The Return'), 'The Return');
  assert.equal(cleanEpisodeTitle('The Show', 'S01E03 - The Return'), 'The Return');
  assert.equal(cleanEpisodeTitle('The Show', 'the show s2 e10: Finale'), 'Finale');
  assert.equal(cleanEpisodeTitle('X', 'Episode 4'), 'Episode 4');
  assert.equal(cleanEpisodeTitle('The Show', 'The Show S2E10'), 'The Show S2E10'); // nothing left -> original
});

const eps = [{ id: 'a', season: 1, num: 2 }, { id: 'b', season: 2, num: 1 }, { id: 'c', season: 1, num: 1 }];
test('seasons grouped and ordered', () => {
  assert.deepEqual(seasonsOf(eps).map((s) => [s.season, s.eps.map((e) => e.id)]), [[1, ['c', 'a']], [2, ['b']]]);
});
test('next episode: first, resume, after finished', () => {
  assert.equal(nextEpisode(eps, {}).ep.id, 'c');
  const r1 = nextEpisode(eps, { 'e|a': { secs: 100, dur: 1000, at: 5 } });
  assert.equal(r1.ep.id, 'a'); assert.equal(r1.resume, true);
  const r2 = nextEpisode(eps, { 'e|a': { secs: 1000, dur: 1000, at: 5, done: true }, 'e|c': { secs: 9, at: 1, done: true } });
  assert.equal(r2.ep.id, 'b'); assert.equal(r2.resume, false);
});
test('runtime text', () => { assert.equal(fmtRuntime(105), '1 h 45 min'); assert.equal(fmtRuntime('50'), '50 min'); assert.equal(fmtRuntime(120), '2 h'); });

test('watched at 90 % or at the end', () => {
  assert.equal(isWatchedPos(899, 1000), false); assert.equal(isWatchedPos(900, 1000), true);
  assert.equal(isWatchedPos(10, 0), false); assert.equal(isWatchedPos(10, 0, true), true);
});
test('episode after: in order and into the next season', () => {
  assert.equal(episodeAfter(eps, 'c').id, 'a');
  assert.equal(episodeAfter(eps, 'a').id, 'b');          // S1 E2 -> S2 E1
  assert.equal(episodeAfter(eps, 'b'), null);
  assert.equal(episodeAfter(eps, 'a', -1).id, 'c');
  assert.equal(episodeAfter(eps, 'zz'), null);
  assert.equal(epCode(eps[1]), 'S2 E1');
});
test('up next: last 30 s when the length is known, else at the end', () => {
  assert.equal(upNextDue(2669, 2700), false); assert.equal(upNextDue(2670, 2700), true);
  assert.equal(upNextDue(50, 60), false);                 // too short to tell
  assert.equal(upNextDue(100, 0), false); assert.equal(upNextDue(100, 0, true), true);
});
test('caption choice per series', () => {
  const tr = [{ language: 'en', label: 'English' }, { language: 'fr', label: 'French' }];
  assert.equal(pickTrack(tr, 'fr'), 1);
  assert.equal(pickTrack(tr, 'English'), 0);
  assert.equal(pickTrack(tr, 'de'), 0);                   // language missing: first text track
  assert.equal(pickTrack(tr, 'off'), -1);
  assert.equal(pickTrack(tr, null), null);
  assert.equal(pickTrack([], 'en'), null);
});