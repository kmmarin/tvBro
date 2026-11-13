// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Test stack for the browser tests: a mock provider (M3U + XMLTV now-12h .. now+24h + an ffmpeg-made
// HLS clip) and the real backend (web/server) on free ports with a temp data dir. No real provider.
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const WEB = path.resolve(HERE, '..', '..');
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function loadPlaywright() {
  const dirs = [process.env.PLAYWRIGHT_DIR, process.cwd()].filter(Boolean);
  for (const d of dirs) {
    try { return createRequire(path.join(d, 'package.json'))('playwright'); } catch {}
  }
  return null;
}
/** Why the browser tests cannot run here, or false. */
export function missing(pw) {
  if (!pw) return 'Playwright not found (set PLAYWRIGHT_DIR)';
  if (spawnSync('ffmpeg', ['-version']).status !== 0) return 'ffmpeg not found';
  if (!fs.existsSync(path.join(WEB, 'frontend', 'vendor', 'hls.min.js'))) return 'web/frontend/vendor/hls.min.js missing (copy it from the CT)';
  return false;
}

export const NOW = Math.floor(Date.now() / 1000);
const H30 = Math.floor(NOW / 1800) * 1800;
export const CHANNELS = [
  { n: 1, name: 'Alpha News', group: 'News', catchup: true },
  { n: 2, name: 'Bravo News', group: 'News', catchup: true },
  { n: 3, name: 'Charlie News', group: 'News', catchup: false },
  { n: 4, name: 'Delta News', group: 'News', catchup: true },
  { n: 5, name: 'Echo Sport', group: 'Sport', catchup: false },
  { n: 6, name: 'Foxtrot Sport', group: 'Sport', catchup: true },
  { n: 7, name: 'Golf Sport', group: 'Sport', catchup: false },
  { n: 8, name: 'Hotel Sport', group: 'Sport', catchup: false },
  // the parity tests (parity.e2e.mjs): a channel whose stream is down, listed again as "HD" with the same tvg-id
  // (its alternate source), and a channel the guide does not match by itself (manual guide mapping)
  { n: 9, name: 'Kilo Movies', group: 'Extra', catchup: false, tvg: 'kilo', dead: true },
  { n: 10, name: 'Kilo Movies HD', group: 'Extra', catchup: false, tvg: 'kilo' },
  { n: 11, name: 'Lima TV', group: 'Extra', catchup: false, tvg: '', noGuide: true },
];
/** In the guide only (no playlist entry matches it): what "Lima TV" gets mapped to. */
export const GUIDE_ONLY = { id: 'lima.guide', name: 'Lima Television', n: 12 };
/** Programmes now-12h .. now+24h; lengths differ per channel (30 / 45 / 60 / 75 min) so some start before the half hour. */
export function schedule(c) {
  const len = [30, 45, 60, 75][c.n % 4] * 60, out = [];
  for (let t = H30 - 12 * 3600 - (c.n * 600); t < NOW + 24 * 3600; t += len) out.push({ start: t, stop: t + len, title: `${c.name} show ${(t / 60) | 0}` });
  return out;
}
const MOVIES = Array.from({ length: 14 }, (_, i) => ({ id: 700 + i, name: 'Test Film ' + (i + 1), cat: i % 3 ? '10' : '11' }));
const xt = (t) => new Date(t * 1000).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');

function makeMedia(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
    '-t', '240', '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '50', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '64k',
    '-f', 'hls', '-hls_time', '2', '-hls_playlist_type', 'vod', '-hls_segment_filename', path.join(dir, 's%03d.ts'), path.join(dir, 'index.m3u8')]);
  if (r.status !== 0) throw new Error('ffmpeg failed: ' + r.stderr);
}

