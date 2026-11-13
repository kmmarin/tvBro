// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Manual channel-to-guide mapping: pick which channel of the playlist's guide (XMLTV) a playlist
// channel shows, or "no guide", or back to automatic. Stored on the server (epgmap.json) for every
// device; the guide, now / next and search use it.

import { api, cacheClear } from './api.js';
import { h, toast, debounce } from './util.js';

export async function guideMapDialog(ch) {
  let first;
  try { first = await api.epgChannels(ch.key, ''); } catch (e) { toast(e.message); return; }
  const prev = document.activeElement;
  const cur = h('div', { class: 'hint gm-cur' });
  const search = h('input', { type: 'search', class: 'focusable gm-q', placeholder: 'Search the guide\'s channels', autocomplete: 'off', spellcheck: 'false' });
  const list = h('div', { class: 'lock-list gm-list' });
  const box = h('div', { class: 'modal gm-modal', role: 'dialog', 'aria-label': 'Guide channel' },
    h('div', { class: 'modal-title' }, 'Guide channel for ' + ch.name), cur, search, list);
  const back = h('div', { class: 'modal-back' }, box);
  const close = () => { document.removeEventListener('keydown', onKey, true); back.remove(); if (prev && prev.focus) prev.focus(); };
  const showCur = (c) => {
    cur.textContent = c.mode === 'manual' ? `Now: picked by hand · ${c.name}${c.hasGuide ? '' : ' (no programmes yet)'}`
      : c.mode === 'none' ? 'Now: no guide for this channel'
      : c.id ? `Now: automatic · ${c.name}` : 'Now: automatic · nothing in the guide matches';
  };
  async function choose(guideId, label) {
    try {
      const r = await api.epgMap(ch.key, guideId);
      cacheClear();
      window.dispatchEvent(new CustomEvent('tvbro-epgmap', { detail: { key: ch.key, syncing: r.syncing } }));
      toast(guideId === '__none' ? 'No guide for ' + ch.name : guideId ? `Guide: ${label}${r.syncing ? ' · fetching the guide…' : ''}` : 'Guide: automatic again');
      close();
    } catch (e) { toast(e.message); }
  }
  function draw(r) {
    showCur(r.current);
    const c = r.current, btn = (label, sel, fn, extra = '') => { const b = h('button', { class: 'btn secondary focusable' + extra }, (sel ? '✓ ' : '') + label); b.onclick = fn; return b; };
    const kids = [btn('Automatic (tvg-id, then the name)', c.mode === 'auto', () => choose(null)),
      btn('No guide for this channel', c.mode === 'none', () => choose('__none'))];
    if (r.needSync) kids.push(h('div', { class: 'hint' }, 'The guide\'s channel list comes with the next guide sync (Settings > Guide > Sync guide now).'));
    else if (!r.items.length) kids.push(h('div', { class: 'hint' }, 'No guide channel matches.'));
    for (const g of r.items) {
      const sel = c.mode === 'manual' && c.id && c.id.toLowerCase() === g.id.toLowerCase();
      const b = btn(g.name + (g.name.toLowerCase() !== g.id.toLowerCase() ? `  ·  ${g.id}` : ''), sel, () => choose(g.id, g.name), g.hasGuide ? '' : ' dim');
      b.dataset.gid = g.id;
      kids.push(b);
    }
    // a new list (the search answered) keeps the focus where it was: same guide channel, else the first
    const ae = document.activeElement, had = list.contains(ae), gid = had && ae.dataset ? ae.dataset.gid : null;
    list.replaceChildren(...kids);
    if (had) { const f = (gid && [...list.querySelectorAll('.btn[data-gid]')].find((b) => b.dataset.gid === gid)) || list.querySelector('.btn[data-gid]') || list.querySelector('.btn'); if (f) f.focus(); }
  }
  draw(first);
  let tok = 0;
  const refresh = debounce(async () => {
    const my = ++tok;
    try { const r = await api.epgChannels(ch.key, search.value.trim()); if (my === tok) draw(r); } catch {}
  }, 200);
  search.addEventListener('input', refresh);
  back.addEventListener('click', (e) => { if (e.target === back) close(); });
  document.body.append(back);
  search.focus();
  const items = () => [search, ...list.querySelectorAll('.btn')];
  function onKey(e) {
    const ae = document.activeElement, it = items(), i = it.indexOf(ae);
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); e.stopPropagation(); (it[Math.min(it.length - 1, i + 1)] || it[0]).focus(); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); e.stopPropagation(); (it[Math.max(0, i - 1)] || it[0]).focus(); return; }
    if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); if (ae === search) { const b = list.querySelector('.btn[data-gid]') || list.querySelector('.btn'); if (b) b.focus(); } else if (ae && ae.click) ae.click(); return; }
    if (e.key === 'Backspace' && ae !== search) { e.preventDefault(); e.stopPropagation(); close(); return; }
    e.stopPropagation();                 // typing stays in the search field; nothing reaches the screen below
  }
  document.addEventListener('keydown', onKey, true);
}
