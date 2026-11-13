// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// TV screen: groups column; header with a live preview + the programme under the cursor;
// a time-proportional guide grid (virtualised rows, guide paged from the backend).

import { api, cacheGet, cacheSet, myList, inMyList, toggleMyList } from './api.js';
import { layoutEdit, addToGroupMenu } from './editor.js';
import { h, esc, fmtTime, fmtDate, nowSec, toast, menu, debounce } from './util.js';
import * as G from './guide.js';
import { PreviewPlayer } from './engine.js';
import { prefs, setPref } from './prefs.js';
import { state as play, watchProg as watchProg0, playLive as playLive0, resumeFullscreen, setCurrent } from './playback.js';
import { setDock, playerIsOpen } from './player.js';
import { uiLoad, uiSave } from './uistate.js';
import { hasReminder, toggleReminder } from './reminders.js';
import { isUnlocked, setLock, unlock } from './parental.js';
import { guideMapDialog } from './epgpick.js';
import { openLiveExternally } from './external.js';

const ROW_H = 52, PAGE = 200;
let LABEL_W = 240, narrow = false;

// remembered across screen switches
// (group, focused row, scroll position and whether the guide or the group list had focus)
const mem = { group: null, row: 0, winStart: null, follow: true, scroll: 0, zone: 'grid' };
{ const u = uiLoad().tv; if (u) { mem.group = u.group != null ? u.group : null; mem.row = u.row | 0; mem.scroll = u.scroll | 0; mem.zone = u.zone === 'groups' || u.zone === 'label' ? u.zone : 'grid'; } }
const saveMem = () => uiSave({ tv: { group: mem.group, row: mem.row, scroll: mem.scroll, zone: mem.zone } });
const guideCache = new Map();   // channel key -> { chunks: Map<chunk, progs[]>, progs: merged[] }

export function favKeys() { return myList().filter((x) => x.kind === 'live').map((x) => x.key); }
export function toggleFavChannel(c) {
  const added = toggleMyList({ key: c.key, kind: 'live', name: c.name, logo: c.logo, ch: c });
  toast(added ? '★ Added to Favourites' : 'Removed from Favourites');
  return added;
}

