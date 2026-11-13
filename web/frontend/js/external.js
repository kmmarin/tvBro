// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Live TV in another player (VLC, Infuse, mpv, a smart TV app): the server's signed stream URL for the
// channel, valid 24 hours on the home network. It names only the channel, an expiry and a signature;
// the provider login stays on the server, which relays the stream (HLS segments come back through it
// too). The link gets its own stream session, so it counts against the stream limit like a browser.

import { api } from './api.js';
import { externalLinks } from './playfmt.js';
import { h, toast } from './util.js';
import { withUnlock } from './parental.js';

/** Absolute, signed live URL for an external player (native format: no fmt, so no browser remux). */
export async function liveExternalUrl(ch) {
  const r = await withUnlock(() => api.liveExternal(ch.key), `${ch.name} is locked: enter the parental PIN`);
  const sid = 'x' + Math.random().toString(36).slice(2, 10);
  return new URL(r.url + (r.url.includes('?') ? '&' : '?') + 'sid=' + sid, location.href).href;
}

/** An .m3u playlist with that one channel (most players open it with a double-click). */
export function m3uFor(ch, url) {
  const name = String(ch.name || 'Channel').replace(/[\r\n,]/g, ' ');
  return `#EXTM3U\n#EXTINF:-1 tvg-name="${name.replace(/"/g, "'")}"${ch.logo ? ` tvg-logo="${String(ch.logo).replace(/"/g, '')}"` : ''},${name}\n${url}\n`;
}

/** The dialog: the link (selectable), Open in VLC / Infuse, Copy, Download .m3u. onLeave: before an app opens. */
export async function openLiveExternally(ch, onLeave) {
  let url;
  try { url = await liveExternalUrl(ch); } catch (e) { if (!e.cancelled) toast('No link: ' + e.message); return; }
  const prev = document.activeElement;
  const field = h('input', { type: 'text', class: 'focusable ext-url', readonly: true, value: url, 'aria-label': 'Stream link' });
  const copy = async () => {
    try { await navigator.clipboard.writeText(url); toast('Link copied'); }
    catch { field.focus(); field.select(); try { document.execCommand('copy'); toast('Link copied'); } catch { toast('Select the link and copy it'); } }
  };
  const download = () => {
    const a = h('a', { href: URL.createObjectURL(new Blob([m3uFor(ch, url)], { type: 'audio/x-mpegurl' })), download: (ch.name || 'channel').replace(/[^\w .-]+/g, '_') + '.m3u' });
    document.body.append(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  };
  const btns = [
    ...externalLinks(url).map((l) => { const b = h('a', { class: 'btn focusable', href: l.href }, l.name); b.addEventListener('click', () => { if (onLeave) onLeave(); }); return b; }),
    h('button', { class: 'btn secondary focusable', 'data-act': 'copy', onclick: copy }, 'Copy link'),
    h('button', { class: 'btn secondary focusable', 'data-act': 'm3u', onclick: download }, 'Download .m3u'),
  ];
  const closeB = h('button', { class: 'btn secondary focusable', onclick: () => close() }, 'Close');
  const box = h('div', { class: 'modal ext-modal', role: 'dialog', 'aria-label': 'Open in a player app' },
    h('div', { class: 'modal-title' }, 'Watch ' + ch.name + ' in a player app'),
    h('div', { class: 'hint' }, 'This link plays the channel in VLC, Infuse, mpv or a TV app on your home network for 24 hours. It holds no login; it counts as one stream for the limit.'),
    field, h('div', { class: 'ext-btns' }, ...btns, closeB));
  const back = h('div', { class: 'modal-back' }, box);
  function close() { document.removeEventListener('keydown', onKey, true); back.remove(); if (prev && prev.focus) prev.focus(); }
  back.addEventListener('click', (e) => { if (e.target === back) close(); });
  document.body.append(back);
  const all = () => [field, ...box.querySelectorAll('.ext-btns .btn')];
  all()[1].focus();
  function onKey(e) {
    e.stopPropagation();
    const it = all(), i = it.indexOf(document.activeElement);
    if (e.key === 'Escape' || e.key === 'Backspace') { e.preventDefault(); close(); }
    else if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); it[Math.min(it.length - 1, i + 1)].focus(); }
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); it[Math.max(0, i - 1)].focus(); }
    else if (e.key === 'Enter' && document.activeElement === field) { e.preventDefault(); copy(); }
  }
  document.addEventListener('keydown', onKey, true);
  return { url, close };
}
