// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Guide grid time window (desktop shared GuideWindow.kt; Android GuideLayout.kt keeps its own `reveal` variant,
//! ported as [android]). All times are epoch seconds.
//!
//! The grid never shows the past: the window starts at the current half hour and only goes forward, a window time
//! has left behind snaps to now, and Left at the window start moves focus to the channel name (never scrolls back,
//! never traps). Earlier programmes with catch-up are reached from the menus ([past_catchup]).

use crate::catchup::{ProgState, classify};
use crate::model::{Channel, Programme};

pub const WINDOW_MIN: i64 = 150;
pub const WINDOW_SEC: i64 = WINDOW_MIN * 60;
pub const STEP_SEC: i64 = 1800;
/// How far ahead of now the window may go (what the guide stores).
pub const AHEAD_SEC: i64 = 30 * 3600;

pub fn floor30(t: i64) -> i64 {
    t.div_euclid(1800) * 1800
}

/// The window start for "now": 05:00 at 05:17.
pub fn initial_start(now: i64) -> i64 {
    floor30(now)
}

/// A window start kept inside [current half hour, now + AHEAD]: one behind now snaps to now.
pub fn clamp_win(win: i64, now: i64) -> i64 {
    let lo = floor30(now);
    win.clamp(lo, lo.max(floor30(now + AHEAD_SEC)))
}

fn reveal_raw(p_start: i64, p_stop: i64, win: i64) -> i64 {
    let win_end = win + WINDOW_SEC;
    if p_stop <= win || p_start >= win_end {
        floor30(p_start)
    } else if p_start >= win && p_stop > win_end {
        floor30(p_start).min(floor30(p_stop - WINDOW_SEC + 1799))
    } else {
        win
    }
}

/// Window start after focus lands on a programme: moves only as far as needed, never before now.
pub fn reveal(p_start: i64, p_stop: i64, win: i64, now: i64) -> i64 {
    clamp_win(reveal_raw(p_start, p_stop, win), now)
}

/// Window start after a manual step of `delta` seconds (never into the past).
pub fn shifted(win: i64, delta: i64, now: i64) -> i64 {
    clamp_win(win + delta, now)
}

/// On a clock tick: following the clock the window moves with it; behind now it snaps to now.
pub fn on_tick(win: i64, following: bool, now: i64) -> i64 {
    if following { initial_start(now) } else { clamp_win(win, now) }
}

/// x offset and width (in `units_per_min` units) of a programme clipped to the window, or None outside it.
pub fn block(start: i64, stop: i64, win: i64, units_per_min: f32, gap: f32) -> Option<(f32, f32)> {
    let s = start.max(win);
    let e = stop.min(win + WINDOW_SEC);
    if e <= s {
        return None;
    }
    Some(((s - win) as f32 / 60.0 * units_per_min, ((e - s) as f32 / 60.0 * units_per_min - gap).max(1.0)))
}

pub fn tick_x(t: i64, win: i64, units_per_min: f32) -> f32 {
    (t - win) as f32 / 60.0 * units_per_min
}

/// Programmes of one channel visible in the window, in order.
pub fn visible(progs: &[Programme], win: i64) -> Vec<&Programme> {
    let end = win + WINDOW_SEC;
    progs.iter().filter(|p| p.stop > win && p.start < end).collect()
}

/// The programme to focus for "Right from the channel name": on now, else the first upcoming, else the last.
pub fn now_target<'a>(vis: &[&'a Programme], now: i64) -> Option<&'a Programme> {
    vis.iter().find(|p| p.start <= now && p.stop > now).or_else(|| vis.iter().find(|p| p.start > now)).or_else(|| vis.last()).copied()
}

/// A focus move: focus `prog` (None = the channel name) with the window at `win`.
#[derive(Clone, Debug, PartialEq)]
pub struct Move<'a> {
    pub prog: Option<&'a Programme>,
    pub win: i64,
}

fn index(progs: &[Programme], p: &Programme) -> Option<usize> {
    progs.iter().position(|x| x.start == p.start && x.stop == p.stop)
}

/// Left from `focus`: the previous programme while it is visible (or the window can go back towards now to show it);
/// at the window start, which is never before the current half hour, the channel name.
pub fn left_of<'a>(progs: &'a [Programme], focus: Option<&'a Programme>, win: i64, now: i64) -> Move<'a> {
    let lo = floor30(now);
    let Some(focus) = focus.filter(|_| !progs.is_empty()) else { return Move { prog: None, win } };
    let pv = match index(progs, focus) {
        Some(i) => i.checked_sub(1).map(|j| &progs[j]),
        None => progs.iter().rev().find(|p| p.stop <= focus.start),
    };
    if let Some(p) = pv
        && p.stop > win
    {
        return Move { prog: Some(p), win };
    }
    if win > lo {
        if let Some(p) = pv {
            let w = clamp_win(floor30(p.start).min(win - STEP_SEC), now);
            if p.stop > w {
                return Move { prog: Some(p), win: w };
            }
        }
        return Move { prog: Some(focus), win: clamp_win(win - STEP_SEC, now) };
    }
    Move { prog: None, win }
}

