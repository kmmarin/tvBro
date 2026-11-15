// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! On-disk guide store (port of the Android EpgDb). Programmes are streamed in batches into a new "generation"
//! that becomes visible only when the refresh is committed (atomic swap), so a failed refresh keeps the old data.
//! Queries are small and indexed by (source, generation, channel key, start); nothing holds the guide in memory.
//!
//! The schema and `user_version` (2) are the ones Android's SQLiteOpenHelper created, so the app can keep its
//! existing `epg.db` when it switches to the core.
//!
//! Write-ahead log discipline (EpgDb.kt, 2026-11-12): old generations are deleted 5000 rows per transaction with
//! checkpoints in between, and the log is folded back and truncated (`wal_checkpoint(TRUNCATE)`) after every step
//! that changes much and while inserting once it passes 32 MB. One transaction deleting a whole generation (440,000
//! programmes) wrote 1.6 GB of log on a Fire TV before it could commit; an unchecked log reached 1.7 GB.

use crate::model::Programme;
use rusqlite::{Connection, OptionalExtension, ToSql, params};
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Meta {
    pub generation: i64,
    pub min_start: i64,
    pub max_stop: i64,
    pub updated: i64,
}

#[derive(Debug)]
pub struct EpgStore {
    pub conn: Connection,
    wal: Option<PathBuf>,
    batches: u64,
    /// Largest write-ahead log size seen (bytes) since [EpgStore::reset_wal_peak].
    pub wal_peak: u64,
    /// Checkpoints that could not finish because a reader was busy.
    pub checkpoint_busy: u64,
}

/// One current programme without its description (the search index rebuild), with its row id for paging.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CurrentRow {
    pub rowid: i64,
    pub ch: String,
    pub start: i64,
    pub stop: i64,
    pub title: String,
    pub sub: Option<String>,
    pub ep: Option<String>,
}

const VERSION: i32 = 2;
/// Rows per delete transaction (EpgDb.deleteChunked).
pub const DELETE_CHUNK: i64 = 5000;
/// Log size past which inserts and deletes checkpoint (EpgDb.WAL_MAX).
pub const WAL_MAX: u64 = 32 << 20;
/// Longest programme [EpgStore::load_many] finds when it started before the queried range (2 days, seconds).
pub const MAX_PROGRAMME: i64 = 2 * 86400;

impl EpgStore {
    /// Opens (or creates) the store for writing and folds back any log left over (EpgDb.open).
    pub fn open(path: &Path) -> crate::Result<Self> {
        let conn = Connection::open(path)?;
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        // Android's default for WAL databases; a crash can lose the last commits, never corrupt the file
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        let mut s = Self::init(conn, Some(wal_path(path)))?;
        s.checkpoint(true)?;
        Ok(s)
    }

    /// A second connection for queries while the writer is busy (same SQLite library, so WAL locking holds).
    pub fn open_reader(path: &Path) -> crate::Result<Self> {
        let conn = Connection::open(path)?;
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        Self::init(conn, Some(wal_path(path)))
    }

    pub fn in_memory() -> crate::Result<Self> {
        Self::init(Connection::open_in_memory()?, None)
    }

    fn init(conn: Connection, wal: Option<PathBuf>) -> crate::Result<Self> {
        let v: i32 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        if v == 0 {
            conn.execute_batch(
                "CREATE TABLE IF NOT EXISTS prog(src TEXT NOT NULL, gen INTEGER NOT NULL, ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT, desc TEXT, sub TEXT, ep TEXT);
                 CREATE UNIQUE INDEX IF NOT EXISTS prog_u ON prog(src, gen, ch, start);
                 CREATE INDEX IF NOT EXISTS prog_t ON prog(src, gen, stop);
                 CREATE TABLE IF NOT EXISTS meta(src TEXT PRIMARY KEY, gen INTEGER, minStart INTEGER, maxStop INTEGER, updated INTEGER);",
            )?;
        } else if v < 2 {
            conn.execute_batch("ALTER TABLE prog ADD COLUMN sub TEXT; ALTER TABLE prog ADD COLUMN ep TEXT;")?;
        }
        if v != VERSION {
            conn.pragma_update(None, "user_version", VERSION)?;
        }
        Ok(EpgStore { conn, wal, batches: 0, wal_peak: 0, checkpoint_busy: 0 })
    }

