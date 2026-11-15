// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Channel <-> guide matching: the automatic match used while reading a guide (Android Repository.refreshEpg: the
//! XMLTV channel id, else one of its display names, equal to a playlist channel's tvg-id or name, case-insensitive),
//! and the manual mapping laid over it (GuideMap.kt / epgmap.py).

use crate::channels::norm_name;
use crate::model::Channel;
use crate::text::fold_nfkd;
use regex::Regex;
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

/// Manual mapping value meaning "no guide" (a wrong automatic match).
pub const NONE: &str = "__none";

/// `c` as the guide sees it with manual mapping `id` (None / blank: automatic).
pub fn apply(c: &Channel, id: Option<&str>) -> Channel {
    match id.map(str::trim) {
        None | Some("") => c.clone(),
        Some(NONE) => Channel { id_key: Some(format!("{}|{NONE}", c.source_id)), name_key: format!("{}|{NONE}", c.source_id), ..c.clone() },
        Some(g) => Channel { id_key: Some(format!("{}|{}", c.source_id, g.to_lowercase())), ..c.clone() },
    }
}

/// Resolves XMLTV channels of one source's guide to guide keys.
#[derive(Debug, Default)]
pub struct EpgResolver {
    source_id: String,
    wanted: HashSet<String>,
}

impl EpgResolver {
    /// `manual`: channel key -> guide channel id (or [NONE]) for this source.
    pub fn new(source_id: &str, channels: &[Channel], manual: &HashMap<String, String>) -> Self {
        let mut wanted = HashSet::new();
        for c in channels {
            if let Some(t) = &c.tvg_id {
                wanted.insert(t.to_lowercase());
            }
            wanted.insert(c.name.to_lowercase());
        }
        for g in manual.values() {
            let g = g.trim();
            if !g.is_empty() && g != NONE {
                wanted.insert(g.to_lowercase());
            }
        }
        EpgResolver { source_id: source_id.to_string(), wanted }
    }

    pub fn resolve(&self, ch: &str, names: Option<&Vec<String>>) -> Option<String> {
        let l = ch.to_lowercase();
        if self.wanted.contains(&l) {
            return Some(format!("{}|{l}", self.source_id));
        }
        names?.iter().map(|n| n.to_lowercase()).find(|n| self.wanted.contains(n)).map(|n| format!("{}|{n}", self.source_id))
    }

    /// Adapter for [crate::xmltv::parse].
    pub fn resolver(&self) -> impl FnMut(&str, Option<&Vec<String>>) -> Vec<String> + '_ {
        move |c, n| self.resolve(c, n).into_iter().collect()
    }
}

/// A channel of a playlist's guide (XMLTV `<channel id>` and its first display name).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GuideChannel {
    pub id: String,
    pub name: String,
}

static SEP: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[^a-z0-9+]+").unwrap());

fn fold(s: &str) -> String {
    SEP.replace_all(&fold_nfkd(s), " ").trim().to_string()
}

fn words(g: &GuideChannel) -> Vec<String> {
    format!("{} {}", fold(&g.name), fold(&g.id)).split(' ').filter(|w| !w.is_empty()).map(String::from).collect()
}

/// Guide channels for the picker: every word of `q` must start a word of the name or id (diacritics and punctuation
/// ignored); exact names first, then names starting with the query, then the rest in guide order. Empty `q`: the
/// channels closest to `hint` (the channel's own name) first.
pub fn search(all: &[GuideChannel], q: &str, hint: &str, limit: usize) -> Vec<GuideChannel> {
    let query = fold(q);
    if query.is_empty() {
        let h: Vec<String> = fold(&norm_name(hint)).split(' ').filter(|w| !w.is_empty()).map(String::from).collect();
        if h.is_empty() {
            return all.iter().take(limit).cloned().collect();
        }
        let is_near = |g: &GuideChannel| {
            let w = words(g);
            h.iter().all(|t| w.iter().any(|x| x.starts_with(t.as_str())))
        };
        let near: Vec<&GuideChannel> = all.iter().filter(|g| is_near(g)).collect();
        return near.iter().copied().chain(all.iter().filter(|g| !is_near(g))).take(limit).cloned().collect();
    }
    let qs: Vec<&str> = query.split(' ').collect();
    let mut hits: Vec<(&GuideChannel, u8)> = all
        .iter()
        .filter_map(|g| {
            let w = words(g);
            if !qs.iter().all(|t| w.iter().any(|x| x.starts_with(t))) {
                return None;
            }
            let n = fold(&g.name);
            Some((g, if n == query { 0 } else if n.starts_with(&query) { 1 } else { 2 }))
        })
        .collect();
    hits.sort_by_key(|h| h.1);
    hits.into_iter().take(limit).map(|h| h.0.clone()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn g(id: &str, name: &str) -> GuideChannel {
        GuideChannel { id: id.into(), name: name.into() }
    }

    #[test]
    fn apply_mapping() {
        let c = Channel::new("s|1", "s", "One", None, "G", "u", Some("one.x".into()), 1, None, 0);
        assert_eq!(apply(&c, None), c);
        assert_eq!(apply(&c, Some(" ")), c);
        assert_eq!(apply(&c, Some("BBC1.uk")).id_key.as_deref(), Some("s|bbc1.uk"));
        let n = apply(&c, Some(NONE));
        assert_eq!(n.id_key.as_deref(), Some("s|__none"));
        assert_eq!(n.name_key, "s|__none");
    }

    #[test]
    fn resolver_auto_and_manual() {
        let chans = vec![Channel::new("s|1", "s", "Channel One", None, "G", "u", Some("One.Example".into()), 1, None, 0), Channel::new("s|2", "s", "Two", None, "G", "u", None, 2, None, 0)];
        let manual: HashMap<String, String> = [("s|2".to_string(), "Other.Id".to_string()), ("s|3".to_string(), NONE.to_string())].into();
        let r = EpgResolver::new("s", &chans, &manual);
        assert_eq!(r.resolve("ONE.example", None).as_deref(), Some("s|one.example"));
        assert_eq!(r.resolve("x9", Some(&vec!["Nope".into(), "TWO".into()])).as_deref(), Some("s|two"));
        assert_eq!(r.resolve("other.id", None).as_deref(), Some("s|other.id"));
        assert_eq!(r.resolve("__none", None), None);
        assert_eq!(r.resolve("zzz", None), None);
        // the mapped channel reads the mapped guide key
        assert_eq!(apply(&chans[1], manual.get("s|2").map(String::as_str)).id_key.as_deref(), Some("s|other.id"));
    }

    #[test]
    fn picker_search() {
        let all = vec![g("bbc1.uk", "BBC One"), g("bbc2.uk", "BBC Two"), g("one", "One"), g("cnn", "CNN International"), g("tele", "Télé Monte Carlo")];
        let names = |v: Vec<GuideChannel>| v.into_iter().map(|g| g.name).collect::<Vec<_>>();
        assert_eq!(names(search(&all, "one", "", 200)), vec!["One", "BBC One"]);
        assert_eq!(names(search(&all, "bbc", "", 200)), vec!["BBC One", "BBC Two"]);
        assert_eq!(names(search(&all, "tele mon", "", 200)), vec!["Télé Monte Carlo"]);
        assert_eq!(names(search(&all, "uk", "", 200)), vec!["BBC One", "BBC Two"]);
        assert_eq!(names(search(&all, "", "BBC Two HD", 200))[0], "BBC Two");
        assert_eq!(search(&all, "", "", 2).len(), 2);
        assert!(search(&all, "zzz", "", 200).is_empty());
    }
}
