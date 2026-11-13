// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// This browser's session. By default no password is needed: /api/session hands every browser a
// session of its own (it carries the CSRF token and signs media links). When the admin turned on
// "Require a password" (Settings ▸ Security), this page asks for it: the admin password, or the
// viewer password (watch only). The session is an HttpOnly cookie; nothing is kept in the page.
import { api } from './api.js';
import { h } from './util.js';

let shown = false;

/** Covers the app until the password is right, then reloads. */
export function showSignIn() {
  if (shown) return;
  shown = true;
  const pw = h('input', { type: 'password', class: 'focusable signin-input', autocomplete: 'current-password', 'aria-label': 'Password', maxlength: '200' });
  const msg = h('div', { class: 'hint signin-msg', role: 'status' });
  const btn = h('button', { class: 'btn focusable', type: 'submit' }, 'Sign in');
  const form = h('form', { class: 'signin-card glass', autocomplete: 'on' },
    h('div', { class: 'brand signin-brand' }, h('span', { class: 'logo' }, 'tv'), 'Bro'),
    h('h1', {}, 'Sign in'),
    h('p', { class: 'hint' }, 'This tvBro server asks for a password. Use the admin password, or the viewer password if your household has one.'),
    h('label', {}, 'Password'), pw, msg, btn);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    msg.textContent = '';
    btn.disabled = true;
    try {
      await api.authLogin(pw.value);
      location.reload();
    } catch (err) {
      msg.textContent = err.status === 429 ? `Too many wrong passwords. Try again in ${err.retryAfter || 30} s.`
        : err.status === 403 ? 'Wrong password.' : err.message;
      btn.disabled = false;
      pw.select();
    }
  });
  // keys stay in the form (the app's own key handling is behind it)
  form.addEventListener('keydown', (e) => e.stopPropagation());
  const back = h('div', { class: 'signin-back' }, form);
  document.body.append(back);
  setTimeout(() => pw.focus(), 30);
}

/** A small dialog with password fields; resolves to their values, or null (Cancel / Escape). */
export function passwordDialog(title, labels, note) {
  return new Promise((resolve) => {
    const inputs = labels.map((l) => h('input', { type: 'password', class: 'focusable signin-input', 'aria-label': l, autocomplete: 'off', maxlength: '200' }));
    const ok = h('button', { class: 'btn focusable', type: 'submit' }, 'OK');
    const cancel = h('button', { class: 'btn secondary focusable', type: 'button' }, 'Cancel');
    const form = h('form', { class: 'modal signin-card', role: 'dialog', 'aria-label': title },
      h('div', { class: 'modal-title' }, title), note ? h('p', { class: 'hint' }, note) : null,
      ...labels.map((l, i) => [h('label', {}, l), inputs[i]]).flat(), h('div', { class: 'pin-btns' }, ok, cancel));
    const back = h('div', { class: 'modal-back' }, form);
    const done = (v) => { back.remove(); resolve(v); };
    form.addEventListener('submit', (e) => { e.preventDefault(); done(inputs.map((i) => i.value)); });
    cancel.addEventListener('click', () => done(null));
    form.addEventListener('keydown', (e) => { e.stopPropagation(); if (e.key === 'Escape') done(null); });
    document.body.append(back);
    setTimeout(() => inputs[0].focus(), 30);
  });
}

/** This browser's session (null while the sign-in page is shown). */
export async function ensureSignedIn() {
  let s;
  try { s = await api.session(); } catch { s = null; }
  if (s && s.auth === 'ok') return s;
  showSignIn();
  return null;
}

// a 401 later on: sign in (password required), or the session was dropped: open the page again (once)
window.addEventListener('tvbro-signin', (e) => {
  if (e.detail === 'login') { showSignIn(); return; }
  let last = 0; try { last = +sessionStorage.getItem('tvbro.reload') || 0; } catch {}
  if (Date.now() - last > 15000) { try { sessionStorage.setItem('tvbro.reload', String(Date.now())); } catch {} location.reload(); }
});
