// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Guide refresh: XMLTV (plain or gzip) into the guide store. The port of Android `Repository.refreshEpg`'s parse
//! and write loop: the same channel resolution (tvg-id or name, then display names, lower case, `src|key`), the
//! same time window, descriptions cut to 160 and sub-titles to 120 UTF-16 units, batches of 500 rows into a new
//! generation of [EpgStore]. Plus an order-independent digest of programme rows, so Kotlin and the core can be
//! compared without either side keeping the rows (shadow mode, parity tests).

use crate::epgstore::EpgStore;
use crate::gz::{self, Limits};
use crate::model::Programme;
use crate::text::take_utf16;
use crate::xmltv::{self, GuideNames, XmltvLimits};
use std::cell::Cell;
use std::collections::HashSet;
use std::io::{self, BufRead, Read};
use std::rc::Rc;

/// Characters (UTF-16 units) of a description kept in the store (Repository.refreshEpg).
pub const DESC_MAX: usize = 160;
/// Characters (UTF-16 units) of a sub-title (XmltvParser).
pub const SUB_MAX: usize = 120;
/// Rows per insert transaction.
pub const BATCH: usize = 500;

#[derive(Clone, Debug)]
pub struct LoadOptions {
    pub source_id: String,
    /// Lower-case tvg-ids and channel names of the source's channels.
    pub wanted: HashSet<String>,
    /// Programmes are kept when `stop >= min_stop` and `start <= max_start`.
    pub min_stop: i64,
    pub max_start: i64,
    pub limits: Limits,
}

#[derive(Debug)]
pub struct LoadStats {
    pub programmes: u64,
    /// Smallest start / largest stop of the programmes delivered (`i64::MAX` / 0 when none, like Kotlin).
    pub min_start: i64,
    pub max_stop: i64,
    /// Guide channel id -> display names (complete only when `error` is None).
    pub names: GuideNames,
    /// Malformed input, a limit, a read or a database error. Programmes before it were delivered.
    pub error: Option<crate::Error>,
}

/// Android's resolver: the channel attribute, else the first display name, lower case, among `wanted`.
pub fn resolve(source_id: &str, wanted: &HashSet<String>, ch: &str, names: Option<&Vec<String>>) -> Option<String> {
    let l = ch.to_lowercase();
    if wanted.contains(&l) {
        return Some(format!("{source_id}|{l}"));
    }
    names?.iter().map(|n| n.to_lowercase()).find(|n| wanted.contains(n)).map(|n| format!("{source_id}|{n}"))
}

/// The form a programme is stored in (description and sub-title cut like Kotlin's `take`).
pub fn stored_form(mut p: Programme) -> Programme {
    p.desc = p.desc.map(|d| take_utf16(&d, DESC_MAX));
    p.sub = p.sub.map(|s| take_utf16(&s, SUB_MAX));
    p
}

/// A reader that fails once `stop` is set (a sink error ends the parse at once, not at the end of a 1 GB file).
struct Stop<R> {
    inner: R,
    stop: Rc<Cell<bool>>,
}

fn stopped() -> io::Error {
    io::Error::other("stopped")
}

impl<R: BufRead> Read for Stop<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.stop.get() {
            return Err(stopped());
        }
        self.inner.read(buf)
    }
}

impl<R: BufRead> BufRead for Stop<R> {
    fn fill_buf(&mut self) -> io::Result<&[u8]> {
        if self.stop.get() {
            return Err(stopped());
        }
        self.inner.fill_buf()
    }
    fn consume(&mut self, amt: usize) {
        self.inner.consume(amt)
    }
}

