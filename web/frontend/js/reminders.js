// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Programme reminders: stored per browser; pure timing helpers are unit-tested.

const KEY = 'tvbro.reminders';
export const LEAD = 0;          // fire when the programme starts
export const GRACE = 10 * 60;   // still fire if the tab was asleep, up to 10 min after start

export function remKey(r) { return r.key + '@' + r.start; }

/** Reminders due at `now` that have not fired yet. */
export function dueReminders(list, now) {
  return list.filter((r) => !r.fired && now >= r.start - LEAD && now < Math.min(r.stop, r.start + GRACE));
}
/** Drop reminders whose programme is over (or fired long ago). */
export function prune(list, now) { return list.filter((r) => r.stop > now && !(r.fired && now > r.start + GRACE)); }
/** Seconds until the next reminder is due (for scheduling a timer), or null. */
export function nextDueIn(list, now) {
  let best = null;
  for (const r of list) if (!r.fired) { const d = Math.max(0, r.start - LEAD - now); if (best === null || d < best) best = d; }
  return best;
}

// storage: the server's shared state (lazy import keeps the pure helpers testable in Node)
let api = null;
export function useStore(mod) { api = mod; }
export function reminders() { return api ? [...api.state().reminders] : []; }
export function saveReminders(l) {
  if (!api) return;
  const cur = new Map(api.state().reminders.map((r) => [remKey(r), r]));
  const next = new Map(l.map((r) => [remKey(r), r]));
  for (const [k, r] of next) if (JSON.stringify(cur.get(k)) !== JSON.stringify(r)) api.statePut('reminders', k, r);
  for (const k of cur.keys()) if (!next.has(k)) api.stateDel('reminders', k);
  api.state().reminders = l;
}
export function hasReminder(key, start) { return reminders().some((r) => r.key === key && r.start === start); }
/** Toggle a reminder; returns true if it was added. r: {key, channel, title, start, stop, logo} */
export function toggleReminder(r) {
  const l = reminders(); const i = l.findIndex((x) => x.key === r.key && x.start === r.start);
  if (i >= 0) l.splice(i, 1); else l.push({ key: r.key, channel: r.channel, title: r.title, start: r.start, stop: r.stop, logo: r.logo || null });
  saveReminders(l); window.dispatchEvent(new Event('tvbro-reminders'));
  return i < 0;
}