/// Right from `focus`: the next programme (the window moves forward as far as needed), else a 30-minute step.
pub fn right_of<'a>(progs: &'a [Programme], focus: &'a Programme, win: i64, now: i64) -> Move<'a> {
    let nx = match index(progs, focus) {
        Some(i) => progs.get(i + 1),
        None => progs.iter().find(|p| p.start >= focus.stop),
    };
    match nx {
        Some(n) if n.start < win + WINDOW_SEC => Move { prog: Some(n), win: reveal(n.start, n.stop, win, now) },
        _ => Move { prog: Some(focus), win: shifted(win, STEP_SEC, now) },
    }
}

/// Right from the channel name: the programme on now (or the first visible one), with the window back at now.
pub fn right_of_label(progs: &[Programme], now: i64) -> Move<'_> {
    let win = initial_start(now);
    Move { prog: now_target(&visible(progs, win), now), win }
}

/// The programme to keep focused once `focus` has ended (the window moved on): the one on now.
pub fn after_end<'a>(progs: &'a [Programme], focus: Option<&'a Programme>, now: i64) -> Option<&'a Programme> {
    match focus {
        None => None,
        Some(f) if f.stop > floor30(now) => Some(f),
        Some(_) => progs.iter().find(|p| p.start <= now && p.stop > now).or_else(|| progs.iter().find(|p| p.start > now)),
    }
}

/// Earlier programmes still in the channel's archive, newest first (the grid shows none of them).
pub fn past_catchup<'a>(ch: &Channel, progs: &'a [Programme], now: i64, max: usize) -> Vec<&'a Programme> {
    let mut l: Vec<&Programme> = progs.iter().filter(|p| classify(Some(ch), p.start, p.stop, now) == ProgState::PastCatchup).collect();
    l.sort_by_key(|p| std::cmp::Reverse(p.start));
    l.truncate(max);
    l
}

/// Android GuideLayout.kt (the TV app's own variant: `reveal` without the clamp, `reveal_no_past` with a floor).
pub mod android {
    use super::*;

    pub fn reveal(p_start: i64, p_stop: i64, win: i64) -> i64 {
        reveal_raw(p_start, p_stop, win)
    }

    /// Manual 30-minute steps from the current half hour to 30 h ahead.
    pub fn shifted(win: i64, delta: i64, now: i64) -> i64 {
        (win + delta).clamp(floor30(now), floor30(now + 30 * 3600).max(floor30(now)))
    }

    pub fn reveal_no_past(p_start: i64, p_stop: i64, win: i64, now: i64) -> i64 {
        reveal_raw(p_start, p_stop, win).max(floor30(now))
    }

