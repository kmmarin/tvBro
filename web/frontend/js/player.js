// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Full-screen player: live (channel up/down, info overlay, quick-settings bar), catch-up and
// VOD (transport bar with seek, ±10 s with hold-to-accelerate, restart, previous/next episode).
// Quick settings: video quality, audio track, audio delay (Web Audio DelayNode), subtitles /
// captions, aspect, sleep timer. Clock top-right.

import { api, setResume, getResume, beginStream, endStream, streamStillOurs } from './api.js';
import { LiveRecovery, watchStall } from './livefix.js';
import { unlock } from './parental.js';
import { openLiveExternally } from './external.js';
import { createEngine, statChips, libsHere } from './engine.js';
import { detectEnv, playPlan, externalReason, externalLinks } from './playfmt.js';
import { parseVtt } from './vtt.js';
import { h, fmtTime, fmtDur, nowSec, menu, toast } from './util.js';
import { prefs, setPref } from './prefs.js';
import { upNextDue, pickTrack, UPNEXT_COUNT } from './vodutil.js';

let cur = null;   // active session (see openPlayer)

export function playerIsOpen() { const l = document.getElementById('player-layer'); return !!l && !l.hidden; }
export function closePlayer() { if (cur) cur.close(); }
/** Play another episode in the open player (same <video>: keeps iPhone full screen and the
 *  browser's permission to play with sound). false when no VOD player is open. */
/** The TV screen offers its preview box for live playback leaving full screen:
 *  fn(channel) -> { rect() -> DOMRect, accept(video, engine) } or null. */
let dockFn = null;
export function setDock(fn) { dockFn = fn; }
export function playerReplace(opts) { return !!(cur && cur.replace && cur.replace(opts)); }

/** Audio delay (ms) chosen in this page session; carried across zaps. */
let sessionDelay = null;
const ASPECTS = [
  { name: 'Normal', fit: 'contain' }, { name: 'Fill', fit: 'fill' }, { name: 'Zoom', fit: 'cover' },
  { name: '16:9', fit: 'fill', ratio: '16 / 9' }, { name: '4:3', fit: 'fill', ratio: '4 / 3' },
];
let aspectIdx = 0;
let sleepAt = 0, sleepTimer = null;

/**
 * opts: { url, title, subtitle?, mode: 'live'|'ts'|'vod', catchup?, channel?, prog?, duration?,
 *         resumeKey?, resumeMeta?, onClose?, zap?(dir), zapNumber?(n), episodeNav?(dir, auto),
 *         // shows (the server keeps the progress per series):
 *         startAt? (seconds), progress?(secs, dur, ended), upNext?() -> {label, go()} | null,
 *         subsPref? (language / label / 'off'), onSubsChange?(v), audioPref?, onAudioChange?(lang) }
 */
