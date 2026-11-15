// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Archive (catch-up) stream URLs and where a programme sits relative to now (Catchup.kt, SearchLogic.kt ProgTime).

use crate::model::Channel;
use crate::timefmt::utc_fields;
use regex::{Captures, Regex};
use std::sync::LazyLock;

static PH: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\$?\{(\w+)\}").unwrap());

/// The channel's archive covers a programme that started at `start` (epoch seconds).
pub fn supports(ch: &Channel, start: i64, now: i64) -> bool {
    ch.catchup.is_some() && ch.catchup_days > 0 && start >= now - ch.catchup_days as i64 * 86400
}

/// The archive URL for [start, end) or None when the channel has no usable catch-up.
pub fn build(ch: &Channel, start: i64, end: i64, now: i64) -> Option<String> {
    let c = ch.catchup.as_ref()?;
    let dur = (end - start).max(60);
    match c.kind.as_str() {
        "xc" => c.template.as_ref().map(|t| {
            let (y, mo, d, h, mi, _) = utc_fields(start);
            t.replace("{duration}", &(dur / 60).to_string()).replace("{start}", &format!("{:04}-{:02}-{:02}:{:02}-{:02}", y, mo, d, h, mi))
        }),
        "append" => c.template.as_ref().map(|t| format!("{}{}", ch.url, subst(t, start, end, now))),
        "shift" => Some(format!("{}{}utc={}&lutc={}", ch.url, if ch.url.contains('?') { "&" } else { "?" }, start, now)),
        _ => c.template.as_ref().map(|t| subst(t, start, end, now)),
    }
}

fn subst(t: &str, s: i64, e: i64, now: i64) -> String {
    PH.replace_all(t, |m: &Captures| match &m[1] {
        "start" | "utc" => s.to_string(),
        "end" | "utcend" => e.to_string(),
        "timestamp" | "lutc" | "now" => now.to_string(),
        "offset" => (now - s).to_string(),
        "duration" => (e - s).to_string(),
        _ => m[0].to_string(),
    })
    .into_owned()
}

/// Where a programme sits relative to now. PastCatchup: finished, and the channel's archive still covers it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ProgState {
    Past,
    PastCatchup,
    Now,
    Future,
}

pub fn classify(ch: Option<&Channel>, start: i64, stop: i64, now: i64) -> ProgState {
    if start > now {
        ProgState::Future
    } else if stop > now {
        ProgState::Now
    } else if ch.is_some_and(|c| supports(c, start, now)) {
        ProgState::PastCatchup
    } else {
        ProgState::Past
    }
}

pub fn minutes_left(stop: i64, now: i64) -> i64 {
    ((stop - now).max(0) + 59) / 60
}

pub fn progress(start: i64, stop: i64, now: i64) -> f32 {
    ((now - start) as f32 / (stop - start).max(1) as f32).clamp(0.0, 1.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Catchup;

    fn ch(c: Option<Catchup>, days: i32) -> Channel {
        Channel::new("s|1", "s", "One", None, "G", "http://example.invalid/1", None, 1, c, days)
    }

    #[test]
    fn kotlin_catchup_and_epg() {
        let m = crate::m3u::parse(crate::m3u::tests_sample(), "s1");
        let a = &m.channels[0];
        assert_eq!(build(a, 1000, 4600, 5000).as_deref(), Some("http://example.invalid/live/one.m3u8?start=1000&end=4600"));
        let xc = Channel::new("x|9", "x", "A", None, "Other", "http://h/live/u/p/9.ts", None, 0, Some(Catchup::new("xc", Some("http://h/timeshift/u/p/{duration}/{start}/9.ts".into()))), 2);
        assert_eq!(build(&xc, 1000, 4600, 5000).as_deref(), Some("http://h/timeshift/u/p/60/1970-01-01:00-16/9.ts"));
        assert!(!supports(&xc, 0, 3 * 86400));
    }

    #[test]
    fn templates() {
        let d = ch(Some(Catchup::new("default", Some("http://e.example/a?s={utc}&e=${end}&o={offset}&d={duration}&n={lutc}&k={keep}".into()))), 1);
        assert_eq!(build(&d, 100, 400, 1000).unwrap(), "http://e.example/a?s=100&e=400&o=900&d=300&n=1000&k={keep}");
        let s = ch(Some(Catchup::new("shift", None)), 1);
        assert_eq!(build(&s, 10, 20, 30).unwrap(), "http://example.invalid/1?utc=10&lutc=30");
        assert!(build(&ch(None, 0), 1, 2, 3).is_none());
        assert!(build(&ch(Some(Catchup::new("flussonic", None)), 1), 1, 2, 3).is_none());
        // short programmes still ask for at least a minute
        let xc = ch(Some(Catchup::new("xc", Some("{duration}".into()))), 1);
        assert_eq!(build(&xc, 0, 10, 20).unwrap(), "1");
    }

    #[test]
    fn prog_states() {
        let t0: i64 = 1_800_000_000; // a half hour
        let now = t0 + 600;
        let c = ch(Some(Catchup::new("shift", None)), 2);
        assert_eq!(classify(Some(&c), now + 60, now + 3600, now), ProgState::Future);
        assert_eq!(classify(Some(&c), now - 60, now + 60, now), ProgState::Now);
        assert_eq!(classify(Some(&c), now - 7200, now - 3600, now), ProgState::PastCatchup);
        assert_eq!(classify(Some(&c), now - 5 * 86400, now - 5 * 86400 + 3600, now), ProgState::Past);
        assert_eq!(classify(Some(&ch(None, 2)), now - 7200, now - 3600, now), ProgState::Past);
        assert_eq!(classify(None, now - 7200, now - 3600, now), ProgState::Past);
        assert_eq!(minutes_left(now + 61, now), 2);
        assert!((progress(now - 100, now + 100, now) - 0.5).abs() < 0.001);
    }
}
