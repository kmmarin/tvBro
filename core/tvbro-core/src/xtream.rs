// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Xtream Codes `player_api.php` JSON (live, VOD, series, series / VOD info) and URL builders.
//!
//! Port of shared/.../Xtream.kt and Vod.kt plus the web server's additions (year / genre on catalogue items,
//! name / cover / runtime on details, HLS variants). Providers send every field as string, number, "" or null, the
//! top level as a list or as a map of id to object, sometimes with a BOM, sometimes truncated: everything is accepted
//! and one bad item never fails the list. Big lists are streamed item by item.

use crate::model::{Catchup, Channel};
use crate::text::collapse_ws;
use regex::Regex;
use serde::de::{Deserializer as _, SeqAccess, Visitor};
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::io::BufRead;
use std::sync::LazyLock;

pub type JsonObj = Map<String, Value>;

// ------------------------------------------------------------------ lenient field access

/// A provider value as a clean string: strings are trimmed ("" and "null" are none), numbers and booleans as
/// written; arrays and objects are none.
pub fn jstr(v: Option<&Value>) -> Option<String> {
    match v? {
        Value::String(s) => {
            let t = s.trim();
            (!t.is_empty() && t != "null").then(|| t.to_string())
        }
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// First present value among `keys`.
pub fn js(o: &JsonObj, keys: &[&str]) -> Option<String> {
    keys.iter().find_map(|k| jstr(o.get(*k)))
}

fn obj(v: Option<&Value>) -> JsonObj {
    match v {
        Some(Value::Object(m)) => m.clone(),
        _ => JsonObj::new(),
    }
}

// ------------------------------------------------------------------ streaming list reader

struct Each<'a, F> {
    f: &'a mut F,
}

impl<'de, F: FnMut(Value) -> bool> Visitor<'de> for Each<'_, F> {
    type Value = ();
    fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str("a list")
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<(), A::Error> {
        while let Some(v) = seq.next_element::<Value>()? {
            if !(self.f)(v) {
                break;
            }
        }
        Ok(())
    }
}

/// Streams the top-level items of a JSON list, or the item objects of a map of id to object, to `f` (return false
/// to stop). A BOM / whitespace before the list is skipped; a truncated list keeps the items before the damage.
/// Returns false when the input was unreadable as a whole.
pub fn each_value<R: BufRead>(mut r: R, mut f: impl FnMut(Value) -> bool) -> bool {
    // skip whitespace and BOM bytes (at most 1000, like VodApi.elements)
    let mut skipped = 0;
    let first = loop {
        let b = match r.fill_buf() {
            Ok(b) if !b.is_empty() => b[0],
            _ => return false,
        };
        if matches!(b, b' ' | b'\t' | b'\n' | b'\r' | 0xEF | 0xBB | 0xBF) && skipped < 1000 {
            r.consume(1);
            skipped += 1;
        } else {
            break b;
        }
    };
    if first == b'[' {
        let mut de = serde_json::Deserializer::from_reader(r);
        let _ = de.deserialize_seq(Each { f: &mut f });
        return true;
    }
    let mut text = Vec::new();
    if r.read_to_end(&mut text).is_err() {
        return false;
    }
    match serde_json::from_slice::<Value>(&text) {
        Ok(Value::Object(m)) => {
            for (_, v) in m {
                if let Value::Object(o) = &v
                    && ["name", "category_id", "stream_id", "series_id"].iter().any(|k| o.contains_key(*k))
                    && !f(v)
                {
                    break;
                }
            }
            true
        }
        _ => false,
    }
}

/// Maps each object item with `map` (None skips it) and hands results to `sink`, stopping at `limit`.
pub fn each_mapped<R: BufRead, T>(r: R, limit: usize, map: impl Fn(&JsonObj) -> Option<T>, mut sink: impl FnMut(T)) -> usize {
    let mut n = 0;
    if limit == 0 {
        return 0;
    }
    each_value(r, |v| {
        if let Value::Object(o) = &v
            && let Some(t) = map(o)
        {
            sink(t);
            n += 1;
        }
        n < limit
    });
    n
}

fn collect<T>(raw: &[u8], limit: usize, map: impl Fn(&JsonObj) -> Option<T>) -> Vec<T> {
    let mut out = Vec::new();
    each_mapped(raw, limit, map, |t| out.push(t));
    out
}

// ------------------------------------------------------------------ URLs

pub fn normalize_server(s: &str) -> String {
    let t = s.trim().trim_end_matches('/');
    let low = t.to_ascii_lowercase();
    if low.starts_with("http://") || low.starts_with("https://") { t.to_string() } else { format!("http://{t}") }
}

