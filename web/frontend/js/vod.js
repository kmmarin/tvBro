// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Movies / Shows: header for the focused title over its backdrop, poster grid with rating
// badges (paged from the backend), details page with actions and one row per season.

import { api, cacheGet, cacheSet, inMyList, toggleMyList, getResume, clearResume } from './api.js';
import { h, toast, fmtDur, spatialMove, debounce, menu, onItemMenu } from './util.js';
import { openPlayer, playerReplace } from './player.js';
import { withUnlock } from './parental.js';
import { cleanEpisodeTitle, seasonsOf, fmtRuntime, episodeAfter, epCode, isWatchedPos } from './vodutil.js';

// ---------------------------------------------------------------- shows progress (server)
// The server keeps, per series: each episode's position / watched mark, the last episode, the
// next one, and the caption / audio choice. This is the browser's view of it.
const seriesState = new Map();      // series id -> {episodes:{epId:{secs,dur,done,at}}, next, finished, subs, audio}
let progressChain = Promise.resolve();
const seriesChanged = () => window.dispatchEvent(new Event('tvbro-series'));
export async function loadSeriesState(id) {
  await progressChain;
  const s = await api.seriesState(id); seriesState.set(String(id), s); return s;
}
/** Shows "Continue watching" rows (after any progress still being sent). */
export async function continueRows() { await progressChain; return (await api.seriesContinue()).items || []; }
export async function markSeries(sid, watched, scope = {}, what = 'Show') {
  try {
    const s = await api.seriesMark({ seriesId: String(sid), watched, ...scope });
    seriesState.set(String(sid), s); seriesChanged();
    toast(`${what} marked as ${watched ? 'watched' : 'unwatched'}`);
    return s;
  } catch (e) { toast('Could not save: ' + e.message); return null; }
}
function reportProgress(series, e, secs, dur, ended) {
  const sid = String(series.id);
  const st = seriesState.get(sid) || { id: sid, episodes: {} };
  st.episodes = st.episodes || {};
  const old = st.episodes[e.id];
  st.episodes[e.id] = { secs: Math.round(secs), dur: Math.round(dur || 0), at: Date.now() / 1000,
    done: isWatchedPos(secs, dur, ended) || !!(old && old.done && secs < 5) };
  seriesState.set(sid, st);
  const body = { seriesId: sid, epId: String(e.id), secs: Math.round(secs), dur: Math.round(dur || 0), ended: !!ended, name: series.name, poster: series.poster };
  progressChain = progressChain.then(() => api.seriesProgress(body).then((r) => { Object.assign(st, r, { episodes: st.episodes }); }).catch(() => {}));
}
function saveSeriesPrefs(sid, p) {
  const st = seriesState.get(String(sid)); if (st) Object.assign(st, p);
  api.seriesPrefs({ seriesId: String(sid), ...p }).catch(() => {});
}
/** A wide tile for a show in Continue watching: the next / in-progress episode with its progress. */
export function cwTile(row, onChange) {
  const n = row.next || {};
  const it = { id: row.id, name: row.name || '', poster: row.poster, key: row.key };
  rememberItem('series', it);
  const frac = n.dur && n.secs ? Math.min(1, n.secs / n.dur) : 0;
  const et = cleanEpisodeTitle(row.name, n.title);
  const t = h('button', { class: 'tile wide cw-tile focusable' },
    h('img', { class: 'poster wide', loading: 'lazy', alt: '', src: api.img(n.image || row.poster), onerror: function () { if (row.poster && n.image && !this._f) { this._f = 1; this.src = api.img(row.poster); } else this.style.opacity = '.15'; } }),
    frac ? h('div', { class: 'progress tile-prog' }, h('i', { style: { width: (frac * 100).toFixed(1) + '%' } })) : null,
    h('div', { class: 'cap' }, row.name || ''),
    h('div', { class: 'cap2' }, `${epCode(n)}${et ? ' · ' + et : ''}${n.resume && n.dur ? ' · ' + fmtDur(n.dur - n.secs) + ' left' : ''}`));
  const play = async () => {
    try { const d = await vodInfo('series', it.id, true); playEpisode(it, d, (d.episodes || []).find((x) => String(x.id) === String(n.id)) || n); }
    catch (e) { if (!e.cancelled) toast('Could not load: ' + e.message); }
  };
  onItemMenu(t, () => menu(row.name || 'Show', [
    { label: `▶ ${n.resume ? 'Resume' : 'Play'} ${epCode(n)}`, action: play },
    { label: 'Go to the show', action: () => { location.hash = `detail:series:${it.id}`; } },
    { label: '✓ Mark the show as watched', action: async () => { await markSeries(it.id, true); if (onChange) onChange(); } },
    { label: 'Remove from Continue watching', action: async () => { await api.seriesHide(it.id).catch(() => {}); seriesChanged(); if (onChange) onChange(); } },
  ]));
  t.onclick = play;
  t._remove = async () => { await api.seriesHide(it.id).catch(() => {}); seriesChanged(); };
  t._series = it;
  return t;
}