function mockProvider(mediaDir) {
  const srv = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x'), base = `http://127.0.0.1:${srv.address().port}`;
    if (u.pathname === '/playlist.m3u') {
      let s = '#EXTM3U\n';
      for (const c of CHANNELS) {
        s += `#EXTINF:-1 tvg-id="${c.tvg != null ? c.tvg : 'ch' + c.n}" tvg-chno="${c.n}" group-title="${c.group}"` +
          (c.catchup ? ` catchup="default" catchup-days="1" catchup-source="${base}/hls/${c.n}/index.m3u8?utc={utc}"` : '') + `,${c.name}\n`;
        s += `${base}/hls/${c.dead ? 'dead' : c.n}/index.m3u8\n`;
      }
      res.writeHead(200, { 'content-type': 'audio/x-mpegurl' }); return res.end(s);
    }
    if (u.pathname === '/epg.xml') {
      let s = '<?xml version="1.0" encoding="UTF-8"?>\n<tv>\n';
      const inGuide = CHANNELS.filter((c) => !c.noGuide && !c.tvg).map((c) => ({ id: 'ch' + c.n, c }));
      inGuide.push({ id: 'kilo', c: CHANNELS.find((c) => c.tvg === 'kilo') }, { id: GUIDE_ONLY.id, c: GUIDE_ONLY });
      for (const g of inGuide) s += `<channel id="${g.id}"><display-name>${esc(g.c.name)}</display-name></channel>\n`;
      for (const g of inGuide) for (const p of schedule(g.c)) s += `<programme start="${xt(p.start)}" stop="${xt(p.stop)}" channel="${g.id}"><title>${esc(p.title)}</title><desc>About ${esc(p.title)}</desc></programme>\n`;
      res.writeHead(200, { 'content-type': 'application/xml' }); return res.end(s + '</tv>\n');
    }
    if (u.pathname.startsWith('/hls/dead/')) { res.writeHead(404); return res.end(); }   // a channel that is down
    // a second playlist: a mock Xtream server with films and a show (no live channels)
    if (u.pathname === '/player_api.php') {
      const a = u.searchParams.get('action'), j = (o) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(o)); };
      if (!a) return j({ user_info: { auth: 1, status: 'Active', max_connections: '2', active_cons: '0', exp_date: null }, server_info: {} });
      if (a === 'get_live_categories' || a === 'get_live_streams') return j([]);
      if (a === 'get_vod_categories') return j([{ category_id: '10', category_name: 'Films' }, { category_id: '11', category_name: 'Docs' }]);
      if (a === 'get_series_categories') return j([{ category_id: '20', category_name: 'Drama' }]);
      if (a === 'get_vod_streams') return j(MOVIES.map((m) => ({ stream_id: m.id, name: m.name, category_id: m.cat, container_extension: 'mp4', stream_icon: '' })));
      if (a === 'get_series') return j([{ series_id: 501, name: 'Mock Show', category_id: '20', cover: '' }, { series_id: 502, name: 'Other Show', category_id: '20', cover: '' }]);
      if (a === 'get_vod_info') return j({ info: { plot: 'A test film.', duration_secs: 240 }, movie_data: { stream_id: u.searchParams.get('vod_id'), container_extension: 'mp4' } });
      if (a === 'get_series_info') return j({ info: { name: 'Mock Show', plot: 'A test show.' }, episodes: {
        1: [1, 2, 3, 4].map((n) => ({ id: String(9000 + n), episode_num: n, season: 1, title: 'Episode ' + n, container_extension: 'mp4', info: { duration_secs: 240 } })),
        2: [1, 2].map((n) => ({ id: String(9100 + n), episode_num: n, season: 2, title: 'Second ' + n, container_extension: 'mp4', info: { duration_secs: 240 } })) } });
      return j([]);
    }
    if (u.pathname === '/xmltv.php') { res.writeHead(200, { 'content-type': 'application/xml' }); return res.end('<?xml version="1.0"?><tv></tv>'); }
    const m = u.pathname.match(/^\/hls\/\d+\/([\w.]+)$/);
    if (m && fs.existsSync(path.join(mediaDir, m[1]))) {
      const file = path.join(mediaDir, m[1]);
      res.writeHead(200, { 'content-type': m[1].endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t', 'content-length': fs.statSync(file).size });
      return fs.createReadStream(file).pipe(res);
    }
    res.writeHead(404); res.end();
  });
  return new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv)));
}

async function freePort() {
  const s = http.createServer(); await new Promise((r) => s.listen(0, '127.0.0.1', r));
  const p = s.address().port; await new Promise((r) => s.close(r)); return p;
}
export async function getJson(url, opts) {
  const r = await fetch(url, opts); if (!r.ok) throw new Error(url + ' -> ' + r.status); return r.json();
}