fn enc(v: &str) -> String {
    url::form_urlencoded::byte_serialize(v.as_bytes()).collect()
}

pub fn api_url(server: &str, user: &str, pass: &str, action: &str) -> String {
    format!("{}/player_api.php?username={}&password={}&action={}", normalize_server(server), enc(user), enc(pass), action)
}

pub fn xmltv_url(server: &str, user: &str, pass: &str) -> String {
    format!("{}/xmltv.php?username={}&password={}", normalize_server(server), enc(user), enc(pass))
}

pub fn live_url(server: &str, user: &str, pass: &str, stream_id: &str) -> String {
    format!("{}/live/{user}/{pass}/{stream_id}.ts", normalize_server(server))
}

pub fn movie_url(server: &str, user: &str, pass: &str, id: &str, ext: &str) -> String {
    format!("{}/movie/{user}/{pass}/{id}.{ext}", normalize_server(server))
}

pub fn episode_url(server: &str, user: &str, pass: &str, id: &str, ext: &str) -> String {
    format!("{}/series/{user}/{pass}/{id}.{ext}", normalize_server(server))
}

pub fn is_vod_url(u: &str) -> bool {
    u.contains("/movie/") || u.contains("/series/")
}

static XC_TS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^(https?://[^/?#]+/(?:live|timeshift)/[^?#]+)\.ts$").unwrap());

fn path_part(url: &str) -> &str {
    let a = url.split('?').next().unwrap_or(url);
    a.split('#').next().unwrap_or(a)
}

/// The HLS form of an upstream stream URL (an Xtream panel serves `.ts` streams as `.m3u8` too), or None.
pub fn hls_variant(url: &str) -> Option<String> {
    if url.is_empty() {
        return None;
    }
    if path_part(url).to_lowercase().ends_with(".m3u8") {
        return Some(url.to_string());
    }
    XC_TS.captures(url).map(|c| format!("{}.m3u8", &c[1]))
}

/// (upstream url, format actually served) for a client asking `want` = "hls" | "ts" | "".
pub fn choose_format(url: &str, want: &str) -> (String, &'static str) {
    let native = url.split('?').next().unwrap_or(url).to_lowercase().ends_with(".m3u8");
    if want == "hls"
        && let Some(h) = hls_variant(url)
    {
        return (h, "hls");
    }
    (url.to_string(), if native { "hls" } else { "ts" })
}

// ------------------------------------------------------------------ categories and live

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct XtreamCategory {
    pub category_id: String,
    pub category_name: String,
}

impl XtreamCategory {
    pub fn new(id: impl Into<String>, name: impl Into<String>) -> Self {
        XtreamCategory { category_id: id.into(), category_name: name.into() }
    }
}

fn category(o: &JsonObj) -> Option<XtreamCategory> {
    let id = js(o, &["category_id", "id"])?;
    let name = js(o, &["category_name", "name"]).unwrap_or_else(|| id.clone());
    Some(XtreamCategory { category_id: id, category_name: name })
}

pub fn parse_categories(raw: &[u8]) -> Vec<XtreamCategory> {
    collect(raw, 100_000, category)
}

/// Live categories with the Android rule (`Xtream.categories`): a missing or blank name stays empty, so
/// [to_channels] puts that category's channels in "Other" (VOD categories, [parse_categories], show the id instead).
pub fn parse_live_categories(raw: &[u8]) -> Vec<XtreamCategory> {
    collect(raw, 100_000, |o| Some(XtreamCategory { category_id: js(o, &["category_id"])?, category_name: js(o, &["category_name"]).unwrap_or_default() }))
}

/// `get_live_streams` items as raw objects (the server keeps them for its own fields).
pub fn parse_live_streams(raw: &[u8]) -> Vec<JsonObj> {
    collect(raw, 2_000_000, |o| Some(o.clone()))
}

/// True when `raw` is a whole JSON list (or map of id to object) from start to end: a download cut off mid-way
/// is not. The lenient readers above keep the items before such damage; the download check (keep-old-data rule,
/// Android `xtreamHasStreams`) must refuse it like Kotlin's strict decoder does, so the previous list stays.
pub fn is_complete(raw: &[u8]) -> bool {
    let mut i = 0;
    while i < raw.len() && i < 1000 && matches!(raw[i], b' ' | b'\t' | b'\n' | b'\r' | 0xEF | 0xBB | 0xBF) {
        i += 1;
    }
    let mut de = serde_json::Deserializer::from_slice(&raw[i..]);
    let ok = match raw.get(i) {
        Some(b'[') => serde::de::Deserialize::deserialize(&mut de).map(|_: serde::de::IgnoredAny| ()).is_ok(),
        Some(b'{') => serde::de::Deserialize::deserialize(&mut de).map(|_: serde_json::Map<String, Value>| ()).is_ok(),
        _ => false,
    };
    ok && de.end().is_ok()
}

/// `get_live_streams` items of a complete list ([is_complete]); 0 for a damaged one.
pub fn count_live_streams_strict(raw: &[u8]) -> usize {
    if is_complete(raw) { parse_live_streams(raw).len() } else { 0 }
}

/// Live streams to channels, in category order (streams of unknown categories last), provider order within.
pub fn to_channels(source_id: &str, server: &str, user: &str, pass: &str, cats: &[XtreamCategory], streams: &[JsonObj]) -> Vec<Channel> {
    let base = normalize_server(server);
    let cat_map: HashMap<&str, &str> = cats.iter().map(|c| (c.category_id.as_str(), c.category_name.as_str())).collect();
    let mut order: HashMap<&str, usize> = HashMap::new();
    for (i, c) in cats.iter().enumerate() {
        order.entry(c.category_id.as_str()).or_insert(i);
    }
    let mut idx: Vec<(usize, String, &JsonObj)> = streams.iter().map(|s| js(s, &["category_id"]).unwrap_or_default()).zip(streams).enumerate().map(|(i, (c, s))| (i, c, s)).collect();
    idx.sort_by_key(|(i, c, _)| (order.get(c.as_str()).copied().unwrap_or(usize::MAX), *i));
    let mut out = Vec::with_capacity(idx.len());
    for (_, cat, s) in idx {
        let Some(sid) = js(s, &["stream_id", "id"]) else { continue };
        let archive = js(s, &["tv_archive"]).as_deref() == Some("1");
        let name = js(s, &["name"]).unwrap_or_else(|| format!("Channel {sid}"));
        let group = cat_map.get(cat.as_str()).filter(|g| !g.trim().is_empty()).map(|g| g.to_string()).unwrap_or_else(|| "Other".into());
        let (catchup, days) = if archive {
            let d = js(s, &["tv_archive_duration"]).and_then(|d| d.parse::<i32>().ok()).unwrap_or(3);
            (Some(Catchup::new("xc", Some(format!("{base}/timeshift/{user}/{pass}/{{duration}}/{{start}}/{sid}.ts")))), d)
        } else {
            (None, 0)
        };
        out.push(Channel::new(
            format!("{source_id}|{sid}"),
            source_id,
            name,
            js(s, &["stream_icon"]),
            group,
            format!("{base}/live/{user}/{pass}/{sid}.ts"),
            js(s, &["epg_channel_id"]),
            js(s, &["num"]).and_then(|n| n.parse::<i32>().ok()).unwrap_or(0),
            catchup,
            days,
        ));
    }
    out
}

// ------------------------------------------------------------------ VOD

/// A movie or a series in the VOD catalogue. `url` is set for direct (M3U) items.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct VodItem {
    pub key: String,
    pub source_id: String,
    pub kind: String,
    pub id: String,
    pub name: String,
    pub poster: Option<String>,
    pub category: String,
    pub rating: Option<String>,
    pub ext: String,
    pub url: Option<String>,
    pub year: Option<String>,
    pub genre: Option<String>,
}

