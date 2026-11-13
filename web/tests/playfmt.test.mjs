// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Stream-format choice: iPhone / iPad (no Media Source Extensions) get HLS and play it natively.
import test from 'node:test';
import assert from 'node:assert/strict';
import { detectEnv, needsHls, wantFormat, isHlsUrl, playPlan, externalReason, externalLinks, clientCaps } from '../frontend/js/playfmt.js';
import { parseVtt } from '../frontend/js/vtt.js';

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1';
const CHROME = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const video = (hls) => ({ createElement: () => ({ canPlayType: (t) => (hls && /mpegurl/i.test(t) ? 'maybe' : '') }) });
const win = (ua, o = {}) => ({ navigator: { userAgent: ua, platform: o.platform || '', maxTouchPoints: o.touch || 0 }, MediaSource: o.mse ? function () {} : undefined, ManagedMediaSource: o.mms ? function () {} : undefined, document: video(o.hls) });

const iphone = detectEnv(win(IPHONE, { mms: true, hls: true }));
const ipad = detectEnv(win('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/18.1 Safari/605.1.15', { platform: 'MacIntel', touch: 5, mse: true, hls: true }));
const mac = detectEnv(win('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/18.1 Safari/605.1.15', { platform: 'MacIntel', touch: 0, mse: true, hls: true }));
const chrome = detectEnv(win(CHROME, { mse: true }));
const LIBS = { hls: true, mpegts: true }, NOLIBS = { hls: false, mpegts: false };

test('environment detection', () => {
  assert.deepEqual(iphone, { mse: false, ios: true, nativeHls: true });
  assert.equal(ipad.ios, true);            // iPadOS pretends to be a Mac, but has a touch screen
  assert.equal(mac.ios, false);
  assert.deepEqual(chrome, { mse: true, ios: false, nativeHls: false });
});

test('iPhone / iPad ask for HLS, desktop browsers for MPEG-TS', () => {
  assert.equal(wantFormat(iphone), 'hls');
  assert.equal(wantFormat(ipad), 'hls');   // iOS/iPadOS WebKit: always native HLS
  assert.equal(wantFormat(chrome), 'ts');
  assert.equal(wantFormat(mac), 'ts');
  assert.equal(needsHls({ mse: false, ios: false, nativeHls: false }), true);   // any browser without MediaSource
});

test('signed URLs name their format', () => {
  assert.equal(isHlsUrl('/stream?exp=1&fmt=hls&key=p0%7C5&sig=ab&sid=x'), true);
  assert.equal(isHlsUrl('/stream?exp=1&fmt=ts&key=p0%7C5&sig=ab'), false);
  assert.equal(isHlsUrl('/stream?exp=1&key=p0%7C5&sig=ab'), false);
  assert.equal(isHlsUrl('/recording.m3u8?id=3&sig=ab'), true);
});

test('live: iPhone plays HLS natively, even though hls.js could use ManagedMediaSource', () => {
  const hls = { url: '/stream?fmt=hls&key=k&sig=s', mode: 'ts' };
  assert.equal(playPlan(hls, iphone, NOLIBS), 'native');
  assert.equal(playPlan(hls, iphone, LIBS), 'native');
  assert.equal(playPlan(hls, chrome, LIBS), 'hls');
  assert.equal(playPlan(hls, mac, NOLIBS), 'native');
  assert.equal(playPlan(hls, chrome, NOLIBS), 'external');
});

test('live MPEG-TS: mpegts.js on desktop, never on iPhone', () => {
  const ts = { url: '/stream?fmt=ts&key=k&sig=s', mode: 'ts' };
  assert.equal(playPlan(ts, chrome, LIBS), 'mpegts');
  assert.equal(playPlan(ts, iphone, LIBS), 'external');     // the source had no HLS form
  assert.match(externalReason(ts, iphone), /iPhone.*MPEG-TS/);
  // a source that already is HLS (M3U playlist) keeps mode 'live'
  assert.equal(playPlan({ url: '/stream?key=k&sig=s', mode: 'live' }, iphone, NOLIBS), 'native');
  assert.equal(playPlan({ url: '/stream?key=k&sig=s', mode: 'live' }, chrome, LIBS), 'hls');
});

test('VOD: mp4 plays natively everywhere, mkv on iPhone goes to a player app', () => {
  const f = (ext) => ({ url: `/vodstream?exp=1&ext=${ext}&id=9&kind=movie&sig=s`, mode: 'vod' });
  assert.equal(playPlan(f('mp4'), iphone, LIBS), 'native');
  assert.equal(playPlan(f('m4v'), iphone, LIBS), 'native');
  assert.equal(playPlan(f('mkv'), iphone, LIBS), 'external');
  assert.equal(playPlan(f('avi'), iphone, LIBS), 'external');
  assert.equal(playPlan(f('mkv'), chrome, LIBS), 'native');   // Chrome tries; the player offers an app on error
  assert.match(externalReason(f('mkv'), iphone), /cannot play MKV files/);
  // a recording is served as an HLS playlist: seekable everywhere
  assert.equal(playPlan({ url: '/recording.m3u8?id=1&sig=s', mode: 'vod' }, iphone, LIBS), 'native');
  assert.equal(playPlan({ url: '/recording.m3u8?id=1&sig=s', mode: 'vod' }, chrome, LIBS), 'hls');
});

test('VOD remuxed by the server is HLS: native on iPhone, hls.js on desktop', () => {
  const r = { url: '/remux/AbC_12/index.m3u8?sid=x1', mode: 'vod' };
  assert.equal(playPlan(r, iphone, LIBS), 'native');
  assert.equal(playPlan(r, chrome, LIBS), 'hls');
});

test('client capabilities for the remux', () => {
  assert.deepEqual(clientCaps(iphone, win(IPHONE, { mms: true, hls: true })), { client: 'ios', caps: ['hevc', 'ac3', 'eac3'] });
  const w = win(CHROME, { mse: true });
  w.MediaSource.isTypeSupported = (t) => /hvc1|av01/.test(t);
  assert.deepEqual(clientCaps(chrome, w), { client: 'mse', caps: ['hevc', 'av1'] });
});

test('WebVTT cues from a growing file', () => {
  const t = 'WEBVTT\n\n00:10.005 --> 00:12.005\nHello\n\n01:02:03.500 --> 01:02:05.000 line:90%\nTwo\nlines\n\n00:20.000 --> 00:2';
  assert.deepEqual(parseVtt(t), [{ start: 10.005, end: 12.005, text: 'Hello' }, { start: 3723.5, end: 3725, text: 'Two\nlines' }]);
  assert.equal(parseVtt('WEBVTT\n\n00:01.000 --> 00:02.000\nLast\n').length, 1);    // ffmpeg ends a cue with one newline
  assert.deepEqual(parseVtt(''), []);
});

test('player-app links carry the encoded absolute URL', () => {
  const l = externalLinks('https://tv.example/vodstream?id=9&ext=mkv&sig=ab');
  assert.equal(l[0].href, 'vlc-x-callback://x-callback-url/stream?url=https%3A%2F%2Ftv.example%2Fvodstream%3Fid%3D9%26ext%3Dmkv%26sig%3Dab');
  assert.ok(l[1].href.startsWith('infuse://x-callback-url/play?url=https%3A%2F%2F'));
});
