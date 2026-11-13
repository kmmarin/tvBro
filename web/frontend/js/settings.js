// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Settings: sign-in, per-playlist sync status (Guide / Channels / Movies & Series) with live
// progress, refresh intervals, stream limit, and the per-browser preferences.

import { api, cacheClear, loadState } from './api.js';
import { h, toast, fmtTime, spatialMove } from './util.js';
import { prefs, setPref } from './prefs.js';
import { storageDialog } from './recstore.js';
import { parentalInfo, changePin, lockManager } from './parental.js';
import { passwordDialog } from './signin.js';

function ago(ts) {
  if (!ts) return 'never';
  const s = Math.max(0, Math.floor(Date.now() / 1000) - ts);
  if (s < 90) return 'just now';
  if (s < 5400) return Math.round(s / 60) + ' minutes ago';
  if (s < 172800) return Math.round(s / 3600) + ' hours ago';
  return Math.round(s / 86400) + ' days ago';
}
function when(ts) {
  if (!ts) return '—';
  const d = new Date(ts * 1000);
  return d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' }) + ' ' + fmtTime(ts);
}
function inFuture(ts) {
  if (!ts) return 'after the first sync';
  const s = ts - Math.floor(Date.now() / 1000);
  if (s <= 60) return 'due now';
  return when(ts) + (s < 5400 ? ` (in ${Math.round(s / 60)} min)` : ` (in ${Math.round(s / 3600)} h)`);
}
const fmtBytes = (n) => n > 1e6 ? (n / 1e6).toFixed(1) + ' MB' : n > 1e3 ? Math.round(n / 1e3) + ' kB' : n + ' B';
const num = (n) => (n || 0).toLocaleString();

/** Run an admin action (provider, settings, storage, restore): a viewer sign-in gets a clear message. */
async function withPin(fn) {
  try { return await fn(); }
  catch (e) {
    if (e.status === 403 && /admin only/.test(e.message)) throw new Error('only the admin can change this (sign in on this device with the admin password)');
    throw e;
  }
}

