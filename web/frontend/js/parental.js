// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Parental PIN in the browser: the PIN dialog, the unlock (a token from the server, kept for this tab
// only, 4 h), "ask before playing" for locked channels / films / shows, and lock / unlock menu items.
// The server decides what is locked and refuses to sign streams or give details without the unlock.

import { api, parentalToken, setParentalToken, cacheClear } from './api.js';
import { h, toast } from './util.js';

let info = null;   // {pin, active, groups, channels, vodCats}
export async function parentalInfo(fresh = false) {
  if (!info || fresh) { try { info = await api.parental(); } catch { info = info || { pin: false, active: false, groups: [], channels: [], vodCats: [] }; } }
  return info;
}
export function isUnlocked() { return !!parentalToken(); }

/** A small PIN dialog (digits, hidden). Resolves the PIN or null (cancelled). */
export function askPin(title, hint = '') {
  return new Promise((resolve) => {
    const prev = document.activeElement;
    const input = h('input', { type: 'password', inputmode: 'numeric', autocomplete: 'off', maxlength: '8', class: 'focusable pin-input', 'aria-label': 'PIN' });
    const msg = h('div', { class: 'hint pin-msg' }, hint);
    const ok = h('button', { class: 'btn focusable' }, 'OK');
    const cancel = h('button', { class: 'btn secondary focusable' }, 'Cancel');
    const back = h('div', { class: 'modal-back' }, h('div', { class: 'modal pin-modal', role: 'dialog', 'aria-label': title },
      h('div', { class: 'modal-title' }, title), msg, input, h('div', { class: 'pin-btns' }, ok, cancel)));
    const done = (v) => { document.removeEventListener('keydown', onKey, true); back.remove(); if (prev && prev.focus) prev.focus(); resolve(v); };
    ok.onclick = () => { const v = input.value.trim(); if (!/^\d{4,8}$/.test(v)) { msg.textContent = 'The PIN is 4 to 8 digits.'; input.focus(); return; } done(v); };
    cancel.onclick = () => done(null);
    back.addEventListener('click', (e) => { if (e.target === back) done(null); });
    function onKey(e) {
      e.stopPropagation();
      if (e.key === 'Escape') { e.preventDefault(); done(null); }
      else if (e.key === 'Enter') { e.preventDefault(); (document.activeElement === cancel ? cancel : ok).click(); }
      else if (e.key === 'ArrowDown' && document.activeElement === input) { e.preventDefault(); ok.focus(); }
      else if (e.key === 'ArrowUp' && document.activeElement !== input) { e.preventDefault(); input.focus(); }
      else if ((e.key === 'ArrowRight' || e.key === 'ArrowLeft') && document.activeElement !== input) { e.preventDefault(); (document.activeElement === ok ? cancel : ok).focus(); }
    }
    document.addEventListener('keydown', onKey, true);
    document.body.append(back);
    input.focus();
    askPin.last = { input, msg };            // the browser tests type into it
  });
}

/** Ask for the PIN until it is right (or cancelled). true = this tab is unlocked now. */
export async function unlock(why = 'Locked: enter the parental PIN') {
  let hint = '';
  for (;;) {
    const pin = await askPin(why, hint);
    if (pin == null) return false;
    try {
      const r = await api.parentalUnlock(pin);
      setParentalToken(r.token, r.ttl);
      window.dispatchEvent(new Event('tvbro-unlocked'));
      toast('Unlocked for this window (4 hours)');
      return true;
    } catch (e) { hint = e.message || 'Wrong PIN'; }
  }
}

/** Before playing ch: true when it is not locked, or after the PIN was given. */
export async function ensureUnlocked(item) {
  if (!item || !item.locked || isUnlocked()) return true;
  return unlock(`${item.name || 'This'} is locked: enter the parental PIN`);
}

/** Run fn; when the server says "locked" (423), ask for the PIN once and run it again. */
export async function withUnlock(fn, why) {
  try { return await fn(); }
  catch (e) {
    if (!e.locked) throw e;
    if (!(await unlock(why))) { const c = new Error('locked'); c.locked = true; c.cancelled = true; throw c; }
    return fn();
  }
}

/** Set a PIN when none exists yet (asked twice). true when one is set afterwards. */
export async function ensurePinSet() {
  const st = await parentalInfo(true);
  if (st.pin) return true;
  const a = await askPin('New parental PIN', 'Choose 4 to 8 digits. It is asked before locked channels, films and shows play.');
  if (a == null) return false;
  const b = await askPin('New parental PIN again');
  if (b == null) return false;
  if (a !== b) { toast('The two PINs differ: nothing changed'); return false; }
  try { const r = await api.parentalPin('', a); if (r.token) setParentalToken(r.token, 4 * 3600); info = r; toast('Parental PIN set'); return true; }
  catch (e) { toast(e.message); return false; }
}

