// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Streaming XMLTV parser (port of XmltvParser.kt + EpisodeNum in SearchLogic.kt).
//!
//! Memory stays flat whatever the file size: programmes go to a sink one by one, and anything outside the time
//! window or not resolved to a channel is dropped at once.
//!
//! Hostile input: the reader never expands DTD entities (no `<!ENTITY>` is ever read, so billion-laughs and external
//! entities (XXE) do nothing; only the five predefined entities and numeric character references are decoded), no
//! single token (tag or text run) may exceed [XmltvLimits::max_token], the whole input is capped by the caller's
//! [crate::gz::Limits], and the per-channel display names are capped.

use crate::error::Error;
use crate::model::Programme;
use crate::text::take_chars;
use crate::timefmt::days_from_civil;
use quick_xml::Reader;
use quick_xml::encoding::DecodingReader;
use quick_xml::events::{BytesStart, Event};
use regex::Regex;
use std::collections::HashMap;
use std::io::{self, BufRead, Read};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, LazyLock};

// ------------------------------------------------------------------ time

/// XMLTV time ("20240101120000 +0100", seconds and offset optional) to epoch seconds, or -1 if malformed.
pub fn parse_time(s: &str) -> i64 {
    let t = s.trim().as_bytes();
    if t.len() < 12 {
        return -1;
    }
    let n = |a: usize, b: usize| -> Option<i64> {
        let p = t.get(a..b)?;
        if p.iter().all(u8::is_ascii_digit) { std::str::from_utf8(p).ok()?.parse().ok() } else { None }
    };
    let f = || -> Option<i64> {
        let (y, mo, d, h, mi) = (n(0, 4)?, n(4, 6)?, n(6, 8)?, n(8, 10)?, n(10, 12)?);
        let has_sec = t.len() >= 14 && t[12].is_ascii_digit();
        let sec = if has_sec { n(12, 14)? } else { 0 };
        if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || sec > 59 {
            return None;
        }
        let rest = std::str::from_utf8(&t[if has_sec { 14 } else { 12 }..]).ok()?.trim().as_bytes();
        let mut off = 0;
        if rest.len() >= 5 && (rest[0] == b'+' || rest[0] == b'-') {
            let sign = if rest[0] == b'-' { -1 } else { 1 };
            let dig = |a: usize, b: usize| -> Option<i64> {
                let p = &rest[a..b];
                if p.iter().all(u8::is_ascii_digit) { std::str::from_utf8(p).ok()?.parse().ok() } else { None }
            };
            off = sign * (dig(1, 3)? * 3600 + dig(3, 5)? * 60);
        }
        Some(days_from_civil(y, mo, d) * 86400 + h * 3600 + mi * 60 + sec - off)
    };
    f().unwrap_or(-1)
}

// ------------------------------------------------------------------ episode numbers

static ONSCREEN: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\bS(?:eason)?\s*([0-9]{1,3})\s*[._x-]?\s*E(?:p(?:isode)?)?\s*([0-9]{1,4})\b").unwrap());
static EP_ONLY: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^\s*(?:E|Ep|Ep\.|Episode)\s*([0-9]{1,4})\s*$").unwrap());
static CROSS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\s*([0-9]{1,3})\s*[xX]\s*([0-9]{1,4})\s*$").unwrap());

/// "S2 E4" / "E4" / "S2" or None.
pub fn episode_label(season: Option<i64>, episode: Option<i64>) -> Option<String> {
    match (season, episode) {
        (Some(s), Some(e)) => Some(format!("S{s} E{e}")),
        (None, Some(e)) => Some(format!("E{e}")),
        (Some(s), None) => Some(format!("S{s}")),
        _ => None,
    }
}

