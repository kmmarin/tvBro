// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Channels & groups editor: the household's own layout, stored on the server and laid over
// the providers' lists. Make your own groups and fill them; rename, hide or reorder any group
// or channel. Every device sees the same layout; a provider sync keeps it.

import { api, cacheClear } from './api.js';
import { h, toast, spatialMove, menu } from './util.js';
import { guideMapDialog } from './epgpick.js';

/** Edit and tell the other screens their cached lists are stale. */
export async function layoutEdit(op, d) {
  const r = await api.layoutEdit(op, d);
  cacheClear();
  window.dispatchEvent(new Event('tvbro-layout'));
  return r;
}

/** "Add to group…" menu for a channel (used by the TV screen too). */
export async function addToGroupMenu(ch) {
  let groups = [];
  try { groups = (await api.groupsAll()).groups.filter((g) => g.custom); } catch (e) { toast(e.message); return; }
  const add = async (gid, name) => { try { await layoutEdit('channel.add', { group: gid, key: ch.key }); toast(`Added to ${name}`); } catch (e) { toast(e.message); } };
  menu(`Add ${ch.name} to…`, [
    ...groups.map((g) => ({ label: g.name, action: () => add(g.id, g.name) })),
    { label: '+ New group…', action: async () => {
      const n = window.prompt('Name of the new group'); if (!n || !n.trim()) return;
      try { const r = await layoutEdit('group.create', { name: n.trim() }); await add(r.id, n.trim()); } catch (e) { toast(e.message); }
    } },
  ]);
}