/** Lock / unlock a live group, a channel or a VOD category (kind: group | channel | vodcat). */
export async function setLock(kind, id, locked, label = '') {
  if (!(await ensurePinSet())) return false;
  const go = () => api.parentalLock(kind, id, locked);
  try {
    if (!isUnlocked() && !(await unlock(locked ? 'Enter the parental PIN to lock it' : 'Enter the parental PIN to unlock it'))) return false;
    info = await withUnlock(go);
    cacheClear();
    window.dispatchEvent(new Event('tvbro-layout'));      // lists show the locks
    toast(`${locked ? '🔒 Locked' : '🔓 Unlocked'}${label ? ': ' + label : ''}`);
    return true;
  } catch (e) { if (!e.cancelled) toast(e.message); return false; }
}
export function isLocked(kind, id) {
  if (!info || !info.pin) return false;
  return (kind === 'group' ? info.groups : kind === 'channel' ? info.channels : info.vodCats).includes(id);
}

/** Settings > Parental controls > "Locked groups and categories…": toggles for every live group, film
 *  category and show category, and the channels locked one by one. */
export async function lockManager() {
  if (!(await ensurePinSet())) return;
  if (!isUnlocked() && !(await unlock('Enter the parental PIN to change the locks'))) return;
  let groups = [], movies = [], shows = [];
  try {
    [groups, movies, shows] = await Promise.all([api.groupsAll().then((r) => r.groups), api.vodCategories('movie').then((r) => r.categories), api.vodCategories('series').then((r) => r.categories).catch(() => [])]);
  } catch (e) { toast(e.message); return; }
  const st = await parentalInfo(true);
  const prev = document.activeElement;
  const list = h('div', { class: 'lock-list' });
  const box = h('div', { class: 'modal lock-modal', role: 'dialog' }, h('div', { class: 'modal-title' }, 'Locked with the parental PIN'), list);
  const back = h('div', { class: 'modal-back' }, box);
  const close = () => { document.removeEventListener('keydown', onKey, true); back.remove(); if (prev && prev.focus) prev.focus(); };
  const toggle = (kind, id, name) => {
    const on = isLocked(kind, id);
    const b = h('button', { class: 'btn secondary focusable', 'data-lock': kind + ':' + id }, (on ? '🔒 ' : '◻ ') + name);
    b.onclick = async () => { if (await setLock(kind, id, !isLocked(kind, id), name)) b.textContent = (isLocked(kind, id) ? '🔒 ' : '◻ ') + name; b.focus(); };
    return b;
  };
  const sect = (t) => h('h3', { class: 'lock-h' }, t);
  list.append(sect('TV groups'), ...groups.filter((g) => !g.id.startsWith('__')).map((g) => toggle('group', g.id, g.name)));
  if (movies.length > 1) list.append(sect('Film categories'), ...movies.filter((c) => c.id !== '__all').map((c) => toggle('vodcat', c.id, c.name)));
  if (shows.length > 1) list.append(sect('Show categories'), ...shows.filter((c) => c.id !== '__all').map((c) => toggle('vodcat', c.id, c.name)));
  if (st.channels.length) {
    let chans = []; try { chans = (await api.channelsByKeys(st.channels.slice(0, 400))).items; } catch {}
    list.append(sect('Channels locked one by one'), ...chans.map((c) => toggle('channel', c.key, c.name)));
  }
  const done = h('button', { class: 'btn focusable' }, 'Done'); done.onclick = close;
  box.append(done);
  document.body.append(back);
  back.addEventListener('click', (e) => { if (e.target === back) close(); });
  const btns = () => [...box.querySelectorAll('.btn')];
  btns()[0].focus();
  function onKey(e) {
    if (document.querySelectorAll('.modal-back').length > 1) return;     // the PIN dialog is on top
    e.stopPropagation();
    const b = btns(), i = b.indexOf(document.activeElement);
    if (e.key === 'Escape' || e.key === 'Backspace') { e.preventDefault(); close(); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); b[Math.min(b.length - 1, i + 1)].focus(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); b[Math.max(0, i - 1)].focus(); }
    else if (e.key === 'Enter') { e.preventDefault(); if (document.activeElement) document.activeElement.click(); }
  }
  document.addEventListener('keydown', onKey, true);
}

/** Settings > Parental controls > change / remove the PIN. */
export async function changePin(remove = false) {
  const st = await parentalInfo(true);
  if (!st.pin) return ensurePinSet();
  const cur = await askPin(remove ? 'Remove the parental PIN: current PIN' : 'Change the parental PIN: current PIN');
  if (cur == null) return false;
  let a = '';
  if (!remove) {
    a = await askPin('New parental PIN'); if (a == null) return false;
    const b = await askPin('New parental PIN again'); if (b == null) return false;
    if (a !== b) { toast('The two PINs differ: nothing changed'); return false; }
  }
  try {
    const r = await api.parentalPin(cur, a);
    setParentalToken(r.token || null, 4 * 3600); info = r;
    toast(remove ? 'Parental PIN removed: nothing is locked now' : 'Parental PIN changed');
    window.dispatchEvent(new Event('tvbro-layout'));
    return true;
  } catch (e) { toast(e.message); return false; }
}