    /// Block clipped to [win, win_end] (the Android signature).
    pub fn block(start: i64, stop: i64, win: i64, win_end: i64, dpm: f32, gap: f32) -> Option<(f32, f32)> {
        let s = start.max(win);
        let e = stop.min(win_end);
        if e <= s {
            return None;
        }
        Some(((s - win) as f32 / 60.0 * dpm, ((e - s) as f32 / 60.0 * dpm - gap).max(1.0)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Catchup;

    const H: i64 = 3600;
    const BASE: i64 = 1_800_000_000 / 1800 * 1800;

    fn p(s: i64, e: i64, t: &str) -> Programme {
        Programme::new("k", s, e, t, None)
    }

    fn close(a: f32, b: f32) {
        assert!((a - b).abs() < 0.01, "{a} != {b}");
    }

    #[test]
    fn opens_at_the_current_half_hour() {
        assert_eq!(initial_start(BASE + 17 * 60), BASE);
        assert_eq!(initial_start(BASE), BASE);
        assert_eq!(initial_start(BASE + 1801), BASE + 1800);
        assert_eq!(floor30(BASE - 1), BASE - 1800);
    }

    #[test]
    fn window_never_goes_into_the_past() {
        let now = BASE + 60;
        assert_eq!(clamp_win(BASE - 3 * H, now), BASE);
        assert_eq!(shifted(BASE, -1800, now), BASE);
        assert_eq!(shifted(BASE, -20 * H, now), BASE);
        assert_eq!(shifted(BASE + H, -1800, now), BASE + 1800);
        assert_eq!(shifted(BASE, 50 * H, now), floor30(now + 30 * H));
    }

    #[test]
    fn reveal_moves_forward_only() {
        let now = BASE + 60;
        assert_eq!(reveal(BASE - H, BASE + 1800, BASE, now), BASE);
        assert_eq!(reveal(BASE - 2 * H, BASE - H, BASE, now), BASE);
        assert_eq!(reveal(BASE + 2 * H, BASE + 3 * H, BASE, now), BASE + 1800);
    }

    #[test]
    fn behind_now_snaps() {
        let now = BASE + 600;
        assert_eq!(on_tick(BASE - H, false, now), BASE);
        assert_eq!(on_tick(BASE + H, false, now), BASE + H);
        assert_eq!(on_tick(BASE, true, BASE + 1805), BASE + 1800);
        assert_eq!(on_tick(BASE, false, BASE + 1805), BASE + 1800);
    }

    #[test]
    fn blocks_on_one_scale() {
        let dpm = 4.0;
        let (x, w) = block(BASE - 1800, BASE + 1800, BASE, dpm, 0.0).unwrap();
        close(x, 0.0);
        close(w, 30.0 * dpm);
        let (x2, w2) = block(BASE + 1800, BASE + 3600, BASE, dpm, 0.0).unwrap();
        close(x2, 30.0 * dpm);
        close(w2, 30.0 * dpm);
        close(tick_x(BASE + 1800, BASE, dpm), x2);
        assert!(block(BASE - H, BASE, BASE, dpm, 2.0).is_none());
        assert!(block(BASE + WINDOW_SEC, BASE + WINDOW_SEC + H, BASE, dpm, 2.0).is_none());
    }

    #[test]
    fn right_from_the_name_lands_on_now() {
        let now = BASE + 600;
        let all = [p(BASE - H, BASE, "past"), p(BASE, BASE + 1800, "now"), p(BASE + 1800, BASE + H, "next")];
        let vis = visible(&all, BASE);
        assert_eq!(vis.iter().map(|p| p.title.as_str()).collect::<Vec<_>>(), vec!["now", "next"]);
        assert_eq!(now_target(&vis, now).unwrap().title, "now");
        assert_eq!(now_target(&vis[1..], now).unwrap().title, "next");
        assert!(now_target(&[], now).is_none());
        let m = right_of_label(&all, now);
        assert_eq!(m.prog.unwrap().title, "now");
        assert_eq!(m.win, BASE);
        assert!(right_of_label(&[], now).prog.is_none());
    }

    #[test]
    fn left_at_the_window_start_goes_to_the_name() {
        let now = BASE + 600;
        let all = [p(BASE - 2 * H, BASE - H, "old"), p(BASE - H, BASE + 1800, "now"), p(BASE + 1800, BASE + H, "next")];
        let m = left_of(&all, Some(&all[1]), BASE, now);
        assert!(m.prog.is_none());
        assert_eq!(m.win, BASE);
        assert_eq!(left_of(&all, Some(&all[2]), BASE, now), Move { prog: Some(&all[1]), win: BASE });
        assert!(left_of(&[], None, BASE, now).prog.is_none());
        let one = [all[1].clone()];
        assert!(left_of(&one, Some(&one[0]), BASE, now).prog.is_none());
    }

    #[test]
    fn left_ahead_steps_back_towards_now_only() {
        let now = BASE + 600;
        let all = [p(BASE, BASE + 1800, "a"), p(BASE + 1800, BASE + 4 * H, "long"), p(BASE + 4 * H, BASE + 5 * H, "late")];
        assert_eq!(left_of(&all, Some(&all[2]), BASE + 4 * H, now), Move { prog: Some(&all[1]), win: BASE + 1800 });
        let far = [p(BASE, BASE + 1800, "a"), p(BASE + 6 * H, BASE + 7 * H, "b")];
        let m = left_of(&far, Some(&far[1]), BASE + 6 * H, now);
        assert!(m.win >= BASE && m.win < BASE + 6 * H);
        let (mut focus, mut win, mut n) = (Some(&far[1]), BASE + 6 * H, 0);
        while focus.is_some() && n < 50 {
            n += 1;
            let r = left_of(&far, focus, win, now);
            focus = r.prog;
            win = r.win;
        }
        assert!(focus.is_none());
        assert_eq!(win, BASE);
    }

    #[test]
    fn right_moves_to_next_or_steps() {
        let now = BASE + 600;
        let all = [p(BASE, BASE + 1800, "a"), p(BASE + 1800, BASE + 4 * H, "long"), p(BASE + 4 * H, BASE + 5 * H, "late")];
        assert_eq!(right_of(&all, &all[0], BASE, now), Move { prog: Some(&all[1]), win: BASE + 1800 });
        let m = right_of(&all, &all[1], BASE, now);
        assert_eq!(m.prog, Some(&all[1]));
        assert_eq!(m.win, BASE + 1800);
        let one = [all[0].clone()];
        let e = right_of(&one, &one[0], BASE, now);
        assert_eq!((e.prog.unwrap().title.as_str(), e.win), ("a", BASE + 1800));
    }

    #[test]
    fn ended_focus_moves_to_now() {
        let all = [p(BASE, BASE + 1800, "a"), p(BASE + 1800, BASE + H, "b")];
        assert_eq!(after_end(&all, Some(&all[0]), BASE + 1810).unwrap().title, "b");
        assert_eq!(after_end(&all, Some(&all[0]), BASE + 600).unwrap().title, "a");
        assert!(after_end(&all, None, BASE).is_none());
    }

    #[test]
    fn catchup_list_newest_first() {
        let now = BASE + 600;
        let ch = Channel::new("c", "s", "C", None, "g", "http://x", None, 1, Some(Catchup::new("shift", None)), 1);
        let all = [p(BASE - 30 * H, BASE - 29 * H, "too old"), p(BASE - 2 * H, BASE - H, "old"), p(BASE - H, BASE, "older?"), p(BASE, BASE + H, "now")];
        assert_eq!(past_catchup(&ch, &all, now, 80).iter().map(|p| p.title.as_str()).collect::<Vec<_>>(), vec!["older?", "old"]);
        let no = Channel { catchup: None, ..ch };
        assert!(past_catchup(&no, &all, now, 80).is_empty());
    }

    // Android GuideSearchLogicTest + ParsersTest.guideLayoutMath
    #[test]
    fn android_guide_rules() {
        let t0: i64 = 1_800_000_000; // a half hour
        assert_eq!(android::reveal(t0 - 1200, t0 + 1800, t0), t0);
        assert_eq!(android::reveal(t0 - 3600, t0 - 1800, t0), t0 - 3600);
        assert!(android::reveal(t0 + 7200, t0 + 7200 + 3600, t0) > t0);
        assert!(android::reveal_no_past(t0 + 7200, t0 + 10800, t0, t0) > t0);
        let now = t0 + 17 * 60;
        assert_eq!(android::shifted(t0, -1800, now), t0);
        assert_eq!(android::shifted(t0, -100 * 3600, now), t0);
        assert_eq!(android::shifted(t0 + 3600, -7200, now), t0);
        assert_eq!(android::shifted(t0, 1800, now), t0 + 1800);
        assert_eq!(android::shifted(t0, 100 * 3600, now), floor30(now + 30 * 3600));
        assert_eq!(android::reveal_no_past(t0 - 3600, t0 - 1800, t0, now), t0);
        assert_eq!(android::reveal_no_past(t0 - 6 * 3600, t0 - 5 * 3600, t0 + 3600, now), t0);
        assert_eq!(android::reveal_no_past(t0 - 3600, t0 + 1800, t0 - 7200, now), t0);
        let (mut w, mut steps) = (t0, 0);
        loop {
            let n = android::shifted(w, 1800, now);
            if n == w {
                break;
            }
            w = n;
            steps += 1;
        }
        assert_eq!(steps, 60);

        let w0 = 10_000 * 1800;
        let w1 = w0 + 150 * 60;
        let dpm = 2.0;
        let a = android::block(w0 + 1800, w0 + 3600, w0, w1, dpm, 2.0).unwrap();
        close(a.0, 60.0);
        close(a.1, 58.0);
        close(android::block(w0, w0 + 900, w0, w1, dpm, 2.0).unwrap().1, 28.0);
        close(android::block(w0, w0 + 5400, w0, w1, dpm, 2.0).unwrap().1, 178.0);
        let b = android::block(w0 - 3600, w0 + 1800, w0, w1, dpm, 2.0).unwrap();
        close(b.0, 0.0);
        close(b.1, 58.0);
        let c = android::block(w1 - 600, w1 + 3600, w0, w1, dpm, 2.0).unwrap();
        close(c.0, 280.0);
        close(c.1, 18.0);
        assert!(android::block(w0 - 3600, w0, w0, w1, dpm, 2.0).is_none());
        assert!(android::block(w1, w1 + 900, w0, w1, dpm, 2.0).is_none());
        close(tick_x(w0 + 1800, w0, dpm), 60.0);
        assert_eq!(floor30(w0 + 1799), w0);
        assert_eq!(floor30(w0 - 1), w0 - 1800);
    }
}