const items = new Map();        // key "movie:id" -> list item (for details deep links)
const infoCache = new Map();    // "movie:id" -> detail json
const mem = { movie: { cat: '__all' }, series: { cat: '__all' } };

/** ask: a locked film / show asks for the parental PIN (the detail page, playing); a focus preview never does. */
export async function vodInfo(kind, id, ask = false) {
  const k = kind + ':' + id;
  if (infoCache.has(k)) return infoCache.get(k);
  const p = (ask ? withUnlock(() => api.vodInfo(kind, id)) : api.vodInfo(kind, id)).then((d) => { infoCache.set(k, d); if (infoCache.size > 300) infoCache.delete(infoCache.keys().next().value); return d; });
  infoCache.set(k, p); p.catch(() => infoCache.delete(k));
  return p;
}
export function rememberItem(kind, it) { items.set(kind + ':' + it.id, it); }
function ratingText(r) { const n = parseFloat(r); return n ? (n > 10 ? (n / 10).toFixed(1) : n.toFixed(1)) : ''; }
function setBackdrop(url) {
  let bd = document.querySelector('.backdrop');
  if (!bd) { bd = h('div', { class: 'backdrop' }); document.body.prepend(bd); }
  bd.style.backgroundImage = url ? `url("${api.img(url)}")` : 'none';
}
export function clearBackdrop() { const bd = document.querySelector('.backdrop'); if (bd) bd.remove(); }

function factsLine(kind, it, d) {
  const year = (d && d.year) || it.year;
  const run = kind === 'movie' ? fmtRuntime(d && d.runtime) : (d && d.seasons ? `${d.seasons} season${d.seasons > 1 ? 's' : ''}` : '');
  const genre = (d && d.genre) || it.genre;
  return [year, run, genre].filter(Boolean).join(' · ');
}

