// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Full-text search (port of SearchIndex.kt / SearchLogic.kt): SQLite FTS4, prefix indexes, contentless, over
//! channel names, movie and series titles, and programme titles + episode titles. Built in one pass into a fresh
//! database file the platform swaps in atomically. Same schema as the Kotlin index, so either side can read a file
//! the other wrote.

use crate::catchup::ProgState;
use crate::text::{fold_nfd, is_letter_or_digit, utf16_len};
use crate::xtream::VodItem;
use rusqlite::{Connection, OptionalExtension, params};
use std::collections::HashMap;
use std::path::Path;

pub const CHANNEL: i64 = 0;
pub const MOVIE: i64 = 1;
pub const SERIES: i64 = 2;
pub const PROG: i64 = 3;
const FTS: [&str; 4] = ["f_ch", "f_mov", "f_ser", "f_prog"];

// ------------------------------------------------------------------ folding and ranking

/// Lower case, diacritics removed, a few letters spelled out, punctuation to single spaces. Like Kotlin's
/// `Fold.text`, which tests every UTF-16 unit: a character outside the BMP is two surrogates there, never a letter,
/// so it separates words here too.
pub fn fold(s: &str) -> String {
    let n = fold_nfd(s);
    let mut b = String::with_capacity(n.len());
    let mut space = true;
    for c in n.chars() {
        let r = match c {
            'ß' => Some("ss"),
            'æ' => Some("ae"),
            'œ' => Some("oe"),
            'ø' => Some("o"),
            'đ' | 'ð' => Some("d"),
            'ł' => Some("l"),
            'þ' => Some("th"),
            'ı' => Some("i"),
            _ => None,
        };
        if let Some(r) = r {
            b.push_str(r);
            space = false;
        } else if (c as u32) < 0x10000 && is_letter_or_digit(c) {
            b.push(c);
            space = false;
        } else if !space {
            b.push(' ');
            space = true;
        }
    }
    b.trim().to_string()
}

pub fn tokens(q: &str) -> Vec<String> {
    fold(q).split(' ').filter(|t| !t.is_empty()).take(8).map(String::from).collect()
}

/// FTS4 query: every word must match as a prefix ("barc" finds "Barcelona").
pub fn match_query(q: &str) -> Option<String> {
    let t = tokens(q);
    (!t.is_empty()).then(|| t.iter().map(|w| format!("{w}*")).collect::<Vec<_>>().join(" "))
}

/// Lower is better: exact title, title starts with the query, every word starts a word in order, anything else.
pub fn rank(name: &str, q: &str) -> i32 {
    let n = fold(name);
    let f = fold(q);
    if n == f {
        return 0;
    }
    if n.starts_with(&f) {
        return 1;
    }
    let words: Vec<&str> = n.split(' ').collect();
    let mut wi = 0;
    for t in f.split(' ') {
        while wi < words.len() && !words[wi].starts_with(t) {
            wi += 1;
        }
        if wi == words.len() {
            return 3;
        }
        wi += 1;
    }
    2
}