export function tvScreen(main, nav) {
  main.innerHTML = '';
  const root = h('div', { class: 'tv2' });
  const gcol = h('div', { class: 'gcol' }, h('div', { class: 'gcol-h' }, 'TV'));
  const glist = h('div', { class: 'glist' }); gcol.append(glist);

  const pvVideo = h('video', { muted: true, playsinline: true, class: 'pv-video' });
  const pvNote = h('div', { class: 'pv-note', hidden: true, role: 'status' });
  const pvBox = h('div', { class: 'pv', title: 'Click: full screen' }, pvVideo, h('div', { class: 'pv-off' }, ''), pvNote);
  const info = h('div', { class: 'hd-info' });
  const side = h('div', { class: 'hd-side' });
  const header = h('div', { class: 'hd' }, pvBox, info, side);

  const rulerLabel = h('div', { class: 'ruler-label' });
  const rulerTrack = h('div', { class: 'ruler-track' });
  const ruler = h('div', { class: 'ruler' }, rulerLabel, rulerTrack,
    h('div', { class: 'ruler-btns' },
      h('button', { class: 'rb', title: 'Earlier (30 min)', onclick: () => shift(-G.STEP) }, '‹'),
      h('button', { class: 'rb', title: 'Now', onclick: () => { mem.winStart = G.initialStart(nowSec()); mem.follow = true; renderAll(); } }, 'Now'),
      h('button', { class: 'rb', title: 'Later (30 min)', onclick: () => shift(G.STEP) }, '›')));
  const body = h('div', { class: 'gbody', tabindex: '-1' });
  const spacer = h('div', { class: 'gspacer' });
  const nowLine = h('div', { class: 'now-line' });
  const empty = h('div', { class: 'gempty', hidden: true });
  body.append(spacer, empty);
  const bodyWrap = h('div', { class: 'gbody-wrap' }, body, nowLine);
  const right = h('div', { class: 'tvr' }, header, ruler, bodyWrap);
  root.append(gcol, right); main.append(root);

  const preview = new PreviewPlayer(pvVideo, () => { pvBox.querySelector('.pv-off').textContent = 'Playing in another window · click to play here'; },
    (msg) => { pvNote.textContent = msg || ''; pvNote.hidden = !msg; });
  let groups = [], chans = [], total = 0, loadingPages = new Set(), zone = mem.zone, gi = 0, focusKey = null;
  // what plays from here remembers its group, so the guide can come back to it
  const playLive = (c, ctx = {}) => playLive0(c, { ...ctx, group: mem.group, row: mem.row });
  const watchProg = (c, p, ctx = {}) => watchProg0(c, p, { ...ctx, group: mem.group, row: mem.row });
  let focusProg = null, alive = true, pxPerMin = 4, groupToken = 0, playerOpen = false;
  const rows = new Map(); // idx -> row element
  // a device that never showed the guide (new / cleared browser) opens on the resumed channel
  let focusResumed = !uiLoad().tv && !!play.playing;
  if (mem.group == null) mem.group = prefs().lastGroup || '__all';
  if (mem.winStart == null || mem.follow) mem.winStart = G.initialStart(nowSec());
  mem.winStart = G.clampWin(mem.winStart, nowSec());   // a window left behind now comes back to now

  // ---------- groups ----------
  (async () => {
    const cached = await cacheGet('groups');
    if (cached) setGroups(cached);
    try { const g = (await api.groups()).groups; cacheSet('groups', g); setGroups(g); }
    catch (e) { if (!cached) glist.append(h('div', { class: 'empty' }, 'Could not load: ' + e.message)); }
  })();
  // the household's layout changed (rename / hide / own groups): reload the lists
  async function onLayout() {
    if (!alive) return;
    try { const g = (await api.groups()).groups; cacheSet('groups', g); groups = g; gi = Math.max(0, groups.findIndex((x) => x.id === mem.group)); renderGroups(); loadGroup(groups[gi] ? groups[gi].id : '__all'); } catch {}
  }
  window.addEventListener('tvbro-layout', onLayout);
  const onUnlocked = () => { if (alive) { renderRows(); renderHeader(); } };
  window.addEventListener('tvbro-unlocked', onUnlocked);
  // a channel got another guide channel (manual mapping): its rows load again (and again while the
  // server fetches the guide for it)
  const epgTimers = [];
  const onEpgMap = (e) => {
    const k = e.detail && e.detail.key; if (!k || !alive) return;
    const again = () => { if (!alive) return; guideCache.delete(k); renderRows(); renderHeader(); };
    again();
    if (e.detail.syncing) for (const ms of [2000, 5000, 10000, 20000, 40000]) epgTimers.push(setTimeout(again, ms));
  };
  window.addEventListener('tvbro-epgmap', onEpgMap);
  function setGroups(gs) {
    const first = !groups.length;
    groups = gs;
    gi = Math.max(0, groups.findIndex((g) => g.id === mem.group));
    renderGroups();
    if (first) loadGroup(groups[gi] ? groups[gi].id : '__all');
  }
  function renderGroups() {
    glist.innerHTML = '';
    groups.forEach((g, i) => {
      const n = g.id === '__fav' ? favKeys().length : g.count;
      const b = h('button', { class: 'gitem' + (g.id === mem.group ? ' sel' : '') + (zone === 'groups' && i === gi ? ' kf' : ''), 'data-i': i, tabindex: '-1' },
        h('span', { class: 'gname' }, (g.locked ? '🔒 ' : '') + g.name), n != null ? h('span', { class: 'count' }, String(n)) : null);
      b.onclick = () => { gi = i; zone = 'groups'; selectGroup(g.id); };
      glist.append(b);
    });
    const kf = glist.querySelector('.kf'); if (kf) kf.scrollIntoView({ block: 'nearest' });
  }
  function selectGroup(id) {
    if (id !== mem.group) { mem.group = id; mem.row = 0; mem.scroll = 0; setPref('lastGroup', id); loadGroup(id); }
    renderGroups();
  }
  const selectGroupSoon = debounce((id) => selectGroup(id), 300);

  async function loadGroup(id) {
    const tok = ++groupToken;
    chans = []; total = 0; loadingPages.clear(); rows.forEach((r) => r.remove()); rows.clear();
    empty.hidden = true;
    if (id === '__fav') {
      const keys = favKeys();
      // show stored copies immediately, then refresh from the backend
      chans = myList().filter((x) => x.kind === 'live').map((x) => x.ch || { key: x.key, name: x.name, logo: x.logo });
      total = chans.length; layout();
      if (keys.length) try { const r = await api.channelsByKeys(keys); if (tok === groupToken) { chans = r.items; total = chans.length; layout(); } } catch {}
    } else {
      const ck = 'chan:' + id + ':1';
      const cached = await cacheGet(ck);
      if (tok !== groupToken) return;
      if (cached) { applyPage(1, cached); }
      try { const r = await api.channels(id, 1, '', PAGE); if (tok !== groupToken) return; cacheSet(ck, r); applyPage(1, r); }
      catch (e) { if (!cached) { empty.hidden = false; empty.textContent = 'Could not load channels: ' + e.message; } }
    }
    if (!total) { empty.hidden = false; empty.textContent = id === '__fav' ? 'No favourite channels yet. Press F on a channel to add it.' : 'No channels in this group.'; }
    if (focusKey) { const i = chans.findIndex((c) => c && c.key === focusKey); if (i >= 0) mem.row = i; focusKey = null; mem.scroll = -1; }
    mem.row = Math.min(mem.row, Math.max(0, total - 1));
    // back to the same place: the remembered scroll position if the focused row is in it
    layout();
    const vh = body.clientHeight || 600, rowTop = mem.row * ROW_H;
    body.scrollTop = mem.scroll >= 0 && rowTop >= mem.scroll && rowTop + ROW_H <= mem.scroll + vh ? mem.scroll : Math.max(0, rowTop - Math.floor(vh / 2 - ROW_H / 2));
    mem.scroll = body.scrollTop;
    renderAll();
    if (focusResumed && total) { focusResumed = false; focusPlaying(); }
  }
  /** Focus the channel that is playing, in its group (the guide coming back from the player). */
  function focusPlaying() {
    const pc = play.playing; if (!pc || !alive) return;
    const i = chans.findIndex((c) => c && c.key === pc.key);
    if (i >= 0) {
      mem.row = i; zone = 'grid'; focusProg = G.progAt(progsOf(chans[i]), nowSec());
      const vh = body.clientHeight || 600, top = i * ROW_H;
      if (top < body.scrollTop || top + ROW_H > body.scrollTop + vh) body.scrollTop = Math.max(0, top - Math.floor(vh / 2 - ROW_H / 2));
      renderGroupsKf(); renderRows(); renderHeader();
      return;
    }
    const g = (play.groupId && groups.find((x) => x.id === play.groupId)) || groups.find((x) => x.name === pc.group) || groups.find((x) => x.id === '__all');
    if (!g || g.id === mem.group) return;
    mem.group = g.id; mem.row = play.groupId === g.id && play.row != null ? play.row : 0; mem.scroll = -1; zone = 'grid';
    gi = Math.max(0, groups.indexOf(g)); focusKey = pc.key;
    renderGroups(); loadGroup(g.id);
  }
  function applyPage(page, r) {
    total = r.total;
    for (let i = 0; i < r.items.length; i++) {
      const c = chans[(page - 1) * r.size + i] = r.items[i];
      if (play.playing && c.key === play.playing.key && !!c.locked !== !!play.playing.locked) play.playing.locked = !!c.locked;   // locked / unlocked since
    }
    layout();
  }
  function ensurePages(first, last) {
    if (mem.group === '__fav') return;
    for (let i = first; i <= last; i++) {
      if (i >= total || chans[i]) continue;
      const page = Math.floor(i / PAGE) + 1;
      if (loadingPages.has(page)) continue;
      loadingPages.add(page);
      const tok = groupToken;
      api.channels(mem.group, page, '', PAGE).then((r) => { if (tok === groupToken) { applyPage(page, r); renderRows(); } })
        .catch(() => {}).finally(() => loadingPages.delete(page));
    }
  }

  // ---------- guide paging ----------
  let guideTimer = null;
  function progsOf(c) { const g = c && guideCache.get(c.key); return g ? g.progs : null; }
  function haveWindow(c) {
    const g = guideCache.get(c.key); if (!g) return false;
    return G.chunksFor(mem.winStart, mem.winStart + G.WINDOW_SEC).every((k) => g.chunks.has(k));
  }
  function requestGuide() { clearTimeout(guideTimer); guideTimer = setTimeout(fetchGuide, 60); }
  function fetchGuide() {
    const [first, last] = visibleRange(2);
    const need = new Map(); // chunk -> keys
    const ws = mem.winStart, we = ws + G.WINDOW_SEC;
    for (let i = first; i <= last; i++) {
      const c = chans[i]; if (!c) continue;
      const g = guideCache.get(c.key) || { chunks: new Map(), progs: [], pending: new Set() };
      guideCache.set(c.key, g); if (!g.pending) g.pending = new Set();
      for (const k of G.chunksFor(ws, we)) if (!g.chunks.has(k) && !g.pending.has(k)) {
        g.pending.add(k); if (!need.has(k)) need.set(k, []); need.get(k).push(c.key);
      }
    }
    const shift = (prefs().epgShiftMin || 0) * 60;
    for (const [k, keys] of need) {
      for (let i = 0; i < keys.length; i += 40) {
        const part = keys.slice(i, i + 40);
        api.guide(part, k * G.CHUNK - shift, (k + 1) * G.CHUNK - shift).then((r) => {
          for (const key of part) {
            const g = guideCache.get(key); if (!g) continue;
            const list = (r.guide[key] || []).map((p) => shift ? { ...p, start: p.start + shift, stop: p.stop + shift } : p);
            g.chunks.set(k, list); g.pending.delete(k);
            g.progs = G.mergeProgs([...g.chunks.values()]);
          }
          if (alive) { renderRows(); renderHeader(); }
        }).catch(() => { for (const key of part) { const g = guideCache.get(key); if (g) g.pending.delete(k); } });
      }
    }
  }

  // ---------- layout / rendering ----------
  function layout() {
    narrow = window.innerWidth < 900;
    root.classList.toggle('narrow', narrow);
    LABEL_W = narrow ? 118 : 240; root.style.setProperty('--lw', LABEL_W + 'px');
    spacer.style.height = (total * ROW_H) + 'px';
    // phones: a fixed scale and the grid scrolls sideways (channel names stay put)
    pxPerMin = narrow ? 4.4 : Math.max(200, body.clientWidth - LABEL_W) / G.WINDOW_MIN;
    const rowW = narrow ? (LABEL_W + G.WINDOW_MIN * pxPerMin) + 'px' : '';
    spacer.style.width = rowW || '1px';
    root.style.setProperty('--roww', rowW || 'auto');
  }
  function visibleRange(pad = 3) {
    const top = body.scrollTop, hgt = body.clientHeight || 600;
    const first = Math.max(0, Math.floor(top / ROW_H) - pad);
    const last = Math.min(total - 1, Math.ceil((top + hgt) / ROW_H) + pad);
    return [first, last];
  }
  function renderAll() { layout(); renderRuler(); renderRows(); renderHeader(); renderGroupsKf(); requestGuide(); }
  function renderGroupsKf() { if (zone !== 'nav') mem.zone = zone; glist.querySelectorAll('.gitem').forEach((b, i) => b.classList.toggle('kf', zone === 'groups' && i === gi)); }

  function renderRuler() {
    const now = nowSec(), ws = mem.winStart, we = ws + G.WINDOW_SEC;
    rulerLabel.innerHTML = `<div class="rl-date">${esc(fmtDate(ws < G.floor30(now) ? ws : now))}</div><div class="rl-clock">${esc(fmtTime(now))}</div>`;
    rulerTrack.innerHTML = G.ticks(ws, we).filter((t) => t < we).map((t) =>
      `<div class="tick" style="left:${G.tickX(t, ws, pxPerMin).toFixed(1)}px"><i></i>${esc(fmtTime(t))}</div>`).join('');
    rulerTrack.style.left = LABEL_W + 'px';
    syncHScroll();
    if (now >= ws && now < we) { nowLine.hidden = false; nowLine.style.left = (LABEL_W + G.tickX(now, ws, pxPerMin) - body.scrollLeft) + 'px'; }
    else nowLine.hidden = true;
  }

  function syncHScroll() {
    const x = body.scrollLeft || 0;
    rulerTrack.firstChild && (rulerTrack.style.transform = `translateX(${-x}px)`);
    if (!nowLine.hidden) { const now = nowSec(); nowLine.style.left = (LABEL_W + G.tickX(now, mem.winStart, pxPerMin) - x) + 'px'; nowLine.style.visibility = G.tickX(now, mem.winStart, pxPerMin) - x < 0 ? 'hidden' : ''; }
  }
  function chanNum(c, i) { return prefs().numbering === 'group' ? i + 1 : (c.number || ''); }
  function labelHtml(c, i) {
    if (!c) return `<div class="g-label"><span class="n"></span><span class="gname dim">…</span></div>`;
    const logo = c.logo ? `<img class="lg" loading="lazy" src="${esc(api.img(c.logo))}">` : '<span class="lg"></span>';
    const fav = inMyList(c.key);
    return `<div class="g-label"><span class="n">${esc(chanNum(c, i))}</span>${logo}<span class="gname">${esc(c.name)}</span>` +
      `${c.catchup ? '<span class="ic-cu" title="Catch-up">⟲</span>' : ''}` +
      `${c.locked ? '<span class="ic-lock" title="Locked (parental PIN)">🔒</span>' : ''}` +
      `<span class="g-fav${fav ? ' on' : ''}" role="button" title="${fav ? 'Remove from Favourites' : 'Add to Favourites'}">${fav ? '★' : '☆'}</span>` +
      `<span class="g-more" role="button" title="Menu">⋯</span></div>`;
  }
  function trackHtml(c) {
    const ws = mem.winStart, we = ws + G.WINDOW_SEC, now = nowSec();
    if (!c) return '';
    const progs = progsOf(c);
    if (!progs && !haveWindow(c)) return `<div class="g-blk gap loading" style="left:0;width:${(G.WINDOW_MIN * pxPerMin - 2).toFixed(1)}px"></div>`;
    let s = '';
    for (const seg of G.rowSegments(progs || [], ws, we)) {
      const b = G.block(seg.start, seg.stop, ws, we, pxPerMin); if (!b) continue;
      const st = `left:${b.x.toFixed(1)}px;width:${b.w.toFixed(1)}px`;
      if (seg.gap) { s += `<div class="g-blk gap" style="${st}">${b.w > 70 ? 'No guide data' : ''}</div>`; continue; }
      const p = seg.prog, state = G.progState(p, now, c);
      const foc = zone === 'grid' && focusProg && focusProg.start === p.start && chans[mem.row] === c;
      const rem = hasReminder(c.key, p.start) ? '<span class="bell">⏰</span>' : '';
      s += `<div class="g-blk ${state}${foc ? ' foc' : ''}" data-s="${p.start}" style="${st}">` +
        `<div class="t">${b.clippedLeft ? '‹ ' : ''}${rem}${esc(p.title)}</div>` +
        (b.w > 90 ? `<div class="tm">${esc(fmtTime(p.start))}${state === 'catchup' ? ' ⟲' : ''}</div>` : '') + '</div>';
    }
    return s;
  }
  function renderRows() {
    if (!alive) return;
    if (zone !== 'nav') mem.zone = zone;
    saveMem();
    const [first, last] = visibleRange();
    ensurePages(first, last);
    for (const [i, el] of rows) if (i < first || i > last) { el.remove(); rows.delete(i); }
    for (let i = first; i <= last; i++) {
      const c = chans[i];
      let el = rows.get(i);
      if (!el) {
        el = h('div', { class: 'g-row', 'data-i': i }); el.style.top = (i * ROW_H) + 'px';
        el.innerHTML = labelHtml(c, i) + '<div class="g-track"></div>'; el._key = c && c.key; el._fav = !!(c && inMyList(c.key));
        rows.set(i, el); body.append(el);
      } else if ((c && c.key) !== el._key || !!(c && inMyList(c.key)) !== el._fav) {
        el.firstChild.outerHTML = labelHtml(c, i); el._key = c && c.key; el._fav = !!(c && inMyList(c.key));
      }
      const here = i === mem.row && (zone === 'grid' || zone === 'label');
      el.classList.toggle('rf', here);
      // the channel name has the focus (label zone, or a row without a focused programme yet)
      el.classList.toggle('lf', here && (zone === 'label' || !focusProg));
      el.classList.toggle('playing', !!(c && play.playing && play.playing.key === c.key));
      el.classList.toggle('fav', !!(c && inMyList(c.key)));
      el.lastChild.style.width = (G.WINDOW_MIN * pxPerMin) + 'px';
      el.lastChild.innerHTML = trackHtml(c);
    }
    requestGuide();
  }

  function renderHeader() {
    const c = chans[mem.row], now = nowSec();
    info.innerHTML = ''; side.innerHTML = '';
    if (!c) { info.append(h('div', { class: 'hd-title' }, total ? 'Loading…' : 'No channel')); renderPreview(); return; }
    // nothing played yet on this device: the first channel the guide shows becomes the current one
    if (!play.playing) setCurrent(c, { list: () => chans, group: mem.group, row: mem.row });
    const progs = progsOf(c);
    const p = focusProg || G.progAt(progs, now);
    info.append(h('div', { class: 'hd-ch' }, `${chanNum(c, mem.row)}  ${c.name}`));
    info.append(h('div', { class: 'hd-title' }, p ? p.title : (progs ? 'No guide data' : 'Loading guide…')));
    if (p) {
      const st = G.progState(p, now, c), pr = G.progress(p, now);
      const when = st === 'now' ? `${pr.minLeft} min left` : st === 'future' ? (fmtDate(p.start) + ' · starts in ' + Math.ceil((p.start - now) / 60) + ' min')
        : st === 'catchup' ? 'finished · catch-up available' : 'finished';
      info.append(h('div', { class: 'hd-time' }, `${fmtTime(p.start)} – ${fmtTime(p.stop)}   ${when}`));
      if (st === 'now') { const bar = h('div', { class: 'progress' }, h('i')); bar.firstChild.style.width = (pr.frac * 100).toFixed(1) + '%'; info.append(bar); }
      const epl = [p.ep, p.sub].filter(Boolean).join(' ');
      if (epl) info.append(h('div', { class: 'hd-ep' }, epl));
      if (p.desc) info.append(h('div', { class: 'hd-desc' }, p.desc));
    }
    side.append(h('div', { class: 'hd-group' }, c.group || ''));
    if (inMyList(c.key)) side.append(h('div', { class: 'hd-star' }, '★ My list'));
    if (c.catchup) side.append(h('div', { class: 'hd-cu' }, `⟲ Catch-up ${c.catchupDays || ''}d`));
    if (play.playing && play.playing.key === c.key) side.append(h('div', { class: 'hd-play' }, '▶ Playing'));
    const isCur = play.playing && play.playing.key === c.key;
    side.append(h('div', { class: 'hd-keys' }, (isCur ? 'Enter full screen' : prefs().previewOn ? 'Enter play here' : 'Enter watch') + ' · M menu · F favourite'));
    renderPreview();
  }
  /** The preview plays the current channel, never the focused one: moving through the guide
   *  leaves it alone; Enter on a channel switches it (see enter()). */
  function renderPreview() {
    const P = prefs(), cur = play.playing;
    const off = pvBox.querySelector('.pv-off');
    if (!P.previewOn && !preview.docked) off.textContent = 'Preview off';
    else if (!off.textContent.startsWith('Playing in')) off.textContent = '';
    if (playerOpen) return;
    // a locked channel waits for the PIN (Enter): no stream is asked for until then
    const lockedCur = cur && cur.locked && !isUnlocked() && !(preview.docked && preview.key === cur.key);
    if (lockedCur) { if (preview.key) preview.show(null); pvNote.textContent = '🔒 Locked · Enter to unlock'; pvNote.hidden = false; return; }
    if (pvNote.textContent.startsWith('🔒')) { pvNote.textContent = ''; pvNote.hidden = true; }
    if (P.previewOn && cur) { preview.setMuted(P.previewMuted); preview.show(cur, (k) => api.streamUrl(k || cur.key), preview.key ? 300 : 0); }
    else if (!(preview.docked && cur && preview.key === cur.key)) preview.show(null);
  }

  // ---------- focus / navigation ----------
  function scrollRowIntoView() {
    const top = mem.row * ROW_H, vh = body.clientHeight;
    if (top < body.scrollTop) body.scrollTop = top;
    else if (top + ROW_H > body.scrollTop + vh) body.scrollTop = top + ROW_H - vh;
  }
  function anchorTime() {
    const now = nowSec(), ws = mem.winStart;
    // never before now: Up / Down keep the time column but do not land on a programme that has
    // ended (the window starts at the half hour, so its first minutes are already over)
    if (focusProg) return Math.max(focusProg.start, ws, now);
    return now >= ws && now < ws + G.WINDOW_SEC ? now : ws;
  }
  function moveRow(d) {
    if (!total) return;
    const t = anchorTime();
    mem.row = Math.max(0, Math.min(total - 1, mem.row + d));
    // the channel name keeps the focus going up / down; in the grid the programme at the same time
    focusProg = zone === 'label' ? null : G.progAt(progsOf(chans[mem.row]), t);
    scrollRowIntoView(); renderRows(); renderHeader();
  }
  function setWin(ws) { ws = G.clampWin(ws, nowSec()); if (ws !== mem.winStart) { mem.winStart = ws; mem.follow = ws === G.initialStart(nowSec()); renderRuler(); renderRows(); } }
  function shift(d) { setWin(G.shifted(mem.winStart, d, nowSec())); }
  function moveProg(d) {
    const c = chans[mem.row]; const progs = progsOf(c);
    const ws = mem.winStart, we = ws + G.WINDOW_SEC;
    if (d < 0) {
      // Left: the previous programme, the window back towards now, else the channel name
      const r = G.leftOf(progs, focusProg, ws, nowSec());
      if (r.label) { toLabel(); return; }
      focusProg = r.prog; setWin(r.win);
    } else {
      if (!progs || !progs.length) { shift(G.STEP); return; }
      if (!focusProg) { focusProg = G.progAt(progs, anchorTime()) || G.firstVisible(progs, ws, we); renderRows(); renderHeader(); return; }
      const nx = G.nextProg(progs, focusProg);
      if (nx && nx.start >= we) shift(G.STEP);
      else if (nx) { focusProg = nx; setWin(G.reveal(nx.start, nx.stop, ws)); }
      else shift(G.STEP);
    }
    renderRows(); renderHeader();
  }
  /** Zones, left to right: nav rail < groups < channel name (label) < programmes (grid). */
  function toGroups() {
    zone = 'groups'; focusProg = null;
    gi = Math.max(0, groups.findIndex((g) => g.id === mem.group));   // the selected group gets the focus
    renderGroups(); renderRows(); renderHeader();
  }
  function toLabel() { zone = 'label'; focusProg = null; renderGroupsKf(); renderRows(); renderHeader(); }
  function toGrid() {
    const r = G.rightOfLabel(progsOf(chans[mem.row]), nowSec());
    zone = 'grid'; focusProg = r.prog; setWin(r.win);
    renderGroupsKf(); renderRows(); renderHeader();
  }
  function toNav() { zone = 'nav'; renderGroupsKf(); renderRows(); nav.focusActive(); }

  /** Enter / OK. On a channel name or its programme on now: the current channel goes full
   *  screen; another channel becomes the current one (the preview switches to it; full screen
   *  when the preview is off). Catch-up plays; a future programme opens its menu. */
  function enter() {
    const c = chans[mem.row]; if (!c) return;
    const now = nowSec(), p = zone === 'grid' ? focusProg : null;
    if (p && p.start > now) { progMenu(); return; }
    if (p && p.stop <= now) { watchProg(c, p, { list: () => chans }); return; }
    const isCur = play.playing && play.playing.key === c.key;
    if (isCur && c.locked && !isUnlocked() && prefs().previewOn) { unlock(`${c.name} is locked: enter the parental PIN`).then((ok) => { if (ok) { renderRows(); renderHeader(); } }); return; }
    if (isCur || !prefs().previewOn) { playLive(c, { list: () => chans }); return; }
    setCurrent(c, { list: () => chans, group: mem.group, row: mem.row });
    renderRows(); renderHeader();
  }
  const watch = enter;
  function progMenu() {
    const c = chans[mem.row]; if (!c) return;
    const p = focusProg || G.progAt(progsOf(c), nowSec());
    const items = [];
    if (p) {
      const st = G.progState(p, nowSec(), c);
      if (st === 'now') items.push({ label: '▶ Watch live', action: () => playLive(c, { list: () => chans }) });
      if (st === 'catchup') items.push({ label: '⟲ Watch catch-up', action: () => watchProg(c, p, { list: () => chans }) });
      if (st === 'future') items.push({ label: hasReminder(c.key, p.start) ? '⏰ Cancel reminder' : '⏰ Remind me',
        action: () => { const on = toggleReminder({ key: c.key, channel: c.name, title: p.title, start: p.start, stop: p.stop, logo: c.logo }); toast(on ? 'Reminder set for ' + fmtTime(p.start) : 'Reminder removed'); renderRows(); } });
      if (st === 'now' || st === 'future') items.push({ label: st === 'now' ? '⏺ Record (rest of this programme)' : '⏺ Record',
        action: async () => { try { const r = await api.record({ key: c.key, title: p.title, start: p.start, stop: p.stop }); toast(st === 'now' ? 'Recording on the server until ' + fmtTime(r.item.stop) : 'Recording scheduled ' + fmtTime(r.item.start) + '–' + fmtTime(r.item.stop)); } catch (e) { toast('Cannot record: ' + e.message); } } });
      if (p.title && st !== 'catchup') items.push({ label: '⟳ Record series (every airing of this title on this channel)',
        action: async () => { try { await api.recordSeries(c.key, p.title); toast('Series recording on: "' + p.title + '"'); } catch (e) { toast('Cannot record: ' + e.message); } } });
    } else items.push({ label: '▶ Watch live', action: () => playLive(c, { list: () => chans }) });
    // the grid never shows the past: earlier programmes are reached from here
    if (c.catchup) items.push({ label: '⟲ Catch-up: earlier programmes…', action: () => catchupMenu(c) });
    items.push({ label: inMyList(c.key) ? '★ Remove from Favourites' : '☆ Add to Favourites', action: () => { toggleFavChannel(c); renderRows(); renderHeader(); renderGroups(); } });
    items.push({ label: '+ Add channel to one of my groups…', action: () => addToGroupMenu(c) });
    items.push({ label: '✎ Rename channel', action: () => { const n = window.prompt('Channel name (empty = the provider\'s name)', c.name); if (n !== null) layoutEdit('channel.rename', { key: c.key, name: n }).then(() => toast('Renamed')).catch((e) => toast(e.message)); } });
    if (!String(mem.group).startsWith('c:')) items.push({ label: 'Hide channel', action: () => layoutEdit('channel.hide', { key: c.key, hidden: true }).then(() => toast('Hidden: show it again in Channels & groups')).catch((e) => toast(e.message)) });
    else items.push({ label: '− Remove from this group', action: () => layoutEdit('channel.remove', { group: mem.group, key: c.key }).then(() => toast('Removed')).catch((e) => toast(e.message)) });
    items.push({ label: '↗ Open in a player app (VLC, link, .m3u)…', action: () => openLiveExternally(c) });
    items.push({ label: '📺 Guide channel… (match it to the guide by hand)', action: () => guideMapDialog(c) });
    // parental PIN: lock / unlock this channel or the whole group (asks for the PIN)
    items.push({ label: c.locked ? '🔓 Unlock channel (parental PIN)' : '🔒 Lock channel (parental PIN)', action: () => setLock('channel', c.key, !c.locked, c.name) });
    const g = groups.find((x) => x.id === mem.group);
    if (g && !String(g.id).startsWith('__')) items.push({ label: g.locked ? `🔓 Unlock group "${g.name}"` : `🔒 Lock group "${g.name}"`, action: () => setLock('group', g.id, !g.locked, g.name) });
    items.push({ label: 'Edit channels & groups…', action: () => { location.hash = 'editor:' + encodeURIComponent(mem.group); } });
    menu(p ? `${c.name} · ${p.title}` : c.name, items);
  }
  /** The channel's past programmes still in its archive, newest first (the grid shows none). */
  async function catchupMenu(c) {
    const now = nowSec(), from = now - Math.max(1, c.catchupDays || 1) * 86400, shift = (prefs().epgShiftMin || 0) * 60;
    let list = [];
    try {
      const r = await api.guide([c.key], from - shift, now - shift);
      list = (r.guide[c.key] || []).map((p) => shift ? { ...p, start: p.start + shift, stop: p.stop + shift } : p);
    } catch (e) { toast('Could not load the guide: ' + e.message); return; }
    const past = list.filter((p) => G.progState(p, now, c) === 'catchup').sort((a, b) => b.start - a.start).slice(0, 80);
    if (!past.length) { toast('No earlier programmes in the archive'); return; }
    menu(c.name + ' · catch-up', past.map((p) => ({
      label: `${fmtDate(p.start)} ${fmtTime(p.start)} · ${p.title}`,
      action: () => watchProg(c, p, { list: () => chans }),
    })));
  }

  // ---------- input ----------
  let escDown = 0, escFired = false;
  function onKey(e) {
    if (playerOpen || document.querySelector('.modal-back')) return false;
    const k = e.key;
    if (k === 'Escape') {
      if (e.type === 'keydown') {
        if (!escDown) { escDown = Date.now(); escFired = false; }
        else if (!escFired && Date.now() - escDown > 450) { escFired = true; resumeFullscreen(); }
        return true;
      }
      return true;
    }
    if (k === 'p' || k === 'P') { resumeFullscreen(); return true; }
    // the keys reach the guide but the nav rail lost the DOM focus (a click elsewhere): no trap,
    // the guide takes the focus back where it was
    if (zone === 'nav') { zone = mem.zone && mem.zone !== 'nav' ? mem.zone : 'label'; renderGroupsKf(); renderRows(); renderHeader(); if (k.startsWith('Arrow') || k === 'Enter') return true; }
    if (zone === 'groups') {
      if (k === 'ArrowDown' || k === 'ArrowUp') { gi = Math.max(0, Math.min(groups.length - 1, gi + (k === 'ArrowDown' ? 1 : -1))); renderGroups(); if (groups[gi]) selectGroupSoon(groups[gi].id); return true; }
      if (k === 'ArrowRight' || k === 'Enter') { if (groups[gi]) selectGroup(groups[gi].id); toLabel(); return true; }
      if (k === 'ArrowLeft') { toNav(); return true; }
      return k === 'PageUp' || k === 'PageDown';
    }
    switch (k) {
      case 'ArrowUp': moveRow(-1); return true;
      case 'ArrowDown': moveRow(1); return true;
      case 'PageUp': moveRow(-Math.max(1, Math.floor(body.clientHeight / ROW_H) - 1)); return true;
      case 'PageDown': moveRow(Math.max(1, Math.floor(body.clientHeight / ROW_H) - 1)); return true;
      case 'ArrowLeft': if (zone === 'label') toGroups(); else moveProg(-1); return true;
      case 'ArrowRight': if (zone === 'label') toGrid(); else moveProg(1); return true;
      case 'Enter': enter(); return true;
      case 'm': case 'M': case 'ContextMenu': case 'i': case 'I': progMenu(); return true;
      case 'f': case 'F': { const c = chans[mem.row]; if (c) { toggleFavChannel(c); renderRows(); renderHeader(); renderGroups(); } return true; }
      case 'Backspace': toGroups(); return true;
      case 'Home': mem.winStart = G.initialStart(nowSec()); mem.follow = true; focusProg = null; renderAll(); return true;
    }
    return false;
  }
  function onKeyUp(e) {
    if (e.key !== 'Escape') return;
    const short = escDown && !escFired && Date.now() - escDown <= 450;
    escDown = 0;
    if (short && !playerOpen && !document.querySelector('.modal-back')) { if (zone === 'grid' || zone === 'label') toGroups(); else if (zone === 'groups') toNav(); }
  }

  function rowOf(ev) { const r = ev.target.closest('.g-row'); return r ? +r.dataset.i : -1; }
  function focusFromEvent(ev) {
    const i = rowOf(ev); if (i < 0 || !chans[i]) return false;
    mem.row = i;
    const blk = ev.target.closest('.g-blk[data-s]');
    focusProg = blk ? (progsOf(chans[i]) || []).find((p) => p.start === +blk.dataset.s) || null : null;
    zone = focusProg || ev.target.closest('.g-track') ? 'grid' : 'label';
    renderGroupsKf(); renderRows(); renderHeader(); return true;
  }
  let pressTimer = 0, longFired = false, lastPointer = 'mouse';
  body.addEventListener('pointerdown', (ev) => {
    lastPointer = ev.pointerType; longFired = false; clearTimeout(pressTimer);
    if (ev.pointerType === 'mouse') return;
    pressTimer = setTimeout(() => { longFired = true; if (focusFromEvent(ev)) progMenu(); }, 550);
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((n) => body.addEventListener(n, () => clearTimeout(pressTimer)));
  body.addEventListener('scroll', () => clearTimeout(pressTimer), { passive: true });
  body.addEventListener('click', (ev) => {
    if (longFired) { longFired = false; return; }
    if (ev.target.closest('.g-fav')) { if (focusFromEvent(ev)) { toggleFavChannel(chans[mem.row]); renderRows(); renderHeader(); renderGroups(); } return; }
    if (ev.target.closest('.g-more')) { if (focusFromEvent(ev)) progMenu(); return; }
    if (lastPointer !== 'mouse') {
      const i = rowOf(ev), blk = ev.target.closest('.g-blk[data-s]');
      const same = i === mem.row && (zone === 'grid' || zone === 'label') && (blk ? focusProg && focusProg.start === +blk.dataset.s : !focusProg);
      if (same) { watch(); return; }
    }
    focusFromEvent(ev);
  });
  body.addEventListener('dblclick', (ev) => { if (focusFromEvent(ev)) watch(); });
  body.addEventListener('contextmenu', (ev) => { if (focusFromEvent(ev)) { ev.preventDefault(); progMenu(); } });
  let wheelAt = 0;
  body.addEventListener('wheel', (ev) => {
    const horiz = Math.abs(ev.deltaX) > Math.abs(ev.deltaY) || ev.shiftKey;
    if (!horiz) return;
    ev.preventDefault();
    if (Date.now() - wheelAt < 180) return; wheelAt = Date.now();
    const d = (ev.deltaX || ev.deltaY) > 0 ? 1 : -1; shift(d * G.STEP);
  }, { passive: false });
  let raf = 0;
  body.addEventListener('scroll', () => { mem.scroll = body.scrollTop; syncHScroll(); if (!raf) raf = requestAnimationFrame(() => { raf = 0; renderRows(); }); });
  pvBox.addEventListener('click', () => { const c = play.playing || chans[mem.row]; if (c) playLive(c, { list: play.list || (() => chans) }); });
  const ro = new ResizeObserver(() => { layout(); renderRuler(); renderRows(); }); ro.observe(body);

  const onPlayer = (e) => { playerOpen = e.detail.open; if (playerOpen) { preview.stop(false); pvBox.querySelector('.pv-off').textContent = ''; } else { focusPlaying(); renderRows(); renderHeader(); } };
  window.addEventListener('tvbro-player', onPlayer);
  // leaving full screen: the live picture shrinks into the preview box and keeps playing there
  setDock((ch) => {
    // also with the preview off: what was watched keeps playing in the box (never paused / reloaded)
    if (!alive || !root.isConnected || pvBox.offsetParent === null) return null;
    return { rect: () => pvBox.getBoundingClientRect(), accept: (video, eng, srcKey) => { preview.adopt(video, eng, ch.key, ch, srcKey); pvBox.querySelector('.pv-off').textContent = ''; } };
  });
  if (playerIsOpen() && play.playing) setTimeout(focusPlaying, 0);
  const tick = setInterval(() => {
    // the guide follows the clock: a window that time has left behind snaps to now, and a focused
    // programme that has ended moves to the one on now
    const now = nowSec(), ws = mem.follow ? G.initialStart(now) : G.clampWin(mem.winStart, now);
    if (ws !== mem.winStart) { mem.winStart = ws; mem.follow = ws === G.initialStart(now); }
    if (focusProg && focusProg.stop <= now) focusProg = G.progAt(progsOf(chans[mem.row]), now);
    renderRuler(); renderRows(); renderHeader();
  }, 30000);

  layout();
  // read-only snapshot for the browser tests (web/tests/e2e): where the guide's own focus is
  root.tvState = () => ({ zone, row: mem.row, group: mem.group, gi, winStart: mem.winStart, total,
    focus: focusProg ? { start: focusProg.start, stop: focusProg.stop, title: focusProg.title } : null,
    current: play.playing ? play.playing.key : null, rowKey: chans[mem.row] ? chans[mem.row].key : null,
    previewKey: preview.key, previewPending: preview.pending || null, docked: !!preview.docked });
  return {
    onKey, onKeyUp,
    virtualFocus: true,          // the guide draws its own focus; app.js must not move the DOM focus into it
    focusIn() { const z = mem.zone && mem.zone !== 'nav' ? mem.zone : 'label'; if (z === 'groups') toGroups(); else { zone = z; renderGroupsKf(); renderRows(); renderHeader(); } },
    destroy() { alive = false; setDock(null); preview.dispose(); clearInterval(tick); ro.disconnect(); window.removeEventListener('tvbro-player', onPlayer); window.removeEventListener('tvbro-layout', onLayout); window.removeEventListener('tvbro-unlocked', onUnlocked); window.removeEventListener('tvbro-epgmap', onEpgMap); epgTimers.forEach(clearTimeout); },
  };
}
