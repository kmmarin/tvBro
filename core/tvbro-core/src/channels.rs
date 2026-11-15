// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Channel and group organisation: default group order (PPV last), the user's hide / rename / order / own groups
//! laid over the provider's list (Android AppModel + GroupOrder.kt, desktop ChannelView.kt, web layout.py), and
//! alternate-source matching (Alternates.kt / alternates.py).
//!
//! Provider data is never changed: the user's edits are kept separately ([Prefs]) and applied on every sync.

use crate::model::Channel;
use crate::text::fold_nfkd;
use indexmap::IndexMap;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

pub const GROUP_ALL: &str = "All channels";
/// The Android label; the desktop app shows the same group as "My list".
pub const GROUP_FAV: &str = "Favourites";

// ------------------------------------------------------------------ GroupOrder

static PPV: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\bppv\b").unwrap());

/// Pay-per-view groups ("PPV Events | 1", "UK: PPV"): shown last by default.
pub fn is_ppv(group: &str) -> bool {
    PPV.is_match(group)
}

/// Groups in display order: the user's saved order first (kept as is, groups the provider no longer has dropped),
/// then the rest in the provider's order with PPV groups moved to the bottom.
pub fn group_order(provider: &[String], saved: &[String]) -> Vec<String> {
    let p: HashSet<&str> = provider.iter().map(String::as_str).collect();
    let s: HashSet<&str> = saved.iter().map(String::as_str).collect();
    let rest: Vec<&String> = provider.iter().filter(|g| !s.contains(g.as_str())).collect();
    saved
        .iter()
        .filter(|g| p.contains(g.as_str()))
        .chain(rest.iter().copied().filter(|g| !is_ppv(g)))
        .chain(rest.iter().copied().filter(|g| is_ppv(g)))
        .cloned()
        .collect()
}

/// `l` with `key` moved to the top (d < 0) or the bottom (d > 0); unchanged when absent.
pub fn send_to<T: PartialEq + Clone>(l: &[T], key: &T, d: i32) -> Vec<T> {
    if !l.contains(key) {
        return l.to_vec();
    }
    let mut r: Vec<T> = l.iter().filter(|x| *x != key).cloned().collect();
    if d < 0 {
        r.insert(0, key.clone());
    } else {
        r.push(key.clone());
    }
    r
}

/// Swaps `key` with its neighbour in direction `d` (-1 up, +1 down); unchanged at either end or when absent.
pub fn swap_move(list: &[String], key: &str, d: i32) -> Vec<String> {
    let mut l = list.to_vec();
    let Some(i) = l.iter().position(|x| x == key) else { return l };
    let j = i as i64 + d as i64;
    if j < 0 || j >= l.len() as i64 {
        return l;
    }
    l.swap(i, j as usize);
    l
}

/// Web layout: ids in the saved order; ids the order does not know keep their own order after them.
pub fn ordered(ids: &[String], order: &[String]) -> Vec<String> {
    let pos: HashMap<&str, usize> = order.iter().enumerate().map(|(i, k)| (k.as_str(), i)).collect();
    let mut known: Vec<&String> = ids.iter().filter(|k| pos.contains_key(k.as_str())).collect();
    known.sort_by_key(|k| pos[k.as_str()]);
    known.into_iter().chain(ids.iter().filter(|k| !pos.contains_key(k.as_str()))).cloned().collect()
}

/// Web layout: ids with `item` moved by `step` places (clamped), or None when it is not there.
pub fn moved(ids: &[String], item: &str, step: i64) -> Option<Vec<String>> {
    let i = ids.iter().position(|x| x == item)?;
    let mut out = ids.to_vec();
    let v = out.remove(i);
    let j = (i as i64 + step).clamp(0, ids.len() as i64 - 1) as usize;
    out.insert(j, v);
    Some(out)
}

/// A user-given name: whitespace collapsed, at most 60 characters.
pub fn clean_name(v: &str) -> String {
    crate::text::take_chars(&crate::text::collapse_ws(v), 60)
}

// ------------------------------------------------------------------ the user's organisation

/// User organisation of channels and groups (Prefs.kt; JSON field names are the Kotlin ones so stored files load).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Prefs {
    pub hidden_ch: HashSet<String>,
    pub names: HashMap<String, String>,
    pub order: Vec<String>,
    pub hidden_groups: HashSet<String>,
    pub group_names: HashMap<String, String>,
    pub group_order: Vec<String>,
    /// Own groups in creation order: name -> channel keys.
    pub custom: IndexMap<String, Vec<String>>,
    pub locked: HashSet<String>,
    pub pin_hash: String,
}

