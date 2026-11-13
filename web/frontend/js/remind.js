// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Fires reminders: an in-app glass pop-up "<title> is starting now on <channel>"
// (Watch now / Dismiss) and a browser Notification when the user allowed them.

import { api } from './api.js';
import { h, nowSec } from './util.js';
import { reminders, saveReminders, dueReminders, prune, nextDueIn } from './reminders.js';
import { playLive } from './playback.js';

let timer = null;

export function startReminders() {
  window.addEventListener('tvbro-reminders', () => { askPermission(); schedule(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) check(); });
  check();
}
function askPermission() {
  try { if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission().catch(() => {}); } catch {}
}
function schedule() {
  clearTimeout(timer);
  const d = nextDueIn(reminders(), nowSec());
  // wake at the due time (capped so a sleeping tab re-checks regularly)
  timer = setTimeout(check, Math.min(60, d == null ? 60 : Math.max(1, d)) * 1000);
}
function check() {
  const now = nowSec();
  let l = reminders();
  const due = dueReminders(l, now);
  for (const r of due) { r.fired = true; fire(r); }
  l = prune(l.map((x) => due.find((d) => d.key === x.key && d.start === x.start) ? { ...x, fired: true } : x), now);
  saveReminders(l);
  schedule();
}
function fire(r) {
  const watch = async () => {
    close();
    try { const res = await api.channelsByKeys([r.key]); if (res.items[0]) playLive(res.items[0]); } catch {}
  };
  const box = h('div', { class: 'rem-pop glass', role: 'alert' },
    r.logo ? h('img', { class: 'rem-logo', alt: '', src: api.img(r.logo) }) : null,
    h('div', { class: 'rem-text' }, h('div', { class: 'rem-t' }, `${r.title} is starting now`), h('div', { class: 'rem-s' }, `on ${r.channel}`)),
    h('div', { class: 'rem-acts' }, h('button', { class: 'btn focusable', onclick: watch }, '▶ Watch now'), h('button', { class: 'btn secondary focusable', onclick: () => close() }, 'Dismiss')));
  const close = () => box.remove();
  document.body.append(box);
  setTimeout(close, 120000);
  try {
    if ('Notification' in window && Notification.permission === 'granted' && document.hidden) {
      const n = new Notification(`${r.title} is starting now`, { body: `on ${r.channel}`, tag: r.key + '@' + r.start, icon: r.logo ? api.img(r.logo) : undefined });
      n.onclick = () => { window.focus(); watch(); n.close(); };
    }
  } catch {}
}