export function settingsScreen(main, nav, ctx) {
  main.innerHTML = '';
  const scr = h('div', { class: 'screen set' }, h('h1', {}, 'Settings'));
  main.append(scr);
  let alive = true, timer = null, status = null, adding = false, role = 'admin', viewerPw = false, required = false;
  const body = h('div', { class: 'set-body' }); scr.append(body);

  (async () => {
    let s = { signedIn: false };
    try { s = await api.session(); } catch {}
    ctx.setSignedIn(s.signedIn);
    role = s.role || 'viewer'; viewerPw = !!s.viewerPassword; required = !!s.required;
    if (!s.signedIn) { if (role === 'admin') signInForm(); else body.replaceChildren(h('div', { class: 'empty' }, 'No playlist is set up yet. Ask the admin to add one.')); return; }
    poll();
  })();

  let parState = null;
  async function refreshPar() { parState = await parentalInfo(true); render(); }
  async function poll() {
    if (!alive) return;
    if (!parState) parentalInfo(true).then((p) => { parState = p; render(); });
    try { status = await api.syncStatus(); render(); } catch (e) { if (!status) body.replaceChildren(h('div', { class: 'empty' }, 'Could not load status: ' + e.message)); }
    const busy = status && status.playlists.some((p) => p.channels.running || p.epg.running || p.vod.running);
    clearTimeout(timer); timer = setTimeout(poll, busy ? 1000 : 10000);
  }
  async function syncNow(kind) {
    try { status = await api.syncNow(kind); render(); toast(kind === 'all' ? 'Syncing everything…' : 'Sync started'); } catch (e) { toast('Sync failed: ' + e.message); }
    clearTimeout(timer); timer = setTimeout(poll, 700);
  }
  async function save(upd) { try { status = await withPin(() => api.saveSettings(upd)); render(); toast('Saved'); } catch (e) { toast('Could not save: ' + e.message); render(); } }

  function select(value, options, onchange) {
    const s = h('select', { class: 'focusable' }, ...options.map(([v, l]) => h('option', { value: String(v), selected: String(v) === String(value) }, l)));
    s.onchange = () => onchange(s.value); return s;
  }
  function row(label, ...val) { return h('div', { class: 'set-row' }, h('div', { class: 'set-k' }, label), h('div', { class: 'set-v' }, ...val)); }
  function jobState(j) {
    if (!j.running) return null;
    const p = j.progress || {};
    const parts = [j.state];
    if (p.total && p.bytes) parts.push(Math.min(100, Math.round((p.bytes / p.total) * 100)) + '%');
    else if (p.bytes) parts.push(fmtBytes(p.bytes));
    if (p.programmes) parts.push(num(p.programmes) + ' programmes');
    const bar = h('div', { class: 'progress set-prog' + (p.total ? '' : ' indet') }, h('i', { style: { width: p.total && p.bytes ? Math.min(100, (p.bytes / p.total) * 100) + '%' : '35%' } }));
    return h('div', { class: 'set-running' }, h('span', { class: 'spin' }), parts.join(' · '), bar);
  }
  function errLine(j) { return j.lastError ? h('div', { class: 'set-err' }, `Last error ${when(j.lastError.at)}: ${j.lastError.msg}` + (j.lastOk && j.lastOk > j.lastError.at ? ' (later sync succeeded)' : ' — the previous data is kept')) : null; }
  function lastLine(j) { return j.lastOk ? `${when(j.lastOk)} · ${ago(j.lastOk)}${j.lastDurationSec ? ` · took ${j.lastDurationSec < 60 ? j.lastDurationSec + ' s' : Math.round(j.lastDurationSec / 60) + ' min'}` : ''}` : 'never'; }
  const INTERVALS = [[6, 'Every 6 hours'], [12, 'Every 12 hours'], [24, 'Every 24 hours'], [48, 'Every 48 hours']];

  function render() {
    if (!status || adding) return;
    const ae = document.activeElement, focusKey = ae && ae.dataset ? ae.dataset.k : null;
    const P = prefs();
    const kids = [];
    const many = status.playlists.length > 1;
    for (const pl of status.playlists) {
      const ep = pl.epg, ch = pl.channels, vd = pl.vod;
      kids.push(h('div', { class: 'set-pl' }, h('h2', {}, pl.name),
        h('span', { class: 'badge ' + (pl.enabled ? 'ok' : 'off') }, pl.enabled ? 'enabled' : 'disabled'),
        h('div', { class: 'set-pl-btns' },
          h('button', { class: 'btn secondary focusable', 'data-k': 'ren-' + pl.id, onclick: () => renameList(pl) }, 'Rename'),
          many || !pl.enabled ? h('button', { class: 'btn secondary focusable', 'data-k': 'en-' + pl.id, onclick: () => listAct(() => api.sourceUpdate(pl.id, { enabled: !pl.enabled }), pl.enabled ? 'Playlist disabled' : 'Playlist enabled') }, pl.enabled ? 'Disable' : 'Enable') : null,
          many ? h('button', { class: 'btn secondary focusable', onclick: () => { if (confirm('Remove the playlist ' + pl.name + ' from the server? Its login, channels and guide are deleted.')) listAct(() => api.sourceDelete(pl.id), 'Playlist removed'); } }, 'Remove') : null)));
      if (!pl.enabled) { kids.push(h('div', { class: 'hint' }, 'Disabled: its channels, guide, movies and series are hidden and not synced.')); continue; }
      // Guide
      kids.push(h('section', { class: 'card glass' },
        h('div', { class: 'card-h' }, h('h3', {}, 'Guide (EPG)'),
          h('button', { class: 'btn focusable', 'data-k': 'sync-epg', disabled: ep.running, onclick: () => syncNow('epg') }, ep.running ? 'Syncing…' : '⟳ Sync guide now')),
        jobState(ep),
        row('Last successful sync', lastLine(ep)),
        row('Channels with guide data', `${num(ep.channelsWithGuide)} / ${num(ep.channels)}`),
        row('Programmes stored', num(ep.programmes)),
        row('Stored range', ep.from ? `${when(ep.from)} → ${when(ep.to)}` : '—'),
        row('Next scheduled sync', inFuture(ep.nextAt)),
        row('Refresh interval', select(status.settings.epgHours, INTERVALS, (v) => save({ epgHours: +v }))),
        row('EPG time shift', select(P.epgShiftMin || 0, [-180, -120, -60, -30, 0, 30, 60, 120, 180].map((m) => [m, m ? `${m > 0 ? '+' : ''}${m / 60} h` : 'None']),
          (v) => { setPref('epgShiftMin', +v); toast('Guide time shift ' + (+v ? `${+v / 60} h` : 'off')); })),
        errLine(ep)));
      // Channels
      kids.push(h('section', { class: 'card glass' },
        h('div', { class: 'card-h' }, h('h3', {}, 'Channels'),
          h('button', { class: 'btn secondary focusable', 'data-k': 'sync-ch', disabled: ch.running, onclick: () => syncNow('channels') }, ch.running ? 'Syncing…' : '⟳ Sync now')),
        jobState(ch),
        row('Last sync', lastLine(ch)),
        row('Channels', `${num(ch.channels)} in ${num(ch.groups)} groups`),
        row('Refresh interval', select(status.settings.channelsHours, INTERVALS, (v) => save({ channelsHours: +v }))),
        errLine(ch)));
      // VOD
      kids.push(h('section', { class: 'card glass' },
        h('div', { class: 'card-h' }, h('h3', {}, 'Movies & Series'),
          vd.supported ? h('button', { class: 'btn secondary focusable', 'data-k': 'sync-vod', disabled: vd.running, onclick: () => syncNow('vod') }, vd.running ? 'Syncing…' : '⟳ Sync now') : null),
        vd.supported ? [jobState(vd), row('Last sync', lastLine(vd)), row('Titles', `${num(vd.movies)} movies · ${num(vd.series)} series`),
          row('Refresh interval', select(status.settings.vodHours, INTERVALS, (v) => save({ vodHours: +v }))), errLine(vd)]
          : h('div', { class: 'hint' }, 'This playlist type has no movies or series.')));
    }
    kids.push(h('div', { class: 'set-actions' },
      h('button', { class: 'btn focusable', 'data-k': 'sync-all', onclick: () => syncNow('all') }, '⟳ Sync everything'),
      h('button', { class: 'btn secondary focusable', 'data-k': 'add-list', onclick: () => { adding = true; signInForm(true); } }, '+ Add a playlist'),
      h('button', { class: 'btn secondary focusable', 'data-k': 'editor', onclick: () => { location.hash = 'editor'; } }, '✎ Channels & groups'),
      h('button', { class: 'btn secondary focusable', onclick: () => { location.hash = 'live'; } }, 'Go to TV')));
    // Streams
    const acc = status.account || {};
    const cap = parseInt(acc.max_connections, 10) || 3;
    const st = status.streams || { max: 1, count: 0, active: [] };
    kids.push(h('section', { class: 'card glass' },
      h('div', { class: 'card-h' }, h('h3', {}, 'Streams')),
      row('Maximum simultaneous streams', select(Math.min(status.settings.maxStreams, cap), [1, 2, 3].filter((n) => n <= cap).map((n) => [n, String(n)]), (v) => save({ maxStreams: +v }))),
      h('div', { class: 'hint' }, `Keep this at or below what your provider account allows${acc.max_connections ? ` (this account allows ${acc.max_connections})` : ''}. When the limit is reached, the oldest window stops and the new one plays.`),
      row('Playing now', st.count ? h('div', {}, ...st.active.map((a) => h('div', { class: 'set-stream' }, `${a.label || 'Browser'} — ${a.what || 'stream'}`, h('span', { class: 'hint' }, ` since ${fmtTime(a.since)}`)))) : 'Nothing')));
    // Recordings (server-side)
    const S = status.settings;
    kids.push(h('section', { class: 'card glass' },
      h('div', { class: 'card-h' }, h('h3', {}, 'Recordings'), h('button', { class: 'btn secondary focusable', onclick: () => { location.hash = 'recordings'; } }, 'Open Recordings')),
      row('Recordings folder', h('button', { class: 'btn secondary focusable', 'data-k': 'recdir', onclick: () => storageDialog(withPin) }, 'Folder / network share…')),
      row('Space for recordings', select(S.recMaxGB, [1, 2, 3, 5, 10, 20, 50, 100, 200].map((n) => [n, n + ' GB']), (v) => save({ recMaxGB: +v }))),
      row('Keep free on the server', select(S.recMinFreeMB, [[512, '512 MB'], [1024, '1 GB'], [2048, '2 GB'], [5120, '5 GB']], (v) => save({ recMinFreeMB: +v }))),
      row('Start early', select(S.recPadBeforeMin, [0, 1, 2, 5].map((n) => [n, n ? n + ' min' : 'No'] ), (v) => save({ recPadBeforeMin: +v }))),
      row('End late', select(S.recPadAfterMin, [0, 2, 5, 10, 15, 30].map((n) => [n, n ? n + ' min' : 'No']), (v) => save({ recPadAfterMin: +v }))),
      h('div', { class: 'hint' }, 'Recordings copy the channel to the server as it is broadcast (no conversion) and count as a stream for the limit above; a viewer never stops a recording. A recording stops by itself when the space is used up or free disk space falls under the floor.')));
    // General
    kids.push(h('section', { class: 'card glass' },
      h('div', { class: 'card-h' }, h('h3', {}, 'General')),
      row('Time format', select(P.timeFormat || '12', [['12', '12-hour (4:30 PM)'], ['24', '24-hour (16:30)'], ['auto', 'Browser default']],
        (v) => { setPref('timeFormat', v); fmtTime.fmt = v; window.dispatchEvent(new Event('tvbro-state')); toast('Time format: ' + (v === 'auto' ? 'browser default' : v + '-hour')); render(); })),
      h('div', { class: 'hint' }, 'Used everywhere times are shown: guide, clock, player, recordings, reminders, search. Shared by every device.')));
    // Playback
    kids.push(h('section', { class: 'card glass' },
      h('div', { class: 'card-h' }, h('h3', {}, 'Playback')),
      row('Play the next episode automatically', select(P.autoplayNext === false ? 'off' : 'on', [['on', 'On'], ['off', 'Off']], (v) => setPref('autoplayNext', v === 'on'))),
      h('div', { class: 'hint' }, 'Shows: near the end of an episode "Up next" counts down 10 seconds, then the next episode plays (into the next season). Cancel or Play now on the card. Captions you turn on or off in a show stay that way for the rest of that show.')));
    // Parental controls (PIN asked before locked channels, films and shows play)
    const par = parState || { pin: false, groups: [], channels: [], vodCats: [] };
    const nLocks = par.groups.length + par.channels.length + par.vodCats.length;
    kids.push(h('section', { class: 'card glass' },
      h('div', { class: 'card-h' }, h('h3', {}, 'Parental controls')),
      row('Parental PIN', par.pin ? 'Set' : 'Not set',
        h('button', { class: 'btn secondary focusable', 'data-k': 'ppin', onclick: async () => { await changePin(false); refreshPar(); } }, par.pin ? 'Change PIN' : 'Set PIN'),
        par.pin ? h('button', { class: 'btn secondary focusable', 'data-k': 'ppin-x', onclick: async () => { await changePin(true); refreshPar(); } }, 'Remove PIN') : null),
      row('Locked', nLocks ? `${par.groups.length} groups · ${par.vodCats.length} film / show categories · ${par.channels.length} channels` : 'Nothing',
        h('button', { class: 'btn secondary focusable', 'data-k': 'plocks', onclick: async () => { await lockManager(); refreshPar(); } }, 'Locked groups and categories…')),
      h('div', { class: 'hint' }, 'The PIN is asked before a locked channel, film or show plays (also catch-up and details), then this window stays unlocked for 4 hours. Lock single channels from the TV channel menu (M). The PIN is stored hashed on the server and shared by every device; after five wrong PINs each try waits 30 seconds.')));
    // Backup / restore (a JSON file; no logins or PINs)
    kids.push(h('section', { class: 'card glass' },
      h('div', { class: 'card-h' }, h('h3', {}, 'Backup & restore')),
      h('div', { class: 'set-actions' },
        h('a', { class: 'btn secondary focusable', 'data-k': 'backup', href: api.backupUrl, download: '' }, '⬇ Download a backup'),
        h('button', { class: 'btn secondary focusable', 'data-k': 'restore', onclick: () => pickRestore() }, '⬆ Restore from a file…')),
      h('div', { class: 'hint' }, 'One file with My list and favourites, resume points and watched marks, shows\' progress, reminders, search history, preferences, your channels & groups layout, parental locks, the guide mapping, refresh / recording settings and series-recording rules. Provider logins and PINs are never in it. Restoring replaces those lists on the server for every device (series-recording rules are added).')));
    // Per-browser preferences
    kids.push(h('section', { class: 'card glass' },
      h('div', { class: 'card-h' }, h('h3', {}, 'This browser')),
      row('Live preview', select(P.previewOn ? (P.previewMuted ? 'muted' : 'sound') : 'off', [['muted', 'On, muted'], ['sound', 'On, with sound'], ['off', 'Off']],
        (v) => { setPref('previewOn', v !== 'off'); setPref('previewMuted', v !== 'sound'); })),
      row('Channel numbers', select(P.numbering, [['provider', 'Provider numbers'], ['group', 'Per group (1, 2, 3…)']], (v) => setPref('numbering', v))),
      row('Default audio delay', select(P.audioDelayMs || 0, [0, 50, 100, 150, 200, 300, 400, 500].map((n) => [n, n ? `+${n} ms` : 'None']), (v) => setPref('audioDelayMs', +v))),
      row('Caption size', select(P.captionSize || 100, [[75, 'Small'], [100, 'Normal'], [130, 'Large'], [160, 'Extra large']], (v) => { setPref('captionSize', +v); applyCaptionPrefs(); })),
      row('Caption background', select(P.captionBg || 'shade', [['none', 'None'], ['shade', 'Shaded'], ['solid', 'Solid black']], (v) => { setPref('captionBg', v); applyCaptionPrefs(); })),
      row('Reduce transparency', select(P.solid ? 'on' : 'off', [['off', 'Off (glass)'], ['on', 'On (solid panels)']], (v) => { setPref('solid', v === 'on'); document.documentElement.classList.toggle('solid', v === 'on'); }))));
    // Account
    const sess = h('section', { class: 'card glass' }, h('div', { class: 'card-h' }, h('h3', {}, 'Account')),
      acc.exp_date ? row('Subscription ends', when(+acc.exp_date)) : null,
      acc.status ? row('Status', String(acc.status)) : null,
      role === 'admin' ? h('div', { class: 'set-actions' },
        h('button', { class: 'btn secondary focusable', onclick: async () => { if (confirm('Remove every playlist from the server? Every device loses the provider until it is set up again.')) { try { await withPin(() => api.signout()); ctx.setSignedIn(false); alive = false; settingsScreen(main, nav, ctx); } catch (e) { toast(e.message); } } } }, 'Remove all playlists (server)')) : null,
      h('div', { class: 'hint' }, 'The provider login stays on the server, encrypted; it is never sent to a browser.'));
    kids.push(sess);
    // Security: this device's sign-in, the admin / viewer passwords, sources on the local network
    const admin = role === 'admin';
    kids.push(h('section', { class: 'card glass' }, h('div', { class: 'card-h' }, h('h3', {}, 'Security')),
      admin ? row('Require a password', select(required ? 'on' : 'off', [['off', 'Off (anyone on your network)'], ['on', 'On']], async (v) => {
        try {
          if (v === 'on') {
            const vals = await passwordDialog('Require a password', ['Admin password (8 characters or more)', 'Again'],
              'Every device then signs in once with this password (or a viewer password you can add for watching only). This device stays signed in.');
            if (!vals) { render(); return; }
            if (vals[0] !== vals[1]) { toast('The passwords differ'); render(); return; }
            await api.authRequire(true, vals[0]);
            required = true; toast('A password is now required on every device');
          } else {
            const vals = await passwordDialog('Stop requiring a password', ['Current admin password'], 'Anyone on your network can then use and change the app without signing in.');
            if (!vals) { render(); return; }
            await api.authRequire(false, null, vals[0]);
            required = false; toast('No password needed any more');
          }
        } catch (e) { toast('Not changed: ' + e.message); }
        render();
      }), required ? null : h('div', { class: 'set-warn' }, 'Without a password anyone on your network can use and change the app (playlists, settings, recordings).')) : null,
      required ? row('This device', admin ? 'Signed in as admin' : 'Signed in as viewer (watch only)',
        h('button', { class: 'btn secondary focusable', 'data-k': 'logout', onclick: async () => { try { await api.authLogout(); } catch {} location.reload(); } }, 'Sign out on this device')) : null,
      admin && required ? row('Admin password', h('button', { class: 'btn secondary focusable', 'data-k': 'adminpw', onclick: async () => {
        const v = await passwordDialog('Change the admin password', ['Current admin password', 'New admin password (8 characters or more)', 'New admin password again']);
        if (!v) return;
        if (v[1] !== v[2]) { toast('The new passwords differ'); return; }
        try { await api.authPassword(v[0], v[1]); toast('Admin password changed; other admin sign-ins ended'); } catch (e) { toast('Not changed: ' + e.message); }
      } }, 'Change…')) : null,
      admin && required ? row('Viewer password', viewerPw ? 'Set' : 'Not set',
        h('button', { class: 'btn secondary focusable', 'data-k': 'viewpw', onclick: async () => {
          const v = await passwordDialog(viewerPw ? 'New viewer password' : 'Viewer password', ['Viewer password (8 characters or more)', 'Again']);
          if (!v) return;
          if (v[0] !== v[1]) { toast('The passwords differ'); return; }
          try { await api.authViewer(v[0]); viewerPw = true; render(); toast('Viewer password set; old viewer sign-ins ended'); } catch (e) { toast('Not set: ' + e.message); }
        } }, viewerPw ? 'Change…' : 'Set…'),
        viewerPw ? h('button', { class: 'btn secondary focusable', 'data-k': 'viewpw-x', onclick: async () => {
          if (!confirm('Remove the viewer password? Devices signed in with it are signed out.')) return;
          try { await api.authViewer(''); viewerPw = false; render(); toast('Viewer password removed'); } catch (e) { toast(e.message); }
        } }, 'Remove') : null) : null,
      admin ? row('Playlists and streams on your local network', select(S.allowLanSources ? 'on' : 'off', [['off', 'Blocked (safer)'], ['on', 'Allowed']], (v) => save({ allowLanSources: v === 'on' }))) : null,
      admin ? row('Container id (for the share steps)', S.ctid ? String(S.ctid) : 'Not set',
        h('button', { class: 'btn secondary focusable', 'data-k': 'ctid', onclick: () => {
          const v = window.prompt('Proxmox container id of tvBro (pct list), empty to clear', S.ctid || ''); if (v === null) return;
          save({ ctid: v.trim() });
        } }, 'Set…')) : null,
      h('div', { class: 'hint' }, 'With a password required, every device signs in once: the admin password changes playlists, settings, storage and restores; the viewer password (optional) only watches. Turn it on when the server can be reached from beyond your own trusted home network. Wrong passwords are slowed down. The server never fetches addresses on your own network (router, NAS, hypervisor) unless you allow it here, e.g. for a tvheadend box; loopback and link-local addresses are always refused.')));
    body.replaceChildren(...kids.flat().filter(Boolean));
    if (focusKey) { const f = body.querySelector(`[data-k="${focusKey}"]`); if (f) f.focus(); }
  }

  /** Restore: pick the file, the server checks it and says what is in it, confirm, restore. */
  function pickRestore() {
    const inp = h('input', { type: 'file', accept: '.json,application/json', style: { display: 'none' } });
    inp.onchange = async () => {
      const f = inp.files && inp.files[0]; inp.remove(); if (!f) return;
      let text = '';
      try { text = await f.text(); } catch (e) { toast('Could not read the file'); return; }
      let chk;
      try { chk = await withPin(() => api.restore(text, true)); } catch (e) { toast('Not restored: ' + e.message); return; }
      const c = chk.contains || {}, st = c.state || {};
      const lines = [st.mylist != null ? `${st.mylist} in My list / favourites` : null, st.resume != null ? `${st.resume} resume points` : null,
        st.reminders != null ? `${st.reminders} reminders` : null, st.prefs != null ? `${st.prefs} preferences` : null,
        c.series ? 'shows\' progress' : null, c.layout ? 'channels & groups layout' : null, c.parental ? 'parental locks' : null,
        c.epgMap != null ? `${c.epgMap} guide mappings` : null, c.seriesRules != null ? `${c.seriesRules} series-recording rules` : null].filter(Boolean);
      const when = chk.createdAt ? new Date(chk.createdAt * 1000).toLocaleString() : 'unknown date';
      if (!window.confirm(`Restore the backup of ${when}?\n\n${lines.join('\n')}\n\nThis replaces those lists on the server for every device.`)) return;
      try {
        const r = await withPin(() => api.restore(text, false));
        cacheClear(); await loadState(); window.dispatchEvent(new Event('tvbro-layout')); window.dispatchEvent(new Event('tvbro-series'));
        parState = null; poll();
        toast('Backup restored' + (r.restored && r.restored.mylist != null ? ` · ${r.restored.mylist} in My list` : ''));
      } catch (e) { toast('Not restored: ' + e.message); }
    };
    document.body.append(inp); inp.click();
  }
  async function listAct(fn, ok) { try { status = await withPin(fn); render(); toast(ok); } catch (e) { toast(e.message); } }
  function renameList(pl) {
    const n = window.prompt('Playlist name', pl.name); if (n === null) return;
    listAct(() => api.sourceUpdate(pl.id, { name: n.trim() }), 'Renamed');
  }
  function signInForm(another) {
    const form = h('div', { class: 'form card glass' });
    form.append(h('p', { class: 'hint' }, another ? 'Add another playlist: its channels, guide, movies and series appear next to the ones you have (groups are labelled with the playlist name). The login is stored encrypted on this server only.'
      : 'Set up the provider once for the whole house: the login is stored only on this server, encrypted, and never sent to browsers or written to logs. Every device in your household can then watch.'));
    const nameF = h('input', { type: 'text', class: 'focusable', placeholder: 'e.g. Main, Sports, Backup', autocomplete: 'off' });
    form.append(h('label', {}, 'Playlist name'), nameF);
    const kind = h('select', { class: 'focusable' }, h('option', { value: 'xtream' }, 'Xtream (username / password)'), h('option', { value: 'm3u' }, 'M3U playlist URL'), h('option', { value: 'demo' }, 'Demo / single stream (testing)'));
    const field = (label, type = 'text') => { const l = h('label', {}, label); const i = h('input', { type, class: 'focusable', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' }); return { l, i, show: (on) => { l.style.display = i.style.display = on ? '' : 'none'; } }; };
    const server = field('Server (http://host:port)'), user = field('Username'), pass = field('Password', 'password'), url = field('Playlist / stream URL'), epg = field('EPG (XMLTV) URL — optional');
    form.append(h('label', {}, 'Provider type'), kind, server.l, server.i, user.l, user.i, pass.l, pass.i, url.l, url.i, epg.l, epg.i);
    const upd = () => { const x = kind.value; [server, user, pass].forEach((f) => f.show(x === 'xtream')); url.show(x !== 'xtream'); epg.show(x === 'm3u'); };
    kind.onchange = upd; upd();
    const label = another ? 'Add playlist' : 'Sign in';
    const btn = h('button', { class: 'btn focusable', style: { marginTop: '18px' } }, label);
    btn.onclick = async () => {
      btn.disabled = true; btn.textContent = 'Saving…';
      try {
        await withPin(() => api.signin({ kind: kind.value, name: nameF.value.trim(), server: server.i.value.trim(), user: user.i.value.trim(), pass: pass.i.value, url: url.i.value.trim(), epgUrl: epg.i.value.trim() }));
        ctx.setSignedIn(true); toast('Playlist saved on the server');
        if (another) { adding = false; poll(); } else location.hash = 'live';
      } catch (e) { toast('Could not save: ' + e.message); btn.disabled = false; btn.textContent = label; }
    };
    form.append(h('div', {}, btn, another ? h('button', { class: 'btn secondary focusable', style: { marginTop: '18px', marginLeft: '10px' }, onclick: () => { adding = false; render(); } }, 'Cancel') : null));
    body.replaceChildren(form);
  }

  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && e.key === 'ArrowLeft') nav.focusActive(); return true; }
      if (e.key === 'Escape' || e.key === 'Backspace') { nav.focusActive(); return true; }
      return false;
    },
    focusIn() { const f = scr.querySelector('.focusable'); if (f) f.focus(); },
    destroy() { alive = false; clearTimeout(timer); },
  };
}

/** Caption style from prefs -> CSS variables used by ::cue. */
export function applyCaptionPrefs() {
  const P = prefs(), r = document.documentElement.style;
  r.setProperty('--cue-size', (P.captionSize || 100) + '%');
  r.setProperty('--cue-bg', P.captionBg === 'none' ? 'transparent' : P.captionBg === 'solid' ? '#000' : 'rgba(0,0,0,.6)');
}