/// Visible channels with the user's names, in the user's order. Without an order PPV channels go last; with one,
/// ordered channels first, then the rest (non-PPV before PPV), provider order within (stable).
pub fn shown(channels: &[Channel], p: &Prefs, unlocked: &HashSet<String>) -> Vec<Channel> {
    let pos: HashMap<&str, usize> = p.order.iter().enumerate().map(|(i, k)| (k.as_str(), i)).collect();
    let mut l: Vec<Channel> = channels
        .iter()
        .filter(|c| !p.hidden_ch.contains(&c.key) && !p.hidden_groups.contains(&c.group) && (!p.locked.contains(&c.group) || unlocked.contains(&c.group)))
        .map(|c| match p.names.get(&c.key) {
            Some(n) => Channel { name: n.clone(), ..c.clone() },
            None => c.clone(),
        })
        .collect();
    if pos.is_empty() {
        l.sort_by_key(|c| is_ppv(&c.group));
    } else {
        l.sort_by_key(|c| pos.get(c.key.as_str()).copied().unwrap_or(if is_ppv(&c.group) { usize::MAX } else { usize::MAX - 1 }));
    }
    l
}

/// Group list: Favourites and All channels always first; then own groups, then provider groups (user order, the rest
/// in provider order with PPV last).
pub fn groups(channels: &[Channel], p: &Prefs) -> Vec<String> {
    let mut seen = HashSet::new();
    let g: Vec<String> = channels.iter().filter(|c| !p.hidden_groups.contains(&c.group)).filter(|c| seen.insert(c.group.as_str())).map(|c| c.group.clone()).collect();
    let mut out = vec![GROUP_FAV.to_string(), GROUP_ALL.to_string()];
    out.extend(p.custom.keys().cloned());
    out.extend(group_order(&g, &p.group_order));
    out
}

/// Provider groups in their current order, hidden ones included (the editor's list).
pub fn provider_groups(channels: &[Channel], p: &Prefs) -> Vec<String> {
    let mut seen = HashSet::new();
    let g: Vec<String> = channels.iter().filter(|c| seen.insert(c.group.as_str())).map(|c| c.group.clone()).collect();
    group_order(&g, &p.group_order)
}

/// What a group is called on screen.
pub fn group_label(g: &str, p: &Prefs) -> String {
    let base = p.group_names.get(g).map(String::as_str).unwrap_or(g);
    if p.locked.contains(g) { format!("{base} (locked)") } else { base.to_string() }
}

pub const NUM_PROVIDER: i32 = 0;
pub const NUM_PER_GROUP: i32 = 1;

/// Channels of `group`; with [NUM_PER_GROUP] they are numbered 1, 2, 3 in the group.
pub fn filter_group(shown: &[Channel], group: &str, p: &Prefs, favourites: &HashSet<String>, number_mode: i32) -> Vec<Channel> {
    let l: Vec<Channel> = if group == GROUP_ALL {
        shown.to_vec()
    } else if group == GROUP_FAV {
        shown.iter().filter(|c| favourites.contains(&c.key)).cloned().collect()
    } else if let Some(ks) = p.custom.get(group) {
        let by: HashMap<&str, &Channel> = shown.iter().map(|c| (c.key.as_str(), c)).collect();
        ks.iter().filter_map(|k| by.get(k.as_str()).map(|c| (*c).clone())).collect()
    } else {
        shown.iter().filter(|c| c.group == group).cloned().collect()
    };
    if number_mode == NUM_PER_GROUP { l.into_iter().enumerate().map(|(i, c)| Channel { number: i as i32 + 1, ..c }).collect() } else { l }
}

/// New channel order after moving `key` within the visible list (keys not yet ordered are appended).
pub fn move_channel(order: &[String], shown_keys: &[String], key: &str, d: i32) -> Vec<String> {
    let have: HashSet<&str> = order.iter().map(String::as_str).collect();
    let mut all = order.to_vec();
    all.extend(shown_keys.iter().filter(|k| !have.contains(k.as_str())).cloned());
    swap_move(&all, key, d)
}

/// New group order after moving provider group `g` among the plain (not special, not own) groups.
pub fn move_group(groups: &[String], p: &Prefs, g: &str, d: i32) -> Vec<String> {
    let plain: Vec<String> = groups.iter().filter(|x| x.as_str() != GROUP_ALL && x.as_str() != GROUP_FAV && !p.custom.contains_key(x.as_str())).cloned().collect();
    swap_move(&plain, g, d)
}

