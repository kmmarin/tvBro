// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Which stream format to ask the server for, and how to play what comes back. Pure logic
// (no DOM access except detectEnv) so it is unit-tested with node.
//
// Raw MPEG-TS needs Media Source Extensions (mpegts.js). No iPhone browser has classic
// MediaSource (they are all WebKit; iOS 17.1+ has only ManagedMediaSource), but WebKit plays
// HLS natively in <video>. So those clients ask for HLS and play it without any library.

/** Containers an iPhone / iPad plays natively as a progressive file. */
export const IOS_FILE_EXTS = ['mp4', 'm4v', 'mov'];

/** @returns {{mse: boolean, ios: boolean, nativeHls: boolean}} */
export function detectEnv(w = globalThis) {
  const nav = w.navigator || {}, ua = nav.userAgent || '';
  // iPadOS reports itself as a Mac; a Mac with a touch screen does not exist
  const ios = /iPhone|iPad|iPod/.test(ua) || (nav.platform === 'MacIntel' && (nav.maxTouchPoints || 0) > 1);
  let nativeHls = false;
  try {
    const v = w.document && w.document.createElement('video');
    nativeHls = !!(v && v.canPlayType && (v.canPlayType('application/vnd.apple.mpegurl') || v.canPlayType('application/x-mpegURL')));
  } catch { /* no DOM */ }
  return { mse: !!w.MediaSource, ios, nativeHls: nativeHls || ios };
}

/** True when this client cannot use Media Source Extensions and must get HLS. */
export function needsHls(env) { return !env.mse || env.ios; }

/** The `fmt` hint sent to /api/play and /api/catchup. */
export function wantFormat(env) { return needsHls(env) ? 'hls' : 'ts'; }

/** Is this (server-signed) media URL an HLS playlist? */
export function isHlsUrl(url) { return /[?&]fmt=hls(&|$)/.test(url) || /\.m3u8(\?|$)/.test(url); }

export function extOf(url) { const m = /[?&]ext=([A-Za-z0-9]+)/.exec(url); return m ? m[1].toLowerCase() : ''; }

/**
 * How to play `url`.
 * @param {{url: string, mode: 'live'|'ts'|'vod'}} what  mode 'live' = the source itself is HLS
 * @param env  detectEnv()
 * @param {{hls: boolean, mpegts: boolean}} libs  hls.js / mpegts.js usable here
 * @returns {'native'|'hls'|'mpegts'|'external'}  'external' = this browser cannot play it
 */
export function playPlan(what, env, libs) {
  const { url, mode } = what;
  const hls = isHlsUrl(url) || mode === 'live';
  if (hls) {
    if (env.ios && env.nativeHls) return 'native';      // never MSE on iOS, even where ManagedMediaSource exists
    if (libs.hls) return 'hls';
    return env.nativeHls ? 'native' : 'external';
  }
  if (mode === 'vod') {                                  // a progressive file (mp4, mkv, …)
    if (env.ios && !IOS_FILE_EXTS.includes(extOf(url) || 'mp4')) return 'external';
    return 'native';
  }
  // raw MPEG-TS
  if (needsHls(env)) return 'external';
  return libs.mpegts ? 'mpegts' : 'external';
}

/** Why playPlan said 'external', as a sentence for the viewer. */
export function externalReason(what, env) {
  const where = env.ios ? 'iPhone and iPad browsers' : 'This browser';
  if (what.mode === 'vod' && !isHlsUrl(what.url)) return `${where} cannot play ${(extOf(what.url) || 'this').toUpperCase()} files.`;
  return `${where} cannot play this stream: it is raw MPEG-TS and the source offers no HLS version.`;
}

/**
 * What the server needs to know to remux a film for this browser (MKV & co. become HLS fMP4):
 * the client kind and which codecs it plays inside MP4, so audio is copied where possible.
 * @returns {{client: 'ios'|'mse', caps: string[]}}
 */
export function clientCaps(env, w = globalThis) {
  const can = (t) => {
    try { if (w.MediaSource && w.MediaSource.isTypeSupported && w.MediaSource.isTypeSupported(t)) return true; } catch { /* */ }
    try { const v = w.document && w.document.createElement('video'); return !!(v && v.canPlayType && v.canPlayType(t)); } catch { return false; }
  };
  const caps = [];
  // Apple devices decode HEVC (tagged hvc1), AC-3 and E-AC-3 in HLS fMP4
  if (env.ios || can('video/mp4; codecs="hvc1.1.6.L93.B0"')) caps.push('hevc');
  if (env.ios || can('audio/mp4; codecs="ac-3"')) caps.push('ac3');
  if (env.ios || can('audio/mp4; codecs="ec-3"')) caps.push('eac3');
  if (can('video/mp4; codecs="av01.0.05M.08"')) caps.push('av1');
  return { client: needsHls(env) ? 'ios' : 'mse', caps };
}

/** Deep links that hand an absolute media URL to a player app. */
export function externalLinks(absUrl) {
  const e = encodeURIComponent(absUrl);
  return [
    { name: 'Open in VLC', href: 'vlc-x-callback://x-callback-url/stream?url=' + e },
    { name: 'Open in Infuse', href: 'infuse://x-callback-url/play?url=' + e },
  ];
}
