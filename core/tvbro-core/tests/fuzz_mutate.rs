// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Mutation fuzzing of the provider-data parsers (M3U, XMLTV + gzip, Xtream JSON, catch-up templates): valid seed
//! documents are cut, spliced, repeated and sprinkled with hostile bytes, and every parser must return (no panic, no
//! hang, bounded output). Deterministic (fixed seeds); `TVBRO_FUZZ_ITERS` raises the count (default 3000 per parser).

use std::collections::HashSet;
use std::io::Write;
use tvbro_core::{catchup, epgload, gz, m3u, xmltv, xtream};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
    fn below(&mut self, n: usize) -> usize {
        if n == 0 { 0 } else { (self.next() % n as u64) as usize }
    }
}

const NASTY: &[&[u8]] = &[
    b"\0", b"\xff\xfe", b"\xef\xbb\xbf", b"\xc3", b"\xf0\x9f", b"\r", b"\n", b"\r\n", b"\"", b"'", b",", b":", b"=",
    b"<", b">", b"&", b"&amp;", b"&#xFFFFFF;", b"&#99999999999;", b"]]>", b"<![CDATA[", b"<!DOCTYPE x [<!ENTITY a \"aaaa\">]>",
    b"&a;", b"{", b"}", b"[", b"]", b"null", b"true", b"-1", b"99999999999999999999999", b"1e309", b"-9223372036854775808",
    b"\\u0000", b"\\ud800", b"#EXTINF:", b"#EXTM3U", b"#EXTGRP:", b"#KODIPROP:", b"tvg-id=\"", b"catchup=\"",
    b"catchup-source=\"", b"catchup-days=\"", b"{utc}", b"{start}", b"{duration}", b"${start}", b"{utc:Y-m-d}",
    b"../../", b"file:///", b"http://", b"javascript:", b"%00", b"20301231235959 +2400", b"99999999999999 -9999",
    b"\x1f\x8b\x08\x00", b"start=\"", b"stop=\"", b"channel=\"", b"</programme>", b"<programme ",
];

fn mutate(r: &mut Rng, seeds: &[Vec<u8>]) -> Vec<u8> {
    let mut d = seeds[r.below(seeds.len())].clone();
    for _ in 0..1 + r.below(8) {
        match r.below(9) {
            0 if !d.is_empty() => { let at = r.below(d.len()); d.truncate(at); }
            1 => { let at = r.below(d.len() + 1); let n = NASTY[r.below(NASTY.len())]; d.splice(at..at, n.iter().copied()); }
            2 if !d.is_empty() => { let at = r.below(d.len()); d[at] = r.next() as u8; }
            3 if !d.is_empty() => { // repeat a slice
                let a = r.below(d.len()); let b = (a + r.below(64)).min(d.len());
                let s = d[a..b].to_vec(); for _ in 0..r.below(20) { d.extend_from_slice(&s); }
            }
            4 if !d.is_empty() => { let a = r.below(d.len()); let b = (a + r.below(32)).min(d.len()); d.drain(a..b); }
            5 => { let o = &seeds[r.below(seeds.len())]; if !o.is_empty() { let a = r.below(o.len()); d.extend_from_slice(&o[a..]); } }
            6 => { let n = NASTY[r.below(NASTY.len())]; for _ in 0..r.below(200) { d.extend_from_slice(n); } }
            7 => { let depth = r.below(3000); let mut x = vec![b'['; depth]; x.extend(std::iter::repeat_n(b']', depth)); let at = r.below(d.len() + 1); d.splice(at..at, x); }
            _ => { let at = r.below(d.len() + 1); d.insert(at, r.next() as u8); }
        }
    }
    d
}

fn iters() -> usize {
    std::env::var("TVBRO_FUZZ_ITERS").ok().and_then(|v| v.parse().ok()).unwrap_or(3000)
}

fn gzip(b: &[u8]) -> Vec<u8> {
    let mut e = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
    e.write_all(b).unwrap();
    e.finish().unwrap()
}

