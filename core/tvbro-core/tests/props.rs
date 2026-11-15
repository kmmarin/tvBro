// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Property / fuzz-style tests: the parsers never panic on arbitrary input and keep their invariants.

use proptest::prelude::*;
use tvbro_core::{channels, guide, m3u, search, update, xmltv, xtream};

proptest! {
    #![proptest_config(ProptestConfig { cases: 400, ..ProptestConfig::default() })]

    #[test]
    fn m3u_never_panics(data in proptest::collection::vec(any::<u8>(), 0..4096)) {
        let text = String::from_utf8_lossy(&data);
        let r = m3u::parse(&text, "s");
        for c in &r.channels {
            prop_assert!(c.key.starts_with("s|"));
            prop_assert!(!c.group.trim().is_empty());
            prop_assert!(!c.url.starts_with('#'));
        }
    }

    #[test]
    fn m3u_round_trips_names(names in proptest::collection::vec("[A-Za-z0-9 ,.:()+-]{1,30}", 1..20)) {
        let mut t = String::from("#EXTM3U\n");
        for (i, n) in names.iter().enumerate() {
            t.push_str(&format!("#EXTINF:-1 tvg-id=\"id{i}\" group-title=\"G\",{n}\nhttp://example.com/{i}.ts\n"));
        }
        let r = m3u::parse(&t, "s");
        prop_assert_eq!(r.channels.len(), names.len());
        for (c, n) in r.channels.iter().zip(&names) {
            let want = if n.trim().is_empty() { c.name.clone() } else { n.trim().to_string() };
            prop_assert_eq!(&c.name, &want);
        }
    }

    #[test]
    fn xmltv_never_panics(data in proptest::collection::vec(any::<u8>(), 0..4096)) {
        let _ = xmltv::parse(&data[..], xmltv::XmltvLimits::default(), 0, i64::MAX, |c, _| vec![c.to_string()], |_| {});
    }

    #[test]
    fn xmltv_structured_never_panics(title in ".{0,40}", ch in "[a-z&;<>\"']{0,8}", start in "[0-9 +-]{0,20}") {
        let doc = format!("<tv><programme start=\"{start}\" stop=\"20300101000000 +0000\" channel=\"{ch}\"><title>{title}</title></programme></tv>");
        let _ = xmltv::parse(doc.as_bytes(), xmltv::XmltvLimits::default(), 0, i64::MAX, |c, _| vec![c.to_string()], |_| {});
    }

    #[test]
    fn xmltv_time_is_total(s in ".{0,30}") {
        let t = xmltv::parse_time(&s);
        prop_assert!(t >= -1 || t < -1); // no panic, any value
    }

    #[test]
    fn xmltv_time_matches_civil(y in 1970i64..2100, mo in 1i64..=12, d in 1i64..=28, h in 0i64..24, mi in 0i64..60, off in -14i64..=14) {
        let sign = if off < 0 { '-' } else { '+' };
        let s = format!("{y:04}{mo:02}{d:02}{h:02}{mi:02}00 {sign}{:02}00", off.abs());
        let base = xmltv::parse_time(&format!("{y:04}{mo:02}{d:02}{h:02}{mi:02}00 +0000"));
        prop_assert_eq!(xmltv::parse_time(&s), base - off * 3600);
    }

    #[test]
    fn json_lists_never_panic(data in proptest::collection::vec(any::<u8>(), 0..2048)) {
        let _ = xtream::parse_movies(&data, "s", &[], 100);
        let _ = xtream::parse_series_info(&data);
        let _ = xtream::parse_vod_info(&data);
        let _ = xtream::parse_categories(&data);
        let _ = update::parse_latest(&String::from_utf8_lossy(&data), "o/r", update::Platform::Android);
    }

    #[test]
    fn norm_name_is_idempotent(n in ".{0,40}") {
        let a = channels::norm_name(&n);
        prop_assert_eq!(channels::norm_name(&a), a.clone());
    }

    #[test]
    fn fold_is_idempotent(n in ".{0,40}") {
        let a = search::fold(&n);
        prop_assert_eq!(search::fold(&a), a);
    }

    #[test]
    fn guide_window_never_in_the_past(win in 0i64..4_000_000_000, delta in -200_000i64..200_000, now in 1_000_000_000i64..3_000_000_000) {
        let w = guide::shifted(win, delta, now);
        prop_assert!(w >= guide::floor30(now));
        prop_assert!(w <= guide::floor30(now + guide::AHEAD_SEC));
        prop_assert_eq!(w % 1800 == 0, (win + delta) % 1800 == 0 || w == guide::floor30(now) || w == guide::floor30(now + guide::AHEAD_SEC));
    }

    #[test]
    fn version_order_is_strict(a in proptest::collection::vec(0u64..20, 1..4), b in proptest::collection::vec(0u64..20, 1..4)) {
        let sa = a.iter().map(|x| x.to_string()).collect::<Vec<_>>().join(".");
        let sb = b.iter().map(|x| x.to_string()).collect::<Vec<_>>().join(".");
        prop_assert!(!(update::is_newer(&sa, &sb) && update::is_newer(&sb, &sa)));
        prop_assert!(!update::is_newer(&sa, &sa));
    }
}
