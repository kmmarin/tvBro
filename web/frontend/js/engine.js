// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Playback engine shared by the full-screen player and the TV-screen live preview:
// hls.js for (rewritten) HLS, mpegts.js for live MPEG-TS, native <video> for progressive VOD
// and for HLS where the browser plays it itself (iPhone / iPad: no Media Source Extensions).
import { api, beginStream, endStream, streamStillOurs } from './api.js';
import { detectEnv, playPlan } from './playfmt.js';
import { LiveRecovery, watchStall } from './livefix.js';

export function libsHere() {
  const Hls = window.Hls, mpegts = window.mpegts;
  let hls = false, ts = false;
  try { hls = !!(Hls && Hls.isSupported()); } catch {}
  try { ts = !!(mpegts && mpegts.isSupported()); } catch {}
  return { hls, mpegts: ts };
}

/**
 * @param video HTMLVideoElement
 * @param opts { url, mode: 'live'|'ts'|'vod', preview?: bool }
 * @returns engine { kind, hls?, ts?, destroy(), stats() }
 */
export function createEngine(video, opts) {
  const Hls = window.Hls, mpegts = window.mpegts;
  const url = opts.url, preview = !!opts.preview;
  const eng = { kind: 'native', hls: null, ts: null, tsInfo: null, destroyed: false };
  const plan = opts.plan || playPlan({ url, mode: opts.mode }, detectEnv(), libsHere());

  const startTs = () => {
    const p = mpegts.createPlayer({ type: 'mpegts', isLive: true, url },
      { enableStashBuffer: false, liveBufferLatencyChasing: true, liveBufferLatencyMaxLatency: preview ? 3 : 6,
        liveBufferLatencyMinRemain: preview ? 0.5 : 1.5, lazyLoad: false });
    p.attachMediaElement(video); p.load();
    p.on(mpegts.Events.MEDIA_INFO, (mi) => { eng.tsInfo = mi; });
    p.on(mpegts.Events.STATISTICS_INFO, (st) => { eng.tsStats = st; });
    p.on(mpegts.Events.ERROR, () => { if (eng.onError) eng.onError(); });
    eng.kind = 'mpegts'; eng.ts = p;
  };

  if (plan === 'external') eng.kind = 'none';          // caller shows why (see player.js)
  else if (plan === 'mpegts') startTs();
  else if (plan === 'hls') {
    const cfg = { enableWorker: true, lowLatencyMode: false, renderTextTracksNatively: true, enableCEA708Captions: true };
    if (preview) Object.assign(cfg, { maxBufferLength: 6, maxMaxBufferLength: 10, capLevelToPlayerSize: true, startLevel: 0 });
    const hls = new Hls(cfg);
    hls.loadSource(url); hls.attachMedia(video);
    hls.on(Hls.Events.ERROR, (_e, d) => {
      if (!d.fatal) return;
      if (opts.mode !== 'vod' && mpegts && mpegts.isSupported() && !eng.destroyed) {
        try { hls.destroy(); } catch {}
        eng.hls = null; startTs();
      } else if (eng.onError) eng.onError();
    });
    eng.kind = 'hls'; eng.hls = hls;
  } else {
    video.src = url; eng.kind = 'native';
    video.addEventListener('error', () => { if (!eng.destroyed && eng.onError) eng.onError(); }, { once: true });
  }

  eng.destroy = () => {
    eng.destroyed = true;
    try { if (eng.hls) eng.hls.destroy(); } catch {}
    try { if (eng.ts) { eng.ts.unload(); eng.ts.detachMediaElement(); eng.ts.destroy(); } } catch {}
    try { video.removeAttribute('src'); video.load(); } catch {}
  };

  /** Stream facts for the info overlay / chips. */
  eng.stats = () => {
    const s = { width: video.videoWidth || 0, height: video.videoHeight || 0 };
    const q = video.getVideoPlaybackQuality ? video.getVideoPlaybackQuality() : null;
    if (q) { s.dropped = q.droppedVideoFrames; s.frames = q.totalVideoFrames; }
    if (eng.hls) {
      const l = eng.hls.levels && eng.hls.levels[eng.hls.currentLevel >= 0 ? eng.hls.currentLevel : eng.hls.loadLevel];
      if (l) {
        s.bitrate = l.bitrate; s.fps = l.frameRate || l.attrs && parseFloat(l.attrs['FRAME-RATE']) || null;
        s.vcodec = l.videoCodec || null; s.acodec = l.audioCodec || null;
      }
      const at = eng.hls.audioTracks && eng.hls.audioTracks[eng.hls.audioTrack];
      if (at && at.attrs && at.attrs.CHANNELS) s.channels = parseInt(at.attrs.CHANNELS, 10);
    } else if (eng.ts) {
      const mi = eng.tsInfo || {};
      s.fps = mi.fps || null; s.vcodec = mi.videoCodec || null; s.acodec = mi.audioCodec || null;
      s.channels = mi.audioChannelCount || null;
      if (eng.tsStats && eng.tsStats.speed) s.bitrate = Math.round(eng.tsStats.speed * 8 * 1024); // KB/s -> bit/s
    }
    return s;
  };
  return eng;
}

