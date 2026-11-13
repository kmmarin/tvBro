// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Live TV that stalls or errors: reconnect with back-off, then try the channel's alternate sources
// (the same channel listed again: same tvg-id or name, from /api/alternates), then rest and start over.
// The policy is pure (unit-tested with node); player.js and the preview drive it and show a small
// notice, never a dialog.

/** Reconnects of the channel itself before alternates are tried (ms to wait before each). */
export const RETRY_DELAYS = [1000, 3000, 6000];
/** An alternate gets one reconnect of its own. */
export const ALT_TRIES = 1;
/** No progress for this long while playing (or starting) = stalled. */
export const STALL_MS = 15000;
/** Playing this long without trouble counts as recovered (the reconnect count starts again). */
export const HEALTHY_MS = 20000;
/** Every source failed: wait this long, then start over from the channel itself. */
export const REST_MS = 30000;

export class LiveRecovery {
  /** origin: the channel's key (what the guide / player show as the current channel). */
  constructor(origin) {
    this.origin = origin; this.key = origin; this.tries = 0;
    this.alts = null; this.altIdx = -1; this.rounds = 0; this.alt = null;
  }
  /** The source playing now failed (error or stall). Returns what to do:
   *  {action:'retry', key, delay, attempt, of} reconnect the same source;
   *  {action:'alts'}  fetch the alternates, call setAlts(list) and failed() again;
   *  {action:'alt', key, alt, delay} switch to an alternate (alt = its channel);
   *  {action:'rest', key, delay}  every source failed: wait, then the channel itself again. */
  failed() {
    const limit = this.key === this.origin ? RETRY_DELAYS.length : ALT_TRIES;
    if (this.tries < limit) {
      const delay = RETRY_DELAYS[Math.min(this.tries, RETRY_DELAYS.length - 1)];
      this.tries++;
      return { action: 'retry', key: this.key, delay, attempt: this.tries, of: limit };
    }
    if (this.alts == null) return { action: 'alts' };
    if (this.altIdx + 1 < this.alts.length) {
      this.altIdx++; this.alt = this.alts[this.altIdx]; this.key = this.alt.key; this.tries = 0;
      return { action: 'alt', key: this.key, alt: this.alt, delay: 300 };
    }
    this.rounds++; this.key = this.origin; this.alt = null; this.altIdx = -1; this.tries = 0;
    return { action: 'rest', key: this.origin, delay: REST_MS };
  }
  setAlts(list) { this.alts = (list || []).filter((c) => c && c.key && c.key !== this.origin); }
  /** Playing fine again (on whatever source): later trouble starts with reconnects again. */
  healthy() { this.tries = 0; this.rounds = 0; }
  /** The notice for a step (short, for a small corner label). */
  static notice(step, name) {
    if (step.action === 'retry') return `Reconnecting… (${step.attempt}/${step.of})`;
    if (step.action === 'alt') return `Trying another source: ${step.alt.name || 'alternate'}`;
    if (step.action === 'rest') return `${name || 'This channel'} is not available · trying again in ${Math.round(step.delay / 1000)} s`;
    return '';
  }
}

/**
 * Watches a <video> for a live stream that stops moving. onStall() once per stall; call
 * reset() after restarting the stream. ok() -> false pauses the watch (paused by the viewer, a
 * layer on top, not ours any more). onHealthy() after HEALTHY_MS of steady playback.
 */
export function watchStall(video, { onStall, onHealthy, ok = () => true, stallMs = STALL_MS, healthyMs = HEALTHY_MS, every = 1000 }) {
  let lastT = -1, lastMove = Date.now(), steadySince = 0, fired = false, healthyFired = false;
  const tick = () => {
    const now = Date.now();
    if (!ok() || (video.paused && video.readyState > 0 && !fired)) { lastMove = now; steadySince = 0; return; }
    const t = video.currentTime || 0;
    if (t !== lastT) {
      lastT = t; lastMove = now;
      if (!steadySince) steadySince = now;
      if (!healthyFired && now - steadySince >= healthyMs) { healthyFired = true; if (onHealthy) onHealthy(); }
      return;
    }
    steadySince = 0;
    if (!fired && now - lastMove >= stallMs) { fired = true; onStall(); }
  };
  const id = setInterval(tick, every);
  return {
    reset() { lastT = -1; lastMove = Date.now(); steadySince = 0; fired = false; healthyFired = false; },
    stop() { clearInterval(id); },
  };
}
