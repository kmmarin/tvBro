// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Data model shared by every parser (port of shared/.../Model.kt).

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Catch-up description. `kind` is xc, default, append, shift, flussonic...
#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Catchup {
    pub kind: String,
    pub template: Option<String>,
}

impl Catchup {
    pub fn new(kind: impl Into<String>, template: Option<String>) -> Self {
        Catchup { kind: kind.into(), template }
    }
}

/// A live channel. `id_key` / `name_key` are the guide keys; like the Kotlin data class they are computed once at
/// construction, so a renamed copy (`Channel { name, ..c }`) keeps the provider's guide match.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Channel {
    pub key: String,
    pub source_id: String,
    pub name: String,
    pub logo: Option<String>,
    pub group: String,
    pub url: String,
    pub tvg_id: Option<String>,
    pub number: i32,
    pub catchup: Option<Catchup>,
    pub catchup_days: i32,
    pub id_key: Option<String>,
    pub name_key: String,
}

impl Channel {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        key: impl Into<String>,
        source_id: impl Into<String>,
        name: impl Into<String>,
        logo: Option<String>,
        group: impl Into<String>,
        url: impl Into<String>,
        tvg_id: Option<String>,
        number: i32,
        catchup: Option<Catchup>,
        catchup_days: i32,
    ) -> Self {
        let source_id = source_id.into();
        let name = name.into();
        let id_key = tvg_id
            .as_deref()
            .filter(|t| !crate::text::kotlin_blank(t))
            .map(|t| format!("{}|{}", source_id, t.to_lowercase()));
        let name_key = format!("{}|{}", source_id, name.to_lowercase());
        Channel {
            key: key.into(),
            source_id,
            name,
            logo,
            group: group.into(),
            url: url.into(),
            tvg_id,
            number,
            catchup,
            catchup_days,
            id_key,
            name_key,
        }
    }

    /// "hls" when the stream is an HLS playlist, else "ts" (web server's `stream_type`).
    pub fn stream_type(&self) -> &'static str {
        if self.url.to_lowercase().contains(".m3u8") { "hls" } else { "ts" }
    }
}

/// One guide entry. Times are epoch seconds; `key` is the guide key (source id | lower-case channel id or name).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Programme {
    pub key: String,
    pub start: i64,
    pub stop: i64,
    pub title: String,
    pub desc: Option<String>,
    pub sub: Option<String>,
    pub ep: Option<String>,
}

impl Programme {
    pub fn new(key: impl Into<String>, start: i64, stop: i64, title: impl Into<String>, desc: Option<String>) -> Self {
        Programme { key: key.into(), start, stop, title: title.into(), desc, sub: None, ep: None }
    }
}

/// In-memory guide: key -> programmes sorted by start.
#[derive(Clone, Debug, Default)]
pub struct EpgIndex {
    map: HashMap<String, Vec<Programme>>,
}

impl EpgIndex {
    pub fn new(map: HashMap<String, Vec<Programme>>) -> Self {
        EpgIndex { map }
    }

    /// The channel's programmes: by tvg-id first, then by name.
    pub fn programmes(&self, ch: &Channel) -> &[Programme] {
        if let Some(v) = ch.id_key.as_ref().and_then(|k| self.map.get(k)) {
            return v;
        }
        self.map.get(&ch.name_key).map(|v| v.as_slice()).unwrap_or(&[])
    }

    pub fn now_next(&self, ch: &Channel, now: i64) -> (Option<&Programme>, Option<&Programme>) {
        now_next(self.programmes(ch), now)
    }
}

/// Binary search for the last programme with start <= now; returns (current, next).
pub fn now_next(a: &[Programme], now: i64) -> (Option<&Programme>, Option<&Programme>) {
    if a.is_empty() {
        return (None, None);
    }
    // index of the first programme with start > now
    let after = a.partition_point(|p| p.start <= now);
    let cur = if after > 0 && a[after - 1].stop > now { Some(&a[after - 1]) } else { None };
    (cur, a.get(after))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_survive_rename() {
        let c = Channel::new("k", "s", "One HD", None, "G", "http://example.com/1.ts", Some("One.Example".into()), 1, None, 0);
        assert_eq!(c.id_key.as_deref(), Some("s|one.example"));
        assert_eq!(c.name_key, "s|one hd");
        let r = Channel { name: "Mine".into(), ..c.clone() };
        assert_eq!(r.name_key, "s|one hd");
        let blank = Channel::new("k", "s", "X", None, "G", "u", Some("  ".into()), 1, None, 0);
        assert!(blank.id_key.is_none());
    }

    #[test]
    fn now_next_edges() {
        let a = vec![Programme::new("x|a", 0, 100, "p1", None), Programme::new("x|a", 100, 200, "p2", None)];
        let (c, n) = now_next(&a, 150);
        assert_eq!(c.unwrap().title, "p2");
        assert!(n.is_none());
        assert_eq!(now_next(&a, 50).0.unwrap().title, "p1");
        assert_eq!(now_next(&a, 50).1.unwrap().title, "p2");
        let (c, n) = now_next(&a, -5);
        assert!(c.is_none());
        assert_eq!(n.unwrap().title, "p1");
        assert_eq!(now_next(&a, 200).0, None);
        assert_eq!(now_next(&[], 1), (None, None));
    }
}