// ---------------------------------------------------------------- browse
export function vodBrowse(main, kind, nav) {
  const title = kind === 'movie' ? 'Movies' : 'Shows';
  main.innerHTML = '';
  const head = h('div', { class: 'vh glass' });
  const chips = h('div', { class: 'chips vchips' });
  const grid = h('div', { class: 'grid vgrid' });
  const more = h('div', { class: 'loading', hidden: true }, 'Loading…');
  // Shows: "Continue watching" is the first row (shows with a started, unfinished episode)
  const cw = h('section', { class: 'cw-row', hidden: true });
  const scr = h('div', { class: 'screen vscreen' }, head, cw, h('div', { class: 'vbar' }, h('h1', {}, title), chips), grid, more);
  main.append(scr);
  const st = mem[kind];
  let page = 0, total = 0, loading = false, alive = true, focused = null, tok = 0, headTok = 0, cwTok = 0;
  async function renderCw() {
    if (kind !== 'series') return;
    const my = ++cwTok;
    let rows; try { rows = await continueRows(); } catch { return; }
    if (!alive || my !== cwTok) return;
    const had = cw.contains(document.activeElement);
    cw.hidden = !rows.length;
    cw.replaceChildren(h('h2', {}, 'Continue watching'), h('div', { class: 'cw-strip' }, ...rows.map((r) => {
      const t = cwTile(r, renderCw);
      t.addEventListener('focus', () => { focused = t._series; headSoon(t._series); });
      return t;
    })));
    if (had) { const f = cw.querySelector('.focusable'); if (f) f.focus(); }
  }
  renderCw();
  const onSeries = () => renderCw();
  window.addEventListener('tvbro-series', onSeries);
  window.addEventListener('tvbro-vod-closed', onSeries);

  renderHead(null);
  (async () => {
    let cats = await cacheGet('vcat:' + kind);
    if (cats) renderChips(cats);
    try { cats = (await api.vodCategories(kind)).categories; cacheSet('vcat:' + kind, cats); renderChips(cats); }
    catch (e) { if (!cats) grid.append(h('div', { class: 'empty' }, 'Could not load: ' + e.message)); }
  })();
  function renderChips(cs) {
    chips.innerHTML = '';
    for (const c of cs) {
      const chip = h('button', { class: 'chip focusable' + (c.id === st.cat ? ' active' : '') }, c.name + (c.count ? ` ${c.count}` : ''));
      chip.onclick = () => { st.cat = c.id; chips.querySelectorAll('.chip').forEach((x) => x.classList.toggle('active', x === chip)); reload(); };
      chips.append(chip);
    }
    if (!page) reload();
  }
  function reload() { tok++; page = 0; total = 0; grid.innerHTML = ''; loadMore(); }
  async function loadMore() {
    if (loading || (page && grid.children.length >= total)) return;
    loading = true; more.hidden = false; const t = tok;
    try {
      const r = await api.vodList(kind, st.cat, page + 1);
      if (t !== tok || !alive) return;
      page = r.page; total = r.total;
      if (!r.items.length && page === 1) grid.append(h('div', { class: 'empty' }, 'Nothing here.'));
      for (const it of r.items) grid.append(tile(kind, it));
      // back from a film / show page: the tile that was opened gets the focus again (no jump to the top)
      if (st.back && st.focusId != null) { const b = grid.querySelector(`[data-id="${CSS.escape(String(st.focusId))}"]`); if (b) { st.back = false; b.focus({ preventScroll: true }); b.scrollIntoView({ block: 'nearest' }); } else if (grid.children.length >= total) st.back = false; else setTimeout(loadMore, 0); }
      if (page === 1 && r.items[0]) renderHead(r.items[0]);
    } catch (e) { if (page === 0) grid.append(h('div', { class: 'empty' }, e.message)); }
    // a reload while this page was on its way (the categories arrive twice: cached, then fresh) threw
    // this answer away: load the new list now, or the grid would stay empty
    finally { loading = false; more.hidden = true; if (t !== tok && alive) loadMore(); }
  }
  function tile(k, it) {
    rememberItem(k, it);
    const rt = ratingText(it.rating);
    const t = h('button', { class: 'tile focusable', 'data-id': it.id },
      h('img', { class: 'poster', loading: 'lazy', src: api.img(it.poster), alt: '', onerror: function () { this.style.opacity = '.15'; } }),
      rt ? h('span', { class: 'rbadge' }, '★ ' + rt) : null,
      inMyList(it.key) ? h('span', { class: 'fbadge' }, '★') : null,
      it.locked ? h('span', { class: 'lbadge', title: 'Locked (parental PIN)' }, '🔒') : null,
      h('div', { class: 'cap' }, it.name));
    onItemMenu(t, () => itemMenu(k, it));
    t.onclick = () => { st.focusId = it.id; st.back = true; location.hash = `detail:${k}:${it.id}`; };
    t.onfocus = () => { focused = it; headSoon(it); const rest = [...grid.children].indexOf(t); if (rest > grid.children.length - 18) loadMore(); };
    t.onmouseenter = () => headSoon(it);
    return t;
  }
  const headSoon = debounce((it) => { if (alive) renderHead(it); }, 250);
  function myListItem(k, it) { return { key: it.key, kind: k, name: it.name, poster: it.poster, id: it.id, ext: it.ext, rating: it.rating, year: it.year }; }
  /** The item menu (right-click / long press / M key): open, My list, and for shows watched / unwatched. */
  function itemMenu(k, it) {
    const items = [
      { label: 'Open', action: () => { location.hash = `detail:${k}:${it.id}`; } },
      { label: inMyList(it.key) ? '★ Remove from My list' : '☆ Add to My list', action: () => { const a = toggleMyList(myListItem(k, it)); toast(a ? '★ Added to My list' : 'Removed from My list'); } },
    ];
    if (k === 'series') {
      items.push({ label: '✓ Mark the show as watched', action: () => markSeries(it.id, true) });
      items.push({ label: '↺ Mark the show as unwatched', action: () => markSeries(it.id, false) });
    }
    menu(it.name || '', items);
  }
  function renderHead(it) {
    const my = ++headTok;
    head.innerHTML = '';
    if (!it) { head.append(h('div', { class: 'vh-title' }, title)); return; }
    const rt = ratingText(it.rating);
    const draw = (d) => {
      if (my !== headTok) return;
      head.innerHTML = '';
      const bdUrl = (d && d.backdrop) || it.poster;
      head.append(h('div', { class: 'vh-bg', style: { backgroundImage: bdUrl ? `url("${api.img(bdUrl)}")` : 'none' } }));
      setBackdrop(bdUrl);
      const box = h('div', { class: 'vh-text' },
        h('div', { class: 'vh-title' }, it.name, rt ? h('span', { class: 'rbadge inline' }, '★ ' + rt) : null),
        h('div', { class: 'vh-facts' }, factsLine(kind, it, d)));
      if (d && d.cast) box.append(h('div', { class: 'vh-cast' }, 'Cast: ' + d.cast));
      if (d && d.director) box.append(h('div', { class: 'vh-cast' }, 'Director: ' + d.director));
      if (d && d.plot) box.append(h('div', { class: 'vh-plot' }, d.plot));
      head.append(box);
    };
    draw(null);
    vodInfo(kind, it.id).then(draw).catch(() => {});
  }
  const onScroll = () => { if (main.scrollTop + main.clientHeight > main.scrollHeight - 800) loadMore(); };
  main.addEventListener('scroll', onScroll);
  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && e.key === 'ArrowLeft') nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && document.activeElement.classList.contains('focusable')) { document.activeElement.click(); return true; }
      if ((e.key === 'f' || e.key === 'F') && focused) { toggleMyList(myListItem(kind, focused));
        toast(inMyList(focused.key) ? '★ Added to My list' : 'Removed from My list'); return true; }
      if ((e.key === 'm' || e.key === 'M' || e.key === 'ContextMenu') && document.activeElement && document.activeElement.classList.contains('has-menu')) {
        document.activeElement.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })); return true; }
      if (e.key === 'Escape' || e.key === 'Backspace') { nav.focusActive(); return true; }
      return false;
    },
    focusIn() { const f = cw.querySelector('.tile') || grid.querySelector('.tile') || chips.querySelector('.chip'); if (f) f.focus(); },
    destroy() { alive = false; main.removeEventListener('scroll', onScroll); clearBackdrop(); window.removeEventListener('tvbro-series', onSeries); window.removeEventListener('tvbro-vod-closed', onSeries); },
  };
}