/// The channel a typed number selects.
pub fn by_number(list: &[Channel], n: i32) -> Option<&Channel> {
    list.iter().find(|c| c.number == n)
}

// ------------------------------------------------------------------ alternates

/// Words that tell copies of one channel apart without changing what it shows.
pub const QUALITY: &[&str] = &[
    "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "avc", "1080", "1080p", "1080i", "720", "720p", "576p", "2160p", "50fps", "60fps", "25fps", "fps", "backup", "bkp",
    "alt", "alternative", "raw", "source", "server", "feed", "link", "mirror", "low", "high", "fullhd", "ultra",
];
pub const MAX_ALTS: usize = 6;

static TOKEN: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\+?[a-z0-9]+").unwrap());
static BRACKET: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[(\[{]([^)\]}]*)[)\]}]").unwrap());

fn quality(t: &str) -> bool {
    QUALITY.contains(&t)
}

fn lone(t: &str) -> bool {
    t.len() == 1 && t.as_bytes()[0].is_ascii_digit()
}

/// The comparable form of a channel name ("" when nothing is left to compare). Quality / backup words and their
/// brackets go; country prefixes and "+1" stay, so "UK: Fox" never stands in for "US: Fox".
pub fn norm_name(name: &str) -> String {
    let f = fold_nfkd(name);
    let s = BRACKET.replace_all(&f, |m: &regex::Captures| {
        let inner: Vec<&str> = TOKEN.find_iter(&m[1]).map(|t| t.as_str()).collect();
        if !inner.is_empty() && inner.iter().all(|t| quality(t) || lone(t)) { " ".to_string() } else { format!(" {} ", &m[1]) }
    });
    let raw: Vec<&str> = TOKEN.find_iter(&s).map(|t| t.as_str()).collect();
    let mut toks: Vec<&str> = raw.iter().copied().filter(|t| !quality(t)).collect();
    // a trailing lone digit after a quality word ("HD 2", "Backup 1") numbers copies of the same channel
    let ends_with_quality = raw.len() >= 2 && quality(raw[raw.len() - 2]);
    while toks.len() > 1 && lone(toks[toks.len() - 1]) && ends_with_quality {
        toks.pop();
    }
    toks.join(" ")
}

pub fn norm_id(tvg_id: Option<&str>) -> String {
    tvg_id.unwrap_or("").trim().to_lowercase()
}

/// name / tvg-id -> channels, over every enabled playlist (build again when the channel list changes).
#[derive(Debug, Default)]
pub struct AltIndex {
    chans: Vec<Channel>,
    by_key: HashMap<String, usize>,
    by_id: HashMap<String, Vec<usize>>,
    by_name: HashMap<String, Vec<usize>>,
}

impl AltIndex {
    pub fn new(channels: Vec<Channel>) -> Self {
        let mut ix = AltIndex { chans: channels, ..Default::default() };
        for (i, c) in ix.chans.iter().enumerate() {
            ix.by_key.insert(c.key.clone(), i);
            let id = norm_id(c.tvg_id.as_deref());
            if !id.is_empty() {
                ix.by_id.entry(id).or_default().push(i);
            }
            let n = norm_name(&c.name);
            if !n.is_empty() {
                ix.by_name.entry(n).or_default().push(i);
            }
        }
        ix
    }