/// Parses `input` and hands every programme of a wanted channel, in its stored form, to `sink` (false stops).
pub fn each_programme<R: BufRead>(input: R, o: &LoadOptions, mut sink: impl FnMut(Programme) -> bool) -> LoadStats {
    let mut st = LoadStats { programmes: 0, min_start: i64::MAX, max_stop: 0, names: GuideNames::new(), error: None };
    let inp = match gz::open_maybe_gzip(input, o.limits) {
        Ok(i) => i,
        Err(e) => {
            st.error = Some(gz::unwrap_io(e));
            return st;
        }
    };
    let stop = Rc::new(Cell::new(false));
    let limits = XmltvLimits { max_desc: usize::MAX, max_sub: usize::MAX, ..XmltvLimits::default() };
    let (mut n, mut lo, mut hi) = (0u64, i64::MAX, 0i64);
    let r = xmltv::parse(
        Stop { inner: inp, stop: stop.clone() },
        limits,
        o.min_stop,
        o.max_start,
        |ch, names| resolve(&o.source_id, &o.wanted, ch, names).into_iter().collect(),
        |p| {
            if stop.get() {
                return;
            }
            let q = stored_form(p);
            n += 1;
            lo = lo.min(q.start);
            hi = hi.max(q.stop);
            if !sink(q) {
                stop.set(true);
            }
        },
    );
    st.programmes = n;
    st.min_start = lo;
    st.max_stop = hi;
    match r {
        Ok(names) => st.names = names,
        Err(e) => st.error = Some(e),
    }
    st
}

/// Loads one guide file into generation `generation` of `store` (500-row transactions; the caller commits or
/// discards). `progress(total)` runs after every batch.
pub fn load_into<R: BufRead>(store: &mut EpgStore, generation: i64, input: R, o: &LoadOptions, mut progress: impl FnMut(u64)) -> LoadStats {
    let mut batch: Vec<Programme> = Vec::with_capacity(BATCH);
    let mut db_err: Option<crate::Error> = None;
    let mut total = 0u64;
    let mut st = each_programme(input, o, |p| {
        batch.push(p);
        total += 1;
        if batch.len() >= BATCH {
            if let Err(e) = store.insert_batch(&o.source_id, generation, &batch) {
                db_err = Some(e);
                return false;
            }
            batch.clear();
            progress(total);
        }
        true
    });
    if db_err.is_none()
        && !batch.is_empty()
        && let Err(e) = store.insert_batch(&o.source_id, generation, &batch)
    {
        db_err = Some(e);
    }
    if let Some(e) = db_err {
        st.error = Some(e);
    }
    st
}

// ------------------------------------------------------------------ digest

/// Field names in [Digest::fields] order.
pub const FIELDS: [&str; 7] = ["key", "start", "stop", "title", "desc", "sub", "ep"];

/// Order-independent digest of programme rows: per field and per row, the wrapping sum of FNV-1a 64 hashes. The
/// Kotlin side (`CoreShadow.Digest`) computes the same values, so two sets of rows compare without keeping them.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Digest {
    pub rows: u64,
    pub fields: [u64; 7],
    pub row: u64,
}

const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;

fn fnv(parts: &[&[u8]]) -> u64 {
    let mut h = FNV_OFFSET;
    for p in parts {
        for b in *p {
            h ^= u64::from(*b);
            h = h.wrapping_mul(FNV_PRIME);
        }
    }
    h
}

fn h_str(s: Option<&str>) -> u64 {
    match s {
        None => fnv(&[b"n"]),
        Some(v) => fnv(&[b"s", v.as_bytes()]),
    }
}

fn h_int(v: i64) -> u64 {
    fnv(&[b"i", v.to_string().as_bytes()])
}

impl Digest {
    pub fn add(&mut self, p: &Programme) {
        let f = [h_str(Some(&p.key)), h_int(p.start), h_int(p.stop), h_str(Some(&p.title)), h_str(p.desc.as_deref()), h_str(p.sub.as_deref()), h_str(p.ep.as_deref())];
        let mut b = [0u8; 56];
        for (i, x) in f.iter().enumerate() {
            self.fields[i] = self.fields[i].wrapping_add(*x);
            b[i * 8..i * 8 + 8].copy_from_slice(&x.to_le_bytes());
        }
        self.row = self.row.wrapping_add(fnv(&[&b]));
        self.rows += 1;
    }

