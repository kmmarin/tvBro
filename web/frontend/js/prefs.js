// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Preferences live on the server (shared by every device); this is a view over them.

import { state, statePut } from './api.js';

const DEFAULTS = {
  previewOn: true, previewMuted: true,
  numbering: 'provider',     // 'provider' | 'group'
  epgShiftMin: 0,            // EPG time shift (minutes)
  audioDelayMs: 0,           // default audio delay for new playback
  captionSize: 100, captionBg: 'shade', // caption style
  solid: false,              // Reduce transparency (glass -> solid fills)
  subs: 'off',               // last subtitle language / 'off'
  timeFormat: '12',          // '12' (4:30 PM) | '24' | 'auto' (browser)
  autoplayNext: true,       // shows: play the next episode after a 10 s "Up next" countdown
  lastGroup: '__all',
};
export function prefs() { return Object.assign({}, DEFAULTS, state().prefs || {}); }
export function setPref(k, v) {
  const s = state(); if (!s.prefs) s.prefs = {};
  if (s.prefs[k] === v) return;
  s.prefs[k] = v; statePut('prefs', k, v);
}