/** Human chips from engine stats: ["1920×1080", "50 fps", "avc1", "5.2 Mbps", "5.1", "0 dropped"]. */
export function statChips(s) {
  const out = [];
  if (s.width && s.height) out.push(`${s.width}×${s.height}`);
  if (s.fps) out.push(`${Math.round(s.fps * 100) / 100} fps`);
  if (s.vcodec) out.push(String(s.vcodec).split('.')[0]);
  if (s.acodec) out.push(String(s.acodec).split('.')[0]);
  if (s.bitrate) out.push(s.bitrate >= 1e6 ? (s.bitrate / 1e6).toFixed(1) + ' Mbps' : Math.round(s.bitrate / 1000) + ' kbps');
  if (s.channels) out.push(s.channels >= 6 ? '5.1' : s.channels === 2 ? 'Stereo' : s.channels === 1 ? 'Mono' : s.channels + ' ch');
  if (s.dropped != null) out.push(`${s.dropped} dropped`);
  return out;
}

/** One shared, muted, small live preview of the current channel (never of the focused one). Uses the tab's
 *  stream slot, like the full player, so preview + player never count as two streams. */
export class PreviewPlayer {
  /** onNotice(text): a small label over the preview while it reconnects ('' = hide). */
  constructor(video, onReplaced, onNotice) {
    this.video = video; this.eng = null; this.key = null; this.timer = null; this.onReplaced = onReplaced;
    this.onNotice = onNotice || (() => {}); this.rec = null; this.recTimer = null; this.stall = null; this.ch = null;
    this.urlFor = (k) => api.streamUrl(k);
    video.muted = true; video.playsInline = true;
    this._rep = () => { if (this.eng) { this._unwatch(); this.eng.destroy(); this.eng = null; this.key = null; if (this.onReplaced) this.onReplaced(); } };
    window.addEventListener('tvbro-replaced', this._rep);
  }
  /** Live recovery for what plays here (also a docked full-screen video): reconnect, then the
   *  channel's alternate sources, as in the full player (see livefix.js). */
  _watch(c, srcKey) {
    this._unwatch();
    this.ch = c; this.rec = new LiveRecovery(c.key);
    if (srcKey && srcKey !== c.key) { this.rec.key = srcKey; this.rec.alt = { key: srcKey }; }
    if (this.eng) this.eng.onError = () => this._recover();
    this.stall = watchStall(this.video, { onStall: () => this._recover(), onHealthy: () => this.rec && this.rec.healthy(),
      ok: () => !!this.eng && !this.recBusy });
  }
  _unwatch() { clearTimeout(this.recTimer); this.recTimer = null; this.recBusy = false; if (this.stall) this.stall.stop(); this.stall = null; this.onNotice(''); }
  async _recover() {
    const c = this.ch, rec = this.rec;
    if (!c || !rec || this.recBusy || this.key !== c.key) return;
    this.recBusy = true;
    if (this.eng) { this.eng.onError = null; this.eng.destroy(); this.eng = null; }
    if (!(await streamStillOurs()) || this.rec !== rec) { this.recBusy = false; return; }
    let step = rec.failed();
    if (step.action === 'alts') { let l = []; try { l = await api.alternates(rec.origin); } catch {} if (this.rec !== rec) return; rec.setAlts(l); step = rec.failed(); }
    this.onNotice(LiveRecovery.notice(step, c.name));
    this.recTimer = setTimeout(async () => {
      if (this.rec !== rec || this.key !== c.key) return;
      let u; try { u = await this.urlFor(rec.key); } catch { this.recBusy = false; this._recover(); return; }
      if (this.rec !== rec || this.key !== c.key) return;
      const alt = rec.key !== rec.origin ? rec.alt : null;
      const st = alt && alt.streamType ? alt.streamType : c.streamType;
      this.eng = createEngine(this.video, { url: u, mode: st === 'hls' ? 'live' : 'ts', preview: !this.docked });
      this.eng.onError = () => this._recover();
      this.recBusy = false; if (this.stall) this.stall.reset();
      const clear = () => { this.video.removeEventListener('playing', clear); if (this.rec === rec) this.onNotice(alt ? 'Another source' : ''); };
      this.video.addEventListener('playing', clear);
      this.video.play().catch(() => {});
    }, step.delay);
  }
  /** Schedule a switch to channel c (after delay ms); c null = stop. url(key) -> signed URL. */
  show(c, url, delay = 600) {
    if (!c) { this.stop(); return; }
    if (c.key === this.key && this.eng) { clearTimeout(this.timer); this.pending = null; return; }
    if (c.key === this.pending) return;                 // already on its way (re-renders do not restart it)
    clearTimeout(this.timer);
    this.pending = c.key;
    this.timer = setTimeout(async () => {
      this.stop(false);
      this.pending = c.key;                              // still "on its way" while the stream starts
      this.docked = false; this.video.muted = this.wantMuted !== false;
      this.key = c.key;
      const done = () => { if (this.pending === c.key) this.pending = null; };
      if ((await beginStream('Preview · ' + c.name)) === false) { if (this.key === c.key) this.key = null; done(); return; }
      if (this.key !== c.key) return;
      if (typeof url === 'function') this.urlFor = (k) => url(k);
      let u; try { u = await (typeof url === 'function' ? url(c.key) : url); } catch (e) { done(); if (e && e.locked) this.onNotice('🔒 Locked'); return; }
      if (this.key !== c.key) return;
      this.eng = createEngine(this.video, { url: u, mode: c.streamType === 'hls' ? 'live' : 'ts', preview: true });
      this._watch(c);
      done();
      this.video.play().catch(() => {});
    }, delay);
  }
  /** Take over the full-screen player's <video> and engine (leaving full screen): it keeps
   *  playing, with sound, until the preview moves to another channel. srcKey: the alternate
   *  source the player had switched to, if any. */
  adopt(video, eng, key, ch, srcKey) {
    clearTimeout(this.timer); this.pending = null;
    if (this.eng) { this.eng.destroy(); }
    if (this.video !== video) {
      video.removeAttribute('style'); video.className = this.video.className;
      this.video.replaceWith(video);
      this.video = video;
    }
    video.playsInline = true;
    this.eng = eng; this.key = key; this.docked = true;
    if (ch) this._watch(ch, srcKey);
    if (video.paused) video.play().catch(() => {});
  }
  setMuted(m) { this.wantMuted = m; if (!this.docked) this.video.muted = m; }
  /** release=false when the full player takes over the same tab slot. */
  stop(release = true) {
    clearTimeout(this.timer); this.pending = null;
    this._unwatch(); this.rec = null; this.ch = null;
    const had = !!this.eng;
    if (this.eng) { this.eng.destroy(); this.eng = null; }
    this.key = null; this.docked = false;
    if (had && release) endStream();
  }
  dispose() { this.stop(); window.removeEventListener('tvbro-replaced', this._rep); }
}