/// xmltv_ns is "season.episode.part", each zero-based and optionally "n/total"; any part may be empty.
pub fn from_xmltv_ns(text: &str) -> Option<String> {
    let parts: Vec<&str> = text.trim().split('.').collect();
    let num = |i: usize| -> Option<i64> {
        let p = parts.get(i)?;
        let v = p.split('/').next()?.trim().parse::<i64>().ok()?;
        (v >= 0).then_some(v + 1)
    };
    episode_label(num(0), num(1))
}

pub fn from_onscreen(text: &str) -> Option<String> {
    if let Some(c) = ONSCREEN.captures(text) {
        return episode_label(c[1].parse().ok(), c[2].parse().ok());
    }
    if let Some(c) = CROSS.captures(text) {
        return episode_label(c[1].parse().ok(), c[2].parse().ok());
    }
    EP_ONLY.captures(text).and_then(|c| episode_label(None, c[1].parse().ok()))
}

/// Episode code from an `<episode-num system=...>`; ids such as dd_progid / crid are not episode numbers.
pub fn episode_parse(system: Option<&str>, text: &str) -> Option<String> {
    match system.map(|s| s.to_lowercase()).as_deref() {
        Some("xmltv_ns") => from_xmltv_ns(text),
        None | Some("") | Some("onscreen") | Some("sxxexx") => from_onscreen(text),
        _ => None,
    }
}

/// "S2 E4 Sub-title", either part optional; None when there is neither.
pub fn episode_line(code: Option<&str>, sub_title: Option<&str>) -> Option<String> {
    let parts: Vec<&str> = [code.filter(|c| !c.trim().is_empty()), sub_title.map(str::trim).filter(|s| !s.is_empty())].into_iter().flatten().collect();
    (!parts.is_empty()).then(|| parts.join(" "))
}

// ------------------------------------------------------------------ limits

#[derive(Clone, Copy, Debug)]
pub struct XmltvLimits {
    /// Largest single tag or text run, in bytes.
    pub max_token: usize,
    /// Display names kept per `<channel>`.
    pub max_names: usize,
    /// `<channel>` elements remembered.
    pub max_channels: usize,
    /// Characters kept of `<desc>` / `<sub-title>` (Kotlin: 400 / 120).
    pub max_desc: usize,
    pub max_sub: usize,
}

impl Default for XmltvLimits {
    fn default() -> Self {
        XmltvLimits { max_token: 4 << 20, max_names: 16, max_channels: 500_000, max_desc: 400, max_sub: 120 }
    }
}

/// Counts bytes consumed since the last reset and fails past `max` (a single huge token).
#[derive(Debug)]
struct TokenCap<R> {
    inner: R,
    used: Arc<AtomicUsize>,
    max: usize,
}

impl<R: BufRead> Read for TokenCap<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let b = self.fill_buf()?;
        let n = b.len().min(buf.len());
        buf[..n].copy_from_slice(&b[..n]);
        self.consume(n);
        Ok(n)
    }
}

impl<R: BufRead> BufRead for TokenCap<R> {
    fn fill_buf(&mut self) -> io::Result<&[u8]> {
        if self.used.load(Ordering::Relaxed) > self.max {
            return Err(crate::gz::limit_error(Error::Xml(format!("a single tag or text is larger than {} bytes", self.max))));
        }
        // never hand out more than the token budget left, so one huge read cannot slip past the check
        let room = self.max.saturating_sub(self.used.load(Ordering::Relaxed)).max(1);
        let b = self.inner.fill_buf()?;
        Ok(&b[..b.len().min(room)])
    }
    fn consume(&mut self, amt: usize) {
        self.used.fetch_add(amt, Ordering::Relaxed);
        self.inner.consume(amt)
    }
}

// ------------------------------------------------------------------ parser

/// Decodes the five predefined entities and numeric character references; anything else (DTD-defined entities) is
/// left out, never expanded.
pub fn unescape(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        let tail = &rest[i + 1..];
        match tail.find(';').filter(|&j| j <= 32) {
            Some(j) => {
                if let Some(c) = entity(&tail[..j]) {
                    out.push(c);
                }
                rest = &tail[j + 1..];
            }
            None => {
                out.push('&');
                rest = tail;
            }
        }
    }
    out.push_str(rest);
    out
}