const M3U: &str = "#EXTM3U url-tvg=\"http://example.com/epg.xml.gz\" x-tvg-url=\"http://example.com/b.xml\"\n\
#EXTINF:-1 tvg-id=\"one.example\" tvg-name=\"One\" tvg-logo=\"http://example.com/1.png\" group-title=\"News\" tvg-chno=\"7\" catchup=\"default\" catchup-days=\"3\" catchup-source=\"http://example.com/c/{utc}/{duration}?o={offset}\",One HD\n\
http://example.com/live/u/p/1.ts\n\
#EXTGRP:Sport\n#EXTINF:0 catchup=\"append\" catchup-source=\"?s=${start}&e=${end}\",Two\n#KODIPROP:inputstream=x\nhttp://example.com/2.m3u8\n\
#EXTINF:-1 catchup=\"shift\",Three\nhttp://example.com/3?a=1\n";

const XMLTV: &str = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv>\
<channel id=\"one.example\"><display-name>One HD</display-name><icon src=\"http://example.com/1.png\"/></channel>\
<channel id=\"two\"><display-name lang=\"en\">Two</display-name></channel>\
<programme start=\"20301101120000 +0100\" stop=\"20301101130000 +0100\" channel=\"one.example\"><title lang=\"en\">News &amp; more</title>\
<sub-title>Part 1</sub-title><desc>A &lt;b&gt; long text</desc><episode-num system=\"xmltv_ns\">1.2.</episode-num></programme>\
<programme start=\"20301101130000\" stop=\"20301101140000 -0500\" channel=\"two\"><title><![CDATA[Film]]></title></programme></tv>";

const CATS: &str = r#"[{"category_id":"1","category_name":"News","parent_id":0},{"category_id":2,"category_name":"Sport"}]"#;
const STREAMS: &str = r#"[{"num":1,"name":"One","stream_type":"live","stream_id":101,"stream_icon":"http://example.com/1.png","epg_channel_id":"one.example","category_id":"1","tv_archive":1,"tv_archive_duration":"5"},
{"num":"2","name":"Two","stream_id":"102","category_id":2,"tv_archive":"0","direct_source":"http://example.com/x.ts"},{"name":null,"stream_id":null}]"#;
const MOVIES: &str = r#"[{"num":1,"name":"Film (2020)","stream_id":5,"stream_icon":"http://example.com/p.jpg","rating":"7.5","category_id":"1","container_extension":"mkv","added":"1700000000"},
{"series_id":9,"name":"Show","cover":"","category_id":"x","rating_5based":4.5,"last_modified":"abc"}]"#;
const VOD_INFO: &str = r#"{"info":{"name":"Film","plot":"Text","duration":"01:42:00","duration_secs":6120,"genre":"Drama","releasedate":"2020-01-01","backdrop_path":["http://example.com/b.jpg"],"youtube_trailer":"abc"},"movie_data":{"stream_id":5,"container_extension":"mp4"}}"#;
const SERIES_INFO: &str = r#"{"info":{"name":"Show","plot":"P","episode_run_time":"45"},"seasons":[{"season_number":1,"name":"S1"}],"episodes":{"1":[{"id":"77","episode_num":1,"title":"Show - S01E01 - Pilot","container_extension":"mkv","info":{"duration":"00:44:10","plot":"x"}}],"2":{"0":{"id":78,"episode_num":"2","title":"E2"}}}}"#;

fn catchup_all(chs: &[tvbro_core::Channel], r: &mut Rng) {
    for c in chs {
        let s = (r.next() % 4_000_000_000) as i64 - 1_000_000_000;
        let e = s + (r.next() % 100_000) as i64;
        let now = s + (r.next() % 200_000) as i64;
        let _ = catchup::supports(c, s, now);
        if let Some(u) = catchup::build(c, s, e, now) { assert!(u.len() < 1 << 20, "catch-up address grew to {} bytes", u.len()); }
        let _ = catchup::classify(Some(c), s, e, now);
    }
}