    /// Other channels showing the same thing as `key`: the same tvg-id first, then the same name; the channel's own
    /// playlist before the others. `usable` leaves out hidden / locked ones.
    pub fn alternates(&self, key: &str, usable: impl Fn(&Channel) -> bool, limit: usize) -> Vec<&Channel> {
        let Some(&ci) = self.by_key.get(key) else { return Vec::new() };
        let c = &self.chans[ci];
        let id = norm_id(c.tvg_id.as_deref());
        let name = norm_name(&c.name);
        let empty = Vec::new();
        let buckets = [if id.is_empty() { &empty } else { self.by_id.get(&id).unwrap_or(&empty) }, if name.is_empty() { &empty } else { self.by_name.get(&name).unwrap_or(&empty) }];
        let mut out: Vec<&Channel> = Vec::new();
        for b in buckets {
            let same = b.iter().filter(|&&i| self.chans[i].source_id == c.source_id);
            let other = b.iter().filter(|&&i| self.chans[i].source_id != c.source_id);
            for &i in same.chain(other) {
                let k = &self.chans[i];
                if k.key != key && !out.iter().any(|o| o.key == k.key) && k.url != c.url && usable(k) {
                    out.push(k);
                }
            }
        }
        out.truncate(limit);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn kotlin_ppv_whole_word_any_case() {
        assert!(is_ppv("PPV Events | 1"));
        assert!(is_ppv("UK: ppv"));
        assert!(is_ppv("Sports (PPV)"));
        assert!(!is_ppv("PPVX Movies"));
        assert!(!is_ppv("Happv"));
    }

    #[test]
    fn kotlin_ppv_last_keeping_provider_order() {
        let p = v(&["PPV Events | 1", "News", "4K / UHD", "PPV Events | 2", "Kids"]);
        assert_eq!(group_order(&p, &[]), v(&["News", "4K / UHD", "Kids", "PPV Events | 1", "PPV Events | 2"]));
    }

    #[test]
    fn kotlin_saved_order_wins() {
        let p = v(&["PPV Events | 1", "News", "Kids"]);
        assert_eq!(group_order(&p, &v(&["PPV Events | 1", "Kids"])), v(&["PPV Events | 1", "Kids", "News"]));
        assert_eq!(group_order(&p, &v(&["Gone", "Kids"])), v(&["Kids", "News", "PPV Events | 1"]));
    }

    #[test]
    fn kotlin_send_to_top_and_bottom() {
        let l = v(&["a", "b", "c", "d"]);
        assert_eq!(send_to(&l, &"c".to_string(), -1), v(&["c", "a", "b", "d"]));
        assert_eq!(send_to(&l, &"c".to_string(), 1), v(&["a", "b", "d", "c"]));
        assert_eq!(send_to(&l, &"x".to_string(), 1), l);
    }

    #[test]
    fn web_layout_helpers() {
        assert_eq!(ordered(&v(&["a", "b", "c", "d"]), &v(&["c", "x", "a"])), v(&["c", "a", "b", "d"]));
        assert_eq!(moved(&v(&["a", "b", "c"]), "a", 1), Some(v(&["b", "a", "c"])));
        assert_eq!(moved(&v(&["a", "b", "c"]), "a", -5), Some(v(&["a", "b", "c"])));
        assert_eq!(moved(&v(&["a", "b", "c"]), "b", 9), Some(v(&["a", "c", "b"])));
        assert_eq!(moved(&v(&["a"]), "z", 1), None);
        assert_eq!(clean_name("  My   group \n"), "My group");
        assert_eq!(clean_name(&"x".repeat(100)).len(), 60);
        assert_eq!(swap_move(&v(&["a", "b"]), "a", -1), v(&["a", "b"]));
        assert_eq!(swap_move(&v(&["a", "b"]), "a", 1), v(&["b", "a"]));
    }

    fn c(key: &str, name: &str, group: &str) -> Channel {
        Channel::new(key, "s", name, None, group, format!("http://example.com/{key}"), None, 0, None, 0)
    }

    #[test]
    fn view_shown_and_groups() {
        let chans = vec![c("1", "One", "PPV 1"), c("2", "Two", "News"), c("3", "Three", "Kids"), c("4", "Four", "Adult"), c("5", "Five", "News")];
        let mut p = Prefs::default();
        let none = HashSet::new();
        assert_eq!(shown(&chans, &p, &none).iter().map(|c| c.key.as_str()).collect::<Vec<_>>(), vec!["2", "3", "4", "5", "1"]);
        p.hidden_ch.insert("5".into());
        p.locked.insert("Adult".into());
        p.names.insert("2".into(), "Mine".into());
        p.order = v(&["3", "1"]);
        let s = shown(&chans, &p, &none);
        assert_eq!(s.iter().map(|c| c.key.as_str()).collect::<Vec<_>>(), vec!["3", "1", "2"]);
        assert_eq!(s[2].name, "Mine");
        assert_eq!(s[2].name_key, "s|two");
        let unl: HashSet<String> = ["Adult".to_string()].into();
        assert_eq!(shown(&chans, &p, &unl).len(), 4);
        p.custom.insert("Zed".into(), v(&["2", "9"]));
        p.custom.insert("Abc".into(), v(&[]));
        p.hidden_groups.insert("Kids".into());
        p.group_order = v(&["News"]);
        assert_eq!(groups(&chans, &p), v(&[GROUP_FAV, GROUP_ALL, "Zed", "Abc", "News", "Adult", "PPV 1"]));
        assert_eq!(provider_groups(&chans, &p), v(&["News", "Kids", "Adult", "PPV 1"]));
        assert_eq!(group_label("Adult", &p), "Adult (locked)");
        p.group_names.insert("News".into(), "Nieuws".into());
        assert_eq!(group_label("News", &p), "Nieuws");
        let s = shown(&chans, &p, &none);
        let fav: HashSet<String> = ["1".to_string()].into();
        assert_eq!(filter_group(&s, GROUP_FAV, &p, &fav, NUM_PROVIDER).len(), 1);
        assert_eq!(filter_group(&s, "Zed", &p, &fav, NUM_PROVIDER)[0].name, "Mine");
        let per = filter_group(&s, GROUP_ALL, &p, &fav, NUM_PER_GROUP);
        assert_eq!(per.iter().map(|c| c.number).collect::<Vec<_>>(), vec![1, 2]);
        assert_eq!(by_number(&per, 2).unwrap().key, "2");
        assert_eq!(move_channel(&v(&["3"]), &v(&["3", "1", "2"]), "2", -1), v(&["3", "2", "1"]));
        assert_eq!(move_group(&groups(&chans, &p), &p, "Adult", -1), v(&["Adult", "News", "PPV 1"]));
    }

    #[test]
    fn prefs_json_is_kotlin_compatible() {
        let j = r#"{"hiddenCh":["a"],"names":{"k":"N"},"order":["k"],"custom":{"Mine":["k1"],"Abc":[]},"locked":["Adult"],"pinHash":"x","unknown":1}"#;
        let p: Prefs = serde_json::from_str(j).unwrap();
        assert_eq!(p.custom.keys().collect::<Vec<_>>(), vec!["Mine", "Abc"]);
        assert_eq!(p.pin_hash, "x");
        let back = serde_json::to_string(&p).unwrap();
        assert!(back.contains("\"hiddenGroups\":[]") && back.contains("\"pinHash\":\"x\""));
    }

    // web tests/test_alternates.py
    #[test]
    fn web_norm_name() {
        for n in ["BBC One HD", "BBC One FHD", "BBC One (Backup)", "BBC ONE [1080p 50fps]", "bbc one hevc", "BBC One"] {
            assert_eq!(norm_name(n), "bbc one", "{n}");
        }
        assert_eq!(norm_name("BBC One HD 2"), "bbc one");
        assert_eq!(norm_name("Sky Sports 1 HD"), "sky sports 1");
        assert_ne!(norm_name("Sky Sports 1"), norm_name("Sky Sports 2"));
        assert_ne!(norm_name("Film4 +1"), norm_name("Film4"));
        assert_ne!(norm_name("UK: Fox"), norm_name("US: Fox"));
        assert_eq!(norm_name("BBC One (Wales)"), "bbc one wales");
        assert_eq!(norm_name("Télé Monte-Carlo"), norm_name("TELE MONTE CARLO HD"));
        assert_eq!(norm_name("HD"), "");
        assert_eq!(norm_name(""), "");
    }

    fn ch(key: &str, name: &str, tvg: Option<&str>, src: &str, url: Option<&str>) -> Channel {
        Channel::new(key, src, name, None, "G", url.map(String::from).unwrap_or_else(|| format!("http://x/{key}.ts")), tvg.map(String::from), 0, None, 0)
    }

    #[test]
    fn web_alt_index() {
        let ix = AltIndex::new(vec![
            ch("p0|a", "Kilo Movies", Some("kilo"), "p0", None),
            ch("p0|b", "Kilo Movies HD", Some("kilo"), "p0", None),
            ch("p0|c", "KILO MOVIES (backup)", None, "p0", None),
            ch("p1|d", "Kilo Movies FHD", None, "p1", None),
            ch("p0|e", "Kilo Movies +1", None, "p0", None),
            ch("p0|f", "Other", Some("kilo"), "p0", None),
            ch("p0|g", "Kilo Movies", None, "p0", Some("http://x/p0|a.ts")),
        ]);
        let alts: Vec<&str> = ix.alternates("p0|a", |_| true, MAX_ALTS).iter().map(|c| c.key.as_str()).collect();
        assert_eq!(&alts[..2], &["p0|b", "p0|f"]);
        let pos = |k: &str| alts.iter().position(|x| *x == k);
        assert!(pos("p0|c").unwrap() < pos("p1|d").unwrap());
        assert!(pos("p0|e").is_none() && pos("p0|g").is_none() && pos("p0|a").is_none());
        assert!(!ix.alternates("p0|a", |c| c.key != "p0|b", MAX_ALTS).iter().any(|c| c.key == "p0|b"));
        assert_eq!(ix.alternates("p0|a", |_| true, 2).len(), 2);
        assert!(ix.alternates("nope", |_| true, MAX_ALTS).is_empty());
        assert!(ix.alternates("p0|e", |_| true, MAX_ALTS).is_empty());
    }
}
