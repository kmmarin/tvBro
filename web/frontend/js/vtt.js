// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Minimal WebVTT cue parser (the server writes remuxed films' text subtitles as WebVTT; the
// player polls the growing file and adds new cues to a TextTrack). Pure logic, unit-tested.

function secs(t) {
  const p = t.trim().split(':');
  if (p.length < 2 || p.length > 3) return NaN;
  const [h, m, s] = p.length === 3 ? p : ['0', ...p];
  return +h * 3600 + +m * 60 + parseFloat(s.replace(',', '.'));
}

/** @returns {{start: number, end: number, text: string}[]} complete cues only */
export function parseVtt(text) {
  const out = [];
  const blocks = String(text || '').replace(/\r\n?/g, '\n').split(/\n{2,}/);
  // the last block may still be being written: keep it only when the file ends with a line end
  const complete = /\n$/.test(String(text || '').replace(/\r\n?/g, '\n'));
  blocks.forEach((b, i) => {
    if (i === blocks.length - 1 && !complete) return;
    const lines = b.split('\n');
    const at = lines.findIndex((l) => l.includes('-->'));
    if (at < 0) return;
    const [a, rest] = lines[at].split('-->');
    const start = secs(a), end = secs((rest || '').trim().split(/\s+/)[0] || '');
    const body = lines.slice(at + 1).join('\n').trim();
    if (isFinite(start) && isFinite(end) && end > start && body) out.push({ start, end, text: body });
  });
  return out;
}
