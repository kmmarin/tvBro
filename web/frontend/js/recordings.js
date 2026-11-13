// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Recordings: what the server is recording now, what is scheduled, and what is recorded
// (title / channel / time / size; play, stop, cancel, delete). Recording happens on the
// server, so it continues with every browser closed.

import { api } from './api.js';
import { openPlayer } from './player.js';
import { h, toast, fmtTime, fmtDate, fmtDur, spatialMove, menu } from './util.js';

export const fmtSize = (n) => (n >= 1e9 ? (n / 1e9).toFixed(2) + ' GB' : n >= 1e6 ? Math.round(n / 1e6) + ' MB' : n > 0 ? Math.round(n / 1e3) + ' kB' : '0');

/** Play a recording from the server's disk (also while it is still recording). */
export function playRecording(x) {
  // the server answers with the recording's own HLS (byte ranges of the .ts), or for an HEVC
  // recording on iPhone / iPad a remux of it to fMP4 HLS (Apple plays HEVC only from fMP4)
  openPlayer({ vod: { kind: 'rec', id: x.id, ext: 'ts' }, title: x.title, subtitle: `${x.channel} · ${fmtDate(x.start)} ${fmtTime(x.start)}`,
    mode: 'vod', local: true, resumeKey: 'r|' + x.id, duration: x.duration || 0 });
}

export function recordingsScreen(main, nav) {
  main.innerHTML = '';
  const scr = h('div', { class: 'screen ml rec' }, h('h1', {}, 'Recordings'));
  main.append(scr);
  let alive = true, timer = null, data = null;
  load();

  async function load() {
    if (!alive) return;
    try { data = await api.recordings(); render(); } catch (e) { if (!data) scr.replaceChildren(h('h1', {}, 'Recordings'), h('div', { class: 'empty' }, 'Could not load recordings: ' + e.message)); }
    clearTimeout(timer);
    timer = setTimeout(load, data && data.items.some((x) => x.status === 'recording') ? 5000 : 30000);
  }
  async function act(fn, ok) { try { await fn(); toast(ok); } catch (e) { toast(e.message); } load(); }

  function tile(x) {
    const when = `${fmtDate(x.progStart || x.start)} ${fmtTime(x.progStart || x.start)}–${fmtTime(x.progStop || x.stop)}`;
    const facts = x.status === 'scheduled' ? `${x.channel} · ${when}`
      : `${x.channel} · ${when} · ${fmtSize(x.size)}${x.duration ? ' · ' + fmtDur(x.duration) : ''}`;
    const badge = { recording: '⏺ REC', scheduled: '⏱', failed: '⚠', done: '' }[x.status] || '';
    const t = h('button', { class: 'tile wide chtile focusable rectile' + (x.status === 'recording' ? ' live' : '') },
      h('div', { class: 'chlogo' }, h('img', { loading: 'lazy', alt: '', src: x.logo ? api.img(x.logo) : '' })),
      badge ? h('span', { class: 'fbadge recbadge' }, badge) : null,
      h('div', { class: 'cap' }, x.title),
      h('div', { class: 'cap2' }, facts),
      x.note ? h('div', { class: 'cap2 dim' }, x.note) : null);
    t.onclick = () => {
      const items = [];
      if (x.size > 0) items.push({ label: x.status === 'recording' ? '▶ Watch from the start' : '▶ Play', action: () => playRecording(x) });
      if (x.status === 'recording') items.push({ label: '■ Stop recording', action: () => act(() => api.recordStop(x.id), 'Recording stopped') });
      if (x.status === 'scheduled') items.push({ label: '✕ Cancel', action: () => act(() => api.recordStop(x.id), 'Recording cancelled') });
      else items.push({ label: '🗑 Delete', action: () => { if (confirm(`Delete "${x.title}"? The file is removed from the server.`)) act(() => api.recordDelete(x.id), 'Recording deleted'); } });
      menu(x.title, items);
    };
    t._rec = x;
    return t;
  }
  function section(title, list, hint) {
    if (!list.length) return null;
    return h('section', { class: 'ml-sec' }, h('div', { class: 'ml-h' }, h('h2', {}, title), h('span', { class: 'hint' }, hint || String(list.length))),
      h('div', { class: 'grid ml-grid' }, ...list.map(tile)));
  }
  function render() {
    const ae = document.activeElement, focusId = ae && ae._rec ? ae._rec.id : null;
    const it = data.items, d = data.disk || {};
    const kids = [h('h1', {}, 'Recordings'),
      h('div', { class: 'hint rec-disk' }, `Using ${fmtSize(d.used || 0)} of ${fmtSize(d.capBytes || 0)} · ${fmtSize(d.free || 0)} free on the server`
        + ' · record from the guide (programme menu) or the player (menu ▸ Record)')];
    const rules = (data.rules || []).map((r) => {
      const t = h('button', { class: 'tile wide chtile focusable rectile' },
        h('div', { class: 'chlogo' }, h('img', { loading: 'lazy', alt: '', src: r.logo ? api.img(r.logo) : '' })),
        h('span', { class: 'fbadge recbadge' }, '⟳'),
        h('div', { class: 'cap' }, r.title), h('div', { class: 'cap2' }, `${r.channel} · every airing in the guide`));
      t.onclick = () => menu(r.title, [{ label: '✕ Stop recording this series (cancels its scheduled recordings)', action: () => act(() => api.recordSeriesDelete(r.id), 'Series recording stopped') }]);
      return t;
    });
    const body = [section('Recording now', it.filter((x) => x.status === 'recording')),
      rules.length ? h('section', { class: 'ml-sec' }, h('div', { class: 'ml-h' }, h('h2', {}, 'Series recordings'), h('span', { class: 'hint' }, 'new airings are scheduled from the guide')),
        h('div', { class: 'grid ml-grid' }, ...rules)) : null,
      section('Scheduled', it.filter((x) => x.status === 'scheduled')),
      section('Recorded', it.filter((x) => x.status === 'done' || x.status === 'failed'))].filter(Boolean);
    scr.replaceChildren(...kids, ...(body.length ? body : [h('div', { class: 'empty' }, 'No recordings yet. In the guide, open a programme (Enter / long press) and choose Record; in the player, open the menu and choose Record.')]));
    if (focusId) { const f = [...scr.querySelectorAll('.rectile')].find((e) => e._rec && e._rec.id === focusId); if (f) f.focus(); }
  }
  const del = () => { const t = document.activeElement; if (t && t._rec && t._rec.status !== 'recording') { if (t._rec.status === 'scheduled') act(() => api.recordStop(t._rec.id), 'Recording cancelled'); else if (confirm(`Delete "${t._rec.title}"?`)) act(() => api.recordDelete(t._rec.id), 'Recording deleted'); } };
  const onClose = () => { if (alive) load(); };
  window.addEventListener('tvbro-vod-closed', onClose);
  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && e.key === 'ArrowLeft') nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if (e.key === 'Delete') { del(); return true; }
      if (e.key === 'Escape' || e.key === 'Backspace') { nav.focusActive(); return true; }
      return false;
    },
    focusIn() { const f = scr.querySelector('.focusable'); if (f) f.focus(); },
    destroy() { alive = false; clearTimeout(timer); window.removeEventListener('tvbro-vod-closed', onClose); },
  };
}
