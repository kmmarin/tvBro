// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Browser tests of the Android-parity items: resume the last channel on open (server state), live
// reconnect + alternate-source fallback, parental PIN, backup / restore, manual guide mapping and the
// external-player link for live TV. Same mock stack as focus.e2e.mjs (no real provider).
//
//   node --test web/tests/e2e/parity.e2e.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { loadPlaywright, missing, startStack, sleep, getJson } from './stack.mjs';

const pw = loadPlaywright();
const skip = missing(pw);

test('parity items in a real browser', { skip, timeout: 300000 }, async (t) => {
  const stack = await startStack();
  const B = stack.base;
  let browser = null;
  t.after(async () => { try { if (browser) await browser.close(); } catch {} await stack.stop(); });
  const launch = async (channel) => pw.chromium.launch({ channel, headless: true, args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio'] });
  try { browser = await launch('chrome'); } catch { browser = await launch('msedge'); }

  const errors = [], logs = [];
  async function newPage() {
    const ctx = await browser.newContext({ viewport: { width: 1400, height: 860 }, acceptDownloads: true });
    const page = await stack.auth(await ctx.newPage());
    page.on('pageerror', (e) => errors.push(String(e)));
    page.on('console', (m) => { logs.push(m.type() + ': ' + m.text()); if (logs.length > 60) logs.shift(); });
    page.on('response', (r) => { if (r.status() >= 400) { logs.push('http ' + r.status() + ' ' + r.url().slice(0, 140)); if (logs.length > 60) logs.shift(); } });
    return page;
  }
  const tvOf = (page) => page.evaluate(() => { const r = document.querySelector('.tv2'); return r && r.tvState ? r.tvState() : null; });
  const keyOf = (page) => async (k, n = 1) => { for (let i = 0; i < n; i++) { await page.keyboard.press(k); await sleep(60); } await sleep(150); };
  const all = (await getJson(B + '/api/channels?group=__all&size=500')).items;
  const byName = (n) => all.find((c) => c.name === n);
  const waitGuide = (page) => page.waitForFunction(() => document.querySelectorAll('.g-blk[data-s]').length > 8, null, { timeout: 30000 });
  const dump = async (page, what) => what + '\n' + JSON.stringify(await page.evaluate(() => ({ hash: location.hash, notice: (document.querySelector('.pl-notice:not([hidden])') || {}).textContent || '', modal: (document.querySelector('.modal') || {}).innerText || '' }))) + '\n' + logs.join('\n');

  const page = await newPage();
  const key = keyOf(page), tv = () => tvOf(page);
  await page.goto(B + '/#live');
  await waitGuide(page);
  await page.waitForFunction(() => document.querySelector('.tv2').tvState().current, null, { timeout: 10000 });

  await t.test('1. resume the last channel on open: kept on the server, a new browser opens on it', async () => {
    await key('ArrowDown', 3);
    const s = await tv(); const target = s.rowKey;
    assert.notEqual(s.current, target);
    await key('Enter');
    assert.equal((await tv()).current, target, 'Enter made it the current channel');
    let last = null;
    for (let i = 0; i < 25 && !(last && last.key === target); i++) { last = (await getJson(B + '/api/state')).prefs.lastChannel; if (!last || last.key !== target) await sleep(200); }
    assert.equal(last && last.key, target, 'the server keeps the last channel');
    // a browser that has never been here (no localStorage): the same channel is current, plays and has the focus
    const p2 = await newPage();
    try {
      await p2.goto(B + '/#live');
      try { await p2.waitForFunction((k) => { const r = document.querySelector('.tv2'); const s = r && r.tvState && r.tvState(); return s && s.current === k && s.rowKey === k; }, target, { timeout: 15000 }); }
      catch (e) { throw new Error('resume: ' + target + ' ' + JSON.stringify(await tvOf(p2)) + '\nserver: ' + JSON.stringify((await getJson(B + '/api/state')).prefs.lastChannel) + '\nui: ' + await p2.evaluate(() => localStorage.getItem('tvbro.ui')) + '\n' + logs.join('\n')); }
      await p2.waitForFunction((k) => document.querySelector('.tv2').tvState().previewKey === k, target, { timeout: 15000 });
      await p2.waitForFunction(() => { const v = document.querySelector('.pv video'); return v && v.currentTime > 0.3; }, null, { timeout: 20000 });
    } finally { await p2.context().close(); }
  });

  await t.test('2. a live channel that is down: reconnects, then its alternate source plays (small notice, no dialog)', async () => {
    const dead = byName('Kilo Movies'), alt = byName('Kilo Movies HD');
    assert.ok(dead && alt, 'mock channels');
    const alts = await getJson(B + '/api/alternates?key=' + encodeURIComponent(dead.key));
    assert.deepEqual(alts.items.map((c) => c.key), [alt.key], 'the HD entry with the same tvg-id is the alternate');
    await page.goto(B + '/#play:' + encodeURIComponent(dead.key));
    try { await page.waitForFunction(() => /Reconnecting/.test((document.querySelector('.pl-notice:not([hidden])') || {}).textContent || ''), null, { timeout: 15000 }); }
    catch (e) { throw new Error(await dump(page, 'no reconnect notice')); }
    assert.equal(await page.$('.modal-back'), null, 'no dialog');
    try {
      await page.waitForFunction(() => { const v = document.querySelector('#player-layer video'); return v && v.currentTime > 1 && !v.paused; }, null, { timeout: 45000 });
    } catch (e) { throw new Error(await dump(page, 'alternate never played')); }
    const seen = await page.evaluate(() => ({ notice: (document.querySelector('.pl-notice') || {}).textContent, layer: !document.getElementById('player-layer').hidden }));
    assert.ok(seen.layer, 'still full screen');
    assert.match(seen.notice, /another source/i);
    // the channel shown stays the one asked for (guide, current channel), only the stream comes from the alternate
    await key('Enter');
    const info = await page.evaluate(() => document.querySelector('.pl-info') && document.querySelector('.pl-info').innerText);
    assert.match(info, /Kilo Movies/); assert.match(info, /Alternate source/);
    await key('Escape'); await key('Escape'); await sleep(800);
    const s = await tv(); assert.equal(s.current, dead.key);
  });

  const PIN = '2468';
  const typePin = async (p, pin) => { await p.waitForSelector('.pin-input', { timeout: 5000 }); await p.fill('.pin-input', pin); await p.keyboard.press('Enter'); await sleep(250); };
  await t.test('3. parental PIN: set in Settings, lock a channel from its menu; a new browser must give the PIN to play it', async () => {
    await page.goto(B + '/#settings');
    await page.waitForSelector('[data-k="ppin"]', { timeout: 15000 });
    await page.click('[data-k="ppin"]');
    await typePin(page, PIN); await typePin(page, PIN);
    let st = await getJson(B + '/api/parental');
    for (let i = 0; i < 25 && !st.pin; i++) { await sleep(200); st = await getJson(B + '/api/parental'); }   // a busy machine
    assert.equal(st.pin, true, 'PIN set on the server');
    const raw = fs.readFileSync(path.join(stack.dataDir, 'parental.json'), 'utf8');
    assert.ok(!raw.includes(PIN) && /pbkdf2_sha256\$/.test(raw), 'stored hashed only');
    // lock "Hotel Sport" from the TV channel menu
    const hotel = byName('Hotel Sport');
    await page.goto(B + '/#live'); await waitGuide(page);
    for (let i = 0; i < 15 && (await tv()).rowKey !== hotel.key; i++) await key((await tv()).row < all.indexOf(hotel) ? 'ArrowDown' : 'ArrowUp');
    assert.equal((await tv()).rowKey, hotel.key);
    await key('m');
    const labels = await page.$$eval('.modal .menu-item', (b) => b.map((x) => x.textContent));
    const li = labels.findIndex((l) => l.startsWith('🔒 Lock channel'));
    assert.ok(li >= 0, 'channel menu offers the lock: ' + labels.join(' | '));
    await (await page.$$('.modal .menu-item'))[li].click(); await sleep(500);
    st = await getJson(B + '/api/parental'); assert.deepEqual(st.channels, [hotel.key]);
    // the server refuses to sign its stream without the unlock
    const r = await fetch(B + '/api/play?kind=live&key=' + encodeURIComponent(hotel.key)); assert.equal(r.status, 423);
    // a new browser: Enter makes it current, the preview does not play it, Enter again asks for the PIN
    const p3 = await newPage();
    try {
      const k3 = keyOf(p3), tv3 = () => tvOf(p3);
      await p3.goto(B + '/#live'); await waitGuide(p3);
      await p3.waitForFunction(() => document.querySelector('.tv2').tvState().total > 8, null, { timeout: 10000 });
      for (let i = 0; i < 15 && (await tv3()).rowKey !== hotel.key; i++) await k3((await tv3()).row < all.indexOf(hotel) ? 'ArrowDown' : 'ArrowUp');
      assert.equal((await tv3()).rowKey, hotel.key);
      assert.ok(await p3.evaluate(() => !!document.querySelector('.g-row.rf .ic-lock')), 'lock mark on the row');
      await k3('Enter'); await sleep(800);
      let s = await tv3(); assert.equal(s.current, hotel.key); assert.equal(s.previewKey, null, 'a locked channel is not previewed');
      assert.match(await p3.evaluate(() => document.querySelector('.pv-note').textContent), /Locked/);
      await k3('Enter');
      await typePin(p3, '1111');
      // wrong PIN: the dialog comes back with the reason (after the server's answer)
      await p3.waitForFunction(() => /wrong/i.test((document.querySelector('.pin-modal .pin-msg') || {}).textContent || ''), null, { timeout: 8000 });
      await typePin(p3, PIN);
      await p3.waitForFunction((k) => document.querySelector('.tv2').tvState().previewKey === k, hotel.key, { timeout: 10000 });
      await k3('Enter');
      await p3.waitForFunction(() => { const v = document.querySelector('#player-layer video'); return v && v.currentTime > 0.5; }, null, { timeout: 20000 });
    } catch (e) { throw new Error(e.message + '\n' + await dump(p3, 'parental')); }
    finally { await p3.context().close(); }
    // a locked film category: no details without the PIN
    const cats = (await getJson(B + '/api/vod/categories?kind=movie')).categories;
    const docs = cats.find((c) => /Docs/.test(c.name));
    const tok = await page.evaluate(() => JSON.parse(sessionStorage.getItem('tvbro.parental')).tok);
    const lk = await fetch(B + '/api/parental/lock', { method: 'POST', headers: { 'content-type': 'application/json', 'X-Parental': tok }, body: JSON.stringify({ kind: 'vodcat', id: docs.id, locked: true }) });
    assert.equal(lk.status, 200);
    const film = (await getJson(B + '/api/vod/list?kind=movie&cat=' + encodeURIComponent(docs.id))).items[0];
    assert.equal(film.locked, true);
    assert.equal((await fetch(B + '/api/vod/info?kind=movie&id=' + encodeURIComponent(film.id))).status, 423);
    assert.equal((await fetch(B + '/api/vod/info?kind=movie&id=' + encodeURIComponent(film.id), { headers: { 'X-Parental': tok } })).status, 200);
    // a locked show category: its episodes are locked too (also when asked for directly, audit L8)
    const scats = (await getJson(B + '/api/vod/categories?kind=series')).categories;
    const drama = scats.find((c) => /Drama/.test(c.name));
    assert.equal((await fetch(B + '/api/parental/lock', { method: 'POST', headers: { 'X-Parental': tok }, body: JSON.stringify({ kind: 'vodcat', id: drama.id, locked: true }) })).status, 200);
    const show = (await getJson(B + '/api/vod/list?kind=series&cat=' + encodeURIComponent(drama.id))).items.find((s) => s.name === 'Mock Show');
    const info = await (await fetch(B + '/api/vod/info?kind=series&id=' + encodeURIComponent(show.id), { headers: { 'X-Parental': tok } })).json();
    const ep = info.episodes[0].id;
    const epUrl = `/api/play?kind=episode&id=${encodeURIComponent(ep)}&ext=mp4`;
    assert.equal((await fetch(B + epUrl)).status, 423, 'episode without its show');
    assert.equal((await fetch(B + epUrl + '&series=' + encodeURIComponent(show.id))).status, 423, 'episode of a locked show');
    assert.equal((await fetch(B + epUrl + '&series=' + encodeURIComponent(show.id), { headers: { 'X-Parental': tok } })).status, 200, 'unlocked');
    assert.equal((await fetch(B + '/api/parental/lock', { method: 'POST', headers: { 'X-Parental': tok }, body: JSON.stringify({ kind: 'vodcat', id: drama.id, locked: false }) })).status, 200);
    assert.equal((await fetch(B + epUrl + '&series=' + encodeURIComponent(show.id))).status, 200, 'show unlocked again');
    // five wrong PINs: the next try waits
    for (let i = 0; i < 5; i++) await fetch(B + '/api/parental/unlock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pin: '0000' }) });
    assert.equal((await fetch(B + '/api/parental/unlock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pin: PIN }) })).status, 429);
  });

  await t.test('4. backup / restore: download the file, change things, restore it from Settings (a bad file changes nothing)', async () => {
    // something to keep: a favourite and a preference
    const alpha = byName('Alpha News');
    await getJson(B + '/api/state/mylist', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ op: 'put', key: alpha.key, value: { key: alpha.key, kind: 'live', name: alpha.name } }) });
    await getJson(B + '/api/state/prefs', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ op: 'put', key: 'timeFormat', value: '24' }) });
    await page.goto(B + '/#settings');
    await page.waitForSelector('[data-k="backup"]', { timeout: 15000 });
    const [dl] = await Promise.all([page.waitForEvent('download'), page.click('[data-k="backup"]')]);
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tvbro-bk-')), 'backup.json');
    await dl.saveAs(file);
    assert.match(dl.suggestedFilename(), /^tvbro-backup-\d{4}-\d\d-\d\d\.json$/);
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.equal(doc.format, 'tvbro-web-backup');
    assert.ok(doc.state.mylist.some((x) => x.key === alpha.key), 'favourite in the backup');
    assert.ok(doc.parental.channels.length === 1, 'locks in the backup: ' + JSON.stringify(doc.parental));
    const raw = fs.readFileSync(file, 'utf8');
    for (const secret of [PIN, 'pbkdf2', '"pass"', stack.provider]) assert.ok(!raw.includes(secret), 'no secret in the backup: ' + secret);
    // change things after the backup
    await getJson(B + '/api/state/mylist', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ op: 'clear' }) });
    await getJson(B + '/api/state/prefs', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ op: 'put', key: 'timeFormat', value: '12' }) });
    // a bad file first: refused, nothing changes
    const bad = path.join(path.dirname(file), 'bad.json'); fs.writeFileSync(bad, JSON.stringify({ format: 'tvbro-web-backup', version: 1, state: { passwords: [] } }));
    let confirms = 0; const onDialog = (d) => { confirms++; d.accept(); }; page.on('dialog', onDialog);
    let [fc] = await Promise.all([page.waitForEvent('filechooser'), page.click('[data-k="restore"]')]);
    await fc.setFiles(bad); await page.waitForFunction(() => /Not restored/.test(document.getElementById('toast').textContent), null, { timeout: 5000 });
    assert.equal(confirms, 0, 'no confirmation for a bad file');
    // the real one: a confirmation, then everything is back
    [fc] = await Promise.all([page.waitForEvent('filechooser'), page.click('[data-k="restore"]')]);
    await fc.setFiles(file);
    await page.waitForFunction(() => /Backup restored/.test(document.getElementById('toast').textContent), null, { timeout: 8000 });
    page.off('dialog', onDialog);
    assert.equal(confirms, 1);
    const s = await getJson(B + '/api/state');
    assert.ok(s.mylist.some((x) => x.key === alpha.key), 'favourite back');
    assert.equal(s.prefs.timeFormat, '24', 'preference back');
    assert.equal((await getJson(B + '/api/parental')).pin, true, 'the PIN is untouched by a restore');
  });

  await t.test('5. manual guide mapping: pick the guide channel for a channel the guide does not match; guide and search use it', async () => {
    const lima = byName('Lima TV');
    await page.goto(B + '/#live'); await waitGuide(page);
    await page.waitForFunction(() => document.querySelector('.tv2').tvState().total > 10, null, { timeout: 10000 });
    for (let i = 0; i < 20 && (await tv()).rowKey !== lima.key; i++) await key((await tv()).row < all.indexOf(lima) ? 'ArrowDown' : 'ArrowUp');
    assert.equal((await tv()).rowKey, lima.key);
    const blocks = () => page.evaluate(() => document.querySelectorAll('.g-row.rf .g-blk[data-s]').length);
    await sleep(800);
    assert.equal(await blocks(), 0, 'no guide before the mapping');
    await key('m');
    const labels = await page.$$eval('.modal .menu-item', (b) => b.map((x) => x.textContent));
    const gi = labels.findIndex((l) => l.startsWith('📺 Guide channel'));
    assert.ok(gi >= 0, 'channel menu offers the guide mapping');
    await (await page.$$('.modal .menu-item'))[gi].click();
    await page.waitForSelector('.gm-modal .gm-q', { timeout: 5000 });
    assert.match(await page.evaluate(() => document.querySelector('.gm-cur').textContent), /automatic · nothing/);
    const n0 = await page.$$eval('.gm-list .btn[data-gid]', (b) => b.length);
    assert.ok(n0 > 5, 'every guide channel is listed');
    await page.keyboard.type('televis');
    await page.waitForFunction(() => { const b = [...document.querySelectorAll('.gm-list .btn[data-gid]')]; return b.length === 1 && b[0].dataset.gid === 'lima.guide'; }, null, { timeout: 5000 });
    // remote keys: Enter from the field goes to the first guide channel, Enter picks it
    await key('Enter');
    assert.equal(await page.evaluate(() => document.activeElement.dataset.gid), 'lima.guide', await page.evaluate(() => document.activeElement.outerHTML.slice(0, 200) + ' | ' + document.querySelector('.gm-list').innerHTML.slice(0, 400)));
    await key('Enter');
    assert.equal(await page.$('.gm-modal'), null, 'dialog closed');
    // the server fetched the guide for it: the row fills in
    try { await page.waitForFunction(() => document.querySelectorAll('.g-row.rf .g-blk[data-s]').length > 0, null, { timeout: 30000 }); }
    catch (e) { throw new Error(await dump(page, 'mapped channel has no guide')); }
    assert.match(await page.evaluate(() => document.querySelector('.hd-title').textContent), /Lima Television show/);
    const st = await getJson(B + '/api/epg/channels?key=' + encodeURIComponent(lima.key) + '&q=lima');
    assert.equal(st.current.mode, 'manual'); assert.equal(st.current.hasGuide, true);
    // search finds its programmes under this channel (the index is rebuilt after the guide sync)
    let found = false;
    for (let i = 0; i < 40 && !found; i++) { const s = await getJson(B + '/api/search?q=' + encodeURIComponent('Lima Television show')); found = (s.programs || []).some((p) => p.ch.key === lima.key); if (!found) await sleep(500); }
    assert.ok(found, 'search finds the mapped programmes');
    // the mapping is part of a backup
    const doc = await getJson(B + '/api/backup');
    assert.equal(doc.epgMap[lima.key], 'lima.guide');
  });

  await t.test('6. live TV in a player app: the signed link (and an .m3u) plays outside the browser, through the server', async () => {
    const bravo = byName('Bravo News');
    await page.goto(B + '/#play:' + encodeURIComponent(bravo.key));
    await page.waitForFunction(() => { const v = document.querySelector('#player-layer video'); return v && v.currentTime > 0.3; }, null, { timeout: 20000 });
    await key('ArrowDown');                                 // quick settings
    const items = await page.$$eval('.qs-btn', (b) => b.map((x) => x.textContent));
    const oi = items.findIndex((l) => l.startsWith('Open in app'));
    assert.ok(oi >= 0, 'quick settings offer Open in app for live: ' + items.join(' | '));
    await page.click('.qs-btn:has-text("Open in app")');               // (the bar may redraw meanwhile)
    await page.waitForSelector('.ext-modal .ext-url', { timeout: 5000 });
    const url = await page.$eval('.ext-url', (i) => i.value);
    assert.ok(url.startsWith(B + '/stream?'), 'absolute link to this server: ' + url);
    assert.ok(/[?&]sig=/.test(url) && /[?&]exp=/.test(url) && /[?&]sid=x/.test(url), 'signed, with its own stream session');
    assert.ok(!url.includes(stack.provider.replace('http://', '')), 'the provider is not in the link');
    // what an external player does: the playlist, then a segment, both through the server
    const pl = await fetch(url); assert.equal(pl.status, 200);
    const text = await pl.text(); assert.match(text, /^#EXTM3U/);
    const seg = text.split('\n').find((l) => l && !l.startsWith('#'));
    assert.ok(seg && seg.startsWith('/seg?'), 'segments come back through the server');
    const sr = await fetch(new URL(seg, B).href); assert.equal(sr.status, 200);
    assert.ok((await sr.arrayBuffer()).byteLength > 1000, 'segment bytes');
    // and the .m3u file holds the same link
    const [dl] = await Promise.all([page.waitForEvent('download'), page.click('.ext-modal [data-act="m3u"]')]);
    const f = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tvbro-m3u-')), 'x.m3u'); await dl.saveAs(f);
    const m3u = fs.readFileSync(f, 'utf8');
    assert.match(m3u, /^#EXTM3U\n#EXTINF:-1 [^\n]*,Bravo News\n/); assert.ok(m3u.includes(url));
    await key('Escape'); assert.equal(await page.$('.ext-modal'), null, 'Escape closes the dialog only');
    assert.equal(await page.evaluate(() => document.getElementById('player-layer').hidden), false);
    await key('Escape'); await key('Escape'); await key('Escape');
  });

  await t.test('no page errors', async () => { assert.deepEqual(errors, [], errors.join('\n')); });
});
