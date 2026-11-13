// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Pure VOD helpers (unit-tested in Node): episode-title cleaning, resume / next-episode choice.

const SEP = '[\\s\\-–:._|]';
/** Strip the series name, a "(year)", "SxxEyy" prefixes and separators providers put in episode titles. */
export function cleanEpisodeTitle(series, title) {
  const orig = String(title || '').trim();
  let t = orig;
  const s = String(series || '').trim();
  if (s && t.toLowerCase().startsWith(s.toLowerCase())) t = t.slice(s.length);
  t = t.replace(/\(\d{4}\)/g, ' ');
  t = t.replace(new RegExp(`^${SEP}*[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}${SEP}*`), '');
  t = t.replace(new RegExp(`^${SEP}+`), '').replace(new RegExp(`${SEP}+$`), '').replace(/\s+/g, ' ');
  return t || orig;
}

/** Group episodes into seasons [{season, eps:[...]}] sorted by season, then episode number. */
export function seasonsOf(episodes) {
  const m = new Map();
  for (const e of episodes || []) { if (!m.has(e.season)) m.set(e.season, []); m.get(e.season).push(e); }
  return [...m.keys()].sort((a, b) => a - b).map((s) => ({ season: s, eps: m.get(s).sort((a, b) => a.num - b.num) }));
}

/**
 * The episode the main button should start: the most recently watched one if unfinished
 * ("Resume S1 E3"), the one after it if it was finished ("Play S1 E4"), else S1 E1.
 * resume: map "e|<id>" -> {secs, dur, at, done}
 */
export function nextEpisode(episodes, resume) {
  const flat = seasonsOf(episodes).flatMap((s) => s.eps);
  if (!flat.length) return null;
  let last = -1, lastAt = -1;
  flat.forEach((e, i) => { const r = resume['e|' + e.id]; if (r && r.at > lastAt) { lastAt = r.at; last = i; } });
  if (last < 0) return { ep: flat[0], resume: false };
  const r = resume['e|' + flat[last].id];
  if (r.done) return last + 1 < flat.length ? { ep: flat[last + 1], resume: false } : { ep: flat[0], resume: false, again: true };
  return { ep: flat[last], resume: true, secs: r.secs };
}

// ---- shows (the server decides watched / next for Continue watching; same rules here) ----
export const WATCHED_FRAC = 0.9;
/** An episode counts as watched once 90 % has played, or it played to the end. */
export function isWatchedPos(secs, dur, ended) { return !!ended || (dur > 0 && secs >= WATCHED_FRAC * dur); }

/** The episode after `id` in season / episode order (into the next season), or null. */
export function episodeAfter(episodes, id, dir = 1) {
  const flat = seasonsOf(episodes).flatMap((s) => s.eps);
  const i = flat.findIndex((x) => String(x.id) === String(id));
  return i < 0 ? null : flat[i + dir] || null;
}

/** When the "Up next" card shows: in the last 30 s of an episode whose length is known
 *  (longer than two minutes), or when it ended. */
export const UPNEXT_LEAD = 30, UPNEXT_COUNT = 10;
export function upNextDue(pos, dur, ended) { return !!ended || (dur > 120 && pos > 0 && dur - pos <= UPNEXT_LEAD); }

/**
 * The caption (or audio) track for a new episode from the series' saved choice.
 * tracks: [{language, label}] ; want: saved language / label, 'off', or null (nothing saved).
 * -> index into tracks, -1 = off, null = leave as it is. A missing language takes the first track.
 */
export function pickTrack(tracks, want) {
  if (want == null || want === '') return null;
  if (want === 'off') return -1;
  if (!tracks || !tracks.length) return null;
  const i = tracks.findIndex((t) => t.language === want || t.label === want || t.lang === want || t.name === want);
  return i >= 0 ? i : 0;
}

/** "S2 E4" */
export const epCode = (e) => `S${e.season} E${e.num}`;

/** "1 h 45 min" / "50 min" from minutes. */
export function fmtRuntime(min) {
  const m = parseInt(min, 10); if (!m) return '';
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60 ? (m % 60) + ' min' : ''}`.trim() : `${m} min`;
}
