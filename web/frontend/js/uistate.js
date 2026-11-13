// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Where this device left the UI (section, guide group / row / scroll, full screen or guide,
// the playing channel): restored on reload, a reopened tab or a PWA relaunch. Per device
// (localStorage): it is UI state, not shared state. Every access is guarded (private mode).

const KEY = 'tvbro.ui';
let cache = null;
export function uiLoad() {
  if (cache) return cache;
  try { cache = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch { cache = {}; }
  return cache;
}
let timer = null;
export function uiSave(patch) {
  Object.assign(uiLoad(), patch);
  clearTimeout(timer);
  timer = setTimeout(flush, 300);
}
function flush() { try { localStorage.setItem(KEY, JSON.stringify(uiLoad())); } catch {} }
window.addEventListener('pagehide', flush);
document.addEventListener('visibilitychange', () => { if (document.hidden) flush(); });