impl VodItem {
    /// A playlist entry that points at a film (VodApi.fromChannel).
    pub fn from_channel(c: &Channel) -> Self {
        VodItem {
            key: format!("v|{}", c.key),
            source_id: c.source_id.clone(),
            kind: "movie".into(),
            id: c.key.clone(),
            name: c.name.clone(),
            poster: c.logo.clone(),
            category: c.group.clone(),
            rating: None,
            ext: String::new(),
            url: Some(c.url.clone()),
            year: None,
            genre: None,
        }
    }
}

static YEAR_IN_NAME: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[(\[](19[0-9][0-9]|20[0-9][0-9])[)\]]").unwrap());

fn year_of(o: &JsonObj) -> Option<String> {
    if let Some(y) = js(o, &["year", "releaseDate", "releasedate", "release_date"])
        && y.len() >= 4
        && y.as_bytes()[..4].iter().all(u8::is_ascii_digit)
    {
        return Some(y[..4].to_string());
    }
    YEAR_IN_NAME.captures(&js(o, &["name", "title"]).unwrap_or_default()).map(|c| c[1].to_string())
}

fn cat_map(cats: &[XtreamCategory]) -> HashMap<String, String> {
    cats.iter().map(|c| (c.category_id.clone(), c.category_name.clone())).collect()
}

