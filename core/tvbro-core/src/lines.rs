// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Bounded line reading for huge text inputs (playlists of hundreds of MB).
//!
//! Lines end at `\n`, `\r\n` or `\r` (like the JVM's `BufferedReader.readLine`). A line longer than `max_len`
//! bytes is cut at `max_len` and the rest of it skipped, so a hostile file without newlines cannot grow memory.
//! Bytes that are not UTF-8 are replaced (U+FFFD) instead of failing the whole file.

use std::io::BufRead;

pub const DEFAULT_MAX_LINE: usize = 1 << 20;

/// UTF-8 with U+FFFD for bad bytes, exactly like the JVM's decoder (`InputStream.bufferedReader()`), so text that
/// feeds channel keys is the same on both sides. It differs from `String::from_utf8_lossy` in one place: an encoded
/// surrogate (ED A0..BF xx) is one replacement for its three bytes (two when the third is not a continuation byte),
/// where Rust replaces each byte.
pub fn java_lossy(b: &[u8]) -> std::borrow::Cow<'_, str> {
    let mut rest = match std::str::from_utf8(b) {
        Ok(s) => return std::borrow::Cow::Borrowed(s),
        Err(_) => b,
    };
    let mut out = String::with_capacity(b.len() + 8);
    loop {
        match std::str::from_utf8(rest) {
            Ok(s) => {
                out.push_str(s);
                return std::borrow::Cow::Owned(out);
            }
            Err(e) => {
                let (ok, bad) = rest.split_at(e.valid_up_to());
                out.push_str(std::str::from_utf8(ok).unwrap_or_default());
                out.push('\u{FFFD}');
                let skip = if bad[0] == 0xED && bad.get(1).is_some_and(|&x| (0xA0..=0xBF).contains(&x)) {
                    if bad.get(2).is_some_and(|&x| x & 0xC0 == 0x80) { 3 } else { 2 }
                } else {
                    e.error_len().unwrap_or(bad.len())
                };
                rest = &bad[skip..];
            }
        }
    }
}

/// Calls `f` for every line (without its terminator). Stops early when `f` returns false.
pub fn for_each_line<R: BufRead>(mut r: R, max_len: usize, mut f: impl FnMut(&str) -> bool) -> std::io::Result<()> {
    let mut line: Vec<u8> = Vec::with_capacity(256);
    let mut overflow = false;
    let mut after_cr = false;
    loop {
        let buf = match r.fill_buf() {
            Ok(b) => b,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        };
        if buf.is_empty() {
            if !line.is_empty() || overflow {
                let s = java_lossy(&line);
                f(&s);
            }
            return Ok(());
        }
        let mut used = 0;
        let mut stop = false;
        for &b in buf {
            used += 1;
            if after_cr {
                after_cr = false;
                if b == b'\n' {
                    continue;
                }
            }
            if b == b'\n' || b == b'\r' {
                after_cr = b == b'\r';
                let s = java_lossy(&line);
                let go = f(&s);
                line.clear();
                overflow = false;
                if !go {
                    stop = true;
                    break;
                }
            } else if line.len() < max_len {
                line.push(b);
            } else {
                overflow = true;
            }
        }
        r.consume(used);
        if stop {
            return Ok(());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn collect(s: &[u8], max: usize) -> Vec<String> {
        let mut out = Vec::new();
        for_each_line(s, max, |l| {
            out.push(l.to_string());
            true
        })
        .unwrap();
        out
    }

    #[test]
    fn terminators_and_limits() {
        assert_eq!(collect(b"a\nb\r\nc\rd", 100), vec!["a", "b", "c", "d"]);
        assert_eq!(collect(b"a\n\nb\n", 100), vec!["a", "", "b"]);
        assert_eq!(collect(b"abcdef\nxy", 3), vec!["abc", "xy"]);
        assert_eq!(collect(b"\xff\xfeok\n", 100), vec!["\u{FFFD}\u{FFFD}ok"]);
        assert!(collect(b"", 10).is_empty());
        // the JVM decoder: one U+FFFD per encoded surrogate, else like from_utf8_lossy
        assert_eq!(java_lossy(b"a\xed\xa0\x80b"), "a\u{FFFD}b");
        assert_eq!(java_lossy(b"a\xed\xa0b"), "a\u{FFFD}b");
        assert_eq!(java_lossy(b"a\xed\xa0"), "a\u{FFFD}");
        assert_eq!(java_lossy(b"\xed\x9f\xbf"), "\u{D7FF}");
        assert_eq!(java_lossy(b"\xff\xc3\xe2\x82"), "\u{FFFD}\u{FFFD}\u{FFFD}");
        assert_eq!(java_lossy(b"\xf4\x90\x80\x80"), "\u{FFFD}\u{FFFD}\u{FFFD}\u{FFFD}");
    }

    #[test]
    fn crlf_split_across_buffers() {
        let r = std::io::BufReader::with_capacity(2, &b"ab\r\ncd\r\n"[..]);
        let mut out = Vec::new();
        for_each_line(r, 100, |l| {
            out.push(l.to_string());
            true
        })
        .unwrap();
        assert_eq!(out, vec!["ab", "cd"]);
    }
}
