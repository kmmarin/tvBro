// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Kotlin bindings (UniFFI) of tvbro-core: what the Android app's switched call sites use (phase 2: M3U and Xtream
//! live parsing with the strict download check, the URL guard, PIN hashing, and the guide: XMLTV + gzip into
//! `epg.db` through `CoreEpgDb`, plus row digests for shadow mode), and the phase-1 smoke API. The core crate itself
//! forbids unsafe code; this crate only hosts the generated FFI glue.
//!
//! Inputs that can be large cross as byte arrays (playlists, Xtream lists) or are streamed in chunks through the
//! `GuideFeed` callback (guides, up to 2 GiB inflated): the app's cache files are sealed (AES-GCM, Keystore key), so
//! Kotlin decrypts and hands over the plaintext; nothing is written unsealed.

use std::net::{IpAddr, SocketAddr};
use tvbro_core::security::{password, urlguard::UrlGuard};

uniffi::setup_scaffolding!();

#[derive(uniffi::Record)]
pub struct FfiChannel {
    pub key: String,
    pub source_id: String,
    pub name: String,
    pub logo: Option<String>,
    pub group: String,
    pub url: String,
    pub tvg_id: Option<String>,
    pub number: i32,
    pub catchup_kind: Option<String>,
    pub catchup_template: Option<String>,
    pub catchup_days: i32,
}

impl From<tvbro_core::model::Channel> for FfiChannel {
    fn from(c: tvbro_core::model::Channel) -> Self {
        let (catchup_kind, catchup_template) = match c.catchup {
            Some(k) => (Some(k.kind), k.template),
            None => (None, None),
        };
        FfiChannel {
            key: c.key,
            source_id: c.source_id,
            name: c.name,
            logo: c.logo,
            group: c.group,
            url: c.url,
            tvg_id: c.tvg_id,
            number: c.number,
            catchup_kind,
            catchup_template,
            catchup_days: c.catchup_days,
        }
    }
}

#[derive(uniffi::Record)]
pub struct FfiM3u {
    pub channels: Vec<FfiChannel>,
    pub epg_urls: Vec<String>,
}

#[uniffi::export]
pub fn core_version() -> String {
    tvbro_core::version().to_string()
}

/// Parses an M3U playlist with the apps' channel keys (JVM `String.hashCode`).
#[uniffi::export]
pub fn parse_m3u(text: String, source_id: String) -> FfiM3u {
    let r = tvbro_core::m3u::parse(&text, &source_id);
    FfiM3u { channels: r.channels.into_iter().map(FfiChannel::from).collect(), epg_urls: r.epg_urls }
}

/// The same from the playlist's bytes (streamed line by line; bytes that are not UTF-8 become U+FFFD, like the
/// JVM's reader).
#[uniffi::export]
pub fn parse_m3u_bytes(data: Vec<u8>, source_id: String) -> FfiM3u {
    let mut channels = Vec::new();
    let epg_urls = tvbro_core::m3u::parse_reader(&data[..], &source_id, tvbro_core::m3u::M3uOptions::default(), |c| channels.push(FfiChannel::from(c))).unwrap_or_default();
    FfiM3u { channels, epg_urls }
}

/// Xtream live channels from the `get_live_categories` (may be empty) and `get_live_streams` responses.
#[uniffi::export]
pub fn parse_xtream_live(categories: Vec<u8>, streams: Vec<u8>, source_id: String, server: String, user: String, pass: String) -> Vec<FfiChannel> {
    use tvbro_core::xtream;
    let cats = xtream::parse_live_categories(&categories);
    let list = xtream::parse_live_streams(&streams);
    xtream::to_channels(&source_id, &server, &user, &pass, &cats, &list).into_iter().map(FfiChannel::from).collect()
}

#[uniffi::export]
pub fn is_newer(candidate: String, current: String) -> bool {
    tvbro_core::update::is_newer(&candidate, &current)
}

/// Errors surfaced to Kotlin as `CoreException` subclasses. Messages never contain URLs or credentials.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("blocked: {reason}")]
    Blocked { reason: String },
    #[error("{reason}")]
    Other { reason: String },
}

impl From<tvbro_core::Error> for CoreError {
    fn from(e: tvbro_core::Error) -> Self {
        match e {
            tvbro_core::Error::Blocked(reason) => CoreError::Blocked { reason },
            other => CoreError::Other { reason: other.to_string() },
        }
    }
}