fn movie(o: &JsonObj, source_id: &str, cm: &HashMap<String, String>) -> Option<VodItem> {
    let id = js(o, &["stream_id", "id"])?;
    Some(VodItem {
        key: format!("m|{source_id}|{id}"),
        source_id: source_id.into(),
        kind: "movie".into(),
        name: js(o, &["name", "title"]).unwrap_or_else(|| format!("Movie {id}")),
        poster: js(o, &["stream_icon", "cover", "movie_image"]),
        category: cm.get(&js(o, &["category_id"]).unwrap_or_default()).cloned().unwrap_or_else(|| "Other".into()),
        rating: js(o, &["rating", "rating_5based"]),
        ext: js(o, &["container_extension"]).unwrap_or_else(|| "mp4".into()),
        url: None,
        year: year_of(o),
        genre: js(o, &["genre"]),
        id,
    })
}

fn series(o: &JsonObj, source_id: &str, cm: &HashMap<String, String>) -> Option<VodItem> {
    let id = js(o, &["series_id", "id"])?;
    Some(VodItem {
        key: format!("s|{source_id}|{id}"),
        source_id: source_id.into(),
        kind: "series".into(),
        name: js(o, &["name", "title"]).unwrap_or_else(|| format!("Series {id}")),
        poster: js(o, &["cover", "stream_icon"]),
        category: cm.get(&js(o, &["category_id"]).unwrap_or_default()).cloned().unwrap_or_else(|| "Other".into()),
        rating: js(o, &["rating", "rating_5based"]),
        ext: String::new(),
        url: None,
        year: year_of(o),
        genre: js(o, &["genre"]),
        id,
    })
}

pub const VOD_LIMIT: usize = 30_000;

pub fn parse_movies(raw: &[u8], source_id: &str, cats: &[XtreamCategory], limit: usize) -> Vec<VodItem> {
    let cm = cat_map(cats);
    collect(raw, limit, |o| movie(o, source_id, &cm))
}

pub fn parse_series_list(raw: &[u8], source_id: &str, cats: &[XtreamCategory], limit: usize) -> Vec<VodItem> {
    let cm = cat_map(cats);
    collect(raw, limit, |o| series(o, source_id, &cm))
}

/// Whole catalogue streamed item by item (for the on-disk catalogue / search index); returns the count.
pub fn each_vod<R: BufRead>(r: R, is_series: bool, source_id: &str, cats: &[XtreamCategory], sink: impl FnMut(VodItem)) -> usize {
    let cm = cat_map(cats);
    each_mapped(r, usize::MAX, |o| if is_series { series(o, source_id, &cm) } else { movie(o, source_id, &cm) }, sink)
}

#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Episode {
    pub id: String,
    pub season: i32,
    pub num: i32,
    pub title: String,
    pub ext: String,
    pub plot: Option<String>,
    pub duration: Option<String>,
    pub image: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct VodDetail {
    pub plot: Option<String>,
    pub year: Option<String>,
    pub rating: Option<String>,
    pub cast: Option<String>,
    pub genre: Option<String>,
    pub trailer: Option<String>,
    pub duration: Option<String>,
    pub episodes: Vec<Episode>,
    pub director: Option<String>,
    pub ext: Option<String>,
    pub backdrop: Option<String>,
    pub name: Option<String>,
    pub cover: Option<String>,
    /// Minutes as text (web server's `_runtime`).
    pub runtime: Option<String>,
}

static HMS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[0-9]{1,2}:[0-9]{2}(:[0-9]{2})?$").unwrap());

fn runtime_minutes(i: &JsonObj) -> Option<String> {
    if let Some(s) = js(i, &["duration_secs"])
        && let Ok(n) = s.parse::<u64>()
        && n > 0
    {
        return Some(((n as f64 / 60.0).round_ties_even() as u64).to_string());
    }
    if let Some(d) = js(i, &["duration"])
        && HMS.is_match(&d)
    {
        let p: Vec<u64> = d.split(':').filter_map(|x| x.parse().ok()).collect();
        let mins = if p.len() == 3 { p[0] * 60 + p[1] } else { p[0] };
        return (mins > 0).then(|| mins.to_string());
    }
    js(i, &["episode_run_time"]).filter(|r| r != "0" && r.bytes().all(|b| b.is_ascii_digit()))
}

