// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Wrong-password / wrong-PIN throttling (ports of the web server's auth.Throttle and parental counter).
//! The clock is passed in (epoch seconds as f64) so the logic is deterministic and testable.

use std::collections::HashMap;

const GLOBAL: &str = "*";

#[derive(Clone, Copy, Debug, Default)]
struct Entry {
    fails: u32,
    wait_until: f64,
    seq: u64,
}

/// Per key (client address) and overall: after `free` wrong tries each further try waits `base` seconds, doubling
/// up to `max_wait`. A right password clears the key and takes `free` off the overall count.
#[derive(Debug)]
pub struct Throttle {
    pub free: u32,
    pub base: f64,
    pub max_wait: f64,
    pub global_free: u32,
    pub max_keys: usize,
    d: HashMap<String, Entry>,
    seq: u64,
}

impl Default for Throttle {
    fn default() -> Self {
        Throttle { free: 5, base: 30.0, max_wait: 900.0, global_free: 30, max_keys: 5000, d: HashMap::new(), seq: 0 }
    }
}

impl Throttle {
    fn wait(&self, fails: u32, free: u32) -> f64 {
        if fails < free {
            return 0.0;
        }
        (self.base * 2f64.powi((fails - free).min(10) as i32)).min(self.max_wait)
    }

    /// Ok, or Err(seconds to wait) while `key` (or everyone) is held up.
    pub fn check(&self, key: &str, now: f64) -> Result<(), u64> {
        for k in [key, GLOBAL] {
            if let Some(e) = self.d.get(k)
                && now < e.wait_until
            {
                return Err(((e.wait_until - now + 0.999) as u64).max(1));
            }
        }
        Ok(())
    }

    pub fn fail(&mut self, key: &str, now: f64) {
        for (k, free) in [(key, self.free), (GLOBAL, self.global_free)] {
            self.seq += 1;
            let seq = self.seq;
            let fails = {
                let e = self.d.entry(k.to_string()).or_default();
                e.fails += 1;
                e.seq = seq;
                e.fails
            };
            let w = self.wait(fails, free);
            if w > 0.0
                && let Some(e) = self.d.get_mut(k)
            {
                e.wait_until = now + w;
            }
        }
        if self.d.len() > self.max_keys {
            // many addresses: forget the oldest fifth
            let mut ks: Vec<(u64, String)> = self.d.iter().filter(|(k, _)| k.as_str() != GLOBAL).map(|(k, e)| (e.seq, k.clone())).collect();
            ks.sort();
            for (_, k) in ks.into_iter().take(self.max_keys / 5) {
                self.d.remove(&k);
            }
        }
    }

    pub fn ok(&mut self, key: &str) {
        self.d.remove(key);
        let free = self.free;
        if let Some(g) = self.d.get_mut(GLOBAL) {
            g.fails = g.fails.saturating_sub(free);
        }
    }
}

/// The parental PIN counter: after 5 wrong PINs in a row each try waits 30 s, 60 s after 10, ... up to 8 min.
#[derive(Debug, Default)]
pub struct PinCounter {
    fails: u32,
    wait_until: f64,
}

impl PinCounter {
    pub fn check(&self, now: f64) -> Result<(), u64> {
        if now < self.wait_until { Err((self.wait_until - now) as u64 + 1) } else { Ok(()) }
    }

    /// Records the result of a PIN check that `check` allowed.
    pub fn record(&mut self, right: bool, now: f64) {
        if right {
            self.fails = 0;
            return;
        }
        self.fails += 1;
        if self.fails >= 5 {
            self.wait_until = now + 30.0 * 2f64.powi(((self.fails - 5) / 5).min(4) as i32);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn web_throttle() {
        let mut t = 1000.0;
        let mut th = Throttle::default();
        for _ in 0..5 {
            th.check("1.2.3.4", t).unwrap();
            th.fail("1.2.3.4", t);
        }
        assert!(th.check("1.2.3.4", t).unwrap_err() >= 29);
        th.check("5.6.7.8", t).unwrap(); // another address is not held up
        t += 31.0;
        th.check("1.2.3.4", t).unwrap();
        th.fail("1.2.3.4", t);
        assert!(th.check("1.2.3.4", t).unwrap_err() >= 59); // doubles
        th.ok("1.2.3.4");
        th.check("1.2.3.4", t).unwrap();
    }

    #[test]
    fn global_limit_and_cap() {
        let mut th = Throttle::default();
        for i in 0..30 {
            th.fail(&format!("10.0.0.{i}"), 0.0);
        }
        assert!(th.check("192.0.2.1", 1.0).is_err()); // everyone waits after 30 wrong tries overall
        assert!(th.check("192.0.2.1", 40.0).is_ok());
        let mut big = Throttle { max_keys: 100, ..Default::default() };
        for i in 0..500 {
            big.fail(&format!("k{i}"), 0.0);
        }
        assert!(big.d.len() <= 101);
        assert!(big.d.contains_key("k499"));
    }

    #[test]
    fn pin_counter() {
        let mut c = PinCounter::default();
        for _ in 0..4 {
            c.record(false, 0.0);
        }
        assert!(c.check(0.0).is_ok());
        c.record(false, 0.0);
        assert_eq!(c.check(0.0), Err(31));
        for _ in 0..5 {
            c.record(false, 100.0);
        }
        assert_eq!(c.check(100.0), Err(61));
        c.record(true, 200.0);
        c.record(false, 200.0);
        assert!(c.check(200.0).is_ok());
    }
}