    /// Names of the fields whose digests differ ("rows" when only the counts or the row pairing differ).
    pub fn differing(&self, other: &Digest) -> Vec<&'static str> {
        let mut v: Vec<&'static str> = (0..7).filter(|&i| self.fields[i] != other.fields[i]).map(|i| FIELDS[i]).collect();
        if v.is_empty() && (self.rows != other.rows || self.row != other.row) {
            v.push("rows");
        }
        v
    }
}

/// Parses a guide like [load_into] without storing it: the digest of the rows Kotlin would have stored before its
/// duplicate filter (shadow mode).
pub fn digest_guide<R: BufRead>(input: R, o: &LoadOptions) -> (Digest, LoadStats) {
    let mut d = Digest::default();
    let st = each_programme(input, o, |p| {
        d.add(&p);
        true
    });
    (d, st)
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::Compression;
    use flate2::write::GzEncoder;
    use std::io::Write;

    fn opts(wanted: &[&str]) -> LoadOptions {
        LoadOptions { source_id: "s".into(), wanted: wanted.iter().map(|w| w.to_string()).collect(), min_stop: 0, max_start: i64::MAX, limits: Limits::default() }
    }

    const XML: &str = r#"<?xml version="1.0"?><tv>
        <channel id="One.Example"><display-name>Ignored</display-name></channel>
        <channel id="x2"><display-name>Other Name</display-name><display-name>Channel TWO</display-name></channel>
        <programme start="20261113120000 +0000" stop="20261113130000 +0000" channel="One.Example"><title>News</title><desc>Daily news</desc></programme>
        <programme start="20261113120000 +0000" stop="20261113130000 +0000" channel="One.Example"><title>Duplicate start</title></programme>
        <programme start="20261113130000 +0000" stop="20261113140000 +0000" channel="x2"><title>Film</title><sub-title>Part one</sub-title><episode-num system="xmltv_ns">0.1.</episode-num></programme>
        <programme start="20261113130000 +0000" stop="20261113140000 +0000" channel="nobody"><title>Dropped</title></programme>
        </tv>"#;

    #[test]
    fn android_resolver_and_cuts() {
        let w: HashSet<String> = ["one.example", "channel two"].iter().map(|s| s.to_string()).collect();
        assert_eq!(resolve("s", &w, "ONE.example", None).as_deref(), Some("s|one.example"));
        assert_eq!(resolve("s", &w, "x2", Some(&vec!["Other".into(), "Channel Two".into()])).as_deref(), Some("s|channel two"));
        assert!(resolve("s", &w, "x3", None).is_none());
        let long = Programme { key: "k".into(), start: 0, stop: 1, title: "t".into(), desc: Some(format!("{}{}", "d".repeat(159), "😀x")), sub: Some("s".repeat(200)), ep: None };
        let q = stored_form(long);
        assert_eq!(q.desc.as_deref(), Some("d".repeat(159).as_str()), "a surrogate pair is not cut in half");
        assert_eq!(q.sub.unwrap().len(), 120);
    }

    #[test]
    fn loads_plain_and_gzip_into_a_generation() {
        let mut gz = GzEncoder::new(Vec::new(), Compression::default());
        gz.write_all(XML.as_bytes()).unwrap();
        let gz = gz.finish().unwrap();
        for input in [XML.as_bytes().to_vec(), gz] {
            let mut db = EpgStore::in_memory().unwrap();
            let g = db.begin_refresh("s").unwrap();
            let mut seen = Vec::new();
            let st = load_into(&mut db, g, &input[..], &opts(&["one.example", "channel two"]), |n| seen.push(n));
            assert!(st.error.is_none(), "{:?}", st.error);
            assert_eq!(st.programmes, 3);
            assert_eq!(st.names["x2"], vec!["Other Name", "Channel TWO"]);
            db.commit("s", g, st.min_start, st.max_stop, 0).unwrap();
            // the duplicate start is ignored like Kotlin's INSERT OR IGNORE
            assert_eq!(db.count("s").unwrap(), 2);
            let f = db.load("s", "s|channel two", 0, i64::MAX).unwrap();
            assert_eq!((f[0].title.as_str(), f[0].sub.as_deref(), f[0].ep.as_deref()), ("Film", Some("Part one"), Some("S1 E2")));
            assert_eq!(st.max_stop - st.min_start, 7200);
        }
    }

    #[test]
    fn digest_matches_the_store_and_names_fields() {
        let o = opts(&["one.example", "channel two"]);
        let (d, st) = digest_guide(XML.as_bytes(), &o);
        assert_eq!((d.rows, st.programmes), (3, 3));
        let mut db = EpgStore::in_memory().unwrap();
        let g = db.begin_refresh("s").unwrap();
        load_into(&mut db, g, XML.as_bytes(), &o, |_| {});
        db.commit("s", g, 0, 0, 0).unwrap();
        let stored = db.digest("s").unwrap();
        assert_eq!(stored.rows, 2);
        assert_eq!(d.differing(&stored), vec!["key", "start", "stop", "title", "desc", "sub", "ep"]);
        let (d2, _) = digest_guide(XML.replace("Daily news", "Daily").as_bytes(), &o);
        assert_eq!(d.differing(&d2), vec!["desc"]);
        assert!(d.differing(&d).is_empty());
    }

    /// Timing of a real guide file: `TVBRO_BENCH_GUIDE=<file> cargo test -p tvbro-core --release bench_guide_file -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn bench_guide_file() {
        let Ok(path) = std::env::var("TVBRO_BENCH_GUIDE") else { return };
        let o = LoadOptions { source_id: "s".into(), wanted: (0..2000).flat_map(|i| [format!("ch{i}.example"), format!("channel {i}")]).collect(), min_stop: 0, max_start: i64::MAX, limits: Limits::default() };
        let t = std::time::Instant::now();
        let (d, _) = digest_guide(std::io::BufReader::new(std::fs::File::open(&path).unwrap()), &o);
        println!("parse only: {} rows in {:?}", d.rows, t.elapsed());
        let db = std::env::var("TVBRO_BENCH_DIR").map(std::path::PathBuf::from).unwrap_or_else(|_| std::env::temp_dir()).join(format!("tvbro-bench-{}.db", std::process::id()));
        let mut s = EpgStore::open(&db).unwrap();
        let t = std::time::Instant::now();
        let g = s.begin_refresh("s").unwrap();
        let st = load_into(&mut s, g, std::io::BufReader::new(std::fs::File::open(&path).unwrap()), &o, |_| {});
        s.commit("s", g, st.min_start, st.max_stop, 0).unwrap();
        println!("parse + store: {} rows in {:?}, log peak {} MB", st.programmes, t.elapsed(), s.wal_peak >> 20);
        drop(s);
        for x in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{x}", db.display()));
        }
    }

    #[test]
    fn damage_keeps_rows_before_and_reports() {
        let bad = &XML[..XML.find("<programme start=\"20261113130000 +0000\" stop=\"20261113140000 +0000\" channel=\"x2\"").unwrap() + 30];
        let mut db = EpgStore::in_memory().unwrap();
        let g = db.begin_refresh("s").unwrap();
        let st = load_into(&mut db, g, format!("{bad} <<<").as_bytes(), &opts(&["one.example"]), |_| {});
        assert!(st.error.is_some());
        assert_eq!(st.programmes, 2);
        let n: i64 = db.conn.query_row("SELECT COUNT(*) FROM prog WHERE gen=?1", [g], |r| r.get(0)).unwrap();
        assert_eq!(n, 1);
    }
}