// ------------------------------------------------------------------ results

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SearchHit {
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

impl SearchHit {
    pub fn to_vod(&self) -> VodItem {
        VodItem {
            key: self.key.clone(),
            source_id: self.src.clone(),
            kind: if self.kind == SERIES { "series" } else { "movie" }.into(),
            id: self.xid.clone(),
            name: self.name.clone(),
            poster: self.img.clone(),
            category: self.cat.clone().unwrap_or_default(),
            rating: self.rating.clone(),
            ext: self.ext.clone().unwrap_or_default(),
            url: self.url.clone(),
            year: None,
            genre: None,
        }
    }
}

/// A matching programme. `ch` is the guide key; times are epoch seconds, unshifted.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProgHit {
    pub title: String,
    pub ch: String,
    pub start: i64,
    pub stop: i64,
    pub sub: Option<String>,
    pub ep: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SearchResult {
    pub channels: Vec<SearchHit>,
    pub movies: Vec<SearchHit>,
    pub series: Vec<SearchHit>,
    pub programmes: Vec<ProgHit>,
}

impl SearchResult {
    pub fn is_empty(&self) -> bool {
        self.channels.is_empty() && self.movies.is_empty() && self.series.is_empty() && self.programmes.is_empty()
    }
}

// ------------------------------------------------------------------ index

/// A search index database.
#[derive(Debug)]
pub struct SearchIndex {
    pub conn: Connection,
}

impl SearchIndex {
    pub fn open(path: &Path) -> crate::Result<Self> {
        Ok(SearchIndex { conn: Connection::open(path)? })
    }

    /// Opens a built index for queries only (the app's reader; the file is replaced, never written in place).
    pub fn open_readonly(path: &Path) -> crate::Result<Self> {
        use rusqlite::OpenFlags;
        Ok(SearchIndex { conn: Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX)? })
    }

    /// A fresh index file for a rebuild, set up like the app's (no journal, no syncs: a failed build is thrown away,
    /// and the finished file is renamed over the old one).
    pub fn create_file(path: &Path) -> crate::Result<Self> {
        let ix = Self::open(path)?;
        ix.conn.query_row("PRAGMA journal_mode=OFF", [], |_| Ok(()))?;
        ix.conn.execute_batch("PRAGMA synchronous=OFF")?;
        ix.create()?;
        Ok(ix)
    }

    pub fn in_memory() -> crate::Result<Self> {
        Ok(SearchIndex { conn: Connection::open_in_memory()? })
    }

    /// Creates the tables in a fresh database.
    pub fn create(&self) -> crate::Result<()> {
        self.conn.execute_batch("CREATE TABLE item(id INTEGER PRIMARY KEY, kind INTEGER, k TEXT, name TEXT, img TEXT, src TEXT, xid TEXT, cat TEXT, rating TEXT, ext TEXT, url TEXT)")?;
        for f in FTS {
            self.conn.execute_batch(&format!("CREATE VIRTUAL TABLE {f} USING fts4(content=\"\", t, prefix=\"2,3\")"))?;
        }
        self.conn.execute_batch("CREATE TABLE pr(tid INTEGER, ch TEXT, start INTEGER, stop INTEGER, sub TEXT, ep TEXT); CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);")?;
        Ok(())
    }

    /// Streams rows into a freshly created index; call [Writer::finish] once at the end, which gives the index back.
    /// Stream addresses are not stored (a playlist VOD address carries the provider login and the index is not
    /// encrypted): hits come back with `url` None and the caller resolves the address at play time, like Kotlin's
    /// `SearchIndex.Writer` (its `storeUrls` default false).
    pub fn into_writer(self) -> crate::Result<Writer> {
        self.into_writer_with(false)
    }

    /// [Self::into_writer], keeping stream addresses when `store_urls` (Kotlin's `storeUrls = true`).
    pub fn into_writer_with(self, store_urls: bool) -> crate::Result<Writer> {
        self.conn.execute_batch("BEGIN")?;
        Ok(Writer { ix: self, store_urls, titles: HashMap::new(), next: 1, pending: 0, counts: [0; 4], programmes: 0 })
    }

    fn candidates(&self, kind: i64, m: &str, max: usize) -> crate::Result<Vec<SearchHit>> {
        let sql = format!("SELECT {COLS} FROM {} f JOIN item i ON i.id=f.docid WHERE f.t MATCH ?1 ORDER BY length(i.name) LIMIT {max}", FTS[kind as usize]);
        let mut st = self.conn.prepare_cached(&sql)?;
        let rows = st.query_map([m], hit)?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Ranked results, at most `per_section` per section. Programmes are limited to those ending after `past_from`
    /// (the catch-up window) and to `max_prog` rows.
    pub fn search(&self, q: &str, past_from: i64, per_section: usize, max_prog: usize) -> crate::Result<SearchResult> {
        let Some(m) = match_query(q) else { return Ok(SearchResult::default()) };
        if utf16_len(&fold(q)) < 2 {
            return Ok(SearchResult::default());
        }
        let cand = 6 * per_section;
        let channels = ranked(self.candidates(CHANNEL, &m, cand)?, q, per_section);
        let movies = ranked(self.candidates(MOVIE, &m, cand)?, q, per_section);
        let series = ranked(self.candidates(SERIES, &m, cand)?, q, per_section);
        let mut titles: Vec<(i64, String)> = {
            let sql = format!("SELECT i.id,i.name FROM {} f JOIN item i ON i.id=f.docid WHERE f.t MATCH ?1 ORDER BY length(i.name) LIMIT {}", FTS[PROG as usize], cand * 2);
            let mut st = self.conn.prepare_cached(&sql)?;
            let rows = st.query_map([&m], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, Option<String>>(1)?.unwrap_or_default())))?;
            rows.collect::<Result<_, _>>()?
        };
        titles.sort_by_cached_key(|t| (rank(&t.1, q), utf16_len(&t.1)));
        titles.truncate(60);
        let mut programmes = Vec::new();
        if !titles.is_empty() {
            let name: HashMap<i64, &str> = titles.iter().map(|t| (t.0, t.1.as_str())).collect();
            let ids = titles.iter().map(|t| t.0.to_string()).collect::<Vec<_>>().join(",");
            let sql = format!("SELECT tid,ch,start,stop,sub,ep FROM pr WHERE tid IN ({ids}) AND stop>?1 ORDER BY start LIMIT {max_prog}");
            let mut st = self.conn.prepare(&sql)?;
            let rows = st.query_map([past_from], |r| {
                Ok(ProgHit {
                    title: name.get(&r.get::<_, i64>(0)?).map(|s| s.to_string()).unwrap_or_default(),
                    ch: r.get::<_, Option<String>>(1)?.unwrap_or_default(),
                    start: r.get(2)?,
                    stop: r.get(3)?,
                    sub: r.get(4)?,
                    ep: r.get(5)?,
                })
            })?;
            programmes = rows.collect::<Result<_, _>>()?;
        }
        Ok(SearchResult { channels, movies, series, programmes })
    }

    /// One page of the stored catalogue: `kind` MOVIE or SERIES, `cat` a category name or None for all.
    pub fn page(&self, kind: i64, cat: Option<&str>, offset: usize, limit: usize) -> crate::Result<Vec<VodItem>> {
        Ok(self.page_hits(kind, cat, offset, limit)?.iter().map(SearchHit::to_vod).collect())
    }

    /// [Self::page] as the stored rows.
    pub fn page_hits(&self, kind: i64, cat: Option<&str>, offset: usize, limit: usize) -> crate::Result<Vec<SearchHit>> {
        Ok(match cat {
            None => {
                let mut st = self.conn.prepare_cached(&format!("SELECT {COLS} FROM item i WHERE kind=?1 ORDER BY id LIMIT {limit} OFFSET {offset}"))?;
                st.query_map(params![kind], hit)?.collect::<Result<Vec<_>, _>>()?
            }
            Some(c) => {
                let mut st = self.conn.prepare_cached(&format!("SELECT {COLS} FROM item i WHERE kind=?1 AND cat=?2 ORDER BY id LIMIT {limit} OFFSET {offset}"))?;
                st.query_map(params![kind, c], hit)?.collect::<Result<Vec<_>, _>>()?
            }
        })
    }

    /// Category names in catalogue order (first appearance).
    pub fn categories(&self, kind: i64) -> crate::Result<Vec<String>> {
        let mut st = self.conn.prepare_cached("SELECT cat FROM item WHERE kind=?1 GROUP BY cat ORDER BY MIN(id)")?;
        let rows = st.query_map([kind], |r| r.get::<_, Option<String>>(0))?;
        Ok(rows.filter_map(|r| r.ok().flatten()).collect())
    }

    pub fn count(&self, kind: i64) -> crate::Result<i64> {
        Ok(self.conn.query_row("SELECT COUNT(*) FROM item WHERE kind=?1", [kind], |r| r.get(0))?)
    }

    pub fn meta(&self, k: &str) -> crate::Result<Option<String>> {
        Ok(self.conn.query_row("SELECT v FROM meta WHERE k=?1", [k], |r| r.get(0)).optional()?)
    }
}