export function openPlayer(opts) {
  if (cur) cur.teardown();
  const layer = document.getElementById('player-layer');
  layer.innerHTML = ''; layer.hidden = false;
  const isVod = opts.mode === 'vod';
  const seekable = isVod || !!opts.catchup;
  const video = h('video', { playsinline: true });
  const stage = h('div', { class: 'pl-stage' }, video);
  const clock = h('div', { class: 'pl-clock' });
  const titleEl = h('div', { class: 'player-title' }, opts.title || '');
  const subEl = h('div', { class: 'player-sub' }, opts.subtitle || '');
  const top = h('div', { class: 'player-top' },
    h('button', { class: 'btn', onclick: () => close() }, '‹ Back'), titleEl, subEl);
  const info = h('div', { class: 'pl-info', hidden: true });
  const digitsEl = h('div', { class: 'pl-digits', hidden: true });
  const status = h('div', { class: 'pl-status', hidden: true });
  const qs = h('div', { class: 'pl-qs', hidden: true });
  const notice = h('div', { class: 'pl-notice', hidden: true, role: 'status', 'aria-live': 'polite' });   // reconnects: small, never a dialog
  layer.append(stage, top, clock, info, qs, digitsEl, status, notice);

  const s = cur = { opts, video, eng: null, teardown, close, audio: null };

  // ---------- engine ----------
  // claim this tab's stream slot first (the server may stop the oldest other tab), then play
  const whatOf = (o) => (o.channel ? o.channel.name : o.title || '') + (o.subtitle ? ' · ' + o.subtitle : '') + (o.catchup ? ' (catch-up)' : '');
  let what = whatOf(opts);
  s.eng = { stats: () => ({}), destroy() {}, hls: null };
  let qsOpen = false, infoTimer = null, hideTimer = null, tickTimer = null, nn = null, saveTimer = null;
  let released = false, replacedEl = null, extEl = null;
  const env = detectEnv();
  const idle = () => ({ stats: () => ({}), destroy() {}, hls: null });
  // A film whose container this browser cannot play is remuxed by the server into HLS that
  // starts at `off` seconds into the film: the player shows and saves positions as off + media time.
  let off = 0, rmx = null, fileUrl = null, fixes = 0, pendingSeek = null, resumed = false, subTimer = null;
  const subTracks = new Map();
  const pos = () => (pendingSeek != null ? pendingSeek : off + (video.currentTime || 0));
  const total = () => (rmx && (rmx.duration || opts.duration)) || (isFinite(video.duration) && video.duration) || opts.duration || 0;
  function resumeSecs() {
    if (opts.startAt != null) return opts.startAt || 0;
    const r = opts.resumeKey && getResume(opts.resumeKey); return r && r.secs && !r.done ? r.secs : 0;
  }
  async function startEngine(extra = {}) {
    // (a reconnect keeps the slot it has: it never takes one back from another window)
    if (!opts.local && !extra.reconnect && (await beginStream(what)) === false) return;   // every stream is held by a recording
    if (cur !== s) return;
    let r;
    try {
      r = opts.vod ? await api.vodPlay(opts.vod.kind, opts.vod.id, opts.vod.ext, { ss: resumed ? 0 : resumeSecs(), series: opts.vod.series, ...extra })
        : { url: extra.key ? await (opts.urlFor || api.streamUrl)(extra.key) : await opts.url };
    } catch (e) {
      // locked (parental PIN): ask, then start again; cancelled = back out of the player
      if (e.locked && !extra.unlocked) {
        if (await unlock(`${(opts.channel && opts.channel.name) || opts.title || 'This'} is locked: enter the parental PIN`)) { if (cur === s) startEngine({ ...extra, unlocked: true, key: opts.channel && !opts.catchup ? opts.channel.key : extra.key }); }
        else if (cur === s) close();
        return;
      }
      if (extra.reconnect && rec) { recover(); return; }
      flash('Could not start: ' + e.message, 5000); return;
    }
    if (cur !== s) return;
    if (r.file) fileUrl = r.file;
    if (r.fmt === 'external') { showExternal(fileUrl, r.why || 'This browser cannot play this file.'); return; }
    const url = r.url;
    rmx = r.remux || null; off = rmx ? rmx.ss : 0;
    if (rmx) resumed = true;                       // the remux already starts at the resume point
    // the series' audio language: a remux picks its audio track on the server, so ask again once
    if (rmx && opts.audioPref && extra.a == null && !s.audioTried && rmx.audio && rmx.audio.length > 1) {
      const j = rmx.audio.findIndex((t) => t.lang === opts.audioPref || t.label === opts.audioPref);
      s.audioTried = true;
      if (j >= 0 && j !== rmx.audioPos) { startEngine({ ...extra, remux: 1, ss: rmx.ss, a: j }); return; }
    }
    const mode = isVod ? 'vod' : (extra.mode || opts.mode);
    const plan = playPlan({ url, mode }, env, libsHere());
    if (plan === 'external') { showExternal(url, externalReason({ url, mode }, env)); return; }
    s.eng = createEngine(video, { url, mode, plan });
    if (s.eng.hls && window.Hls) s.eng.hls.on(window.Hls.Events.AUDIO_TRACKS_UPDATED, () => applyAudioPref());
    s.eng.onError = () => {
      if (opts.vod) {
        // the browser refused the provider's file (container / codec): let the server remux it
        if (!rmx && plan === 'native' && !extra.remux) { flash('Converting for this browser…', 4000); restart({ remux: 1, ss: pos() }); return; }
        // the remux stopped (idle while paused, a hiccup upstream): continue where we are
        if (rmx && fixes < 3) { fixes++; restart({ remux: 1, ss: pos(), a: rmx.audioPos }); return; }
      }
      // live TV: reconnect, then the channel's alternate sources (small notice, no dialog)
      if (rec) { recover(); return; }
      // a file or stream the browser itself refuses (codec / container): offer a player app
      if (plan === 'native' && !opts.local) showExternal(fileUrl || url, 'This browser could not play the ' + (isVod ? 'file' : 'stream') + '.');
      else flash('Playback error');
    };
    if (rmx) { s.startKnown = learnStart(rmx); setupRemuxSubs(); if (rmx.notes && rmx.notes.length) flash(rmx.notes.join(' · '), 4000); }
    video.play().catch(() => { video.muted = true; video.play().catch(() => {}); flash('Muted by the browser: tap to unmute'); });
  }
  // ---------- live TV that stalls or errors: reconnect with back-off, then alternate sources ----------
  const rec = opts.live && opts.channel && !opts.catchup ? new LiveRecovery(opts.channel.key) : null;
  let recTimer = null, recBusy = false, onAlt = false;
  function note(msg, ms = 0) {
    clearTimeout(note.t);
    if (!msg) { notice.hidden = true; return; }
    notice.textContent = msg; notice.hidden = false;
    if (ms) note.t = setTimeout(() => { notice.hidden = true; }, ms);
  }
  async function recover() {
    if (!rec || cur !== s || recBusy || replacedEl || extEl) return;
    recBusy = true;
    try { s.eng.destroy(); } catch {}
    s.eng = idle();
    // another window took the stream: that is "Playback moved", not a broken channel
    if (!(await streamStillOurs()) || cur !== s || replacedEl) { recBusy = false; return; }
    let step = rec.failed();
    if (step.action === 'alts') {
      let list = []; try { list = await api.alternates(rec.origin); } catch {}
      if (cur !== s) return;
      rec.setAlts(list); step = rec.failed();
    }
    note(LiveRecovery.notice(step, opts.channel.name));
    clearTimeout(recTimer);
    recTimer = setTimeout(() => {
      recBusy = false;
      if (cur !== s || replacedEl || extEl) return;
      const alt = rec.key !== rec.origin ? rec.alt : null;
      onAlt = !!alt;
      if (stall) stall.reset();
      startEngine({ reconnect: true, key: rec.key, mode: alt ? (alt.streamType === 'hls' ? 'live' : 'ts') : opts.mode });
    }, step.delay);
  }
  const stall = rec ? watchStall(video, { onStall: () => recover(), onHealthy: () => rec.healthy(),
    ok: () => cur === s && !replacedEl && !extEl && !recBusy && !released }) : null;
  video.addEventListener('playing', () => {
    if (!rec || notice.hidden) return;
    note(onAlt ? 'Playing from another source: ' + ((rec.alt && rec.alt.name) || 'alternate') : '', onAlt ? 5000 : 0);
  });
  /** Stop the engine and start again (remux: at another time / with another audio track). */
  function restart(extra) {
    try { s.eng.destroy(); } catch {}
    s.eng = idle();
    startEngine(extra);
  }
  /** Seek to t seconds into the title. A remux seeks in place within what it has produced
   *  (and has not yet thrown away behind the viewer), else it starts again at t. */
  function seekTo(t) {
    const d = total();
    t = Math.max(0, d ? Math.min(d - 1, t) : t);
    if (!rmx) { video.currentTime = t; return; }
    const rel = t - off, sk = video.seekable;
    const end = sk && sk.length ? sk.end(sk.length - 1) : 0;
    if (pendingSeek == null && rel >= Math.max(0, video.currentTime - 240) && rel <= end - 1) { video.currentTime = rel; return; }
    pendingSeek = t;
    clearTimeout(seekTo.timer);
    seekTo.timer = setTimeout(() => { fixes = 0; restart({ remux: 1, ss: t, a: rmx ? rmx.audioPos : null }); }, 400);
  }
  /** The remux keeps the film's timestamps and starts at the keyframe at or before the asked
   *  time: the server reads that time from the first segment. */
  async function learnStart(info) {
    try { const j = await (await fetch(info.info)).json(); if (rmx === info && j.start != null) off = j.start; } catch {}
  }
  // --- remuxed films: text subtitles come as a growing WebVTT file per track (film times) ---
  function clearCues(tr) {
    const m = tr.mode; if (m === 'disabled') tr.mode = 'hidden';
    try { while (tr.cues && tr.cues.length) tr.removeCue(tr.cues[0]); } catch {}
    tr.mode = m;
  }
  function setupRemuxSubs() {
    clearInterval(subTimer);
    for (const tr of subTracks.values()) { clearCues(tr); tr._src = null; }
    if (!rmx || !rmx.subs || !rmx.subs.length) return;
    for (const sd of rmx.subs) {
      let tr = subTracks.get(sd.label);
      if (!tr) { tr = video.addTextTrack('subtitles', sd.label, sd.lang || ''); tr.mode = 'disabled'; subTracks.set(sd.label, tr); }
      tr._src = { url: sd.url, seen: 0 };
    }
    const poll = async () => {
      await s.startKnown;
      for (const tr of subTracks.values()) {
        const src = tr._src; if (!src) continue;
        try {
          const cues = parseVtt(await (await fetch(src.url)).text());
          if (tr._src !== src) continue;
          const m = tr.mode; if (m === 'disabled') tr.mode = 'hidden';
          for (const c of cues.slice(src.seen)) { if (c.end > off) { try { tr.addCue(new VTTCue(Math.max(0, c.start - off), c.end - off, c.text)); } catch {} } }
          tr.mode = m; src.seen = cues.length;
        } catch {}
      }
    };
    poll(); subTimer = setInterval(poll, 15000);
    applyCaptionDefaults();
  }
  /** Record this channel on the server now, until the current programme ends (+ padding). */
  async function recordNow() {
    if (s.recId) { flash('Already recording: see the Recordings screen'); return; }
    const ch = opts.channel, p = nn && nn.now;
    try {
      const r = await api.record({ key: ch.key, title: p ? p.title : ch.name, start: p ? p.start : null, stop: p ? p.stop : null });
      s.recId = r.item; flash('Recording on the server until ' + fmtTime(r.item.stop), 3500);
    } catch (e) { flash('Cannot record: ' + e.message, 4000); }
  }
  /** "Open in VLC / Infuse" from the player menu (the provider's file, signed for 24 h). */
  function openInApp() {
    if (!fileUrl) { flash('No file link'); return; }
    const abs = new URL(fileUrl.replace(/([?&]sid=)[^&]*/, '$1' + 'x' + Math.random().toString(36).slice(2, 10)), location.href).href;
    menu('Open in a player app', [
      ...externalLinks(abs).map((l) => ({ label: l.name, action: () => { video.pause(); location.href = l.href; } })),
      { label: 'Copy link', action: async () => { try { await navigator.clipboard.writeText(abs); flash('Link copied'); } catch { window.prompt('Stream link', abs); } } },
    ]);
  }
  /** Nothing in this browser can play `url`: say so and hand the signed URL to a player app. */
  function showExternal(url, why) {
    if (cur !== s || extEl) return;
    try { s.eng.destroy(); } catch {}
    s.eng = idle();
    endStream();                                   // the app's own connection takes the slot
    const abs = new URL(url.replace(/([?&]sid=)[^&]*/, '$1' + 'x' + Math.random().toString(36).slice(2, 10)), location.href).href;
    extEl = h('div', { class: 'pl-replaced pl-ext glass' },
      h('div', { class: 'pr-title' }, 'Cannot play here'),
      h('div', { class: 'pr-sub' }, why + ' A player app can: the link below is valid for 24 hours on your home network.'),
      h('div', { class: 'pl-ext-btns' },
        ...externalLinks(abs).map((l) => h('a', { class: 'btn focusable', href: l.href }, l.name)),
        h('button', { class: 'btn secondary focusable', onclick: async () => { try { await navigator.clipboard.writeText(abs); flash('Link copied'); } catch { window.prompt('Stream link', abs); } } }, 'Copy link'),
        h('button', { class: 'btn secondary focusable', onclick: () => close() }, 'Close')));
    layer.append(extEl);
    qsOpen = false; qs.hidden = true; info.hidden = true;
    extEl.querySelector('.btn').focus();
  }
  startEngine();
  const onReplaced = (ev) => {
    if (cur !== s || replacedEl) return;
    const byRec = ev && ev.detail === 'recording';
    if (s.save) s.save();
    const again = rmx ? { remux: 1, ss: pos(), a: rmx.audioPos } : {};
    try { s.eng.destroy(); } catch {}
    s.eng = idle();
    if (!rmx) resumed = false;                     // a file resumes from the saved point
    replacedEl = h('div', { class: 'pl-replaced glass' },
      h('div', { class: 'pr-title' }, byRec ? 'A recording needs the stream' : 'Playback moved to another window'),
      h('div', { class: 'pr-sub' }, byRec ? 'Every stream allowed by your limit is used by a server recording. Stop a recording (Recordings screen) or raise the limit in Settings.' : 'Your stream limit was reached, so the newest window took over.'),
      h('button', { class: 'btn focusable', onclick: () => { replacedEl.remove(); replacedEl = null; startEngine(again); layer.focus(); } }, '▶ Play here'));
    if (byRec && s.recId) {                       // the recording took this window's stream: watch it from disk
      const rec = s.recId;
      replacedEl.append(h('button', { class: 'btn secondary focusable', onclick: () => { close(); openPlayer({ vod: { kind: 'rec', id: rec.id, ext: 'ts' }, title: rec.title, subtitle: rec.channel + ' · recording', mode: 'vod', local: true, resumeKey: 'r|' + rec.id }); } }, '▶ Watch the recording'));
    }
    layer.append(replacedEl); replacedEl.querySelector('button').focus();
  };
  window.addEventListener('tvbro-replaced', onReplaced);
  video.addEventListener('pause', () => { if (!video.ended && cur === s && !opts.local) { released = true; endStream(); } });
  video.addEventListener('play', () => { if (released && cur === s) { released = false; beginStream(what); } });
  applyAspect();
  const delay0 = sessionDelay != null ? sessionDelay : (prefs().audioDelayMs || 0);
  if (delay0 > 0 && !env.ios) setAudioDelay(delay0, true);
  video.addEventListener('loadedmetadata', () => {
    pendingSeek = null;
    if (!resumed && (opts.resumeKey || opts.startAt != null)) {
      resumed = true;
      const t = resumeSecs(); if (t && (!video.duration || t < video.duration - 30)) video.currentTime = t;
    }
    applyCaptionDefaults();
    applyAudioPref();
  });
  video.addEventListener('playing', () => {
    fixes = 0;
    // a show appears in Continue watching as soon as an episode plays
    if (!s.reported && opts.progress && pendingSeek == null) { s.reported = true; opts.progress(pos(), total(), false); }
  });
  /** Save the position: a show's progress goes to the server per series, films to resume points. */
  const save = (ended) => {
    if (pendingSeek != null || !(video.currentTime > 0)) return;
    if (opts.progress) opts.progress(ended === true ? total() : pos(), total(), ended === true);
    else if (opts.resumeKey) setResume(opts.resumeKey, pos(), total(), opts.resumeMeta);
  };
  video.addEventListener('pause', () => { if (!video.ended) save(); });
  saveTimer = setInterval(save, 10000); s.save = save;

  // ---------- clock / overlays ----------
  const tickClock = () => { clock.textContent = fmtTime(nowSec()); };
  tickClock();
  tickTimer = setInterval(() => { tickClock(); if (!info.hidden) renderInfo(); if (qsOpen) renderQs(); }, 1000);

  function showChrome() {
    layer.classList.add('chrome');
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (!qsOpen) layer.classList.remove('chrome'); }, 3500);
    armQsHide();
  }
  /** The quick-settings bar hides after 5 s without input (mouse, key, touch); a picker keeps it. */
  let qsHideTimer = null;
  function armQsHide() {
    clearTimeout(qsHideTimer);
    if (!qsOpen) return;
    qsHideTimer = setTimeout(() => { if (!qsOpen) return; if (document.querySelector('.modal-back')) armQsHide(); else closeQs(); }, 5000);
  }
  layer.addEventListener('mousemove', showChrome);
  layer.addEventListener('pointerdown', showChrome, true);
  layer.addEventListener('touchstart', showChrome, { passive: true, capture: true });
  showChrome();

  async function loadNowNext() {
    if (!opts.channel || opts.catchup) return;
    try { const r = await api.nownext([opts.channel.key]); nn = r.nownext[opts.channel.key] || null; if (!info.hidden) renderInfo(); } catch {}
  }
  function showInfo(ms = 5000) {
    info.hidden = false; renderInfo(); clearTimeout(infoTimer);
    if (ms) infoTimer = setTimeout(() => { info.hidden = true; }, ms);
  }
  function chipsEl() { return h('div', { class: 'chips pl-chips' }, ...statChips(s.eng.stats()).map((c) => h('span', { class: 'chip' }, c))); }
  function renderInfo() {
    const now = nowSec(), ch = opts.channel;
    const rows = [];
    const head = h('div', { class: 'pl-info-head' });
    if (ch && ch.logo) head.append(h('img', { class: 'pl-logo', src: api.img(ch.logo), onerror: function () { this.style.visibility = 'hidden'; } }));
    head.append(h('div', { class: 'pl-info-ch' }, ch ? `${ch.number || ''}  ${ch.name}` : (opts.title || '')));
    if (opts.catchup) head.append(h('span', { class: 'badge catchup' }, '⟲ Catch-up'));
    if (rec && rec.key !== rec.origin) head.append(h('span', { class: 'badge' }, 'Alternate source'));
    rows.push(head);
    const p = opts.prog || (nn && nn.now);
    let frac = null, timeLine = '';
    if (seekable) {
      const d = total(), t = pos();
      if (d) { frac = Math.min(1, t / d); timeLine = `${fmtDur(t)} / ${fmtDur(d)}   ${fmtDur(Math.max(0, d - t))} left`; }
      if (opts.subtitle) rows.push(h('div', { class: 'pl-info-title' }, opts.subtitle));
      if (p) rows.push(h('div', { class: 'pl-info-time' }, `${fmtTime(p.start)} – ${fmtTime(p.stop)}`));
    } else if (p) {
      frac = Math.min(1, Math.max(0, (now - p.start) / Math.max(1, p.stop - p.start)));
      rows.push(h('div', { class: 'pl-info-title' }, p.title + (p.ep ? '  ·  ' + p.ep : '') + (p.sub ? ' ' + p.sub : '')));
      timeLine = `${fmtTime(p.start)} – ${fmtTime(p.stop)}   ${Math.max(0, Math.ceil((p.stop - now) / 60))} min left`;
    } else rows.push(h('div', { class: 'pl-info-title dim' }, 'No guide information'));
    if (timeLine) rows.push(h('div', { class: 'pl-info-time' }, timeLine));
    if (frac != null) { const b = h('div', { class: 'progress' }, h('i')); b.firstChild.style.width = (frac * 100).toFixed(1) + '%'; rows.push(b); }
    if (!seekable && nn && nn.next) rows.push(h('div', { class: 'pl-info-next' }, `Next ${fmtTime(nn.next.start)}  ${nn.next.title}`));
    rows.push(chipsEl());
    info.replaceChildren(...rows);
  }
  function flash(msg, ms = 2200) { status.textContent = msg; status.hidden = false; clearTimeout(flash.t); flash.t = setTimeout(() => { status.hidden = true; }, ms); }

  // ---------- quick settings / transport bar ----------
  let qsIdx = 0;
  function qsItems() {
    const items = [];
    if (seekable) {
      items.push({ label: 'Restart', value: 'from start', act: () => seekTo(0) });
      items.push({ label: '« 10 s', value: 'rewind', act: () => seekBy(-10) });
      items.push({ label: video.paused ? '▶ Play' : '❚❚ Pause', value: '', act: () => togglePlay() });
      items.push({ label: '10 s »', value: 'forward', act: () => seekBy(10) });
      if (opts.episodeNav) {
        items.push({ label: '⏮ Previous', value: 'episode', act: () => opts.episodeNav(-1) });
        items.push({ label: 'Next ⏭', value: 'episode', act: () => opts.episodeNav(1) });
      }
    }
    items.push({ label: 'Video', value: qualityLabel(), act: pickQuality });
    items.push({ label: 'Audio', value: audioLabel(), act: pickAudio });
    items.push({ label: 'Audio delay', value: `${curDelay() > 0 ? '+' : ''}${curDelay()} ms`, act: pickDelay, adjust: (d) => setAudioDelay(curDelay() + d * 50) });
    items.push({ label: 'Subtitles', value: subsLabel(), act: pickSubs });
    items.push({ label: 'Aspect', value: ASPECTS[aspectIdx].name, act: pickAspect });
    items.push({ label: 'Sleep timer', value: sleepAt ? Math.max(1, Math.ceil((sleepAt - Date.now()) / 60000)) + ' min' : 'Off', act: pickSleep });
    if (video.muted) items.push({ label: 'Unmute', value: 'sound off', act: () => { video.muted = false; } });
    if (opts.vod && fileUrl) items.push({ label: 'Open in app', value: 'VLC · Infuse', act: openInApp });
    if (opts.channel && !opts.catchup && !opts.local) items.push({ label: 'Open in app', value: 'VLC · link · .m3u', act: () => openLiveExternally(opts.channel, () => video.pause()) });
    if (opts.channel && !opts.catchup && !opts.local) items.push({ label: '⏺ Record', value: s.recId ? 'recording' : 'on the server', act: recordNow });
    if (pipOk()) items.push({ label: 'Picture in picture', value: inPip() ? 'on' : 'off', act: togglePip });
    items.push({ label: document.fullscreenElement ? 'Window' : 'Full screen', value: 'F', act: toggleFs });
    return items;
  }
  function renderQs() {
    const items = qsItems();
    qsIdx = Math.min(qsIdx, items.length - 1);
    const kids = [];
    if (seekable) {
      const d = total();
      const seek = h('input', { type: 'range', class: 'pl-seek', min: 0, max: Math.max(1, Math.floor(d)), step: 1, value: Math.floor(pos()), tabindex: '-1' });
      seek.oninput = () => seekTo(+seek.value);
      kids.push(h('div', { class: 'pl-seekrow' }, h('span', {}, fmtDur(pos())), seek, h('span', {}, d ? fmtDur(d) : '--:--')));
    }
    kids.push(h('div', { class: 'qs-row' }, ...items.map((it, i) => h('button', { class: 'qs-btn' + (i === qsIdx ? ' kf' : ''), tabindex: '-1', onclick: () => { qsIdx = i; it.act(); renderQs(); } },
      h('div', { class: 'qs-l' }, it.label), it.value ? h('div', { class: 'qs-v' }, it.value) : null))));
    kids.push(h('div', { class: 'qs-hint' }, 'Left/Right choose · Enter open · +/− adjust delay · Up/Esc close'));
    kids.push(chipsEl());
    qs.replaceChildren(...kids);
    s._items = items;
  }
  function openQs() { qsOpen = true; qs.hidden = false; layer.classList.add('chrome'); renderQs(); armQsHide(); }
  function closeQs() { qsOpen = false; qs.hidden = true; clearTimeout(qsHideTimer); showChrome(); }

  function pick(title, rows) {
    if (!rows.length) { flash('Nothing to choose'); return; }
    menu(title, rows.map((r) => ({ label: r[0], action: () => { r[1](); if (qsOpen) renderQs(); layer.focus(); } })));
  }
  function qualityLabel() {
    const hl = s.eng.hls;
    if (hl && hl.levels && hl.levels.length) {
      const l = hl.levels[hl.currentLevel];
      const name = l ? (l.height ? l.height + 'p' : Math.round(l.bitrate / 1000) + ' kbps') : '';
      return hl.autoLevelEnabled ? 'Auto' + (name ? ` (${name})` : '') : (name || 'Auto');
    }
    return video.videoHeight ? video.videoHeight + 'p' : 'Auto';
  }
  function pickQuality() {
    const hl = s.eng.hls;
    if (!hl || !hl.levels || hl.levels.length < 2) { flash('Single quality stream'); return; }
    const rows = [['Auto', () => { hl.currentLevel = -1; }]];
    hl.levels.forEach((l, i) => rows.push([`${l.height ? l.height + 'p' : '?'} · ${(l.bitrate / 1e6).toFixed(1)} Mbps${l.frameRate ? ' · ' + l.frameRate + ' fps' : ''}`, () => { hl.currentLevel = i; }]));
    pick('Video quality', rows);
  }
  function audioLabel() {
    if (rmx && rmx.audio && rmx.audio.length) { const t = rmx.audio[rmx.audioPos || 0]; return t ? t.label : 'Default'; }
    const hl = s.eng.hls;
    if (hl && hl.audioTracks && hl.audioTracks.length) { const t = hl.audioTracks[hl.audioTrack]; return t ? (t.name || t.lang || 'Track ' + (hl.audioTrack + 1)) : 'Default'; }
    const st = s.eng.stats(); return st.channels ? (st.channels >= 6 ? '5.1' : st.channels === 2 ? 'Stereo' : st.channels + ' ch') : 'Default';
  }
  function pickAudio() {
    if (rmx && rmx.audio && rmx.audio.length > 1) {
      const chs = (n) => (n >= 6 ? '5.1' : n === 2 ? 'stereo' : n + ' ch');
      pick('Audio track', rmx.audio.map((t, i) => [`${i === rmx.audioPos ? '• ' : ''}${t.label} · ${t.codec.toUpperCase()} ${chs(t.channels)}`,
        () => { saveAudio(t.lang || t.label); if (i !== rmx.audioPos) { pendingSeek = pos(); restart({ remux: 1, ss: pendingSeek, a: i }); } }]));
      return;
    }
    const hl = s.eng.hls;
    const at = video.audioTracks;
    if (!hl && !rmx && at && at.length > 1) {        // Safari playing the file / HLS itself
      const list = []; for (let j = 0; j < at.length; j++) list.push(at[j]);
      pick('Audio track', list.map((t, i) => [`${t.enabled ? '• ' : ''}${t.label || t.language || 'Track ' + (i + 1)}`,
        () => { list.forEach((x, j) => { x.enabled = j === i; }); saveAudio(t.language || t.label); }]));
      return;
    }
    if (!hl || !hl.audioTracks || hl.audioTracks.length < 2) { flash('Only one audio track'); return; }
    pick('Audio track', hl.audioTracks.map((t, i) => [`${i === hl.audioTrack ? '• ' : ''}${t.name || t.lang || 'Track ' + (i + 1)}${t.attrs && t.attrs.CHANNELS ? ' · ' + t.attrs.CHANNELS + ' ch' : ''}`, () => { hl.audioTrack = i; saveAudio(t.lang || t.name); }]));
  }
  // --- audio delay: <video> -> MediaElementSource -> DelayNode -> speakers ---
  function curDelay() { return s.audio ? Math.round(s.audio.delay.delayTime.value * 1000) : 0; }
  function setAudioDelay(ms, quiet) {
    ms = Math.round(ms / 50) * 50;
    if (ms < 0) {
      // A browser cannot hold back video frames behind its own audio clock, so "audio
      // early" (negative delay) is not possible here: it is clamped to 0 and the user is told.
      ms = 0; if (!quiet) flash('Negative delay is not possible in a browser: audio can only be delayed');
    }
    ms = Math.min(2000, ms);
    // WebKit on iOS goes silent when HLS audio is routed through Web Audio
    if (env.ios) { if (!quiet) flash('Audio delay is not available on iPhone / iPad'); return; }
    if (!s.audio && ms === 0) { sessionDelay = 0; return; }
    try {
      if (!s.audio) {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        const ctx = new Ctx(); const src = ctx.createMediaElementSource(video);
        const delay = ctx.createDelay(2.5); src.connect(delay); delay.connect(ctx.destination);
        s.audio = { ctx, delay };
      }
      s.audio.ctx.resume().catch(() => {});
      s.audio.delay.delayTime.setValueAtTime(ms / 1000, s.audio.ctx.currentTime);
      sessionDelay = ms;
      if (!quiet) flash(`Audio delay +${ms} ms`);
    } catch { if (!quiet) flash('Audio delay unavailable'); }
  }
  function pickDelay() {
    const d = curDelay();
    pick(`Audio delay (now +${d} ms)`, [['+50 ms', () => setAudioDelay(d + 50)], ['−50 ms', () => setAudioDelay(d - 50)],
      ['+250 ms', () => setAudioDelay(d + 250)], ['−250 ms', () => setAudioDelay(d - 250)], ['Reset to 0', () => setAudioDelay(0)]]);
  }
  // --- subtitles / closed captions (hls.js subtitle tracks + CEA-608/708 as TextTracks) ---
  // (a remux's subtitle tracks stay on the element: unused ones, e.g. after the next episode, are left out)
  function textTracks() { return [...(video.textTracks || [])].filter((t) => (t.kind === 'subtitles' || t.kind === 'captions') && !(t._src === null && [...subTracks.values()].includes(t))); }
  function subsLabel() { const t = textTracks().find((x) => x.mode === 'showing'); return t ? (t.label || t.language || 'On') : 'Off'; }
  function setSubs(track, remember = true) {
    const hl = s.eng.hls;
    for (const t of textTracks()) t.mode = t === track ? 'showing' : 'disabled';
    if (hl && hl.subtitleTracks && hl.subtitleTracks.length) {
      const i = track ? hl.subtitleTracks.findIndex((x) => (x.name && x.name === track.label) || (x.lang && x.lang === track.language)) : -1;
      hl.subtitleTrack = i; hl.subtitleDisplay = i >= 0;
    }
    if (remember) {
      const v = track ? (track.language || track.label || 'on') : 'off';
      setPref('subs', v);
      if (opts.onSubsChange) { opts.subsPref = v; opts.onSubsChange(v); }   // kept for the rest of the series
    }
  }
  function pickSubs() {
    const tr = textTracks();
    if (!tr.length) { flash('No subtitles or captions in this stream'); return; }
    pick('Subtitles / captions', [['Off', () => setSubs(null)], ...tr.map((t) => [`${t.mode === 'showing' ? '• ' : ''}${t.label || t.language || t.kind}${t.kind === 'captions' ? ' (CC)' : ''}`, () => setSubs(t)])]);
  }
  /** A show's saved choice (else the last one anywhere): its language, else the first text track; or off. */
  function applyCaptionDefaults() {
    const want = (opts.subsPref != null && opts.subsPref !== '' ? opts.subsPref : prefs().subs) || 'off';
    const tr = textTracks();
    const i = pickTrack(tr, want);
    if (i === -1) { for (const t of tr) t.mode = 'disabled'; return; }
    if (i != null && tr[i].mode !== 'showing') setSubs(tr[i], false);
  }
  /** A show's audio language (hls.js tracks, Safari's own audioTracks; remuxes: see startEngine). */
  function applyAudioPref() {
    const want = opts.audioPref; if (!want) return;
    const hl = s.eng.hls;
    if (hl && hl.audioTracks && hl.audioTracks.length > 1) {
      const i = hl.audioTracks.findIndex((t) => t.lang === want || t.name === want);
      if (i >= 0 && i !== hl.audioTrack) hl.audioTrack = i;
      return;
    }
    const at = video.audioTracks;
    if (!rmx && at && at.length > 1) {
      let i = -1; for (let j = 0; j < at.length; j++) if (at[j].language === want || at[j].label === want) { i = j; break; }
      if (i >= 0) for (let j = 0; j < at.length; j++) at[j].enabled = j === i;
    }
  }
  function saveAudio(lang) { if (lang && opts.onAudioChange) { opts.audioPref = lang; opts.onAudioChange(lang); } }
  if (video.textTracks) video.textTracks.addEventListener('addtrack', () => applyCaptionDefaults());
  // --- aspect / sleep / fullscreen ---
  function applyAspect() {
    const a = ASPECTS[aspectIdx];
    video.style.objectFit = a.fit;
    if (a.ratio) Object.assign(video.style, { aspectRatio: a.ratio, width: 'auto', height: '100%', maxWidth: '100%' });
    else Object.assign(video.style, { aspectRatio: '', width: '100%', height: '100%', maxWidth: '' });
  }
  function pickAspect() { pick('Aspect', ASPECTS.map((a, i) => [`${i === aspectIdx ? '• ' : ''}${a.name}`, () => { aspectIdx = i; applyAspect(); }])); }
  function pickSleep() {
    pick('Sleep timer', [0, 15, 30, 60, 90].map((m) => [m ? m + ' minutes' : 'Off', () => {
      clearTimeout(sleepTimer); sleepAt = m ? Date.now() + m * 60000 : 0;
      if (m) sleepTimer = setTimeout(() => { sleepAt = 0; if (cur) cur.close(); toast('Sleep timer: playback stopped'); }, m * 60000);
    }]));
  }
  // picture in picture: the standard API (Chrome / Edge / Safari on Mac) or Safari's own (iPhone / iPad)
  function pipOk() { return (document.pictureInPictureEnabled && !!video.requestPictureInPicture) || typeof video.webkitSetPresentationMode === 'function'; }
  function inPip() { return document.pictureInPictureElement === video || video.webkitPresentationMode === 'picture-in-picture'; }
  async function togglePip() {
    try {
      if (document.pictureInPictureEnabled && video.requestPictureInPicture) {
        if (document.pictureInPictureElement) await document.exitPictureInPicture(); else await video.requestPictureInPicture();
      } else video.webkitSetPresentationMode(inPip() ? 'inline' : 'picture-in-picture');
    } catch { toast('Picture in picture is not available here'); }
  }
  function toggleFs() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else if (layer.requestFullscreen) layer.requestFullscreen().catch(() => {});
    else if (video.webkitEnterFullscreen) { try { video.webkitEnterFullscreen(); } catch {} }   // iPhone: only the video itself
  }

  // ---------- transport ----------
  function togglePlay() { if (video.paused) video.play().catch(() => {}); else video.pause(); }
  function seekBy(d) {
    const t = Math.max(0, pos() + d);
    seekTo(t);
    flash(`${d > 0 ? '+' : ''}${d} s  ·  ${fmtDur(pendingSeek != null ? pendingSeek : pos())}`, 900);
  }
  let holdCount = 0, holdKey = '';
  function holdStep(e) {   // hold Left/Right to accelerate: 10, 20, 30, 60 s per step
    if (e.repeat && holdKey === e.key) holdCount++; else { holdCount = 0; holdKey = e.key; }
    return [10, 20, 30, 60][Math.min(3, Math.floor(holdCount / 4))];
  }

  // ---------- keys ----------
  let digits = '', digitTimer = null;
  layer.tabIndex = 0; layer.focus();
  function onKey(e) {
    if (document.querySelector('.modal-back')) return;
    const k = e.key;
    if (up.el && (k === 'Enter' || k === 'Escape' || k === 'Backspace')) {
      e.preventDefault(); e.stopPropagation();
      if (k === 'Enter' && document.activeElement && up.el.contains(document.activeElement)) document.activeElement.click();
      else if (k === 'Enter') up.go(); else up.cancel();
      return;
    }
    if (replacedEl && (k === 'Enter' || k === ' ')) { e.preventDefault(); e.stopPropagation(); replacedEl.querySelector('button').click(); return; }
    if (extEl) {
      if (k === 'Escape' || k === 'Backspace') { e.preventDefault(); close(); }
      else if (k === 'ArrowLeft' || k === 'ArrowRight') { e.preventDefault(); const b = [...extEl.querySelectorAll('.btn')]; const i = b.indexOf(document.activeElement); b[(i + (k === 'ArrowRight' ? 1 : -1) + b.length) % b.length].focus(); }
      return;                                    // Enter activates the focused link / button itself
    }
    showChrome();
    if (qsOpen) {
      const items = s._items || [];
      if (k === 'ArrowRight') { qsIdx = Math.min(items.length - 1, qsIdx + 1); renderQs(); }
      else if (k === 'ArrowLeft') { qsIdx = Math.max(0, qsIdx - 1); renderQs(); }
      else if ((k === '+' || k === '=' || k === '-') && items[qsIdx] && items[qsIdx].adjust) { items[qsIdx].adjust(k === '-' ? -1 : 1); renderQs(); }
      else if (k === 'Enter' || k === ' ') { if (items[qsIdx]) items[qsIdx].act(); renderQs(); }
      else if (k === 'ArrowUp' || k === 'Escape' || k === 'Backspace' || k === 'ArrowDown') { if (!e.repeat) closeQs(); }
      else return;
      e.preventDefault(); e.stopPropagation(); return;
    }
    let handled = true;
    if (k === 'Escape' || k === 'Backspace') { if (!e.repeat) { if (!info.hidden) info.hidden = true; else close(); } }
    else if (k === 'ArrowDown' || k === 'ContextMenu' || k === 'm' || k === 'M') openQs();
    else if (k === 'Enter' || k === 'i' || k === 'I') { if (info.hidden) showInfo(); else info.hidden = true; }
    else if (k === ' ' || k === 'k' || k === 'MediaPlayPause') togglePlay();
    else if (k === 'f' || k === 'F') toggleFs();
    else if (seekable) {
      if (k === 'ArrowLeft') seekBy(-holdStep(e));
      else if (k === 'ArrowRight') seekBy(holdStep(e));
      else if (k === 'ArrowUp') showInfo();
      else if (k === 'PageDown' && opts.episodeNav) opts.episodeNav(1);
      else if (k === 'PageUp' && opts.episodeNav) opts.episodeNav(-1);
      else handled = false;
    } else {
      if ((k === 'ArrowUp' || k === 'PageUp' || k === 'ChannelUp') && opts.zap) opts.zap(1);
      else if ((k === 'PageDown' || k === 'ChannelDown') && opts.zap) opts.zap(-1);
      else if ((k === 'ArrowLeft' || k === 'ArrowRight') && !e.repeat) close();
      else if (/^[0-9]$/.test(k) && opts.zapNumber) {
        digits = (digits + k).slice(0, 4); digitsEl.textContent = digits; digitsEl.hidden = false;
        clearTimeout(digitTimer);
        digitTimer = setTimeout(() => { digitsEl.hidden = true; const n = +digits; digits = ''; if (!opts.zapNumber(n)) flash('No channel ' + n); }, 1500);
      } else handled = false;
    }
    if (handled) { e.preventDefault(); e.stopPropagation(); }
  }
  layer.addEventListener('keydown', onKey);
  stage.addEventListener('click', () => { if (extEl) return; if (video.muted) { video.muted = false; return; } if (qsOpen) closeQs(); else openQs(); });
  stage.addEventListener('dblclick', toggleFs);

  // ---------- shows: "Up next" (last 30 s when the length is known, or at the end) ----------
  let up = { el: null, timer: null, cancelled: false };
  function clearUpNext() { clearInterval(up.timer); if (up.el) up.el.remove(); up.el = null; up.timer = null; }
  function checkUpNext(ended) {
    if (!isVod || !opts.upNext || prefs().autoplayNext === false || up.cancelled || up.el || extEl || replacedEl) return;
    if (pendingSeek != null || !upNextDue(pos(), total(), ended)) return;
    const n = opts.upNext(); if (!n) return;
    let left = UPNEXT_COUNT;
    const cnt = h('div', { class: 'un-count' });
    const draw = () => { cnt.textContent = `Playing in ${left} s`; };
    const go = () => {
      clearUpNext();
      if (opts.progress) opts.progress(total(), total(), true);    // skipping the credits counts as watched
      n.go();
    };
    const cancel = () => { clearUpNext(); up.cancelled = true; layer.focus(); };
    up.go = go; up.cancel = cancel;
    up.el = h('div', { class: 'pl-upnext glass' },
      h('div', { class: 'un-k' }, 'Up next'), h('div', { class: 'un-title' }, n.label), cnt,
      h('div', { class: 'un-btns' },
        h('button', { class: 'btn focusable', onclick: go }, '▶ Play now'),
        h('button', { class: 'btn secondary focusable', onclick: cancel }, 'Cancel')));
    draw(); layer.append(up.el);
    up.timer = setInterval(() => { if (video.paused && !video.ended) return; left--; draw(); if (left <= 0) go(); }, 1000);
  }
  video.addEventListener('timeupdate', () => checkUpNext(false));
  video.addEventListener('ended', () => {
    if (!isVod) return;
    save(true);
    if (opts.upNext) checkUpNext(true);
    else if (opts.episodeNav && prefs().autoplayNext !== false) opts.episodeNav(1, true);
  });

  /** Next / previous episode in this player: same <video> element, new source. */
  s.replace = (n) => {
    if (cur !== s || !isVod || !n || n.mode !== 'vod' || !n.vod || n.local || opts.local || extEl || replacedEl) return false;
    save();
    clearUpNext(); up = { el: null, timer: null, cancelled: false };
    s.reported = false; s.audioTried = false;
    for (const k of Object.keys(opts)) delete opts[k];
    Object.assign(opts, n);
    off = 0; rmx = null; fileUrl = null; fixes = 0; pendingSeek = null; resumed = false; clearTimeout(seekTo.timer);
    setupRemuxSubs();                              // drops the last remux's subtitle cues
    what = whatOf(opts);
    titleEl.textContent = opts.title || ''; subEl.textContent = opts.subtitle || '';
    restart();
    flash(opts.subtitle || opts.title || '', 3000);
    if (qsOpen) renderQs();
    return true;
  };

  loadNowNext();
  if (!isVod) showInfo(); else openQs();

  function teardown() {
    if (s.save) s.save();
    clearUpNext(); clearTimeout(qsHideTimer); clearTimeout(recTimer); clearTimeout(note.t); if (stall) stall.stop();
    clearInterval(tickTimer); clearInterval(saveTimer); clearInterval(subTimer); clearTimeout(seekTo.timer); clearTimeout(infoTimer); clearTimeout(hideTimer); clearTimeout(digitTimer);
    try { s.eng.destroy(); } catch {}
    try { if (s.audio) s.audio.ctx.close(); } catch {}
    layer.removeEventListener('keydown', onKey);
    window.removeEventListener('tvbro-replaced', onReplaced);
    if (cur === s) cur = null;
  }
  function close() {
    // Live TV back to the guide: the picture shrinks into the preview box and the same <video>
    // (same engine, same stream, with sound) keeps playing there: nothing reloads.
    const d = opts.channel && !opts.catchup && !opts.local && !s.audio && s.eng && s.eng.kind !== 'none' && !extEl && !replacedEl && dockFn ? dockFn(opts.channel) : null;
    if (d) { dockClose(d); return; }
    teardown();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    layer.hidden = true; layer.innerHTML = ''; layer.classList.remove('chrome');
    if (!opts.local) endStream();
    if (opts.onClose) opts.onClose();
  }
  function dockClose(d) {
    const eng = s.eng;
    eng.onError = null;
    s.eng = idle();                                // teardown must not stop it
    teardown();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    for (const el of [...layer.children]) if (el !== stage) el.remove();
    layer.classList.remove('chrome');
    const from = layer.getBoundingClientRect(), to = d.rect();
    const reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
    const finish = () => {
      if (finish.done) return; finish.done = true;
      d.accept(video, eng, rec ? rec.key : null);  // moved, not reloaded: keeps playing with sound
      layer.hidden = true; layer.innerHTML = ''; layer.removeAttribute('style');
      if (opts.onClose) opts.onClose();
    };
    if (reduce || !to || !to.width || !from.width) { finish(); return; }
    Object.assign(layer.style, { transformOrigin: '0 0', transition: 'transform .32s cubic-bezier(.2,.7,.2,1), border-radius .32s, background-color .32s', willChange: 'transform', overflow: 'hidden' });
    requestAnimationFrame(() => {
      Object.assign(layer.style, { transform: `translate(${to.left - from.left}px, ${to.top - from.top}px) scale(${to.width / from.width}, ${to.height / from.height})`,
        borderRadius: `${12 * from.width / to.width}px`, backgroundColor: 'rgba(0,0,0,0)' });
    });
    layer.addEventListener('transitionend', finish, { once: true });
    setTimeout(finish, 600);
  }
  return s;
}