fn detail(i: &JsonObj, eps: Vec<Episode>, ext: Option<String>) -> VodDetail {
    let backdrop = match i.get("backdrop_path") {
        Some(Value::Array(a)) => jstr(a.first()),
        _ => None,
    }
    .or_else(|| js(i, &["backdrop_path", "cover_big", "movie_image"]));
    VodDetail {
        plot: js(i, &["plot", "description"]),
        year: js(i, &["releasedate", "releaseDate", "release_date", "year"]).map(|y| crate::text::take_chars(&y, 4)),
        rating: js(i, &["rating", "rating_5based"]),
        cast: js(i, &["cast", "actors"]),
        genre: js(i, &["genre"]),
        trailer: js(i, &["youtube_trailer", "trailer"]).map(|t| if t.starts_with("http") { t } else { format!("https://www.youtube.com/watch?v={t}") }),
        duration: js(i, &["duration"]).or_else(|| js(i, &["duration_secs"])),
        episodes: eps,
        director: js(i, &["director"]),
        ext,
        backdrop,
        name: js(i, &["name", "title", "o_name"]),
        cover: js(i, &["cover", "movie_image", "cover_big"]),
        runtime: runtime_minutes(i),
    }
}

fn root_obj(text: &[u8]) -> JsonObj {
    match serde_json::from_slice::<Value>(text) {
        Ok(Value::Object(m)) => m,
        _ => JsonObj::new(),
    }
}

pub fn parse_vod_info(text: &[u8]) -> VodDetail {
    let root = root_obj(text);
    detail(&obj(root.get("info")), Vec::new(), js(&obj(root.get("movie_data")), &["container_extension"]))
}

fn num_i32(s: Option<String>) -> Option<i32> {
    s.and_then(|v| v.parse::<f64>().ok()).filter(|f| f.is_finite()).map(|f| f as i32)
}

fn episode(o: &JsonObj, season: i32) -> Option<Episode> {
    let id = js(o, &["id"])?;
    let inf = obj(o.get("info"));
    Some(Episode {
        id,
        season: num_i32(js(o, &["season"])).unwrap_or(season),
        num: num_i32(js(o, &["episode_num"])).unwrap_or(0),
        title: js(o, &["title"]).unwrap_or_else(|| "Episode".into()),
        ext: js(o, &["container_extension"]).unwrap_or_else(|| "mp4".into()),
        plot: js(&inf, &["plot"]),
        duration: js(&inf, &["duration", "duration_secs"]),
        image: js(&inf, &["movie_image", "cover_big"]),
    })
}

/// `episodes` is normally an object keyed by season, but some panels send an array (of seasons or of episodes).
pub fn parse_series_info(text: &[u8]) -> VodDetail {
    let root = root_obj(text);
    let mut eps = Vec::new();
    match root.get("episodes") {
        Some(Value::Object(m)) => {
            for (season, arr) in m {
                let sn = season.parse::<i32>().unwrap_or(0);
                match arr {
                    Value::Array(a) => eps.extend(a.iter().filter_map(|x| x.as_object()).filter_map(|o| episode(o, sn))),
                    Value::Object(o) => eps.extend(episode(o, sn)),
                    _ => {}
                }
            }
        }
        Some(Value::Array(a)) => {
            for (i, x) in a.iter().enumerate() {
                match x {
                    Value::Array(y) => eps.extend(y.iter().filter_map(|v| v.as_object()).filter_map(|o| episode(o, i as i32 + 1))),
                    Value::Object(o) => eps.extend(episode(o, 1)),
                    _ => {}
                }
            }
        }
        _ => {}
    }
    eps.sort_by_key(|e| (e.season, e.num));
    detail(&obj(root.get("info")), eps, None)
}

// ------------------------------------------------------------------ formatting (VodFmt)

/// "01:32:00", "42:15" or plain seconds to seconds; 0 if unknown.
pub fn seconds(d: Option<&str>) -> i64 {
    let Some(s) = d.map(str::trim).filter(|s| !s.is_empty()) else { return 0 };
    let num = |x: &str| x.trim().parse::<f64>().ok().filter(|f| f.is_finite()).map(|f| f as i64);
    if s.contains(':') {
        let mut a: i64 = 0;
        for x in s.split(':') {
            match num(x) {
                Some(v) => a = a.saturating_mul(60).saturating_add(v),
                None => return 0,
            }
        }
        a
    } else {
        num(s).unwrap_or(0)
    }
}

pub fn fmt_duration(sec: i64) -> String {
    if sec >= 3600 { format!("{}h {}m", sec / 3600, (sec % 3600) / 60) } else { format!("{}m", sec / 60) }
}

pub fn runtime(d: Option<&str>) -> Option<String> {
    let s = seconds(d);
    (s > 0).then(|| fmt_duration(s))
}