/// URL guard: scheme / port rules plus system DNS; returns the `ip:port` addresses the caller must connect to.
#[uniffi::export]
pub fn check_url(url: String, allow_lan: bool) -> Result<Vec<String>, CoreError> {
    let c = UrlGuard::new(allow_lan).check(&url)?;
    Ok(c.addrs.iter().map(|a| a.to_string()).collect())
}

/// URL guard without DNS: scheme / port rules, and the address rule when the host is an IP literal. For clients
/// that resolve names themselves and check the answer with [check_addrs] (OkHttp's `Dns`).
#[uniffi::export]
pub fn check_url_static(url: String, allow_lan: bool) -> Result<(), CoreError> {
    UrlGuard::new(allow_lan).check_static(&url)?;
    Ok(())
}

/// Address rule for a resolved host: refused when ANY address is not allowed (or there is none). `addrs` are IP
/// addresses as text (`InetAddress.getHostAddress()`; an IPv6 scope suffix is ignored).
#[uniffi::export]
pub fn check_addrs(addrs: Vec<String>, allow_lan: bool) -> Result<(), CoreError> {
    let mut parsed = Vec::with_capacity(addrs.len());
    for a in &addrs {
        let bare = a.split('%').next().unwrap_or("");
        let ip: IpAddr = bare.parse().map_err(|_| CoreError::Blocked { reason: "bad address".into() })?;
        parsed.push(SocketAddr::new(ip, 0));
    }
    UrlGuard::new(allow_lan).check_addrs(&parsed)?;
    Ok(())
}

#[derive(uniffi::Record)]
pub struct FfiPinCheck {
    pub ok: bool,
    /// The stored hash is the legacy unsalted form or weaker than today's: store `pin_hash` after this right PIN.
    pub needs_rehash: bool,
}

/// New parental PIN hash: `pbkdf2_sha256$120000$<salt>$<hash>` (random 16-byte salt).
#[uniffi::export]
pub fn pin_hash(pin: String) -> Result<String, CoreError> {
    Ok(password::hash_pin(&pin)?)
}

/// PIN hash with a given salt and iteration count (comparison tests against the Kotlin implementation).
#[uniffi::export]
pub fn pin_hash_with(pin: String, salt: Vec<u8>, iterations: u32) -> String {
    password::hash_with(&pin, &salt, iterations)
}

/// Checks a PIN against a stored hash (PBKDF2 of any iteration count up to 10M, or the legacy unsalted SHA-256).
#[uniffi::export]
pub fn pin_verify(pin: String, stored: String) -> FfiPinCheck {
    let ok = !pin.is_empty() && !stored.is_empty() && password::verify(&pin, &stored);
    FfiPinCheck { ok, needs_rehash: ok && password::pin_needs_rehash(&stored) }
}

/// Format check only (no hashing): see [FfiPinCheck::needs_rehash].
#[uniffi::export]
pub fn pin_needs_rehash(stored: String) -> bool {
    password::pin_needs_rehash(&stored)
}

#[uniffi::export]
pub fn fold_text(text: String) -> String {
    tvbro_core::search::fold(&text)
}

// ------------------------------------------------------------------ Xtream download check

/// Download check (keep-old-data rule): the number of streams of a complete `get_live_streams` list, 0 when it is
/// damaged (cut off mid-way, not JSON). Like Kotlin's strict decoder, so a truncated download keeps the old list.
#[uniffi::export]
pub fn xtream_live_count_strict(streams: Vec<u8>) -> u64 {
    tvbro_core::xtream::count_live_streams_strict(&streams) as u64
}

// ------------------------------------------------------------------ guide (row EPG)

impl From<uniffi::UnexpectedUniFFICallbackError> for CoreError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        CoreError::Other { reason: format!("callback failed: {}", e.reason) }
    }
}

/// A guide file streamed from Kotlin (the cache is sealed: Kotlin decrypts, the core never sees an unsealed file).
#[uniffi::export(callback_interface)]
pub trait GuideFeed: Send + Sync {
    /// Up to `max` bytes; empty at the end.
    fn read(&self, max: u32) -> Result<Vec<u8>, CoreError>;
    /// After every stored batch: the programmes so far.
    fn progress(&self, programmes: u64);
}

struct FeedReader<'a> {
    feed: &'a dyn GuideFeed,
    buf: Vec<u8>,
    pos: usize,
}

impl std::io::Read for FeedReader<'_> {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if self.pos >= self.buf.len() {
            self.buf = self.feed.read(256 * 1024).map_err(|e| std::io::Error::other(e.to_string()))?;
            self.pos = 0;
            if self.buf.is_empty() {
                return Ok(0);
            }
        }
        let n = out.len().min(self.buf.len() - self.pos);
        out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