fn entity(name: &str) -> Option<char> {
    match name {
        "amp" => Some('&'),
        "lt" => Some('<'),
        "gt" => Some('>'),
        "quot" => Some('"'),
        "apos" => Some('\''),
        _ => {
            let n = name.strip_prefix('#')?;
            let v = if let Some(h) = n.strip_prefix('x').or_else(|| n.strip_prefix('X')) { u32::from_str_radix(h, 16).ok()? } else { n.parse().ok()? };
            char::from_u32(v).filter(|c| *c != '\0')
        }
    }
}

struct Ctx<R: BufRead> {
    r: Reader<DecodingReader<TokenCap<R>>>,
    first: bool,
    used: Arc<AtomicUsize>,
    buf: Vec<u8>,
}

enum Ev {
    Start(String, Vec<(String, String)>),
    End,
    Text(String),
    Eof,
}

impl<R: BufRead> Ctx<R> {
    fn start(e: &BytesStart) -> Ev {
        let name = e.local_name().as_ref().to_string();
        let mut attrs = Vec::new();
        for a in e.attributes().with_checks(false).flatten() {
            attrs.push((a.key.local_name().as_ref().to_string(), unescape(&a.value)));
        }
        Ev::Start(name, attrs)
    }

    fn next(&mut self) -> crate::Result<Ev> {
        loop {
            self.used.store(0, Ordering::Relaxed);
            self.buf.clear();
            let ev = match self.r.read_event_into(&mut self.buf) {
                Ok(e) => e,
                Err(quick_xml::Error::Io(e)) => {
                    let e = Arc::try_unwrap(e).unwrap_or_else(|a| io::Error::new(a.kind(), a.to_string()));
                    return Err(crate::gz::unwrap_io(e));
                }
                Err(e) => return Err(Error::Xml(e.to_string())),
            };
            return Ok(match ev {
                Event::Start(e) => Self::start(&e),
                Event::End(_) => Ev::End,
                Event::Text(t) => Ev::Text(unescape(&t)),
                Event::CData(t) => Ev::Text(t.to_string()),
                // only predefined entities and character references; DTD-defined ones are dropped, never expanded
                Event::GeneralRef(r) => Ev::Text(entity(&r).map(String::from).unwrap_or_default()),
                Event::Decl(d) if self.first => {
                    // the declared encoding (ISO-8859-1, windows-1252, ...); only possible while the reader still
                    // holds the first bytes, which is where a declaration is
                    if let Some(enc) = d.encoder() {
                        let r = &mut self.r;
                        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| r.get_mut().set_encoding(enc)));
                    }
                    self.first = false;
                    continue;
                }
                Event::Eof => Ev::Eof,
                // DocType (never expanded), declaration (the reader decodes the declared encoding itself),
                // comments, PIs; Empty is expanded to Start + End
                _ => continue,
            });
        }
    }

    /// Text of the current element up to its end tag (nested elements' text included).
    fn text(&mut self) -> crate::Result<String> {
        let mut s = String::new();
        let mut depth = 1;
        loop {
            match self.next()? {
                Ev::Start(..) => depth += 1,
                Ev::End => {
                    depth -= 1;
                    if depth == 0 {
                        return Ok(s);
                    }
                }
                Ev::Text(t) => {
                    if s.len() < 1 << 20 {
                        s.push_str(&t)
                    }
                }
                Ev::Eof => return Ok(s),
            }
        }
    }
}

fn attr<'a>(a: &'a [(String, String)], k: &str) -> Option<&'a str> {
    a.iter().find(|(n, _)| n == k).map(|(_, v)| v.as_str())
}

/// Guide channel id -> display names seen (in document order).
pub type GuideNames = HashMap<String, Vec<String>>;

