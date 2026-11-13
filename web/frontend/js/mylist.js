// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// My list: tile grids per section — Continue watching, Channels (with now playing),
// Movies, Series, Reminders. F (or the context menu) favourites anything; stars mark them.

import { api, myList, toggleMyList, resumeMap, clearResume } from './api.js';
import { h, toast, fmtTime, fmtDate, fmtDur, nowSec, spatialMove, menu, onItemMenu } from './util.js';
import { reminders, toggleReminder } from './reminders.js';
import { playLive } from './playback.js';
import { rememberItem, playMovie, cwTile, continueRows, markSeries } from './vod.js';
import * as G from './guide.js';

export function myListScreen(main, nav) {
  main.innerHTML = '';
  const scr = h('div', { class: 'screen ml' }, h('h1', {}, 'My list'));
  main.append(scr);
  let alive = true, cwShows = [];
  async function loadShows() { try { cwShows = await continueRows(); } catch { return; } if (alive) render(); }
  render();
  loadShows();

  function section(title, tiles, hint) {
    if (!tiles.length) return null;
    return h('section', { class: 'ml-sec' }, h('div', { class: 'ml-h' }, h('h2', {}, title), h('span', { class: 'hint' }, hint || `${tiles.length}`)),
      h('div', { class: 'grid ml-grid' }, ...tiles));
  }
  function render() {
    const items = myList();
    const res = resumeMap();
    const kids = [];
    // Continue watching: unfinished movies (resume points) and shows (the server's per-series
    // progress; old per-episode resume points of shows are left out), newest first
    const cont = Object.entries(res).filter(([, r]) => !r.done && r.meta && r.dur && !(r.meta.kind === 'episode' && r.meta.seriesId))
      .map(([k, r]) => ({ k, r, at: r.at / 1000 }))
      .concat(cwShows.map((row) => ({ row, at: row.at || 0 })))
      .sort((a, b) => b.at - a.at).slice(0, 24);
    kids.push(section('Continue watching', cont.map(({ k, r, row }) => {
      if (row) return cwTile(row, () => loadShows());
      const m = r.meta;
      const t = h('button', { class: 'tile wide focusable' },
        h('img', { class: 'poster wide', loading: 'lazy', alt: '', src: m.poster ? api.img(m.poster) : '' }),
        h('div', { class: 'progress tile-prog' }, h('i', { style: { width: Math.min(100, (r.secs / r.dur) * 100).toFixed(1) + '%' } })),
        h('div', { class: 'cap' }, m.title + (m.sub ? ' · ' + m.sub : '')),
        h('div', { class: 'cap2' }, `${fmtDur(r.dur - r.secs)} left`));
      onItemMenu(t, () => menu(m.title, [{ label: '▶ Resume', action: () => t.click() }, { label: 'Remove from Continue watching', action: () => { clearResume(k); render(); } }]));
      t.onclick = () => {
        if (m.kind === 'movie') playMovie({ id: m.id, name: m.title, poster: m.poster, ext: m.ext }, { ext: m.ext });
        else if (m.seriesId) { const [src, raw] = String(m.seriesId).includes('~') ? String(m.seriesId).split('~') : ['p0', m.seriesId];
          rememberItem('series', { id: m.seriesId, name: m.title, poster: m.poster, key: `s|${src}|${raw}` }); location.hash = `detail:series:${m.seriesId}`; }
      };
      t._remove = () => clearResume(k);
      return t;
    }), 'Resume where you left off'));
    // Channels
    const chans = items.filter((x) => x.kind === 'live');
    const chTiles = chans.map((it) => {
      const c = it.ch || { key: it.key, name: it.name, logo: it.logo };
      const now = h('div', { class: 'cap2', 'data-now': c.key }, '');
      const bar = h('div', { class: 'progress tile-prog', 'data-bar': c.key, hidden: true }, h('i'));
      const t = h('button', { class: 'tile wide chtile focusable' },
        h('div', { class: 'chlogo' }, h('img', { loading: 'lazy', alt: '', src: c.logo ? api.img(c.logo) : '' })),
        h('span', { class: 'fbadge' }, '★'), bar,
        h('div', { class: 'cap' }, (c.number ? c.number + '  ' : '') + c.name), now);
      const watch = () => playLive(c, { list: () => chans.map((x) => x.ch || x), group: '__fav' });
      onItemMenu(t, () => menu(c.name, [{ label: '▶ Watch', action: watch }, { label: '★ Remove from Favourites', action: () => { toggleMyList(it); toast('Removed from Favourites'); render(); } }]));
      t.onclick = watch;
      t._item = it;
      return t;
    });
    kids.push(section('Channels', chTiles));
    if (chans.length) api.nownext(chans.map((x) => x.key).slice(0, 100)).then((r) => {
      const n = nowSec();
      for (const [k, nn] of Object.entries(r.nownext)) {
        if (!nn.now) continue;
        const cap = scr.querySelector(`[data-now="${CSS.escape(k)}"]`); if (cap) cap.textContent = `${nn.now.title} · ${G.progress(nn.now, n).minLeft} min left`;
        const b = scr.querySelector(`[data-bar="${CSS.escape(k)}"]`); if (b) { b.hidden = false; b.firstChild.style.width = (G.progress(nn.now, n).frac * 100).toFixed(1) + '%'; }
      }
    }).catch(() => {});
    // Movies / Series
    for (const [kind, title] of [['movie', 'Movies'], ['series', 'Series']]) {
      kids.push(section(title, items.filter((x) => x.kind === kind).map((it) => {
        const r = it.rating && parseFloat(it.rating) ? (parseFloat(it.rating) > 10 ? parseFloat(it.rating) / 10 : parseFloat(it.rating)).toFixed(1) : '';
        const t = h('button', { class: 'tile focusable' },
          h('img', { class: 'poster', loading: 'lazy', alt: '', src: it.poster ? api.img(it.poster) : '' }),
          r ? h('span', { class: 'rbadge' }, '★ ' + r) : null, h('span', { class: 'fbadge' }, '★'),
          h('div', { class: 'cap' }, it.name));
        const open = () => { rememberItem(kind, it); location.hash = `detail:${kind}:${it.id}`; };
        onItemMenu(t, () => menu(it.name, [{ label: 'Open', action: open }, { label: '★ Remove from My list', action: () => { toggleMyList(it); toast('Removed from My list'); render(); } },
          ...(kind === 'series' ? [{ label: '✓ Mark the show as watched', action: () => markSeries(it.id, true) }, { label: '↺ Mark the show as unwatched', action: () => markSeries(it.id, false) }] : [])]));
        t.onclick = open;
        t._item = it;
        return t;
      })));
    }
    // Reminders
    const rems = reminders().filter((x) => x.stop > nowSec()).sort((a, b) => a.start - b.start);
    kids.push(section('Reminders', rems.map((x) => {
      const t = h('button', { class: 'tile wide focusable remtile' },
        h('div', { class: 'chlogo' }, h('img', { loading: 'lazy', alt: '', src: x.logo ? api.img(x.logo) : '' })),
        h('span', { class: 'fbadge' }, '⏰'),
        h('div', { class: 'cap' }, x.title), h('div', { class: 'cap2' }, `${fmtDate(x.start)} ${fmtTime(x.start)} · ${x.channel}`));
      const rm = () => menu(x.title, [{ label: 'Cancel reminder', action: () => { toggleReminder(x); render(); } }]);
      onItemMenu(t, rm); t.onclick = rm;
      t._rem = x;
      return t;
    }), 'You get a pop-up when they start'));
    const body = kids.filter(Boolean);
    scr.replaceChildren(h('h1', {}, 'My list'), ...(body.length ? body : [h('div', { class: 'empty' }, 'Nothing here yet. Press F on a channel, movie or show (or use its menu) to add it; set reminders from the guide.')]));
  }
  function toggleFocused() {
    const t = document.activeElement;
    if (!t || !scr.contains(t)) return;
    if (t._item) { toggleMyList(t._item); toast('Removed from My list'); render(); }
    else if (t._rem) { toggleReminder(t._rem); toast('Reminder removed'); render(); }
    else if (t._remove) { Promise.resolve(t._remove()).then(() => loadShows()); toast('Removed from Continue watching'); render(); }
  }
  const onRem = () => { if (alive) render(); };
  window.addEventListener('tvbro-reminders', onRem);
  window.addEventListener('tvbro-state', onRem);
  const onShows = () => loadShows();
  window.addEventListener('tvbro-series', onShows);
  window.addEventListener('tvbro-vod-closed', onShows);
  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && e.key === 'ArrowLeft') nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if (e.key === 'f' || e.key === 'F' || e.key === 'Delete') { toggleFocused(); return true; }
      if ((e.key === 'm' || e.key === 'M' || e.key === 'ContextMenu') && document.activeElement && document.activeElement.classList.contains('has-menu')) {
        document.activeElement.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })); return true; }
      if (e.key === 'Escape' || e.key === 'Backspace') { nav.focusActive(); return true; }
      return false;
    },
    focusIn() { const f = scr.querySelector('.focusable'); if (f) f.focus(); },
    destroy() { alive = false; window.removeEventListener('tvbro-reminders', onRem); window.removeEventListener('tvbro-state', onRem);
      window.removeEventListener('tvbro-series', onShows); window.removeEventListener('tvbro-vod-closed', onShows); },
  };
}