    /// Current write-ahead log size in bytes (0 in memory or when there is none).
    pub fn wal_len(&self) -> u64 {
        self.wal.as_ref().and_then(|p| std::fs::metadata(p).ok()).map(|m| m.len()).unwrap_or(0)
    }

    fn note_wal(&mut self) -> u64 {
        let n = self.wal_len();
        self.wal_peak = self.wal_peak.max(n);
        n
    }

    pub fn reset_wal_peak(&mut self) {
        self.wal_peak = self.wal_len();
        self.checkpoint_busy = 0;
    }

    /// Folds the log back into the database and truncates it; unless `force`, only once it passes [WAL_MAX].
    /// Returns false when a reader kept it from finishing (the next call tries again).
    pub fn checkpoint(&mut self, force: bool) -> crate::Result<bool> {
        if !force && self.note_wal() < WAL_MAX {
            return Ok(true);
        }
        self.note_wal();
        let busy: i64 = self.conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| r.get(0))?;
        if busy != 0 {
            self.checkpoint_busy += 1;
        }
        Ok(busy == 0)
    }

    /// Deletes matching programmes [DELETE_CHUNK] rows per transaction, checkpointing every 10 chunks and at the end.
    fn delete_chunked(&mut self, where_sql: &str, args: &[&dyn ToSql]) -> crate::Result<()> {
        let sql = format!("DELETE FROM prog WHERE rowid IN (SELECT rowid FROM prog WHERE {where_sql} LIMIT {DELETE_CHUNK})");
        let mut n = 0u64;
        loop {
            let del = self.conn.execute(&sql, args)?;
            if del == 0 {
                break;
            }
            n += 1;
            if n.is_multiple_of(10) {
                self.checkpoint(false)?;
            } else {
                self.note_wal();
            }
        }
        self.checkpoint(true)?;
        Ok(())
    }

    pub fn meta(&self, src: &str) -> crate::Result<Option<Meta>> {
        Ok(self
            .conn
            .query_row("SELECT gen,minStart,maxStop,updated FROM meta WHERE src=?1", [src], |r| Ok(Meta { generation: r.get(0)?, min_start: r.get(1)?, max_stop: r.get(2)?, updated: r.get(3)? }))
            .optional()?)
    }

    /// Starts a refresh: drops leftovers of failed runs and returns the new generation number.
    pub fn begin_refresh(&mut self, src: &str) -> crate::Result<i64> {
        let cur = self.meta(src)?.map(|m| m.generation).unwrap_or(0);
        self.delete_chunked("src=?1 AND gen<>?2", &[&src, &cur])?;
        Ok(cur + 1)
    }

    pub fn insert_batch(&mut self, src: &str, generation: i64, rows: &[Programme]) -> crate::Result<()> {
        let tx = self.conn.transaction()?;
        {
            let mut st = tx.prepare_cached("INSERT OR IGNORE INTO prog(src,gen,ch,start,stop,title,desc,sub,ep) VALUES(?,?,?,?,?,?,?,?,?)")?;
            for p in rows {
                st.execute(params![src, generation, p.key, p.start, p.stop, p.title, p.desc, p.sub, p.ep])?;
            }
        }
        tx.commit()?;
        self.batches += 1;
        if self.batches.is_multiple_of(40) {
            self.checkpoint(false)?;
        } else {
            self.note_wal();
        }
        Ok(())
    }

    /// Makes generation `gen` the visible one (atomic: readers follow meta.gen), then drops every other in small
    /// steps. `updated` is epoch milliseconds.
    pub fn commit(&mut self, src: &str, generation: i64, min_start: i64, max_stop: i64, updated: i64) -> crate::Result<()> {
        self.conn.execute("INSERT OR REPLACE INTO meta(src,gen,minStart,maxStop,updated) VALUES(?,?,?,?,?)", params![src, generation, min_start, max_stop, updated])?;
        self.delete_chunked("src=?1 AND gen<>?2", &[&src, &generation])
    }

    pub fn discard(&mut self, src: &str, generation: i64) -> crate::Result<()> {
        self.delete_chunked("src=?1 AND gen=?2", &[&src, &generation])
    }

    pub fn delete_source(&mut self, src: &str) -> crate::Result<()> {
        self.conn.execute("DELETE FROM meta WHERE src=?1", [src])?;
        self.delete_chunked("src=?1", &[&src])
    }

    /// Programmes of guide key `key` overlapping [from, to), by start.
    pub fn load(&self, src: &str, key: &str, from: i64, to: i64) -> crate::Result<Vec<Programme>> {
        let mut st = self.conn.prepare_cached("SELECT start,stop,title,desc,sub,ep FROM prog WHERE src=?1 AND gen=(SELECT gen FROM meta WHERE src=?1) AND ch=?2 AND stop>?3 AND start<?4 ORDER BY start")?;
        let rows = st.query_map(params![src, key, from, to], |r| {
            Ok(Programme { key: key.to_string(), start: r.get(0)?, stop: r.get(1)?, title: r.get::<_, Option<String>>(2)?.unwrap_or_default(), desc: r.get(3)?, sub: r.get(4)?, ep: r.get(5)? })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Programmes of several guide keys of one source overlapping [from, to), by key then start: one query for a
    /// whole screen of guide rows (the TV screen loads its visible rows per 3-hour chunk). Only programmes that
    /// start at most [MAX_PROGRAMME] before `from` are found, so the index range stays short.
    pub fn load_many(&self, src: &str, keys: &[String], from: i64, to: i64) -> crate::Result<Vec<Programme>> {
        if keys.is_empty() {
            return Ok(Vec::new());
        }
        let marks = vec!["?"; keys.len()].join(",");
        let sql = format!(
            "SELECT ch,start,stop,title,desc,sub,ep FROM prog WHERE src=? AND gen=(SELECT gen FROM meta WHERE src=?) AND ch IN ({marks}) AND start>=? AND start<? AND +stop>? ORDER BY ch,start"
        );
        let mut st = self.conn.prepare_cached(&sql)?;
        let lo = from - MAX_PROGRAMME;
        let mut args: Vec<&dyn ToSql> = Vec::with_capacity(keys.len() + 5);
        args.push(&src);
        args.push(&src);
        for k in keys {
            args.push(k);
        }
        args.push(&lo);
        args.push(&to);
        args.push(&from);
        let rows = st.query_map(args.as_slice(), |r| {
            Ok(Programme { key: r.get(0)?, start: r.get(1)?, stop: r.get(2)?, title: r.get::<_, Option<String>>(3)?.unwrap_or_default(), desc: r.get(4)?, sub: r.get(5)?, ep: r.get(6)? })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// The channel's programmes by tvg-id key first, then by name key (Android DbEpg), shifted by `shift_sec`.
    pub fn load_channel(&self, id_key: Option<&str>, name_key: &str, from: i64, to: i64, shift_sec: i64) -> crate::Result<Vec<Programme>> {
        let src = |k: &str| k.split('|').next().unwrap_or("").to_string();
        let mut r = match id_key {
            Some(k) => self.load(&src(k), k, from - shift_sec, to - shift_sec)?,
            None => Vec::new(),
        };
        if r.is_empty() {
            r = self.load(&src(name_key), name_key, from - shift_sec, to - shift_sec)?;
        }
        if shift_sec != 0 {
            for p in &mut r {
                p.start += shift_sec;
                p.stop += shift_sec;
            }
        }
        Ok(r)
    }

    /// Title search (LIKE, case-insensitive for ASCII) over current and upcoming programmes of all sources.
    pub fn search(&self, q: &str, now: i64, limit: i64) -> crate::Result<Vec<Programme>> {
        let like = format!("%{}%", q.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_"));
        let mut st = self.conn.prepare_cached("SELECT p.ch,p.start,p.stop,p.title,p.desc FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen WHERE p.stop>?1 AND p.title LIKE ?2 ESCAPE '\\' ORDER BY p.start LIMIT ?3")?;
        let rows = st.query_map(params![now, like, limit], |r| Ok(Programme { key: r.get(0)?, start: r.get(1)?, stop: r.get(2)?, title: r.get::<_, Option<String>>(3)?.unwrap_or_default(), desc: r.get(4)?, sub: None, ep: None }))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Every current programme (all sources) without descriptions, for the search index.
    pub fn for_each_current(&self, mut sink: impl FnMut(&str, i64, i64, &str, Option<&str>, Option<&str>)) -> crate::Result<()> {
        let mut st = self.conn.prepare("SELECT p.ch,p.start,p.stop,p.title,p.sub,p.ep FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen")?;
        let mut rows = st.query([])?;
        while let Some(r) = rows.next()? {
            let ch: String = r.get(0)?;
            let title: Option<String> = r.get(3)?;
            let sub: Option<String> = r.get(4)?;
            let ep: Option<String> = r.get(5)?;
            sink(&ch, r.get(1)?, r.get(2)?, title.as_deref().unwrap_or(""), sub.as_deref(), ep.as_deref());
        }
        Ok(())
    }

    /// [EpgStore::for_each_current] in pages by row id (callers across an FFI boundary never hold the whole guide).
    pub fn current_page(&self, after_rowid: i64, limit: i64) -> crate::Result<Vec<CurrentRow>> {
        let mut st = self.conn.prepare_cached("SELECT p.rowid,p.ch,p.start,p.stop,p.title,p.sub,p.ep FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen WHERE p.rowid>?1 ORDER BY p.rowid LIMIT ?2")?;
        let rows = st.query_map(params![after_rowid, limit], |r| {
            Ok(CurrentRow { rowid: r.get(0)?, ch: r.get(1)?, start: r.get(2)?, stop: r.get(3)?, title: r.get::<_, Option<String>>(4)?.unwrap_or_default(), sub: r.get(5)?, ep: r.get(6)? })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Description of one programme.
    pub fn desc(&self, ch: &str, start: i64) -> crate::Result<Option<String>> {
        let src = ch.split('|').next().unwrap_or("");
        Ok(self
            .conn
            .query_row("SELECT p.desc FROM prog p JOIN meta m ON m.src=p.src AND m.gen=p.gen WHERE p.src=?1 AND p.ch=?2 AND p.start=?3 LIMIT 1", params![src, ch, start], |r| r.get(0))
            .optional()?
            .flatten())
    }

    pub fn count(&self, src: &str) -> crate::Result<i64> {
        Ok(self.conn.query_row("SELECT COUNT(*) FROM prog WHERE src=?1 AND gen=(SELECT gen FROM meta WHERE src=?1)", [src], |r| r.get(0))?)
    }

    /// Order-independent digest of the visible rows of `src` (comparison with the Kotlin store, see [crate::epgload]).
    pub fn digest(&self, src: &str) -> crate::Result<crate::epgload::Digest> {
        let mut d = crate::epgload::Digest::default();
        let mut st = self.conn.prepare("SELECT ch,start,stop,title,desc,sub,ep FROM prog WHERE src=?1 AND gen=(SELECT gen FROM meta WHERE src=?1)")?;
        let mut rows = st.query([src])?;
        while let Some(r) = rows.next()? {
            let p = Programme { key: r.get(0)?, start: r.get(1)?, stop: r.get(2)?, title: r.get::<_, Option<String>>(3)?.unwrap_or_default(), desc: r.get(4)?, sub: r.get(5)?, ep: r.get(6)? };
            d.add(&p);
        }
        Ok(d)
    }
}

fn wal_path(db: &Path) -> PathBuf {
    let mut s = db.as_os_str().to_os_string();
    s.push("-wal");
    PathBuf::from(s)
}

/// Keep-old-data rule (SyncRules): an empty guide never replaces a stored one.
pub fn accept(total: u64) -> bool {
    total > 0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(k: &str, s: i64, e: i64, t: &str) -> Programme {
        Programme { key: k.into(), start: s, stop: e, title: t.into(), desc: Some(format!("about {t}")), sub: None, ep: Some("S1 E1".into()) }
    }

    fn tmp(name: &str) -> PathBuf {
        // the system temp directory (SQLite's small writes can be very slow on the build tree's drive), unique per run
        let dir = std::env::temp_dir().join("tvbro-core-test");
        std::fs::create_dir_all(&dir).unwrap();
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        dir.join(format!("{name}-{}-{nanos}.db", std::process::id()))
    }

    #[test]
    fn generations_swap_atomically() {
        let mut db = EpgStore::in_memory().unwrap();
        let g1 = db.begin_refresh("s").unwrap();
        assert_eq!(g1, 1);
        db.insert_batch("s", g1, &[p("s|a", 0, 100, "A1"), p("s|a", 100, 200, "A2"), p("s|a", 100, 200, "dup")]).unwrap();
        // not visible before commit
        assert!(db.load("s", "s|a", 0, 1000).unwrap().is_empty());
        db.commit("s", g1, 0, 200, 1).unwrap();
        let l = db.load("s", "s|a", 0, 1000).unwrap();
        assert_eq!(l.iter().map(|x| x.title.as_str()).collect::<Vec<_>>(), vec!["A1", "A2"]);
        assert_eq!(l[0].ep.as_deref(), Some("S1 E1"));
        // a failed refresh keeps the old data
        let g2 = db.begin_refresh("s").unwrap();
        db.insert_batch("s", g2, &[p("s|a", 0, 100, "new")]).unwrap();
        db.discard("s", g2).unwrap();
        assert_eq!(db.load("s", "s|a", 0, 1000).unwrap()[0].title, "A1");
        let g3 = db.begin_refresh("s").unwrap();
        db.insert_batch("s", g3, &[p("s|a", 0, 100, "B1")]).unwrap();
        db.commit("s", g3, 0, 100, 2).unwrap();
        assert_eq!(db.count("s").unwrap(), 1);
        assert_eq!(db.meta("s").unwrap().unwrap().generation, g3);
        assert_eq!(db.desc("s|a", 0).unwrap().as_deref(), Some("about B1"));
        assert_eq!(db.search("b1", 50, 10).unwrap().len(), 1);
        assert_eq!(db.search("%", 50, 10).unwrap().len(), 0);
        let mut n = 0;
        db.for_each_current(|_, _, _, _, _, _| n += 1).unwrap();
        assert_eq!(n, 1);
        let page = db.current_page(0, 10).unwrap();
        assert_eq!((page.len(), page[0].title.as_str()), (1, "B1"));
        assert!(db.current_page(page[0].rowid, 10).unwrap().is_empty());
        db.delete_source("s").unwrap();
        assert!(db.meta("s").unwrap().is_none());
        assert!(!accept(0) && accept(1));
    }

    #[test]
    fn channel_lookup_and_shift() {
        let mut db = EpgStore::in_memory().unwrap();
        let g = db.begin_refresh("s").unwrap();
        db.insert_batch("s", g, &[p("s|name", 1000, 2000, "ByName")]).unwrap();
        db.commit("s", g, 1000, 2000, 0).unwrap();
        let l = db.load_channel(Some("s|missing.id"), "s|name", 0, 5000, 3600).unwrap();
        assert_eq!(l.len(), 1);
        assert_eq!(l[0].start, 4600);
    }

    #[test]
    fn load_many_matches_load_per_key() {
        let mut db = EpgStore::in_memory().unwrap();
        let g = db.begin_refresh("s").unwrap();
        let mut rows = Vec::new();
        for c in 0..5 {
            for h in 0..10 {
                rows.push(p(&format!("s|c{c}"), h * 3600, h * 3600 + 3600, &format!("c{c}h{h}")));
            }
        }
        rows.push(p("s|long", -3 * 86400, 20 * 3600, "too long")); // started more than 2 days before the range
        rows.push(p("s|day", -86400, 20 * 3600, "one day"));
        db.insert_batch("s", g, &rows).unwrap();
        db.commit("s", g, 0, 36000, 0).unwrap();
        let keys: Vec<String> = ["s|c0", "s|c3", "s|missing", "s|day", "s|long"].iter().map(|k| k.to_string()).collect();
        let (from, to) = (3 * 3600 + 600, 6 * 3600);
        let got = db.load_many("s", &keys, from, to).unwrap();
        for k in ["s|c0", "s|c3", "s|day"] {
            let one: Vec<_> = got.iter().filter(|x| x.key == k).cloned().collect();
            assert_eq!(one, db.load("s", k, from, to).unwrap(), "{k}");
        }
        assert_eq!(got.iter().filter(|x| x.key == "s|c0").count(), 3); // 3:00-4:00 (overlaps), 4:00, 5:00
        assert!(got.iter().all(|x| x.key != "s|missing" && x.key != "s|long"));
        assert!(db.load_many("s", &[], from, to).unwrap().is_empty());
        assert!(db.load_many("other", &keys, from, to).unwrap().is_empty());
        // the plan walks the channel index (prog_u), never the stop index (prog_t): on a Fire TV with 440,000
        // programmes the stop index made one 11-row query take 1-3 s (2026-11-13)
        db.conn.execute_batch("ANALYZE").unwrap();
        let mut st = db.conn.prepare("EXPLAIN QUERY PLAN SELECT ch,start,stop,title,desc,sub,ep FROM prog WHERE src=? AND gen=(SELECT gen FROM meta WHERE src=?) AND ch IN (?,?) AND start>=? AND start<? AND +stop>? ORDER BY ch,start").unwrap();
        let plan: Vec<String> = st.query_map(params!["s", "s", "s|c0", "s|c1", 0, 1, 0], |r| r.get::<_, String>(3)).unwrap().map(|r| r.unwrap()).collect();
        assert!(plan.iter().any(|l| l.contains("prog_u")) && !plan.iter().any(|l| l.contains("prog_t")), "{plan:?}");
    }

    #[test]
    fn reopens_existing_file() {
        let path = tmp("epg");
        {
            let mut db = EpgStore::open(&path).unwrap();
            let g = db.begin_refresh("s").unwrap();
            db.insert_batch("s", g, &[p("s|a", 0, 10, "x")]).unwrap();
            db.commit("s", g, 0, 10, 0).unwrap();
        }
        let db = EpgStore::open(&path).unwrap();
        assert_eq!(db.count("s").unwrap(), 1);
        let v: i32 = db.conn.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap();
        assert_eq!(v, 2);
        let r = EpgStore::open_reader(&path).unwrap();
        assert_eq!(r.count("s").unwrap(), 1);
        drop((db, r));
        remove(&path);
    }

    fn remove(path: &Path) {
        for x in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{x}", path.display()));
        }
    }

    /// 60,000 rows replaced twice: deletes run in 5000-row chunks with checkpoints, and the log ends truncated.
    #[test]
    fn chunked_delete_keeps_the_log_small() {
        let path = tmp("epg-chunk");
        let mut db = EpgStore::open(&path).unwrap();
        let rows: Vec<Programme> = (0..60_000).map(|i| p(&format!("s|c{}", i % 300), i64::from(i) * 60, i64::from(i) * 60 + 60, "t")).collect();
        for round in 0..2 {
            let g = db.begin_refresh("s").unwrap();
            for b in rows.chunks(500) {
                db.insert_batch("s", g, b).unwrap();
            }
            db.commit("s", g, 0, 1, round).unwrap();
            assert_eq!(db.count("s").unwrap(), 60_000);
            assert_eq!(db.wal_len(), 0, "log truncated after the commit's chunked delete");
        }
        let all: i64 = db.conn.query_row("SELECT COUNT(*) FROM prog", [], |r| r.get(0)).unwrap();
        assert_eq!(all, 60_000, "the old generation is gone");
        assert!(db.wal_peak > 0 && db.wal_peak < 64 << 20, "{}", db.wal_peak);
        let d = db.digest("s").unwrap();
        assert_eq!(d.rows, 60_000);
        db.delete_source("s").unwrap();
        let all: i64 = db.conn.query_row("SELECT COUNT(*) FROM prog", [], |r| r.get(0)).unwrap();
        assert_eq!(all, 0);
        drop(db);
        remove(&path);
    }
}