static YEAR_PAREN: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\([0-9]{4}\)").unwrap());
static SXXEYY: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[\s\-–:._|]*[Ss][0-9]{1,2}\s*[Ee][0-9]{1,3}[\s\-–:._|]*").unwrap());
static LEAD: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[\s\-–:._|]+").unwrap());
static TRAIL: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[\s\-–:._|]+$").unwrap());

/// Strips the series name, year and "SxxEyy - " prefixes that providers put in episode titles.
pub fn clean_title(series: &str, title: &str) -> String {
    let orig = title.trim();
    let mut t = orig.to_string();
    if !series.trim().is_empty() && t.to_lowercase().starts_with(&series.to_lowercase()) {
        let n = series.chars().count();
        t = t.chars().skip(n).collect();
    }
    let t = YEAR_PAREN.replace_all(&t, " ");
    let t = SXXEYY.replace(&t, "");
    let t = LEAD.replace(&t, "");
    let t = TRAIL.replace(&t, "");
    let t = collapse_ws(&t);
    if t.trim().is_empty() { orig.to_string() } else { t }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn live_categories_keep_blank_names() {
        let c = parse_live_categories(br#"[{"category_id":"1","category_name":"News"},{"category_id":4,"category_name":""},{"category_id":"5"},{"category_name":"no id"}]"#);
        assert_eq!(c, vec![XtreamCategory::new("1", "News"), XtreamCategory::new("4", ""), XtreamCategory::new("5", "")]);
        let s = parse_live_streams(br#"[{"name":"A","stream_id":1,"category_id":"4"}]"#);
        assert_eq!(to_channels("s", "h", "u", "p", &c, &s)[0].group, "Other");
    }

    #[test]
    fn truncated_download_is_not_complete() {
        let whole = br#"[{"name":"J","stream_id":"25"},{"name":"K","stream_id":"26"}]"#;
        assert!(is_complete(whole));
        assert_eq!(count_live_streams_strict(whole), 2);
        for cut in [&whole[..whole.len() - 1], &whole[..40], &br#"[{"name":"J","stream_id":"25"},{"name":"L","str"#[..]] {
            assert!(!is_complete(cut));
            assert_eq!(count_live_streams_strict(cut), 0);
            assert!(!parse_live_streams(cut).is_empty(), "the lenient reader still keeps the items before the cut");
        }
        assert!(is_complete("\u{feff} [] ".as_bytes()));
        assert!(is_complete(br#"{"a":{"name":"H","stream_id":"23"}}"#));
        assert!(!is_complete(br#"{"a":{"name":"H","stream_id":"23"}"#));
        assert!(!is_complete(b"<html>error</html>"));
        assert!(!is_complete(br#"[1] [2]"#), "trailing data");
        assert!(!is_complete(b""));
    }

    #[test]
    fn kotlin_xtream_json() {
        let cats = br#"[{"category_id":"1","category_name":"News"},{"category_id":2,"category_name":"Sport"}]"#;
        let streams = br#"[{"num":1,"name":"A","stream_id":101,"stream_icon":"","epg_channel_id":"a.x","category_id":"1","tv_archive":1,"tv_archive_duration":"5","extra":{}},
            {"num":"2","name":"B","stream_id":"102","epg_channel_id":null,"category_id":2,"tv_archive":0}]"#;
        let c = parse_categories(cats);
        let s = parse_live_streams(streams);
        assert_eq!((c.len(), s.len()), (2, 2));
        let ch = to_channels("x", "host:8080/", "u", "p", &c, &s);
        assert_eq!(ch[0].url, "http://host:8080/live/u/p/101.ts");
        assert_eq!(ch[0].group, "News");
        assert_eq!(ch[1].group, "Sport");
        assert_eq!(ch[0].catchup_days, 5);
        assert!(ch[1].catchup.is_none());
        assert!(ch[0].logo.is_none());
        assert_eq!(ch[0].tvg_id.as_deref(), Some("a.x"));
        assert_eq!(ch[0].number, 1);
        assert_eq!(ch[1].number, 2);
        assert!(parse_live_streams(br#"{"user_info":{}}"#).is_empty());
    }

    #[test]
    fn category_order_and_defaults() {
        let cats = vec![XtreamCategory::new("2", "B"), XtreamCategory::new("1", "A")];
        let streams = parse_live_streams(br#"[{"stream_id":1,"category_id":"1"},{"stream_id":2,"category_id":"9"},{"stream_id":3,"category_id":"2","tv_archive":"1"},{"name":"no id"}]"#);
        let ch = to_channels("x", "https://example.com", "u", "p", &cats, &streams);
        assert_eq!(ch.iter().map(|c| c.key.as_str()).collect::<Vec<_>>(), vec!["x|3", "x|1", "x|2"]);
        assert_eq!(ch[0].catchup_days, 3);
        assert_eq!(ch[0].catchup.as_ref().unwrap().template.as_deref(), Some("https://example.com/timeshift/u/p/{duration}/{start}/3.ts"));
        assert_eq!(ch[2].group, "Other");
        assert_eq!(ch[1].name, "Channel 1");
    }

    #[test]
    fn urls() {
        assert_eq!(normalize_server(" example.com:8080/ "), "http://example.com:8080");
        assert_eq!(normalize_server("HTTPS://example.com"), "HTTPS://example.com");
        assert_eq!(api_url("example.com", "a b", "p&w", "get_live_streams"), "http://example.com/player_api.php?username=a+b&password=p%26w&action=get_live_streams");
        assert_eq!(xmltv_url("http://example.com/", "u", "p"), "http://example.com/xmltv.php?username=u&password=p");
        assert_eq!(movie_url("example.com", "u", "p", "7", "mkv"), "http://example.com/movie/u/p/7.mkv");
        assert_eq!(episode_url("example.com", "u", "p", "8", "mp4"), "http://example.com/series/u/p/8.mp4");
        assert!(is_vod_url("http://x/movie/a") && !is_vod_url("http://x/live/a"));
        assert_eq!(hls_variant("http://example.com/live/u/p/1.ts").as_deref(), Some("http://example.com/live/u/p/1.m3u8"));
        assert_eq!(hls_variant("http://example.com/a.m3u8?x=1").as_deref(), Some("http://example.com/a.m3u8?x=1"));
        assert_eq!(hls_variant("http://example.com/other/1.ts"), None);
        assert_eq!(choose_format("http://example.com/live/u/p/1.ts", "hls"), ("http://example.com/live/u/p/1.m3u8".to_string(), "hls"));
        assert_eq!(choose_format("http://example.com/x.ts", "hls"), ("http://example.com/x.ts".to_string(), "ts"));
        assert_eq!(choose_format("http://example.com/x.m3u8", ""), ("http://example.com/x.m3u8".to_string(), "hls"));
    }

    #[test]
    fn kotlin_vod_quirks_movies() {
        let cats = parse_categories(br#"[{"category_id":1,"category_name":"Act","parent_id":0},{"category_id":"2","category_name":null},{"name":"no id"}]"#);
        assert_eq!(cats.len(), 2);
        assert_eq!(cats[0].category_id, "1");
        assert_eq!(cats[1].category_name, "2");
        let json = br#"[{"num":1,"name":"A","stream_id":101,"stream_icon":null,"rating":7.5,"rating_5based":3.8,"added":"1700000000","category_id":1,"container_extension":"mkv"},
          {"name":"B","stream_id":"102","stream_icon":"","rating":"","category_id":"","container_extension":null},
          "garbage", 5, {"name":"no id"}, {"stream_id":103,"name":null,"category_id":99,"rating":null}]"#;
        let l = parse_movies(json, "s", &cats, VOD_LIMIT);
        assert_eq!(l.len(), 3);
        assert_eq!(l[0].category, "Act");
        assert_eq!(l[0].rating.as_deref(), Some("7.5"));
        assert_eq!(l[0].ext, "mkv");
        assert!(l[0].poster.is_none());
        assert_eq!(l[1].category, "Other");
        assert_eq!(l[1].ext, "mp4");
        assert!(l[1].rating.is_none());
        assert_eq!(l[2].name, "Movie 103");
        assert_eq!(parse_movies(json, "s", &cats, 1).len(), 1);
    }

    #[test]
    fn kotlin_vod_quirks_shapes() {
        assert!(parse_movies(br#"{"user_info":{"auth":0}}"#, "s", &[], VOD_LIMIT).is_empty());
        assert!(parse_movies(b"[]", "s", &[], VOD_LIMIT).is_empty());
        assert!(parse_movies(b"not json", "s", &[], VOD_LIMIT).is_empty());
        assert_eq!(parse_movies(br#"{"0":{"stream_id":1,"name":"a"},"1":{"stream_id":2,"name":"b"}}"#, "s", &[], VOD_LIMIT).len(), 2);
        assert_eq!(parse_movies("\u{FEFF} [{\"stream_id\":1,\"name\":\"a\"}]".as_bytes(), "s", &[], VOD_LIMIT).len(), 1);
        assert!(parse_movies(br#"[{"stream_id":1,"name":"a"},{"stream_id":2,"na"#, "s", &[], VOD_LIMIT).len() <= 1);
        let s = parse_series_list(br#"[{"series_id":7,"name":"S","cover":null,"category_id":3,"rating":"8"}]"#, "s", &[XtreamCategory::new("3", "Drama")], VOD_LIMIT);
        assert_eq!(s[0].category, "Drama");
        assert_eq!(s[0].key, "s|s|7");
        let y = parse_movies(br#"[{"stream_id":1,"name":"Film (1999)"},{"stream_id":2,"name":"F","releaseDate":"2001-02-03","genre":"Drama"}]"#, "s", &[], VOD_LIMIT);
        assert_eq!(y[0].year.as_deref(), Some("1999"));
        assert_eq!(y[1].year.as_deref(), Some("2001"));
        assert_eq!(y[1].genre.as_deref(), Some("Drama"));
    }

    #[test]
    fn kotlin_vod_info_quirks() {
        let d = parse_vod_info(br#"{"info":{"plot":"P","releasedate":"2020-05-01","rating":7,"youtube_trailer":"abc","director":"D","duration_secs":3600},"movie_data":{"container_extension":"avi"}}"#);
        assert_eq!(d.year.as_deref(), Some("2020"));
        assert_eq!(d.rating.as_deref(), Some("7"));
        assert_eq!(d.ext.as_deref(), Some("avi"));
        assert_eq!(d.director.as_deref(), Some("D"));
        assert_eq!(d.trailer.as_deref(), Some("https://www.youtube.com/watch?v=abc"));
        assert_eq!(d.runtime.as_deref(), Some("60"));
        assert!(parse_vod_info(br#"{"info":[]}"#).plot.is_none());
        assert!(parse_vod_info(b"[]").plot.is_none());
        let o = parse_series_info(br#"{"info":[],"seasons":[],"episodes":{"2":[{"id":"22","episode_num":"1","season":2,"title":"B","container_extension":"mkv","info":[]}],"1":[{"id":11,"episode_num":2,"title":"A2"},{"id":12,"episode_num":1.0,"info":{"plot":"x","duration":"40:00"}}]}}"#);
        assert_eq!(o.episodes.iter().map(|e| e.id.as_str()).collect::<Vec<_>>(), vec!["12", "11", "22"]);
        assert_eq!(o.episodes[2].ext, "mkv");
        assert_eq!(o.episodes[2].season, 2);
        assert_eq!(o.episodes[0].duration.as_deref(), Some("40:00"));
        let arr = parse_series_info(br#"{"episodes":[[{"id":"1","episode_num":1}],[{"id":"2","episode_num":1}]]}"#);
        assert_eq!(arr.episodes.iter().map(|e| e.season).collect::<Vec<_>>(), vec![1, 2]);
        let flat = parse_series_info(br#"{"episodes":[{"id":"9","season":3,"episode_num":4}]}"#);
        assert_eq!(flat.episodes[0].season, 3);
        assert!(parse_series_info(br#"{"episodes":[]}"#).episodes.is_empty());
        assert!(parse_series_info(br#"{"info":{},"episodes":null}"#).episodes.is_empty());
    }

    #[test]
    fn kotlin_vod_formatting() {
        assert_eq!(clean_title("My Show", "My Show - S01E02 - Pilot"), "Pilot");
        assert_eq!(clean_title("My Show", "S01E02 - Pilot"), "Pilot");
        assert_eq!(clean_title("My Show", "My Show (2019) S01E02 Pilot"), "Pilot");
        assert_eq!(clean_title("X", "Plain"), "Plain");
        assert_eq!(clean_title("My Show", "My Show S01E02"), "My Show S01E02");
        assert_eq!(runtime(Some("01:32:00")).as_deref(), Some("1h 32m"));
        assert_eq!(runtime(Some("2700")).as_deref(), Some("45m"));
        assert!(runtime(Some("")).is_none());
        let d = parse_vod_info(br#"{"info":{"backdrop_path":["http://x/b.jpg"],"duration_secs":5520}}"#);
        assert_eq!(d.backdrop.as_deref(), Some("http://x/b.jpg"));
        assert_eq!(d.duration.as_deref(), Some("5520"));
    }

    #[test]
    fn streaming_vod_list() {
        let mut big = String::from("[");
        for i in 0..50_000 {
            if i > 0 {
                big.push(',');
            }
            big.push_str(&format!(r#"{{"stream_id":{i},"name":"M{i}","category_id":"1"}}"#));
        }
        big.push(']');
        let mut n = 0;
        let got = each_vod(big.as_bytes(), false, "s", &[XtreamCategory::new("1", "A")], |v| {
            assert_eq!(v.category, "A");
            n += 1;
        });
        assert_eq!((got, n), (50_000, 50_000));
    }
}