/// Parses an XMLTV document.
///
/// `resolve(channel attribute, display names seen so far)` returns the guide keys the programme belongs to (one
/// guide channel can feed several keys: its automatic match and a manual mapping); empty drops it. Only programmes
/// with `stop >= min_stop` and `start <= max_start` are resolved and sent to `sink`. Returns the display names.
/// On malformed XML the error is returned, after everything before the damage was already delivered.
pub fn parse<R: BufRead>(
    input: R,
    limits: XmltvLimits,
    min_stop: i64,
    max_start: i64,
    mut resolve: impl FnMut(&str, Option<&Vec<String>>) -> Vec<String>,
    mut sink: impl FnMut(Programme),
) -> crate::Result<GuideNames> {
    let used = Arc::new(AtomicUsize::new(0));
    let mut r = Reader::from_reader(DecodingReader::new(TokenCap { inner: input, used: used.clone(), max: limits.max_token }));
    {
        let c = r.config_mut();
        c.expand_empty_elements = true;
        c.check_end_names = false;
        c.trim_text(false);
    }
    let mut x = Ctx { r, used, buf: Vec::with_capacity(4096), first: true };
    let mut names: GuideNames = HashMap::new();
    loop {
        match x.next()? {
            Ev::Eof => return Ok(names),
            Ev::Start(n, a) if n == "channel" => read_channel(&mut x, attr(&a, "id").unwrap_or("").to_string(), &mut names, &limits)?,
            Ev::Start(n, a) if n == "programme" => read_programme(&mut x, &a, &names, &limits, min_stop, max_start, &mut resolve, &mut sink)?,
            _ => {}
        }
    }
}