export function editorScreen(main, nav, startGroup) {
  main.innerHTML = '';
  const scr = h('div', { class: 'screen ed' });
  main.append(scr);
  let alive = true, groups = [], sel = startGroup || null, chans = [], busy = false;
  const left = h('div', { class: 'ed-col ed-groups' }), right = h('div', { class: 'ed-col ed-chans' });
  scr.append(h('div', { class: 'ed-head' }, h('h1', {}, 'Channels & groups'),
    h('button', { class: 'btn focusable', onclick: newGroup }, '+ New group'),
    h('button', { class: 'btn secondary focusable', onclick: resetAll }, 'Reset all'),
    h('button', { class: 'btn secondary focusable', onclick: () => { location.hash = 'live'; } }, 'Done')),
  h('div', { class: 'hint' }, 'Your layout is kept on the server for every device; provider updates keep it. Hidden groups and channels disappear from TV, the guide and search.'),
  h('div', { class: 'ed-cols' }, left, right));
  loadGroups();

  async function loadGroups() {
    try { groups = (await api.groupsAll()).groups.filter((g) => !g.id.startsWith('__')); } catch (e) { left.replaceChildren(h('div', { class: 'empty' }, e.message)); return; }
    if (!alive) return;
    if (!sel || !groups.some((g) => g.id === sel)) sel = groups.length ? groups[0].id : null;
    renderGroups();
    loadChannels();
  }
  async function loadChannels() {
    if (!sel) { right.replaceChildren(); return; }
    const want = sel; const all = [];
    try {
      for (let page = 1; page < 40; page++) {
        const r = await api.channelsAll(want, page);
        all.push(...r.items);
        if (all.length >= r.total || !r.items.length) break;
      }
    } catch (e) { right.replaceChildren(h('div', { class: 'empty' }, e.message)); return; }
    if (!alive || want !== sel) return;
    chans = all; renderChannels();
  }
  async function edit(op, d, ok, then = 'both') {
    if (busy) return; busy = true;
    const ae = document.activeElement, fk = ae && ae.dataset ? ae.dataset.k : null;
    try { await layoutEdit(op, d); if (ok) toast(ok); } catch (e) { toast(e.message); }
    busy = false;
    if (then === 'both') await loadGroups(); else await loadChannels();
    if (fk) { const f = scr.querySelector(`[data-k="${CSS.escape(fk)}"]`); if (f) f.focus(); }
  }
  const btn = (label, title, k, fn, extra = '') => h('button', { class: 'btn secondary focusable ed-b ' + extra, title, 'aria-label': title, 'data-k': k, onclick: fn }, label);

  function renderGroups() {
    left.replaceChildren(h('h2', {}, 'Groups'), ...groups.map((g) => {
      const row = h('div', { class: 'ed-row' + (g.id === sel ? ' sel' : '') + (g.hidden ? ' hid' : '') },
        h('button', { class: 'ed-name focusable', 'data-k': 'g:' + g.id, onclick: () => { sel = g.id; renderGroups(); loadChannels(); } },
          (g.custom ? '★ ' : '') + g.name, g.origName ? h('span', { class: 'ed-orig' }, ' (' + g.origName + ')') : null, h('span', { class: 'ed-count' }, ' ' + (g.count || 0))),
        btn('↑', 'Move up', 'gu:' + g.id, () => edit('group.move', { id: g.id, dir: -1 })),
        btn('↓', 'Move down', 'gd:' + g.id, () => edit('group.move', { id: g.id, dir: 1 })),
        btn('✎', 'Rename', 'gr:' + g.id, () => { const n = window.prompt(g.custom ? 'Group name' : 'Group name (empty = the provider\'s name)', g.name); if (n !== null) edit('group.rename', { id: g.id, name: n }, 'Renamed'); }),
        btn(g.hidden ? 'Show' : 'Hide', g.hidden ? 'Show this group' : 'Hide this group', 'gh:' + g.id, () => edit('group.hide', { id: g.id, hidden: !g.hidden }, g.hidden ? 'Group shown' : 'Group hidden')),
        g.custom ? btn('🗑', 'Delete group', 'gx:' + g.id, () => { if (confirm(`Delete the group "${g.name}"? Its channels stay in their provider groups.`)) edit('group.delete', { id: g.id }, 'Group deleted'); }) : null);
      return row;
    }));
  }
  function renderChannels() {
    const g = groups.find((x) => x.id === sel); if (!g) return;
    right.replaceChildren(h('h2', {}, g.name + (g.custom ? ' (your group)' : '')),
      chans.length ? null : h('div', { class: 'empty' }, g.custom ? 'Empty. Use “+ Group” on any channel (here or in the TV channel menu) to add it.' : 'No channels.'),
      ...chans.map((c) => h('div', { class: 'ed-row' + (c.hidden ? ' hid' : '') },
        h('div', { class: 'ed-name' }, (c.number ? c.number + '  ' : '') + c.name, c.origName ? h('span', { class: 'ed-orig' }, ' (' + c.origName + ')') : null),
        btn('↑', 'Move up', 'cu:' + c.key, () => edit('channel.move', { group: sel, key: c.key, dir: -1 }, null, 'chans')),
        btn('↓', 'Move down', 'cd:' + c.key, () => edit('channel.move', { group: sel, key: c.key, dir: 1 }, null, 'chans')),
        btn('✎', 'Rename', 'cr:' + c.key, () => { const n = window.prompt('Channel name (empty = the provider\'s name)', c.name); if (n !== null) edit('channel.rename', { key: c.key, name: n }, 'Renamed', 'chans'); }),
        btn('📺', 'Guide channel (match it to the guide by hand)', 'cg:' + c.key, () => guideMapDialog(c)),
        g.custom ? null : btn(c.hidden ? 'Show' : 'Hide', c.hidden ? 'Show this channel' : 'Hide this channel', 'ch:' + c.key, () => edit('channel.hide', { key: c.key, hidden: !c.hidden }, c.hidden ? 'Channel shown' : 'Channel hidden', 'chans')),
        g.custom ? btn('−', 'Remove from this group', 'cx:' + c.key, () => edit('channel.remove', { group: sel, key: c.key }, 'Removed')) : btn('+ Group', 'Add to one of your groups', 'ca:' + c.key, async () => { await addToGroupMenu(c); loadGroups(); }))));
  }
  function newGroup() {
    const n = window.prompt('Name of the new group'); if (!n || !n.trim()) return;
    layoutEdit('group.create', { name: n.trim() }).then((r) => { sel = r.id; toast('Group created'); loadGroups(); }).catch((e) => toast(e.message));
  }
  function resetAll() {
    if (confirm('Undo every change: your groups, names, hidden and moved groups and channels?')) edit('reset', {}, 'Layout reset');
  }
  return {
    onKey(e) {
      if (e.key.startsWith('Arrow')) { if (!spatialMove(scr, e.key) && e.key === 'ArrowLeft') nav.focusActive(); return true; }
      if (e.key === 'Enter' && document.activeElement && scr.contains(document.activeElement)) { document.activeElement.click(); return true; }
      if (e.key === 'Escape' || e.key === 'Backspace') { nav.focusActive(); return true; }
      return false;
    },
    focusIn() { const f = scr.querySelector('.ed-row.sel .ed-name') || scr.querySelector('.focusable'); if (f) f.focus(); },
    destroy() { alive = false; },
  };
}