fn feed_input(feed: &dyn GuideFeed) -> std::io::BufReader<FeedReader<'_>> {
    std::io::BufReader::with_capacity(64 * 1024, FeedReader { feed, buf: Vec::new(), pos: 0 })
}

fn load_options(source_id: String, wanted: Vec<String>, min_stop: i64, max_start: i64) -> tvbro_core::epgload::LoadOptions {
    tvbro_core::epgload::LoadOptions { source_id, wanted: wanted.into_iter().collect(), min_stop, max_start, limits: tvbro_core::gz::Limits::default() }
}

#[derive(uniffi::Record)]
pub struct FfiProgramme {
    pub key: String,
    pub start: i64,
    pub stop: i64,
    pub title: String,
    pub desc: Option<String>,
    pub sub: Option<String>,
    pub ep: Option<String>,
}

impl From<tvbro_core::Programme> for FfiProgramme {
    fn from(p: tvbro_core::Programme) -> Self {
        FfiProgramme { key: p.key, start: p.start, stop: p.stop, title: p.title, desc: p.desc, sub: p.sub, ep: p.ep }
    }
}

#[derive(uniffi::Record)]
pub struct FfiEpgMeta {
    pub generation: i64,
    pub min_start: i64,
    pub max_stop: i64,
    pub updated: i64,
}

#[derive(uniffi::Record)]
pub struct FfiCurrentRow {
    pub rowid: i64,
    pub ch: String,
    pub start: i64,
    pub stop: i64,
    pub title: String,
    pub sub: Option<String>,
    pub ep: Option<String>,
}

#[derive(uniffi::Record)]
pub struct FfiGuideName {
    pub id: String,
    pub name: String,
}

/// Result of loading one guide file. `error` set: the input was damaged / too large / unreadable (the programmes
/// before the damage are stored, like Kotlin's), and `names` is empty.
#[derive(uniffi::Record)]
pub struct FfiGuideLoad {
    pub programmes: u64,
    pub min_start: i64,
    pub max_stop: i64,
    pub names: Vec<FfiGuideName>,
    pub error: Option<String>,
    /// Largest write-ahead log size seen during the load (bytes) and the checkpoints a reader kept busy.
    pub wal_peak: u64,
    pub checkpoint_busy: u64,
}

/// Order-independent digest of programme rows (see `tvbro_core::epgload::Digest`).
#[derive(uniffi::Record)]
pub struct FfiDigest {
    pub rows: u64,
    pub fields: Vec<u64>,
    pub row: u64,
    pub min_start: i64,
    pub max_stop: i64,
    pub error: Option<String>,
}

fn digest(d: tvbro_core::epgload::Digest, min_start: i64, max_stop: i64, error: Option<String>) -> FfiDigest {
    FfiDigest { rows: d.rows, fields: d.fields.to_vec(), row: d.row, min_start, max_stop, error }
}

/// Parses a guide like `CoreEpgDb::load_guide` without storing anything (shadow mode: compared with Kotlin's rows).
#[uniffi::export]
pub fn guide_digest(feed: Box<dyn GuideFeed>, source_id: String, wanted: Vec<String>, min_stop: i64, max_start: i64) -> FfiDigest {
    let o = load_options(source_id, wanted, min_stop, max_start);
    let (d, st) = tvbro_core::epgload::digest_guide(feed_input(feed.as_ref()), &o);
    digest(d, st.min_start, st.max_stop, st.error.map(|e| e.to_string()))
}