fn read_channel<R: BufRead>(x: &mut Ctx<R>, id: String, names: &mut GuideNames, l: &XmltvLimits) -> crate::Result<()> {
    let mut depth = 1;
    while depth > 0 {
        match x.next()? {
            Ev::Start(n, _) if n == "display-name" => {
                let t = x.text()?.trim().to_string();
                if names.len() < l.max_channels || names.contains_key(&id) {
                    let v = names.entry(id.clone()).or_default();
                    if v.len() < l.max_names {
                        v.push(t);
                    }
                }
            }
            Ev::Start(..) => depth += 1,
            Ev::End => depth -= 1,
            Ev::Text(_) => {}
            Ev::Eof => return Ok(()),
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments, clippy::collapsible_match, clippy::collapsible_if)]
fn read_programme<R: BufRead>(
    x: &mut Ctx<R>,
    a: &[(String, String)],
    names: &GuideNames,
    l: &XmltvLimits,
    min_stop: i64,
    max_start: i64,
    resolve: &mut impl FnMut(&str, Option<&Vec<String>>) -> Vec<String>,
    sink: &mut impl FnMut(Programme),
) -> crate::Result<()> {
    let ch = attr(a, "channel").unwrap_or("");
    let start = parse_time(attr(a, "start").unwrap_or(""));
    let stop = parse_time(attr(a, "stop").unwrap_or(""));
    let keys = if start < 0 || stop < 0 || stop < min_stop || start > max_start { Vec::new() } else { resolve(ch, names.get(ch)) };
    let want = !keys.is_empty();
    let mut title: Option<String> = None;
    let mut desc: Option<String> = None;
    let mut sub: Option<String> = None;
    let mut ep_ns: Option<String> = None;
    let mut ep_on: Option<String> = None;
    let mut depth = 1;
    while depth > 0 {
        match x.next()? {
            Ev::Start(n, at) if want && depth == 1 => match n.as_str() {
                "title" => {
                    let s = x.text()?;
                    title.get_or_insert_with(|| s.trim().to_string());
                }
                "desc" => {
                    let s = x.text()?;
                    desc.get_or_insert_with(|| s.trim().to_string());
                }
                "sub-title" => {
                    let s = x.text()?;
                    if sub.is_none() {
                        sub = Some(s.trim().to_string()).filter(|v| !v.is_empty());
                    }
                }
                "episode-num" => {
                    let sys = attr(&at, "system").map(str::to_string);
                    let s = x.text()?;
                    match sys.as_deref() {
                        Some(v) if v.eq_ignore_ascii_case("xmltv_ns") => {
                            if ep_ns.is_none() {
                                ep_ns = from_xmltv_ns(&s);
                            }
                        }
                        None => {
                            if ep_on.is_none() {
                                ep_on = from_onscreen(&s);
                            }
                        }
                        Some(v) if v.eq_ignore_ascii_case("onscreen") => {
                            if ep_on.is_none() {
                                ep_on = from_onscreen(&s);
                            }
                        }
                        _ => {}
                    }
                }
                _ => depth += 1,
            },
            Ev::Start(..) => depth += 1,
            Ev::End => depth -= 1,
            Ev::Text(_) => {}
            Ev::Eof => return Ok(()),
        }
    }
    if want {
        let p = Programme {
            key: String::new(),
            start,
            stop,
            title: title.unwrap_or_default(),
            desc: desc.map(|d| take_chars(&d, l.max_desc)),
            sub: sub.map(|s| take_chars(&s, l.max_sub)),
            ep: ep_ns.or(ep_on),
        };
        let n = keys.len();
        for (i, k) in keys.into_iter().enumerate() {
            let mut q = if i + 1 == n { Programme { key: String::new(), ..p.clone() } } else { p.clone() };
            q.key = k;
            sink(q);
        }
    }
    Ok(())
}

/// Single-key resolver adapter (the Kotlin signature).
pub fn single(mut f: impl FnMut(&str, Option<&Vec<String>>) -> Option<String>) -> impl FnMut(&str, Option<&Vec<String>>) -> Vec<String> {
    move |c, n| f(c, n).into_iter().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(xml: &str, min: i64, max: i64, resolve: impl FnMut(&str, Option<&Vec<String>>) -> Option<String>) -> (Vec<Programme>, crate::Result<GuideNames>) {
        let mut got = Vec::new();
        let r = parse(xml.as_bytes(), XmltvLimits::default(), min, max, single(resolve), |p| got.push(p));
        (got, r)
    }

    #[test]
    fn kotlin_xmltv_time() {
        assert_eq!(parse_time("19700101000000 +0000"), 0);
        assert_eq!(parse_time("20240101120000 +0000"), 1704110400);
        assert_eq!(parse_time("20240101120000 +0100"), 1704110400 - 3600);
        assert_eq!(parse_time("20240101120000 -0130"), 1704110400 + 5400);
        assert_eq!(parse_time("202401011200"), 1704110400);
        assert_eq!(parse_time("202401011200 +0100"), 1704110400 - 3600);
        assert_eq!(parse_time("garbage"), -1);
        assert_eq!(parse_time("20241301120000 +0000"), -1);
        assert_eq!(parse_time("2024010112x000"), -1);
        assert_eq!(parse_time("20240101120000 +01x0"), -1);
    }

    #[test]
    fn kotlin_xmltv() {
        let xml = r#"<?xml version="1.0"?><tv>
            <channel id="one.example"><display-name>Channel One</display-name></channel>
            <channel id="x2"><display-name>Channel Two</display-name><icon src="a"/></channel>
            <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="one.example"><title lang="en">News</title><desc>Daily</desc></programme>
            <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="x2"><title>Film</title></programme>
            <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="other"><title>Dropped</title></programme>
            <programme start="20200101130000 +0000" stop="20200101140000 +0000" channel="one.example"><title>Old</title></programme>
            </tv>"#;
        let (got, r) = run(xml, 1704000000, 1705000000, |ch, names| {
            if ch == "one.example" {
                Some("s|one.example".into())
            } else if names.is_some_and(|n| n.iter().any(|x| x == "Channel Two")) {
                Some("s|channel two".into())
            } else {
                None
            }
        });
        let names = r.unwrap();
        assert_eq!(got.len(), 2);
        assert_eq!(got[0].title, "News");
        assert_eq!(got[0].desc.as_deref(), Some("Daily"));
        assert_eq!(got[1].key, "s|channel two");
        assert!(got[1].desc.is_none());
        assert_eq!(got[0].stop - got[0].start, 3600);
        assert_eq!(names["x2"], vec!["Channel Two"]);
    }

    #[test]
    fn kotlin_episode_fields() {
        let xml = r#"<tv><programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a">
            <title>Show</title><sub-title>The Return</sub-title><episode-num system="dd_progid">EP123</episode-num>
            <episode-num system="xmltv_ns">1.3.0/1</episode-num><episode-num system="onscreen">S9E9</episode-num></programme>
            <programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="a"><title>Other</title><episode-num system="onscreen">S03E12</episode-num></programme></tv>"#;
        let (got, r) = run(xml, 0, i64::MAX, |c, _| Some(format!("s|{c}")));
        r.unwrap();
        assert_eq!(got[0].ep.as_deref(), Some("S2 E4"));
        assert_eq!(got[0].sub.as_deref(), Some("The Return"));
        assert_eq!(got[1].ep.as_deref(), Some("S3 E12"));
        assert!(got[1].sub.is_none());
    }

    #[test]
    fn kotlin_episode_codes() {
        assert_eq!(episode_parse(Some("xmltv_ns"), "1.3.").as_deref(), Some("S2 E4"));
        assert_eq!(episode_parse(Some("xmltv_ns"), "1/5 . 3/10 . 0/1").as_deref(), Some("S2 E4"));
        assert_eq!(episode_parse(Some("xmltv_ns"), ".6.").as_deref(), Some("E7"));
        assert_eq!(episode_parse(Some("onscreen"), "S03E12").as_deref(), Some("S3 E12"));
        assert_eq!(episode_parse(Some("onscreen"), "1x02").as_deref(), Some("S1 E2"));
        assert_eq!(episode_parse(Some("onscreen"), "Season 2 Episode 5").as_deref(), Some("S2 E5"));
        assert_eq!(episode_parse(None, "Ep. 9").as_deref(), Some("E9"));
        assert!(episode_parse(Some("dd_progid"), "EP0123").is_none());
        assert_eq!(episode_line(Some("S2 E4"), Some("The Return")).as_deref(), Some("S2 E4 The Return"));
        assert!(episode_line(None, Some(" ")).is_none());
    }

    #[test]
    fn entities_cdata_latin1_and_multi_keys() {
        let xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><tv><programme start=\"20240101120000 +0000\" stop=\"20240101130000 +0000\" channel=\"a&amp;b\"><title>Tom &amp; Jerry &#233;&#x41; caf\u{e9}</title><desc><![CDATA[<b>raw</b>]]></desc></programme></tv>";
        let bytes: Vec<u8> = xml.chars().map(|c| c as u32 as u8).collect();
        let mut got = Vec::new();
        parse(&bytes[..], XmltvLimits::default(), 0, i64::MAX, |c, _| vec![format!("s|{c}"), "m|x".to_string()], |p| got.push(p)).unwrap();
        assert_eq!(got.len(), 2);
        assert_eq!(got[0].key, "s|a&b");
        assert_eq!(got[1].key, "m|x");
        assert_eq!(got[0].title, "Tom & Jerry éA café");
        assert_eq!(got[0].desc.as_deref(), Some("<b>raw</b>"));
    }

    #[test]
    fn billion_laughs_and_xxe_are_inert() {
        let lol = r#"<?xml version="1.0"?>
<!DOCTYPE tv [
 <!ENTITY lol "lol">
 <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
 <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
 <!ENTITY lol9 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
 <!ENTITY xxe SYSTEM "file:///etc/passwd">
]>
<tv><programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><title>A&lol9;B&xxe;C</title></programme></tv>"#;
        let (got, r) = run(lol, 0, i64::MAX, |c, _| Some(c.to_string()));
        r.unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].title, "ABC");
    }

    #[test]
    fn huge_token_is_refused() {
        let mut xml = String::from("<tv><programme start=\"20240101120000 +0000\" stop=\"20240101130000 +0000\" channel=\"a\"><title>");
        xml.push_str(&"x".repeat(2 << 20));
        xml.push_str("</title></programme></tv>");
        let mut n = 0;
        let r = parse(xml.as_bytes(), XmltvLimits { max_token: 1 << 20, ..Default::default() }, 0, i64::MAX, |c, _| vec![c.to_string()], |_| n += 1);
        assert!(matches!(r, Err(Error::Xml(_))), "{r:?}");
        assert_eq!(n, 0);
    }

    #[test]
    fn malformed_keeps_what_came_before() {
        let xml = r#"<tv><programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><title>One</title></programme><programme start="1" <<<"#;
        let (got, r) = run(xml, 0, i64::MAX, |c, _| Some(c.to_string()));
        assert_eq!(got.len(), 1);
        assert!(r.is_err());
    }

    #[test]
    fn limits_trim_text() {
        let xml = format!(
            r#"<tv><programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><title> T </title><desc>{}</desc><sub-title>{}</sub-title><sub-title>second</sub-title></programme></tv>"#,
            "d".repeat(1000),
            "s".repeat(500)
        );
        let (got, r) = run(&xml, 0, i64::MAX, |c, _| Some(c.to_string()));
        r.unwrap();
        assert_eq!(got[0].title, "T");
        assert_eq!(got[0].desc.as_ref().unwrap().len(), 400);
        assert_eq!(got[0].sub.as_ref().unwrap().len(), 120);
    }

    /// 2000 channels x 7 days generated on the fly: the parser never holds the document.
    #[test]
    fn streaming_guide_stays_small() {
        struct Gen {
            ch: i64,
            channels: i64,
            per: i64,
            buf: Vec<u8>,
            pos: usize,
        }
        fn ts(min: i64) -> String {
            format!("202611{:02}{:02}{:02}00 +0000", 11 + min / 1440, (min % 1440) / 60, min % 60)
        }
        impl Read for Gen {
            fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
                if self.pos >= self.buf.len() {
                    self.ch += 1;
                    if self.ch > self.channels {
                        return Ok(0);
                    }
                    self.pos = 0;
                    if self.ch == self.channels {
                        self.buf = b"</tv>".to_vec();
                    } else {
                        let c = self.ch;
                        let mut s = format!("<channel id=\"c{c}\"><display-name>Channel {c}</display-name></channel>");
                        for i in 0..self.per {
                            s.push_str(&format!("<programme start=\"{}\" stop=\"{}\" channel=\"c{c}\"><title>Programme {c}-{i}</title><desc>{}</desc></programme>", ts(i * 90), ts(i * 90 + 90), "d".repeat(120)));
                        }
                        self.buf = s.into_bytes();
                    }
                }
                let n = out.len().min(self.buf.len() - self.pos);
                out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
                self.pos += n;
                Ok(n)
            }
        }
        let g = io::BufReader::new(io::Cursor::new(b"<?xml version=\"1.0\"?><tv>".to_vec()).chain(Gen { ch: -1, channels: 2000, per: 112, buf: Vec::new(), pos: 0 }));
        let mut n = 0u64;
        let names = parse(g, XmltvLimits::default(), 0, i64::MAX, |c, _| vec![format!("s|{c}")], |_| n += 1).unwrap();
        assert_eq!(n, 2000 * 112);
        assert_eq!(names.len(), 2000);
    }
}