// ---------------------------------------------------------------- details
export function vodDetail(main, kind, id, nav) {
  main.innerHTML = '';
  const it = items.get(kind + ':' + id) || { id, key: (kind === 'movie' ? 'm|p0|' : 's|p0|') + id, name: '', poster: null };
  const scr = h('div', { class: 'screen dt' }, h('div', { class: 'loading' }, 'Loading…'));
  main.append(scr);
  let alive = true, d = null, ss = null;     // ss: this show's progress (server)
  const refreshSeries = async () => { if (kind === 'series') { try { ss = await loadSeriesState(id); } catch {} } };
  const onClosed = async () => { if (!alive || !d) return; await refreshSeries(); if (alive) render(); };
  const onSeries = () => { if (alive && d) { ss = seriesState.get(String(id)) || ss; render(true); } };
  window.addEventListener('tvbro-vod-closed', onClosed);
  window.addEventListener('tvbro-series', onSeries);
  Promise.all([vodInfo(kind, id, true), refreshSeries()]).then(([x]) => { if (alive) { d = x; render(); } }).catch((e) => { scr.innerHTML = ''; scr.append(h('div', { class: 'empty' }, 'Could not load: ' + e.message)); });
  const epProg = (e) => (ss && ss.episodes && ss.episodes[e.id]) || null;

  function render(keepFocus) {
    const ae = document.activeElement, fk = keepFocus && ae && scr.contains(ae) ? ae.dataset.fk : null;
    const sy = main.scrollTop;
    const name = it.name || d.name || '';
    it.name = name; it.poster = it.poster || d.cover;
    setBackdrop(d.backdrop || it.poster);
    scr.innerHTML = '';
    const rt = ratingText(it.rating || d.rating);
    const top = h('div', { class: 'dt-top' },
      h('img', { class: 'dt-poster', src: api.img(it.poster), alt: '', onerror: function () { this.style.opacity = '.15'; } }),
      h('div', { class: 'dt-info' },
        h('h1', { class: 'dt-title' }, name, rt ? h('span', { class: 'rbadge inline' }, '★ ' + rt) : null),
        h('div', { class: 'vh-facts' }, factsLine(kind, it, d)),
        d.director ? h('div', { class: 'vh-cast' }, 'Director: ' + d.director) : null,
        d.cast ? h('div', { class: 'vh-cast' }, 'Cast: ' + d.cast) : null,
        d.plot ? h('p', { class: 'dt-plot' }, d.plot) : null,
        actionRow()));
    scr.append(h('div', { class: 'dt-bg', style: { backgroundImage: (d.backdrop || it.poster) ? `url("${api.img(d.backdrop || it.poster)}")` : 'none' } }), top);
    if (kind === 'series') for (const s of seasonsOf(d.episodes)) scr.append(seasonRow(s));
    const back = fk && scr.querySelector(`[data-fk="${CSS.escape(fk)}"]`);
    if (back) { back.focus({ preventScroll: true }); main.scrollTop = sy; }
    else { const first = scr.querySelector('.dt-actions .btn'); if (first) first.focus(); }
  }
  const epName = (e) => `${epCode(e)} · ${cleanEpisodeTitle(it.name, e.title)}`;
  function epMenu(e) {
    const p = epProg(e);
    menu(epName(e), [
      { label: p && !p.done && p.secs > 10 ? `▶ Resume (${fmtDur(p.secs)})` : '▶ Play', action: () => playEpisode(it, d, e) },
      ...(p && !p.done && p.secs > 10 ? [{ label: '⟲ Play from start', action: () => playEpisode(it, d, e, { fromStart: true }) }] : []),
      p && p.done ? { label: '↺ Mark as unwatched', action: () => markSeries(id, false, { epId: String(e.id) }, epCode(e)) }
        : { label: '✓ Mark as watched', action: () => markSeries(id, true, { epId: String(e.id) }, epCode(e)) },
    ]);
  }
  function seasonMenu(s) {
    menu('Season ' + s.season, [
      { label: '✓ Mark season as watched', action: () => markSeries(id, true, { season: s.season }, 'Season ' + s.season) },
      { label: '↺ Mark season as unwatched', action: () => markSeries(id, false, { season: s.season }, 'Season ' + s.season) },
    ]);
  }
  function actionRow() {
    const row = h('div', { class: 'dt-actions' });
    if (kind === 'movie') {
      const r = getResume('m|' + id);
      const label = r && !r.done && r.dur ? `▶ Resume · ${fmtDur(r.dur - r.secs)} left` : '▶ Play';
      row.append(h('button', { class: 'btn focusable', onclick: () => playMovie(it, d) }, label));
      if (r && !r.done) row.append(h('button', { class: 'btn secondary focusable', onclick: () => playMovie(it, d, true) }, '⟲ From start'));
    } else {
      const eps = seasonsOf(d.episodes).flatMap((s) => s.eps);
      const n = ss && ss.next, find = (x) => eps.find((e) => String(e.id) === String(x.id)) || x;
      if (ss && ss.finished && eps[0]) {
        row.append(h('button', { class: 'btn focusable', 'data-fk': 'main', onclick: () => playEpisode(it, d, eps[0], { fromStart: true }) }, `▶ Watch again ${epCode(eps[0])}`));
      } else if (n && n.resume && n.secs) {
        row.append(h('button', { class: 'btn focusable', 'data-fk': 'main', onclick: () => playEpisode(it, d, find(n)) }, `▶ Resume ${epCode(n)} (${fmtDur(n.secs)})`));
        row.append(h('button', { class: 'btn secondary focusable', onclick: () => playEpisode(it, d, find(n), { fromStart: true }) }, '⟲ Play from start'));
      } else {
        const e = n ? find(n) : eps[0];
        if (e) row.append(h('button', { class: 'btn focusable', 'data-fk': 'main', onclick: () => playEpisode(it, d, e, { fromStart: true }) }, `▶ Play ${epCode(e)}`));
      }
      const done = !!(ss && ss.finished);
      row.append(h('button', { class: 'btn secondary focusable', 'data-fk': 'markall', onclick: () => markSeries(id, !done) }, done ? '↺ Mark as unwatched' : '✓ Mark as watched'));
    }
    if (d.trailer) row.append(h('button', { class: 'btn secondary focusable', onclick: () => window.open(d.trailer, '_blank', 'noopener') }, '▷ Trailer'));
    const fav = h('button', { class: 'btn secondary focusable' }, inMyList(it.key) ? '★ In My list' : '☆ Add to My list');
    fav.onclick = () => { const a = toggleMyList({ key: it.key, kind, name: it.name, poster: it.poster, id, ext: it.ext || d.ext, rating: it.rating || d.rating, year: it.year || d.year });
      fav.textContent = a ? '★ In My list' : '☆ Add to My list'; toast(a ? 'Added to My list' : 'Removed from My list'); };
    row.append(fav);
    return row;
  }
  function seasonRow(s) {
    const counter = h('span', { class: 'sr-count' }, `${s.eps.length} episodes`);
    const strip = h('div', { class: 'sr-strip' });
    s.eps.forEach((e, i) => {
      const r = epProg(e);
      const card = h('button', { class: 'ep focusable' + (r && r.done ? ' watched' : ''), 'data-fk': 'e' + e.id },
        h('div', { class: 'ep-still' },
          h('img', { loading: 'lazy', alt: '', src: api.img(e.image || d.backdrop || it.poster), onerror: function () { this.style.opacity = '.12'; } }),
          r && r.done ? h('span', { class: 'ep-tick', title: 'Watched' }, '✓') : null,
          r && !r.done && r.dur && r.secs ? h('div', { class: 'progress ep-prog' }, h('i', { style: { width: Math.min(100, (r.secs / r.dur) * 100).toFixed(1) + '%' } })) : null),
        h('div', { class: 'ep-title' }, `E${e.num}. ${cleanEpisodeTitle(it.name, e.title)}`),
        e.duration ? h('div', { class: 'ep-sub' }, e.duration) : null);
      card.onfocus = () => { counter.textContent = `${i + 1} / ${s.eps.length}`; };
      onItemMenu(card, () => epMenu(e));
      card.onclick = () => playEpisode(it, d, e);
      strip.append(card);
    });
    const more = h('button', { class: 'btn secondary focusable sr-menu', 'data-fk': 's' + s.season, title: 'Season menu', onclick: () => seasonMenu(s) }, '⋯');
    return h('div', { class: 'sr' }, h('div', { class: 'sr-head' }, h('h3', {}, 'Season ' + s.season), counter, more), strip);
  }
  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && e.key === 'ArrowLeft') nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if ((e.key === 'm' || e.key === 'M' || e.key === 'ContextMenu') && document.activeElement && document.activeElement.classList.contains('has-menu')) {
        document.activeElement.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })); return true; }
      if (e.key === 'Escape' || e.key === 'Backspace') { history.back(); return true; }
      return false;
    },
    focusIn() { const f = scr.querySelector('.focusable'); if (f) f.focus(); },
    destroy() { alive = false; clearBackdrop(); window.removeEventListener('tvbro-vod-closed', onClosed); window.removeEventListener('tvbro-series', onSeries); },
  };
}

