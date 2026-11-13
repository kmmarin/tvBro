// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Search: mic (Web Speech API where available), search box (not auto-focused), history
// chips (last 20 + clear), result rows: Movies, Shows, Channels (now playing + progress),
// Programs grouped by channel (catch-up clock / progress + minutes left / air time) with a
// details card: Watch (live or catch-up), Remind me, Add to My list.

import { api, inMyList, searchHistory, addSearchHistory, clearSearchHistory } from './api.js';
import { h, toast, fmtTime, fmtDate, nowSec, spatialMove, debounce } from './util.js';
import * as G from './guide.js';
import { playLive, watchProg } from './playback.js';
import { hasReminder, toggleReminder } from './reminders.js';
import { rememberItem } from './vod.js';
import { toggleFavChannel } from './tv.js';

let lastQuery = '';

export function searchScreen(main, nav, initial = '') {
  main.innerHTML = '';
  if (initial) lastQuery = initial;
  const box = h('input', { class: 'search-box focusable', placeholder: 'Search channels, movies, shows, programmes…', value: lastQuery, enterkeyhint: 'search' });
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const mic = SR ? h('button', { class: 'mic focusable', title: 'Search by voice' }, '🎤') : null;
  const top = h('div', { class: 'sbar' }, mic, box);
  const chips = h('div', { class: 'chips hist' });
  const results = h('div', { class: 'sres' });
  const detail = h('div', { class: 'sdetail glass', hidden: true });
  const scr = h('div', { class: 'screen search' }, h('h1', {}, 'Search'), top, chips, results, detail);
  main.append(scr);
  let alive = true, runTok = 0;

  function renderHistory() {
    const l = searchHistory();
    chips.replaceChildren(...l.map((q) => h('button', { class: 'chip focusable', onclick: () => { box.value = q; run(q, true); } }, q)),
      ...(l.length ? [h('button', { class: 'chip focusable clear', onclick: () => { clearSearchHistory(); renderHistory(); } }, '✕ Clear')] : []));
  }
  function addHist(q) { if (q.length < 2) return; addSearchHistory(q); renderHistory(); }
  renderHistory();
  const runSoon = debounce((q) => run(q), 200);
  box.addEventListener('input', () => runSoon(box.value.trim()));
  box.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); run(box.value.trim(), true); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); box.blur(); spatialMove(scr, 'ArrowDown') || results.querySelector('.focusable')?.focus(); }
    else if (e.key === 'Escape') { box.blur(); nav.focusActive(); }
  });
  if (mic) mic.onclick = () => {
    const rec = new SR(); rec.lang = navigator.language || 'en-US'; rec.interimResults = false; rec.maxAlternatives = 1;
    mic.classList.add('on'); toast('Listening…');
    rec.onresult = (ev) => { const q = ev.results[0][0].transcript.trim(); box.value = q; run(q, true); };
    rec.onend = () => mic.classList.remove('on');
    rec.onerror = () => { mic.classList.remove('on'); toast('Voice search unavailable'); };
    try { rec.start(); } catch { mic.classList.remove('on'); }
  };
  let ctl = null, shown = '';               // request in flight; query whose results are on screen
  if (lastQuery) run(lastQuery);

  async function run(q, remember) {
    lastQuery = q;
    if (remember) addHist(q);
    const my = ++runTok;
    if (ctl) ctl.abort();                     // a newer query cancels the one in flight
    ctl = null;
    if (q.length < 2) { results.replaceChildren(); results.classList.remove('busy'); shown = ''; return; }
    if (q === shown) { results.classList.remove('busy'); return; }
    // keep the previous results on screen (dimmed) instead of flashing a placeholder
    if (results.childElementCount) results.classList.add('busy');
    else results.replaceChildren(h('div', { class: 'loading' }, 'Searching…'));
    const c = ctl = new AbortController();
    let r;
    try { r = await api.search(q, c.signal); }
    catch (e) {
      if (my === runTok && e.name !== 'AbortError') { results.classList.remove('busy'); results.replaceChildren(h('div', { class: 'empty' }, e.message)); }
      return;
    }
    if (my !== runTok || !alive) return;
    ctl = null; shown = q;
    results.classList.remove('busy');
    if (r.indexing) {
      shown = '';
      results.replaceChildren(h('div', { class: 'loading' }, 'Preparing search… (first run after an update, a few seconds)'));
      setTimeout(() => { if (alive && my === runTok) run(q); }, 3000);
      return;
    }
    const now = nowSec(), rows = [];
    const row = (title, tiles) => tiles.length ? h('section', { class: 'srow' }, h('h3', {}, `${title} · ${tiles.length}`), h('div', { class: 'srow-strip' }, ...tiles)) : null;
    rows.push(row('Movies', r.movies.map((m) => posterTile('movie', m))));
    rows.push(row('Shows', r.series.map((s) => posterTile('series', s))));
    rows.push(row('Channels', r.channels.map((c) => {
      const p = c.now, pr = p ? G.progress(p, now) : null;
      const t = h('button', { class: 'stile focusable' },
        h('div', { class: 'chlogo' }, h('img', { loading: 'lazy', decoding: 'async', alt: '', src: c.logo ? api.img(c.logo) : '' })),
        h('div', { class: 'st-t' }, (inMyList(c.key) ? '★ ' : '') + c.name),
        h('div', { class: 'st-s' }, p ? `${p.title} · ${pr.minLeft} min left` : 'No guide data'),
        p ? h('div', { class: 'progress' }, h('i', { style: { width: (pr.frac * 100).toFixed(1) + '%' } })) : null);
      t.onclick = () => playLive(c);
      return t;
    })));
    // Programs, grouped by channel on the server: [{ch, items:[{start, stop, title, sub?, ep?, st}]}]
    const ptiles = [];
    for (const { ch, items: progs } of r.programs) for (const p of progs) {
      const si = G.searchInfo(p, now, ch); if (!si) continue;
      const badge = si.kind === 'now' ? h('span', { class: 'badge live' }, 'LIVE') : si.kind === 'catchup' ? h('span', { class: 'badge catchup', title: 'Catch-up' }, '⟲ ' + fmtTime(p.start))
        : h('span', { class: 'badge' }, `${fmtDate(p.start)} ${fmtTime(p.start)}`);
      const t = h('button', { class: 'stile prog focusable' },
        h('div', { class: 'st-ch' }, h('img', { class: 'mini', alt: '', loading: 'lazy', decoding: 'async', src: ch.logo ? api.img(ch.logo) : '' }), ch.name),
        h('div', { class: 'st-t' }, p.title), h('div', { class: 'st-s' }, badge, si.kind === 'now' ? ` ${si.minLeft} min left` : si.kind === 'future' ? (hasReminder(ch.key, p.start) ? ' ⏰ reminder set' : ` in ${si.inMin < 120 ? si.inMin + ' min' : Math.round(si.inMin / 60) + ' h'}`) : ''),
        si.kind === 'now' ? h('div', { class: 'progress' }, h('i', { style: { width: (si.frac * 100).toFixed(1) + '%' } })) : null);
      t.onclick = () => showDetail(ch, p, t);
      ptiles.push(t);
    }
    rows.push(row('Programs', ptiles));
    const any = rows.filter(Boolean);
    results.replaceChildren(...(any.length ? any : [h('div', { class: 'empty' }, `No results for “${q}”.`)]));
  }
  function posterTile(kind, it) {
    rememberItem(kind, it);
    const t = h('button', { class: 'tile sposter focusable' },
      h('img', { class: 'poster', loading: 'lazy', decoding: 'async', alt: '', src: it.poster ? api.img(it.poster) : '' }),
      h('div', { class: 'cap' }, it.name));
    t.onclick = () => { location.hash = `detail:${kind}:${it.id}`; };
    return t;
  }
  function showDetail(ch, p, from) {
    const now = nowSec(), si = G.searchInfo(p, now, ch);
    const acts = [];
    if (si && si.watch) acts.push(h('button', { class: 'btn focusable', onclick: () => { hide(); watchProg(ch, p, {}); } }, si.watch === 'live' ? '▶ Watch live' : '⟲ Watch catch-up'));
    if (si && si.kind === 'future') acts.push(h('button', { class: 'btn focusable', onclick: () => { const on = toggleReminder({ key: ch.key, channel: ch.name, title: p.title, start: p.start, stop: p.stop, logo: ch.logo }); toast(on ? 'Reminder set for ' + fmtTime(p.start) : 'Reminder removed'); showDetail(ch, p, from); } },
      hasReminder(ch.key, p.start) ? '⏰ Cancel reminder' : '⏰ Remind me'));
    acts.push(h('button', { class: 'btn secondary focusable', onclick: () => { toggleFavChannel(ch); showDetail(ch, p, from); } }, inMyList(ch.key) ? '★ Channel in My list' : '☆ Add channel to My list'));
    acts.push(h('button', { class: 'btn secondary focusable', onclick: () => { playLive(ch); hide(); } }, 'Go to channel'));
    acts.push(h('button', { class: 'btn secondary focusable', onclick: hide }, 'Close'));
    // the result list carries no descriptions: the card asks for this programme's own
    const desc = h('div', { class: 'sd-d' }, p.desc || '');
    detail.replaceChildren(
      h('div', { class: 'sd-ch' }, ch.name), h('div', { class: 'sd-t' }, p.title),
      h('div', { class: 'sd-s' }, `${fmtDate(p.start)} ${fmtTime(p.start)} – ${fmtTime(p.stop)}` + (p.ep ? ` · ${p.ep}` : '') + (p.sub ? ` · ${p.sub}` : '')),
      desc,
      h('div', { class: 'dt-actions' }, ...acts));
    detail.hidden = false; detail._from = from;
    acts[0].focus();
    if (p.desc === undefined) {
      api.prog(ch.key, p.start).then((full) => { p.desc = full.desc || ''; if (desc.isConnected) desc.textContent = p.desc; })
        .catch(() => { p.desc = ''; });
    }
  }
  function hide() { detail.hidden = true; if (detail._from) detail._from.focus(); }
  return {
    onKey(e) {
      if (!detail.hidden) {
        if (e.key === 'Escape' || e.key === 'Backspace') { hide(); return true; }
        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') { spatialMove(detail, e.key); return true; }
        if (e.key === 'Enter') { document.activeElement.click(); return true; }
        return e.key.startsWith('Arrow');
      }
      if (e.key.startsWith('Arrow')) {
        if (!spatialMove(scr, e.key)) { if (e.key === 'ArrowLeft') nav.focusActive(); else if (e.key === 'ArrowUp') box.focus(); }
        return true;
      }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if (e.key === 'Escape' || e.key === 'Backspace') { nav.focusActive(); return true; }
      if (e.key.length === 1 && /\S/.test(e.key)) { box.focus(); return false; }   // typing jumps into the box
      return false;
    },
    focusIn() { box.focus(); },
    destroy() { alive = false; if (ctl) ctl.abort(); },
  };
}