const COLS: &str = "i.kind,i.k,i.name,i.img,i.src,i.xid,i.cat,i.rating,i.ext,i.url";

fn hit(r: &rusqlite::Row) -> rusqlite::Result<SearchHit> {
    let s = |i| r.get::<_, Option<String>>(i);
    Ok(SearchHit {
        kind: r.get(0)?,
        key: s(1)?.unwrap_or_default(),
        name: s(2)?.unwrap_or_default(),
        img: s(3)?,
        src: s(4)?.unwrap_or_default(),
        xid: s(5)?.unwrap_or_default(),
        cat: s(6)?,
        rating: s(7)?,
        ext: s(8)?,
        url: s(9)?,
    })
}

/// Kotlin's order: rank, then length and text in UTF-16 units (`String.length`, `String.compareTo`); stable.
fn ranked(mut l: Vec<SearchHit>, q: &str, limit: usize) -> Vec<SearchHit> {
    l.sort_by_cached_key(|h| (rank(&h.name, q), utf16_len(&h.name), h.name.encode_utf16().collect::<Vec<u16>>()));
    l.truncate(limit);
    l
}

/// Index writer (owns the index until [Writer::finish]): commits every 2000 rows, like Kotlin's.
#[derive(Debug)]
pub struct Writer {
    ix: SearchIndex,
    store_urls: bool,
    titles: HashMap<String, i64>,
    next: i64,
    pending: u32,
    pub counts: [u64; 4],
    pub programmes: u64,
}

