// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Settings > Recordings > Folder / network share: pick the folder recordings are written to
// (free space, Test), and set up an SMB / NFS share. The server runs in an unprivileged
// container that cannot mount shares itself, so the share is mounted on the Proxmox host and
// bind-mounted into the container: this dialog shows the exact host commands. The share's
// password is stored encrypted on the server and never shown again.

import { api } from './api.js';
import { h, toast } from './util.js';
import { fmtSize } from './recordings.js';

export function storageDialog(withPin) {
  const back = h('div', { class: 'modal-back' });
  const box = h('div', { class: 'modal glass rs-box', role: 'dialog' });
  back.append(box); document.body.append(back);
  const prev = document.activeElement;
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey, true); if (prev && prev.focus) prev.focus(); };
  function onKey(e) {
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); return; }
    e.stopPropagation();                         // typing here must not drive the screens behind
  }
  document.addEventListener('keydown', onKey, true);
  back.addEventListener('click', (e) => { if (e.target === back) close(); });

  let info = null, browse = '', listing = null, result = null, steps = [];
  const pathIn = h('input', { class: 'focusable rs-path', type: 'text', spellcheck: 'false', placeholder: '/mnt/recordings' });
  const shareF = {
    type: h('select', { class: 'focusable' }, h('option', { value: 'smb' }, 'SMB / CIFS (Windows, NAS)'), h('option', { value: 'nfs' }, 'NFS')),
    server: h('input', { class: 'focusable', type: 'text', placeholder: 'nas.example.com or 192.0.2.20' }),
    share: h('input', { class: 'focusable', type: 'text', placeholder: 'share name (SMB) or /export/path (NFS)' }),
    user: h('input', { class: 'focusable', type: 'text', placeholder: 'user (SMB)', autocomplete: 'off' }),
    pass: h('input', { class: 'focusable', type: 'password', placeholder: 'password (SMB)', autocomplete: 'new-password' }),
    mountpoint: h('input', { class: 'focusable', type: 'text', value: '/mnt/recordings' }),
  };

  async function load() {
    try {
      info = (await api.recordings()).storage || {};
      pathIn.value = info.dir || '';
      const sh = info.share;
      if (sh) { shareF.type.value = sh.type; shareF.server.value = sh.server || ''; shareF.share.value = sh.share || ''; shareF.user.value = sh.user || '';
        shareF.mountpoint.value = sh.mountpoint || '/mnt/recordings'; shareF.pass.placeholder = sh.hasPassword ? 'stored (leave empty to keep)' : 'password (SMB)';
        steps = (await api.recShareSteps()).steps || []; }
      await test(true);
    } catch (e) { toast('Could not load: ' + e.message); }
    render();
  }
  async function test(quiet) {
    try { result = await api.recDirTest(pathIn.value.trim()); } catch (e) { result = { ok: false, why: e.message }; }
    if (!quiet) toast(result.ok ? 'This folder works' : 'Not usable: ' + (result.why || ''));
    render();
  }
  async function open(path) {
    try { listing = await api.recDirs(path); browse = listing.path; } catch (e) { toast(e.message); }
    render();
  }
  async function useFolder() {
    try { const r = await withPin(() => api.recDirSet(pathIn.value.trim())); toast('New recordings go to ' + r.dir); info.dir = r.dir; await test(true); }
    catch (e) { toast('Could not use it: ' + e.message); }
  }
  async function saveShare() {
    const p = { type: shareF.type.value, server: shareF.server.value.trim(), share: shareF.share.value.trim(), user: shareF.user.value.trim(),
      pass: shareF.pass.value, mountpoint: shareF.mountpoint.value.trim() };
    try { const r = await withPin(() => api.recShare(p)); steps = r.steps || []; shareF.pass.value = ''; shareF.pass.placeholder = 'stored (leave empty to keep)';
      pathIn.value = p.mountpoint || '/mnt/recordings'; toast('Share saved: now do the host step below'); await test(true); }
    catch (e) { toast('Could not save: ' + e.message); }
    render();
  }
  async function clearShare() {
    try { await withPin(() => api.recShare({ clear: true })); steps = []; toast('Share removed'); } catch (e) { toast(e.message); }
    render();
  }

  function render() {
    const res = result;
    const kids = [h('div', { class: 'modal-title' }, 'Recordings folder'),
      h('div', { class: 'rs-row' }, pathIn,
        h('button', { class: 'btn secondary focusable', onclick: () => test(false) }, 'Test'),
        h('button', { class: 'btn focusable', onclick: useFolder }, 'Use this folder')),
      res ? h('div', { class: 'rs-res ' + (res.ok ? 'ok' : 'bad') },
        res.ok ? `✓ Writable · ${fmtSize(res.free)} free of ${fmtSize(res.total)}${res.separateMount ? ' · own mount (' + res.mount + ')' : ' · on the container\'s own disk'}`
          : '✕ ' + (res.why || 'not usable')) : null,
      h('div', { class: 'hint' }, `Now: ${info && info.dir ? info.dir : '…'}${info && info.dir === info.default ? ' (default, the container\'s disk)' : ''}. New recordings go to the chosen folder; existing ones stay where they are.`),
      h('div', { class: 'rs-sub' }, 'Browse folders on the server'),
      h('div', { class: 'rs-browse' },
        h('div', { class: 'rs-crumb' }, browse || 'Top',
          listing && listing.parent != null ? h('button', { class: 'btn secondary focusable rs-small', onclick: () => open(listing.parent) }, '↑ Up') : null,
          browse ? h('button', { class: 'btn secondary focusable rs-small', onclick: () => { pathIn.value = browse; test(true); } }, 'Pick this one') : null),
        ...(listing ? (listing.dirs.length ? listing.dirs.map((d) => h('button', { class: 'rs-dir focusable', onclick: () => open(d.path) }, '📁 ' + d.name)) : [h('div', { class: 'hint' }, 'No sub-folders.')])
          : [h('button', { class: 'btn secondary focusable rs-small', onclick: () => open('') }, 'Browse…')])),
      h('div', { class: 'rs-sub' }, 'Network share (SMB / NFS)'),
      h('div', { class: 'hint' }, 'The server runs in an unprivileged container, which cannot mount network shares itself. Save the share here, then run the commands below once on the Proxmox host (they mount it on the host and pass it into the container), and pick the folder above. The password is stored encrypted on the server and is not part of the commands: put it in the credentials file on the host.'),
      h('div', { class: 'rs-grid' },
        h('label', {}, 'Type'), shareF.type, h('label', {}, 'Server'), shareF.server, h('label', {}, 'Share / export'), shareF.share,
        h('label', {}, 'User'), shareF.user, h('label', {}, 'Password'), shareF.pass, h('label', {}, 'Folder in the container'), shareF.mountpoint),
      h('div', { class: 'rs-row' }, h('button', { class: 'btn focusable', onclick: saveShare }, 'Save share'),
        info && info.share ? h('button', { class: 'btn secondary focusable', onclick: clearShare }, 'Remove share') : null),
      steps.length ? h('div', { class: 'rs-steps' }, h('div', { class: 'rs-sub' }, 'On the Proxmox host (as root), once:'),
        h('pre', {}, steps.join('\n')),
        h('button', { class: 'btn secondary focusable rs-small', onclick: async () => { try { await navigator.clipboard.writeText(steps.join('\n')); toast('Copied'); } catch { toast('Select and copy the text'); } } }, 'Copy')) : null,
      h('div', { class: 'rs-row end' }, h('button', { class: 'btn secondary focusable', onclick: close }, 'Close')),
    ];
    const ae = document.activeElement;
    box.replaceChildren(...kids.filter(Boolean));
    if (ae && box.contains(ae)) ae.focus();
  }
  render();
  load();
  pathIn.focus();
}