// ---------------------------------------------------------------- playback
const vodClosed = () => window.dispatchEvent(new Event('tvbro-vod-closed'));
/** '01:19:13' / '45:10' / seconds -> seconds (0 when unknown). */
const durSecs = (v) => { if (!v) return 0; if (typeof v === 'number') return v; const p = String(v).split(':').map(Number); return p.some(isNaN) ? 0 : p.reduce((a, b) => a * 60 + b, 0); };
export function playMovie(it, d, fromStart) {
  if (fromStart) clearResume('m|' + it.id);
  const ext = (d && d.ext) || it.ext;
  openPlayer({ vod: { kind: 'movie', id: it.id, ext }, duration: durSecs(d && (d.duration || (d.runtime && d.runtime * 60))), title: it.name, mode: 'vod', resumeKey: 'm|' + it.id, onClose: vodClosed,
    resumeMeta: { kind: 'movie', id: it.id, ext, title: it.name, poster: it.poster } });
}
/**
 * Play an episode of a show: resumes its own position (unless fromStart), reports progress to the
 * server per series, re-applies the show's captions / audio language, and offers "Up next"
 * (the next episode in order, into the next season). An open player switches episodes in place.
 */
export function playEpisode(series, d, e, o = {}) {
  const sid = String(series.id);
  const st = seriesState.get(sid);
  if (!st) {     // (normally loaded with the show's page / Continue watching)
    loadSeriesState(sid).catch(() => seriesState.set(sid, { id: sid, episodes: {} })).then(() => playEpisode(series, d, e, o));
    return;
  }
  const p = st.episodes && st.episodes[e.id];
  const startAt = o.fromStart ? 0 : (p && !p.done && p.secs > 10 && (!p.dur || p.secs < p.dur - 30) ? p.secs : 0);
  const popts = {
    vod: { kind: 'episode', id: e.id, ext: e.ext, series: sid }, duration: durSecs(e.duration), title: series.name,
    subtitle: `${epCode(e)} · ${cleanEpisodeTitle(series.name, e.title)}`, mode: 'vod', onClose: vodClosed, startAt,
    progress: (secs, dur, ended) => reportProgress(series, e, secs, dur, ended),
    subsPref: st.subs || null, onSubsChange: (v) => saveSeriesPrefs(sid, { subs: v }),
    audioPref: st.audio || null, onAudioChange: (v) => saveSeriesPrefs(sid, { audio: v }),
    upNext: () => {
      const n = episodeAfter(d.episodes, e.id);
      return n ? { label: `${epCode(n)} – ${cleanEpisodeTitle(series.name, n.title)}`, go: () => playEpisode(series, d, n) } : null;
    },
    episodeNav: (dir, auto) => { const n = episodeAfter(d.episodes, e.id, dir); if (n) playEpisode(series, d, n); else if (!auto) toast(dir > 0 ? 'Last episode' : 'First episode'); },
  };
  if (!playerReplace(popts)) openPlayer(popts);
}