fn lock<T>(m: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

/// The guide store `epg.db` (same schema as Android's EpgDb). One writer connection (refreshes, deletes) and one
/// reader connection (the UI's queries run while a refresh writes); both in this library's SQLite, which is why,
/// with row EPG on, every access to `epg.db` goes through here and Android's SQLite never opens the file.
#[derive(uniffi::Object)]
pub struct CoreEpgDb {
    w: std::sync::Mutex<tvbro_core::epgstore::EpgStore>,
    r: std::sync::Mutex<tvbro_core::epgstore::EpgStore>,
}

#[uniffi::export]
impl CoreEpgDb {
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<std::sync::Arc<Self>, CoreError> {
        let p = std::path::Path::new(&path);
        let w = tvbro_core::epgstore::EpgStore::open(p)?;
        let r = tvbro_core::epgstore::EpgStore::open_reader(p)?;
        Ok(std::sync::Arc::new(CoreEpgDb { w: std::sync::Mutex::new(w), r: std::sync::Mutex::new(r) }))
    }

    pub fn meta(&self, src: String) -> Result<Option<FfiEpgMeta>, CoreError> {
        Ok(lock(&self.r).meta(&src)?.map(|m| FfiEpgMeta { generation: m.generation, min_start: m.min_start, max_stop: m.max_stop, updated: m.updated }))
    }

    pub fn begin_refresh(&self, src: String) -> Result<i64, CoreError> {
        let mut w = lock(&self.w);
        w.reset_wal_peak();
        Ok(w.begin_refresh(&src)?)
    }

    pub fn insert_batch(&self, src: String, generation: i64, rows: Vec<FfiProgramme>) -> Result<(), CoreError> {
        let rows: Vec<tvbro_core::Programme> = rows.into_iter().map(|p| tvbro_core::Programme { key: p.key, start: p.start, stop: p.stop, title: p.title, desc: p.desc, sub: p.sub, ep: p.ep }).collect();
        Ok(lock(&self.w).insert_batch(&src, generation, &rows)?)
    }

    /// Parses one guide file (plain or gzip, within the gzip limits) into `generation` of `src`.
    pub fn load_guide(&self, src: String, generation: i64, feed: Box<dyn GuideFeed>, wanted: Vec<String>, min_stop: i64, max_start: i64) -> FfiGuideLoad {
        let o = load_options(src, wanted, min_stop, max_start);
        let mut w = lock(&self.w);
        let f = feed.as_ref();
        let st = tvbro_core::epgload::load_into(&mut w, generation, feed_input(f), &o, |n| f.progress(n));
        let names = if st.error.is_none() { st.names.into_iter().map(|(id, n)| FfiGuideName { name: n.into_iter().next().unwrap_or_else(|| id.clone()), id }).collect() } else { Vec::new() };
        FfiGuideLoad { programmes: st.programmes, min_start: st.min_start, max_stop: st.max_stop, names, error: st.error.map(|e| e.to_string()), wal_peak: w.wal_peak, checkpoint_busy: w.checkpoint_busy }
    }

    pub fn commit(&self, src: String, generation: i64, min_start: i64, max_stop: i64, updated: i64) -> Result<(), CoreError> {
        Ok(lock(&self.w).commit(&src, generation, min_start, max_stop, updated)?)
    }

    pub fn discard(&self, src: String, generation: i64) -> Result<(), CoreError> {
        Ok(lock(&self.w).discard(&src, generation)?)
    }

    pub fn delete_source(&self, src: String) -> Result<(), CoreError> {
        Ok(lock(&self.w).delete_source(&src)?)
    }

    /// Folds the log back and truncates it (unless `force`, only past 32 MB). False when a reader kept it busy.
    pub fn checkpoint(&self, force: bool) -> Result<bool, CoreError> {
        Ok(lock(&self.w).checkpoint(force)?)
    }

    /// Largest log size seen since the last `begin_refresh` (bytes).
    pub fn wal_peak(&self) -> u64 {
        lock(&self.w).wal_peak
    }

    pub fn load(&self, src: String, key: String, from: i64, to: i64) -> Result<Vec<FfiProgramme>, CoreError> {
        Ok(lock(&self.r).load(&src, &key, from, to)?.into_iter().map(FfiProgramme::from).collect())
    }

    /// Programmes of several guide keys of `src` overlapping [from, to) in one query (see `EpgStore::load_many`).
    pub fn load_many(&self, src: String, keys: Vec<String>, from: i64, to: i64) -> Result<Vec<FfiProgramme>, CoreError> {
        Ok(lock(&self.r).load_many(&src, &keys, from, to)?.into_iter().map(FfiProgramme::from).collect())
    }

    pub fn search(&self, q: String, now: i64, limit: i64) -> Result<Vec<FfiProgramme>, CoreError> {
        Ok(lock(&self.r).search(&q, now, limit)?.into_iter().map(FfiProgramme::from).collect())
    }

    /// Current programmes without descriptions, `limit` rows after row id `after_rowid` (0 = from the start).
    pub fn current_page(&self, after_rowid: i64, limit: i64) -> Result<Vec<FfiCurrentRow>, CoreError> {
        Ok(lock(&self.r)
            .current_page(after_rowid, limit)?
            .into_iter()
            .map(|c| FfiCurrentRow { rowid: c.rowid, ch: c.ch, start: c.start, stop: c.stop, title: c.title, sub: c.sub, ep: c.ep })
            .collect())
    }

    pub fn desc(&self, ch: String, start: i64) -> Result<Option<String>, CoreError> {
        Ok(lock(&self.r).desc(&ch, start)?)
    }

    pub fn count(&self, src: String) -> Result<i64, CoreError> {
        Ok(lock(&self.r).count(&src)?)
    }

    /// Digest of the visible rows of `src` (parity checks against the Kotlin store).
    pub fn digest(&self, src: String) -> Result<FfiDigest, CoreError> {
        let d = lock(&self.r).digest(&src)?;
        Ok(digest(d, 0, 0, None))
    }
}

// ------------------------------------------------------------------ search index (row SEARCH)

use tvbro_core::search;

/// A channel for the index (stream addresses are never passed or stored).
#[derive(uniffi::Record)]
pub struct FfiIndexChannel {
    pub key: String,
    pub name: String,
    pub src: String,
}

/// A movie or series title for the index (Kotlin `VodItem` without the stream address).
#[derive(uniffi::Record)]
pub struct FfiIndexVod {
    pub key: String,
    pub source_id: String,
    pub kind: String,
    pub id: String,
    pub name: String,
    pub poster: Option<String>,
    pub category: String,
    pub rating: Option<String>,
    pub ext: String,
}

#[derive(uniffi::Record)]
pub struct FfiIndexProgramme {
    pub title: String,
    pub ch: String,
    pub start: i64,
    pub stop: i64,
    pub sub: Option<String>,
    pub ep: Option<String>,
}

/// What a finished build stored: channels, movies, series, programme titles, programmes (the `counts` meta value).
#[derive(uniffi::Record)]
pub struct FfiIndexCounts {
    pub channels: u64,
    pub movies: u64,
    pub series: u64,
    pub titles: u64,
    pub programmes: u64,
}

#[derive(uniffi::Record)]
pub struct FfiSearchHit {
    pub kind: i64,
    pub key: String,
    pub name: String,
    pub img: Option<String>,
    pub src: String,
    pub xid: String,
    pub cat: Option<String>,
    pub rating: Option<String>,
    pub ext: Option<String>,
    pub url: Option<String>,
}

impl From<search::SearchHit> for FfiSearchHit {
    fn from(h: search::SearchHit) -> Self {
        FfiSearchHit { kind: h.kind, key: h.key, name: h.name, img: h.img, src: h.src, xid: h.xid, cat: h.cat, rating: h.rating, ext: h.ext, url: h.url }
    }
}

#[derive(uniffi::Record)]
pub struct FfiProgHit {
    pub title: String,
    pub ch: String,
    pub start: i64,
    pub stop: i64,
    pub sub: Option<String>,
    pub ep: Option<String>,
}

#[derive(uniffi::Record)]
pub struct FfiSearchResult {
    pub channels: Vec<FfiSearchHit>,
    pub movies: Vec<FfiSearchHit>,
    pub series: Vec<FfiSearchHit>,
    pub programmes: Vec<FfiProgHit>,
}

fn hits(v: Vec<search::SearchHit>) -> Vec<FfiSearchHit> {
    v.into_iter().map(FfiSearchHit::from).collect()
}

/// One rebuild of the search index into a fresh file (Kotlin renames it over `search.db` afterwards). The rows come
/// in batches; programmes either from Kotlin or straight from the core's guide store.
#[derive(uniffi::Object)]
pub struct CoreSearchBuilder {
    w: std::sync::Mutex<Option<search::Writer>>,
}

impl CoreSearchBuilder {
    fn with<T>(&self, f: impl FnOnce(&mut search::Writer) -> tvbro_core::Result<T>) -> Result<T, CoreError> {
        let mut g = lock(&self.w);
        let w = g.as_mut().ok_or_else(|| CoreError::Other { reason: "index build already finished".into() })?;
        Ok(f(w)?)
    }
}

#[uniffi::export]
impl CoreSearchBuilder {
    /// Creates the index tables in a new file at `path` (which must not exist).
    #[uniffi::constructor]
    pub fn create(path: String) -> Result<std::sync::Arc<Self>, CoreError> {
        let ix = search::SearchIndex::create_file(std::path::Path::new(&path))?;
        Ok(std::sync::Arc::new(CoreSearchBuilder { w: std::sync::Mutex::new(Some(ix.into_writer()?)) }))
    }

    pub fn channels(&self, rows: Vec<FfiIndexChannel>) -> Result<(), CoreError> {
        self.with(|w| rows.iter().try_for_each(|c| w.channel(&c.key, &c.name, &c.src)))
    }

    pub fn vods(&self, rows: Vec<FfiIndexVod>) -> Result<(), CoreError> {
        self.with(|w| {
            rows.into_iter().try_for_each(|v| {
                let item = tvbro_core::xtream::VodItem { key: v.key, source_id: v.source_id, kind: v.kind, id: v.id, name: v.name, poster: v.poster, category: v.category, rating: v.rating, ext: v.ext, url: None, year: None, genre: None };
                w.vod(&item)
            })
        })
    }

    pub fn programmes(&self, rows: Vec<FfiIndexProgramme>) -> Result<(), CoreError> {
        self.with(|w| rows.iter().try_for_each(|p| w.programme(&p.title, &p.ch, p.start, p.stop, p.sub.as_deref(), p.ep.as_deref())))
    }

    /// Every current programme of the core's guide store (row EPG on): read in pages through its reader connection,
    /// so nothing crosses to Kotlin. Returns the number of programmes read.
    pub fn programmes_from_guide(&self, guide: std::sync::Arc<CoreEpgDb>) -> Result<u64, CoreError> {
        let mut after = 0;
        let mut n = 0u64;
        loop {
            let page = lock(&guide.r).current_page(after, 2000)?;
            let Some(last) = page.last() else { return Ok(n) };
            after = last.rowid;
            n += page.len() as u64;
            self.with(|w| page.iter().try_for_each(|p| w.programme(&p.title, &p.ch, p.start, p.stop, p.sub.as_deref(), p.ep.as_deref())))?;
        }
    }

    /// Indexes, meta rows, FTS optimize; closes the file. `built_at` in epoch milliseconds (the `built` meta value).
    pub fn finish(&self, built_at: i64) -> Result<FfiIndexCounts, CoreError> {
        let w = lock(&self.w).take().ok_or_else(|| CoreError::Other { reason: "index build already finished".into() })?;
        let c = FfiIndexCounts { channels: w.counts[0], movies: w.counts[1], series: w.counts[2], titles: w.counts[3], programmes: w.programmes };
        drop(w.finish(built_at)?);
        Ok(c)
    }

    /// Gives up a build (the file stays incomplete; the caller deletes it).
    pub fn abort(&self) {
        drop(lock(&self.w).take());
    }
}

/// A built search index opened read-only (queries and the stored catalogue pages).
#[derive(uniffi::Object)]
pub struct CoreSearchIndex {
    ix: std::sync::Mutex<search::SearchIndex>,
}

#[uniffi::export]
impl CoreSearchIndex {
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<std::sync::Arc<Self>, CoreError> {
        Ok(std::sync::Arc::new(CoreSearchIndex { ix: std::sync::Mutex::new(search::SearchIndex::open_readonly(std::path::Path::new(&path))?) }))
    }

    /// Ranked results (Kotlin `SearchIndex.search`): at most `per_section` per section, programmes ending after
    /// `past_from`, at most `max_prog` of them.
    pub fn search(&self, q: String, past_from: i64, per_section: u32, max_prog: u32) -> Result<FfiSearchResult, CoreError> {
        let r = lock(&self.ix).search(&q, past_from, per_section as usize, max_prog as usize)?;
        Ok(FfiSearchResult {
            channels: hits(r.channels),
            movies: hits(r.movies),
            series: hits(r.series),
            programmes: r.programmes.into_iter().map(|p| FfiProgHit { title: p.title, ch: p.ch, start: p.start, stop: p.stop, sub: p.sub, ep: p.ep }).collect(),
        })
    }

    /// One page of the stored catalogue: `kind` 1 (movies) or 2 (series), `cat` None for all categories.
    pub fn page(&self, kind: i64, cat: Option<String>, offset: u32, limit: u32) -> Result<Vec<FfiSearchHit>, CoreError> {
        Ok(hits(lock(&self.ix).page_hits(kind, cat.as_deref(), offset as usize, limit as usize)?))
    }

    pub fn categories(&self, kind: i64) -> Result<Vec<String>, CoreError> {
        Ok(lock(&self.ix).categories(kind)?)
    }

    pub fn count(&self, kind: i64) -> Result<i64, CoreError> {
        Ok(lock(&self.ix).count(kind)?)
    }

    pub fn meta(&self, k: String) -> Result<Option<String>, CoreError> {
        Ok(lock(&self.ix).meta(&k)?)
    }
}