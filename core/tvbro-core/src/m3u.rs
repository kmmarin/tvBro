// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Extended M3U / M3U8 playlists (#EXTM3U, #EXTINF with key="value" attributes, #EXTGRP), streamed line by line.
//!
//! Port of shared/.../M3uParser.kt. The web server's Python port differs in two places, both handled here:
//! - channel keys: the apps hash the URL line with the JVM `String.hashCode()`, the web server with CRC-32
//!   ([KeyStyle]); each platform keeps its stored keys by choosing its style.
//! - the display name: Kotlin takes the text after the first comma that follows the last attribute, Python after the
//!   last comma of the line. Kotlin's rule keeps names with commas ("News, Weather") whole and is the one used.

use crate::javahash::{java_string_hash, kotlin_hex};
use crate::lines::{DEFAULT_MAX_LINE, for_each_line};
use crate::model::{Catchup, Channel};
use crate::text::{kotlin_blank, kotlin_to_int, kotlin_trim};
use regex::Regex;
use std::collections::HashMap;
use std::io::BufRead;
use std::sync::LazyLock;

static ATTR: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"([A-Za-z0-9_.-]+)="([^"]*)""#).unwrap());

/// How the last part of a channel key is made from the stream URL line.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum KeyStyle {
    /// Android / desktop: Kotlin `line.hashCode().toString(16)`.
    #[default]
    JavaHash,
    /// Web server: `zlib.crc32(line.encode()):x`.
    Crc32,
}

#[derive(Clone, Debug)]
pub struct M3uOptions {
    pub key_style: KeyStyle,
    /// Longer lines are cut (a hostile file without newlines cannot grow memory).
    pub max_line: usize,
    /// Stop after this many channels.
    pub max_channels: usize,
}

impl Default for M3uOptions {
    fn default() -> Self {
        M3uOptions { key_style: KeyStyle::JavaHash, max_line: DEFAULT_MAX_LINE, max_channels: 2_000_000 }
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct M3uResult {
    pub channels: Vec<Channel>,
    pub epg_urls: Vec<String>,
}

fn attrs(line: &str) -> HashMap<String, String> {
    let mut m = HashMap::new();
    for c in ATTR.captures_iter(line) {
        m.insert(c[1].to_lowercase(), c[2].to_string());
    }
    m
}

fn name_of(line: &str) -> String {
    let last = ATTR.find_iter(line).last().map(|m| m.end() as isize - 1).unwrap_or_else(|| line.find(':').map(|i| i as isize).unwrap_or(-1));
    let from = last.max(0) as usize;
    match line[from..].find(',') {
        Some(i) => kotlin_trim(&line[from + i + 1..]).to_string(),
        None => String::new(),
    }
}

fn starts_with_ci(s: &str, p: &str) -> bool {
    s.len() >= p.len() && s.as_bytes()[..p.len()].eq_ignore_ascii_case(p.as_bytes())
}

fn to_int(s: Option<&String>) -> Option<i32> {
    s.and_then(|v| kotlin_to_int(v))
}

fn non_blank(s: Option<&String>) -> Option<String> {
    s.filter(|v| !kotlin_blank(v)).cloned()
}

/// Streaming parser state: feed lines, take channels as they complete.
#[derive(Debug)]
pub struct M3uParser {
    source_id: String,
    opts: M3uOptions,
    attrs: Option<HashMap<String, String>>,
    name: String,
    grp: Option<String>,
    count: usize,
    pub epg_urls: Vec<String>,
}

impl M3uParser {
    pub fn new(source_id: impl Into<String>, opts: M3uOptions) -> Self {
        M3uParser { source_id: source_id.into(), opts, attrs: None, name: String::new(), grp: None, count: 0, epg_urls: Vec::new() }
    }

    /// Number of channels produced so far.
    pub fn count(&self) -> usize {
        self.count
    }

    /// One line of the playlist; returns the channel it completes, if any.
    pub fn line(&mut self, raw: &str) -> Option<Channel> {
        // Kotlin trim rules (not Rust's): the trimmed URL line is hashed into the channel key
        let t = kotlin_trim(raw);
        let line = t.strip_prefix('\u{FEFF}').unwrap_or(t);
        if line.is_empty() {
            return None;
        }
        if starts_with_ci(line, "#EXTM3U") {
            let h = attrs(line);
            if let Some(src) = h.get("url-tvg").or_else(|| h.get("x-tvg-url")) {
                self.epg_urls.extend(src.split(',').map(kotlin_trim).filter(|s| !s.is_empty()).map(String::from));
            }
            return None;
        }
        if starts_with_ci(line, "#EXTINF") {
            self.attrs = Some(attrs(line));
            self.name = name_of(line);
            return None;
        }
        if starts_with_ci(line, "#EXTGRP:") {
            self.grp = Some(kotlin_trim(&line[8..]).to_string());
            return None;
        }
        if line.starts_with('#') {
            return None;
        }
        let at = self.attrs.take().unwrap_or_default();
        let tvg_id = non_blank(at.get("tvg-id"));
        let title = if kotlin_blank(&self.name) {
            at.get("tvg-name").cloned().unwrap_or_else(|| format!("Channel {}", self.count + 1))
        } else {
            std::mem::take(&mut self.name)
        };
        let kind = at.get("catchup").or_else(|| at.get("catchup-type")).map(|s| s.to_lowercase()).filter(|s| !kotlin_blank(s));
        let src = non_blank(at.get("catchup-source"));
        let days = to_int(at.get("catchup-days")).or_else(|| to_int(at.get("tvg-rec"))).unwrap_or(if kind.is_some() { 7 } else { 0 });
        let hash = match self.opts.key_style {
            KeyStyle::JavaHash => kotlin_hex(java_string_hash(line)),
            KeyStyle::Crc32 => format!("{:x}", crc32fast::hash(line.as_bytes())),
        };
        let group = at.get("group-title").cloned().or_else(|| self.grp.take()).filter(|g| !kotlin_blank(g)).unwrap_or_else(|| "Ungrouped".to_string());
        let ch = Channel::new(
            format!("{}|{}|{}", self.source_id, tvg_id.as_deref().unwrap_or(""), hash),
            self.source_id.clone(),
            title,
            non_blank(at.get("tvg-logo")),
            group,
            line,
            tvg_id,
            to_int(at.get("tvg-chno")).or_else(|| to_int(at.get("channel-number"))).unwrap_or(0),
            kind.map(|k| Catchup::new(k, src)),
            days,
        );
        self.name.clear();
        self.grp = None;
        self.count += 1;
        Some(ch)
    }
}

/// Streams a playlist from `r`, handing each channel to `sink` (memory stays flat for huge playlists).
/// Returns the guide URLs from the #EXTM3U header.
pub fn parse_reader<R: BufRead>(r: R, source_id: &str, opts: M3uOptions, mut sink: impl FnMut(Channel)) -> crate::Result<Vec<String>> {
    let max_line = opts.max_line;
    let max = opts.max_channels;
    let mut p = M3uParser::new(source_id, opts);
    for_each_line(r, max_line, |l| {
        if let Some(c) = p.line(l) {
            sink(c);
        }
        p.count < max
    })?;
    Ok(p.epg_urls)
}

/// Whole playlist in memory (small playlists, tests).
pub fn parse_str(text: &str, source_id: &str, opts: M3uOptions) -> M3uResult {
    let mut channels = Vec::new();
    let epg_urls = parse_reader(text.as_bytes(), source_id, opts, |c| channels.push(c)).unwrap_or_default();
    M3uResult { channels, epg_urls }
}

/// Kotlin-compatible convenience: `M3uParser.parse(text.lineSequence(), sourceId)`.
pub fn parse(text: &str, source_id: &str) -> M3uResult {
    parse_str(text, source_id, M3uOptions::default())
}

#[cfg(test)]
pub(crate) fn tests_sample() -> &'static str {
    tests::SAMPLE
}

#[cfg(test)]
mod tests {
    use super::*;

    pub const SAMPLE: &str = "#EXTM3U url-tvg=\"http://example.invalid/guide.xml.gz\"\n\
#EXTINF:-1 tvg-id=\"one.example\" tvg-name=\"One HD\" tvg-logo=\"http://example.invalid/1.png\" group-title=\"News, World\" tvg-chno=\"7\" catchup=\"append\" catchup-source=\"?start=${start}&end=${end}\" catchup-days=\"3\",Channel One\n\
http://example.invalid/live/one.m3u8\n\
#EXTGRP:Sports\n\
#EXTINF:-1,Channel Two\n\
http://example.invalid/live/two.ts\n";

    #[test]
    fn kotlin_parsers_test_m3u() {
        let r = parse(SAMPLE, "s1");
        assert_eq!(r.epg_urls, vec!["http://example.invalid/guide.xml.gz"]);
        assert_eq!(r.channels.len(), 2);
        let a = &r.channels[0];
        assert_eq!(a.name, "Channel One");
        assert_eq!(a.tvg_id.as_deref(), Some("one.example"));
        assert_eq!(a.group, "News, World");
        assert_eq!(a.number, 7);
        assert_eq!(a.catchup_days, 3);
        assert_eq!(a.catchup.as_ref().unwrap().kind, "append");
        assert_eq!(a.catchup.as_ref().unwrap().template.as_deref(), Some("?start=${start}&end=${end}"));
        let b = &r.channels[1];
        assert_eq!(b.group, "Sports");
        assert!(b.catchup.is_none());
        assert_eq!(b.url, "http://example.invalid/live/two.ts");
        assert_eq!(b.name, "Channel Two");
    }

    #[test]
    fn keys_match_both_platforms() {
        let line = "http://example.invalid/live/one.m3u8";
        let r = parse(SAMPLE, "s1");
        assert_eq!(r.channels[0].key, format!("s1|one.example|{}", kotlin_hex(java_string_hash(line))));
        assert_eq!(r.channels[1].key, format!("s1||{}", kotlin_hex(java_string_hash("http://example.invalid/live/two.ts"))));
        let w = parse_str(SAMPLE, "p0", M3uOptions { key_style: KeyStyle::Crc32, ..Default::default() });
        assert_eq!(w.channels[0].key, format!("p0|one.example|{:x}", crc32fast::hash(line.as_bytes())));
    }

    #[test]
    fn fallbacks() {
        let t = "\u{FEFF}#EXTM3U x-tvg-url=\"http://a.example/1.xml, http://b.example/2.xml\"\r\n\
#EXTINF:-1 tvg-name=\"From Attr\",\r\n\
http://example.com/a\r\n\
#EXTINF:-1 group-title=\"\" catchup=\"Shift\" tvg-rec=\"2\" channel-number=\"12\",X\r\n\
http://example.com/b\r\n\
http://example.com/c\r\n\
#extinf:-1 catchup-type=\"default\" catchup-days=\"bad\" tvg-chno=\"x\",Name, With Comma\n\
http://example.com/d\n";
        let r = parse(t, "s");
        assert_eq!(r.epg_urls, vec!["http://a.example/1.xml", "http://b.example/2.xml"]);
        assert_eq!(r.channels.len(), 4);
        assert_eq!(r.channels[0].name, "From Attr");
        assert_eq!(r.channels[0].group, "Ungrouped");
        assert_eq!(r.channels[1].group, "Ungrouped");
        assert_eq!(r.channels[1].catchup.as_ref().unwrap().kind, "shift");
        assert_eq!(r.channels[1].catchup_days, 2);
        assert_eq!(r.channels[1].number, 12);
        assert_eq!(r.channels[2].name, "Channel 3");
        assert!(r.channels[2].catchup.is_none());
        assert_eq!(r.channels[3].name, "Name, With Comma");
        assert_eq!(r.channels[3].catchup_days, 7);
        assert_eq!(r.channels[3].number, 0);
    }

    #[test]
    fn huge_streams_without_holding_it() {
        // 200k channels generated on the fly and counted through the sink
        struct Gen {
            i: usize,
            n: usize,
            buf: Vec<u8>,
            pos: usize,
        }
        impl std::io::Read for Gen {
            fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
                if self.pos >= self.buf.len() {
                    if self.i >= self.n {
                        return Ok(0);
                    }
                    self.buf = format!("#EXTINF:-1 tvg-id=\"c{0}\" group-title=\"G{1}\",Channel {0}\nhttp://example.com/{0}.ts\n", self.i, self.i % 50).into_bytes();
                    self.pos = 0;
                    self.i += 1;
                }
                let n = out.len().min(self.buf.len() - self.pos);
                out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
                self.pos += n;
                Ok(n)
            }
        }
        let g = std::io::BufReader::new(Gen { i: 0, n: 200_000, buf: Vec::new(), pos: 0 });
        let mut n = 0;
        let mut last = String::new();
        parse_reader(g, "s", M3uOptions::default(), |c| {
            n += 1;
            last = c.name;
        })
        .unwrap();
        assert_eq!(n, 200_000);
        assert_eq!(last, "Channel 199999");
    }

    #[test]
    fn channel_cap() {
        let r = parse_str(SAMPLE, "s", M3uOptions { max_channels: 1, ..Default::default() });
        assert_eq!(r.channels.len(), 1);
    }
}