impl Writer {
    fn tick(&mut self) -> crate::Result<()> {
        self.pending += 1;
        if self.pending >= 2000 {
            self.ix.conn.execute_batch("COMMIT; BEGIN")?;
            self.pending = 0;
        }
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    fn add(&mut self, kind: i64, key: &str, name: &str, text: &str, img: Option<&str>, src: &str, xid: &str, cat: Option<&str>, rating: Option<&str>, ext: Option<&str>, url: Option<&str>) -> crate::Result<i64> {
        let id = self.next;
        self.next += 1;
        let db = &self.ix.conn;
        db.prepare_cached("INSERT INTO item(id,kind,k,name,img,src,xid,cat,rating,ext,url) VALUES(?,?,?,?,?,?,?,?,?,?,?)")?
            .execute(params![id, kind, key, name, img, src, xid, cat, rating, ext, url])?;
        db.prepare_cached(&format!("INSERT INTO {}(docid,t) VALUES(?,?)", FTS[kind as usize]))?.execute(params![id, fold(text)])?;
        self.counts[kind as usize] += 1;
        self.tick()?;
        Ok(id)
    }

    pub fn channel(&mut self, key: &str, name: &str, src: &str) -> crate::Result<()> {
        self.add(CHANNEL, key, name, name, None, src, "", None, None, None, None).map(|_| ())
    }

    pub fn vod(&mut self, v: &VodItem) -> crate::Result<()> {
        let k = if v.kind == "series" { SERIES } else { MOVIE };
        let url = if self.store_urls { v.url.as_deref() } else { None };
        self.add(k, &v.key, &v.name, &v.name, v.poster.as_deref(), &v.source_id, &v.id, Some(&v.category), v.rating.as_deref(), Some(&v.ext), url).map(|_| ())
    }

    /// One document per title + episode title; every airing is a row of `pr`.
    pub fn programme(&mut self, title: &str, ch: &str, start: i64, stop: i64, sub: Option<&str>, ep: Option<&str>) -> crate::Result<()> {
        if crate::text::kotlin_blank(title) {
            return Ok(());
        }
        let tk = format!("{}\u{1}{}", title.to_lowercase(), sub.map(|s| s.to_lowercase()).unwrap_or_default());
        let tid = match self.titles.get(&tk) {
            Some(&t) => t,
            None => {
                let text = match sub {
                    Some(s) => format!("{title} {s}"),
                    None => title.to_string(),
                };
                let t = self.add(PROG, "", title, &text, None, "", "", None, None, None, None)?;
                self.titles.insert(tk, t);
                t
            }
        };
        self.ix.conn.prepare_cached("INSERT INTO pr(tid,ch,start,stop,sub,ep) VALUES(?,?,?,?,?,?)")?.execute(params![tid, ch, start, stop, sub, ep])?;
        self.programmes += 1;
        self.tick()
    }

    /// The `counts` meta value: channels, movies, series, programme titles, programmes.
    pub fn counts_text(&self) -> String {
        format!("{},{},{},{},{}", self.counts[0], self.counts[1], self.counts[2], self.counts[3], self.programmes)
    }

    /// Indexes, meta rows, FTS optimize, last commit; gives the finished index back.
    pub fn finish(self, built_at: i64) -> crate::Result<SearchIndex> {
        let db = &self.ix.conn;
        db.execute_batch("CREATE INDEX pr_t ON pr(tid, start); CREATE INDEX item_k ON item(kind, cat, id);")?;
        db.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('built',?1)", [built_at.to_string()])?;
        db.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('counts',?1)", [self.counts_text()])?;
        for f in FTS {
            db.execute_batch(&format!("INSERT INTO {f}({f}) VALUES('optimize')"))?;
        }
        db.execute_batch("COMMIT")?;
        Ok(self.ix)
    }
}

// ------------------------------------------------------------------ result grouping and history

/// Groups programme hits by channel for the results view: channels with something on now first, then the soonest
/// upcoming, then catch-up. Finished programmes outside the archive are dropped.
pub fn group_programmes<C: Clone>(hits: &[ProgHit], channel_of: impl Fn(&str) -> Option<C>, state: impl Fn(&C, &ProgHit) -> ProgState, key_of: impl Fn(&C) -> String) -> Vec<(C, Vec<ProgHit>)> {
    let mut order: Vec<String> = Vec::new();
    let mut by: HashMap<String, (C, Vec<ProgHit>)> = HashMap::new();
    for h in hits {
        let Some(c) = channel_of(&h.ch) else { continue };
        if state(&c, h) == ProgState::Past {
            continue;
        }
        let k = key_of(&c);
        by.entry(k.clone())
            .or_insert_with(|| {
                order.push(k);
                (c, Vec::new())
            })
            .1
            .push(h.clone());
    }
    let score = |p: &(C, Vec<ProgHit>)| -> i64 {
        let s: Vec<ProgState> = p.1.iter().map(|h| state(&p.0, h)).collect();
        if s.contains(&ProgState::Now) {
            0
        } else if s.contains(&ProgState::Future) {
            (1i64 << 40) | p.1.iter().filter(|h| state(&p.0, h) == ProgState::Future).map(|h| h.start).min().unwrap_or(0)
        } else {
            (2i64 << 40) | ((i64::MAX >> 30) - p.1.iter().map(|h| h.start).max().unwrap_or(0))
        }
    };
    let mut out: Vec<(C, Vec<ProgHit>)> = order.into_iter().filter_map(|k| by.remove(&k)).collect();
    out.sort_by_cached_key(score);
    out
}

pub const HISTORY_MAX: usize = 20;

/// Previous search keywords: newest first, de-duplicated without regard to case, capped.
pub fn history_add(list: &[String], query: &str, max: usize) -> Vec<String> {
    let q = crate::text::collapse_ws(query);
    if q.chars().count() < 2 {
        return list.to_vec();
    }
    let ql = q.to_lowercase();
    std::iter::once(q).chain(list.iter().filter(|x| x.to_lowercase() != ql).cloned()).take(max).collect()
}

pub fn history_remove(list: &[String], query: &str) -> Vec<String> {
    let q = query.trim().to_lowercase();
    list.iter().filter(|x| x.to_lowercase() != q).cloned().collect()
}

pub fn history_encode(list: &[String]) -> String {
    list.iter().map(|s| s.replace('\n', " ")).collect::<Vec<_>>().join("\n")
}

pub fn history_decode(s: Option<&str>) -> Vec<String> {
    s.unwrap_or("").split('\n').map(str::trim).filter(|x| !x.is_empty()).map(String::from).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::catchup::classify;
    use crate::model::{Catchup, Channel};

    const NOW: i64 = 1_800_000_000;

    fn build(f: impl FnOnce(&mut Writer)) -> SearchIndex {
        let ix = SearchIndex::in_memory().unwrap();
        ix.create().unwrap();
        let mut w = ix.into_writer().unwrap();
        f(&mut w);
        w.finish(NOW).unwrap()
    }

    #[test]
    fn stream_addresses_not_stored() {
        let mut m = movie("1", "Secret Film", "Films");
        m.url = Some("http://192.0.2.1/movie/user/pass/1.mkv".into());
        let db = build(|w| w.vod(&m).unwrap());
        assert_eq!(db.search("secret", 0, 30, 300).unwrap().movies[0].url, None);
        assert_eq!(db.page(MOVIE, None, 0, 10).unwrap()[0].url, None);
        let ix = SearchIndex::in_memory().unwrap();
        ix.create().unwrap();
        let mut w = ix.into_writer_with(true).unwrap();
        w.vod(&m).unwrap();
        assert_eq!(w.finish(NOW).unwrap().page(MOVIE, None, 0, 10).unwrap()[0].url, m.url);
    }

    #[test]
    fn fold_like_kotlin_chars() {
        // outside the BMP: two surrogates in Kotlin, never letters
        assert_eq!(fold("a\u{1D400}b"), "a b");
        assert_eq!(fold("\u{20000}"), "");
        // Kotlin's isBlank: U+001C..U+001F are blank, U+0085 is not
        let db = build(|w| {
            w.programme("\u{1C}\u{1F}", "s|a", NOW, NOW + 60, None, None).unwrap();
            w.programme("\u{85}", "s|a", NOW + 60, NOW + 120, None, None).unwrap();
        });
        assert_eq!(db.meta("counts").unwrap().as_deref(), Some("0,0,0,1,1"));
    }

    fn movie(id: &str, name: &str, cat: &str) -> VodItem {
        VodItem { key: format!("m|s|{id}"), source_id: "s".into(), kind: "movie".into(), id: id.into(), name: name.into(), poster: None, category: cat.into(), rating: Some("7.1".into()), ext: "mkv".into(), url: None, year: None, genre: None }
    }

    fn show(id: &str, name: &str) -> VodItem {
        VodItem { key: format!("s|s|{id}"), kind: "series".into(), category: "Shows".into(), rating: None, ext: String::new(), ..movie(id, name, "") }
    }

    fn names(h: &[SearchHit]) -> Vec<&str> {
        h.iter().map(|x| x.name.as_str()).collect()
    }

    #[test]
    fn fold_removes_diacritics_and_punctuation() {
        assert_eq!(fold("Pokémon: La Série!"), "pokemon la serie");
        assert_eq!(fold("Straße  Müller"), "strasse muller");
        assert_eq!(match_query("Barc, FC").as_deref(), Some("barc* fc*"));
        assert!(match_query("  -- ").is_none());
    }

    #[test]
    fn prefix_per_word() {
        let db = build(|w| {
            w.channel("s|1", "FC Barcelona TV", "s").unwrap();
            w.channel("s|2", "BBC One", "s").unwrap();
            w.vod(&movie("1", "Barcelona (2019)", "Films")).unwrap();
            w.vod(&movie("2", "Vicky Cristina Barcelona", "Films")).unwrap();
            w.vod(&movie("3", "Madrid Nights", "Films")).unwrap();
            w.programme("Barcelona vs Real Madrid", "s|sport", NOW - 600, NOW + 3000, None, None).unwrap();
        });
        let r = db.search("barc", NOW - 86400, 30, 300).unwrap();
        assert_eq!(names(&r.channels), vec!["FC Barcelona TV"]);
        assert_eq!(r.movies.len(), 2);
        assert_eq!(r.programmes.len(), 1);
        let r2 = db.search("barc mad", NOW - 86400, 30, 300).unwrap();
        assert!(r2.movies.is_empty());
        assert_eq!(r2.programmes.len(), 1);
        assert_eq!(r2.programmes[0].title, "Barcelona vs Real Madrid");
        assert!(db.search("arcelona", NOW - 86400, 30, 300).unwrap().is_empty());
        assert!(db.search("b", 0, 30, 300).unwrap().is_empty());
    }

    #[test]
    fn diacritics_fold_both_ways() {
        let db = build(|w| {
            w.vod(&show("1", "Pokémon")).unwrap();
            w.vod(&movie("1", "Amélie", "Films")).unwrap();
            w.channel("s|1", "Müller TV", "s").unwrap();
        });
        assert_eq!(names(&db.search("pokemon", 0, 30, 300).unwrap().series), vec!["Pokémon"]);
        assert_eq!(names(&db.search("AMELIE", 0, 30, 300).unwrap().movies), vec!["Amélie"]);
        assert_eq!(names(&db.search("müll", 0, 30, 300).unwrap().channels), vec!["Müller TV"]);
        assert_eq!(names(&db.search("mull", 0, 30, 300).unwrap().channels), vec!["Müller TV"]);
    }

    #[test]
    fn ranking_exact_then_prefix_then_word() {
        let db = build(|w| {
            for (k, n) in [("1", "Sky Newsroom Extra"), ("2", "BBC News"), ("3", "News"), ("4", "Newsroom")] {
                w.channel(k, n, "s").unwrap();
            }
        });
        assert_eq!(names(&db.search("news", 0, 30, 300).unwrap().channels), vec!["News", "Newsroom", "BBC News", "Sky Newsroom Extra"]);
        assert_eq!(rank("News", "news"), 0);
        assert_eq!(rank("Newsroom", "news"), 1);
        assert_eq!(rank("BBC World News", "bbc news"), 2);
        assert_eq!(rank("News BBC", "bbc news"), 3);
    }

    #[test]
    fn limit_per_section() {
        let db = build(|w| {
            for i in 1..=80 {
                w.vod(&movie(&i.to_string(), &format!("Star Film {i}"), "Films")).unwrap();
            }
            w.channel("s|1", "Star Channel", "s").unwrap();
        });
        let r = db.search("star", 0, 20, 300).unwrap();
        assert_eq!(r.movies.len(), 20);
        assert_eq!(r.channels.len(), 1);
    }

    #[test]
    fn programmes_past_now_future() {
        let db = build(|w| {
            w.programme("Evening News", "s|a", NOW - 5 * 86400, NOW - 5 * 86400 + 1800, None, None).unwrap();
            w.programme("Evening News", "s|a", NOW - 7200, NOW - 5400, None, None).unwrap();
            w.programme("Evening News", "s|b", NOW - 600, NOW + 1200, Some("Late edition"), Some("S1 E2")).unwrap();
            w.programme("Evening News", "s|c", NOW + 3600, NOW + 5400, None, None).unwrap();
            w.programme("  ", "s|c", NOW, NOW + 1, None, None).unwrap();
        });
        let hits = db.search("even", NOW - 2 * 86400, 30, 300).unwrap().programmes;
        assert_eq!(hits.len(), 3);
        assert_eq!(hits.iter().find(|h| h.ch == "s|b").unwrap().sub.as_deref(), Some("Late edition"));
        assert_eq!(db.search("late edit", NOW - 2 * 86400, 30, 300).unwrap().programmes.len(), 1);
        let chans: HashMap<&str, Channel> = [
            ("s|a", Channel::new("a", "s", "A", None, "G", "u", Some("a".into()), 1, Some(Catchup::new("shift", None)), 3)),
            ("s|b", Channel::new("b", "s", "B", None, "G", "u", Some("b".into()), 2, None, 0)),
            ("s|c", Channel::new("c", "s", "C", None, "G", "u", Some("c".into()), 3, None, 0)),
        ]
        .into();
        let g = group_programmes(&hits, |k| chans.get(k).cloned(), |c, h| classify(Some(c), h.start, h.stop, NOW), |c| c.key.clone());
        assert_eq!(g.iter().map(|x| x.0.key.as_str()).collect::<Vec<_>>(), vec!["b", "c", "a"]);
        assert_eq!(db.meta("counts").unwrap().as_deref(), Some("0,0,0,2,4"));
        assert_eq!(db.meta("built").unwrap().as_deref(), Some("1800000000"));
    }

    #[test]
    fn catalogue_pages() {
        let db = build(|w| {
            for i in 1..=25 {
                w.vod(&movie(&i.to_string(), &format!("Film {i}"), if i % 2 == 0 { "Drama" } else { "Action" })).unwrap();
            }
            w.vod(&show("1", "Show")).unwrap();
        });
        assert_eq!(db.categories(MOVIE).unwrap(), vec!["Action", "Drama"]);
        assert_eq!(db.page(MOVIE, None, 0, 10).unwrap().len(), 10);
        assert_eq!(db.page(MOVIE, None, 20, 10).unwrap().len(), 5);
        assert_eq!(db.page(MOVIE, Some("Drama"), 0, 100).unwrap().len(), 12);
        assert_eq!(db.page(MOVIE, None, 0, 1).unwrap()[0].ext, "mkv");
        assert_eq!(db.count(SERIES).unwrap(), 1);
    }

    #[test]
    fn big_index_commits_in_batches() {
        let db = build(|w| {
            for i in 0..5000 {
                w.channel(&format!("s|{i}"), &format!("Channel {i}"), "s").unwrap();
            }
        });
        assert_eq!(db.count(CHANNEL).unwrap(), 5000);
        assert_eq!(db.search("channel 4999", 0, 30, 300).unwrap().channels[0].name, "Channel 4999");
    }

    #[test]
    fn search_history() {
        let mut h: Vec<String> = Vec::new();
        h = history_add(&h, "news", HISTORY_MAX);
        h = history_add(&h, "Football", HISTORY_MAX);
        h = history_add(&h, "NEWS ", HISTORY_MAX);
        assert_eq!(h, vec!["NEWS", "Football"]);
        assert_eq!(history_add(&h, "a", HISTORY_MAX), h);
        for i in 0..30 {
            h = history_add(&h, &format!("q{i}"), HISTORY_MAX);
        }
        assert_eq!(h.len(), 20);
        assert_eq!(h[0], "q29");
        assert_eq!(history_decode(Some(&history_encode(&h))), h);
        assert_eq!(history_remove(&h, "Q29").len(), 19);
    }
}