/** Starts everything and waits for the channel list and the guide. Returns { base, stop(), stderr() }. */
export async function startStack() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'tvbro-e2e-'));
  makeMedia(path.join(tmp, 'media'));
  const prov = await mockProvider(path.join(tmp, 'media'));
  const PB = `http://127.0.0.1:${prov.address().port}`;
  const port = await freePort();
  const server = spawn('python', ['run.py'], { cwd: path.join(WEB, 'server'), stdio: ['ignore', 'ignore', 'pipe'],
    // the mock provider is on loopback, which the server refuses unless told (tests only)
    env: { ...process.env, TVBRO_PORT: String(port), TVBRO_DATA: path.join(tmp, 'data'), TVBRO_STATIC: path.join(WEB, 'frontend'), PYTHONDONTWRITEBYTECODE: '1', TVBRO_ALLOW_LOOPBACK_SOURCES: '1' } });
  let serr = '';
  server.stderr.on('data', (d) => { serr += String(d).split('\n').filter((l) => !/ INFO "GET \//.test(l)).join('\n'); if (serr.length > 20000) serr = serr.slice(-10000); });
  const base = `http://127.0.0.1:${port}`;
  const stop = async () => { server.kill(); prov.close(); await sleep(300); try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {} };
  let cookie = null;
  try {
    for (let i = 0; i < 100; i++) { try { await getJson(base + '/api/session'); break; } catch { await sleep(150); } }
    // no password by default: /api/session gives this client a session; the test's own requests then
    // carry it (cookie + CSRF token), and pages get the cookie through auth(page)
    const r = await realFetch(base + '/api/session');
    if (!r.ok) throw new Error('session -> ' + r.status);
    cookie = r.headers.get('set-cookie').split(';')[0];
    const csrf = (await r.json()).csrf;
    authedFetch(base, cookie, csrf);
    await getJson(base + '/api/signin', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ kind: 'm3u', name: 'Mock', url: PB + '/playlist.m3u', epgUrl: PB + '/epg.xml' }) });
    await getJson(base + '/api/signin', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ kind: 'xtream', name: 'Mock VOD', server: PB, user: 'u', pass: 'p' }) });
    await getJson(base + '/api/sync/epg', { method: 'POST' });
    await getJson(base + '/api/sync/vod', { method: 'POST' });
    for (let i = 0; ; i++) {
      const s = await getJson(base + '/api/sync/status'); const p = s.playlists && s.playlists[0];
      const v = s.playlists && s.playlists[1];
      if (p && p.channels.channels >= 8 && p.epg.programmes > 100 && !p.epg.running && v && v.vod.movies > 0 && v.vod.series > 0) break;
      if (i === 200) throw new Error('sync did not finish: ' + JSON.stringify(s).slice(0, 400) + serr.slice(-800));
      await sleep(200);
    }
  } catch (e) { await stop(); throw e; }
  const [cname, cvalue] = cookie.split('=');
  /** Signs a Playwright page's browser context in (the session cookie of the admin). */
  const auth = async (page) => { await page.context().addCookies([{ name: cname, value: cvalue, url: base, httpOnly: true, sameSite: 'Strict' }]); return page; };
  return { base, provider: PB, dataDir: path.join(tmp, 'data'), stop, stderr: () => serr, auth };
}

const realFetch = globalThis.fetch;
/** fetch() of the tests, to the backend: with the session cookie, and for writes JSON + the CSRF token. */
function authedFetch(base, cookie, csrf) {
  globalThis.fetch = (url, opts = {}) => {
    const u = String(url);
    if (!u.startsWith(base)) return realFetch(url, opts);
    const headers = Object.assign({}, opts.headers || {});
    const has = (k) => Object.keys(headers).some((x) => x.toLowerCase() === k);
    if (!has('cookie')) headers.cookie = cookie;
    const method = (opts.method || 'GET').toUpperCase();
    const o = { ...opts, headers };
    if (method === 'POST') {
      if (!has('content-type')) headers['content-type'] = 'application/json';
      if (!has('x-csrf-token')) headers['X-CSRF-Token'] = csrf;
      if (o.body == null) o.body = '{}';
    }
    return realFetch(url, o);
  };
}