#[test]
fn m3u_mutations_never_panic() {
    let seeds = vec![M3U.as_bytes().to_vec(), b"#EXTM3U\n".to_vec(), b"#EXTINF:-1,x\n\n\n".to_vec()];
    let mut r = Rng(0x9E37_79B9_7F4A_7C15);
    for _ in 0..iters() {
        let d = mutate(&mut r, &seeds);
        let a = m3u::parse(&String::from_utf8_lossy(&d), "s");
        let mut b = Vec::new();
        let _ = m3u::parse_reader(&d[..], "s", m3u::M3uOptions::default(), |c| b.push(c));
        assert!(a.channels.len() <= d.len() && b.len() <= d.len());
        catchup_all(&a.channels, &mut r);
    }
}

#[test]
fn xmltv_mutations_never_panic() {
    let plain = XMLTV.as_bytes().to_vec();
    let seeds = vec![plain.clone(), gzip(&plain)];
    let mut r = Rng(0xD1B5_4A32_D192_ED03);
    let wanted: HashSet<String> = ["one.example".to_string(), "two".to_string()].into_iter().collect();
    for i in 0..iters() {
        let mut d = mutate(&mut r, &seeds);
        if i % 4 == 0 { d = gzip(&d); } // valid gzip around damaged XML
        let _ = xmltv::parse(&d[..], xmltv::XmltvLimits::default(), 0, i64::MAX, |c, _| vec![c.to_string()], |_| {});
        let o = epgload::LoadOptions { source_id: "s".into(), wanted: wanted.clone(), min_stop: 0, max_start: i64::MAX, limits: gz::Limits::default() };
        let mut n = 0usize;
        let _ = epgload::each_programme(&d[..], &o, |p| { n += 1; assert!(p.title.len() < 1 << 20); true });
        let _ = epgload::digest_guide(&d[..], &o);
        let _ = gz::read_all(&d[..], gz::Limits::default());
        let _ = xmltv::parse_time(&String::from_utf8_lossy(&d[..d.len().min(40)]));
    }
}

#[test]
fn xtream_mutations_never_panic() {
    let seeds: Vec<Vec<u8>> = [CATS, STREAMS, MOVIES, VOD_INFO, SERIES_INFO, "[]", "{}"].iter().map(|s| s.as_bytes().to_vec()).collect();
    let mut r = Rng(0x2545_F491_4F6C_DD1D);
    for _ in 0..iters() {
        let a = mutate(&mut r, &seeds);
        let b = mutate(&mut r, &seeds);
        let cats = xtream::parse_live_categories(&a);
        let _ = xtream::parse_categories(&a);
        let list = xtream::parse_live_streams(&b);
        let _ = xtream::is_complete(&b);
        let _ = xtream::count_live_streams_strict(&b);
        let chs = xtream::to_channels("s", "http://example.com:8080", "u", "p", &cats, &list);
        catchup_all(&chs, &mut r);
        let _ = xtream::parse_movies(&b, "s", &cats, 10_000);
        let _ = xtream::parse_series_list(&b, "s", &cats, 10_000);
        let _ = xtream::parse_vod_info(&b);
        let _ = xtream::parse_series_info(&b);
        let t = String::from_utf8_lossy(&a[..a.len().min(30)]).into_owned();
        let _ = xtream::seconds(Some(&t));
        let _ = xtream::runtime(Some(&t));
        let _ = xtream::clean_title(&t, &String::from_utf8_lossy(&b[..b.len().min(60)]));
        let _ = xtream::hls_variant(&t);
        let _ = xtream::choose_format(&t, "hls");
    }
}

/// A gzip bomb (1 GB of zeros compressed to ~1 MB) is cut off by the size limit instead of filling the memory.
#[test]
fn gzip_bomb_is_bounded() {
    let mut e = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
    let zeros = vec![0u8; 1 << 20];
    for _ in 0..1024 { e.write_all(&zeros).unwrap(); }
    let bomb = e.finish().unwrap();
    let r = gz::read_all(&bomb[..], gz::Limits::default());
    assert!(r.is_err(), "the bomb was inflated ({} bytes)", r.map(|v| v.len()).unwrap_or(0));
}
