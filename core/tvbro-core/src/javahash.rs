// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! `String.hashCode()` of the JVM, so channel keys made by the core match the keys the Android and desktop apps
//! already stored (favourites, order, names, recordings refer to them).

/// Java's `String.hashCode()`: `s[0]*31^(n-1) + ... + s[n-1]` over UTF-16 code units, wrapping at 32 bits.
pub fn java_string_hash(s: &str) -> i32 {
    let mut h: i32 = 0;
    for u in s.encode_utf16() {
        h = h.wrapping_mul(31).wrapping_add(u as i32);
    }
    h
}

/// Kotlin's `Int.toString(16)`: lower-case hex with a leading minus for negative values.
pub fn kotlin_hex(v: i32) -> String {
    if v < 0 { format!("-{:x}", (v as i64).unsigned_abs()) } else { format!("{:x}", v) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_the_jvm() {
        // values computed by the JVM ("abc".hashCode() == 96354, "".hashCode() == 0)
        assert_eq!(java_string_hash(""), 0);
        assert_eq!(java_string_hash("abc"), 96354);
        assert_eq!(java_string_hash("http://example.invalid/live/one.m3u8"), {
            let mut h: i32 = 0;
            for b in "http://example.invalid/live/one.m3u8".bytes() {
                h = h.wrapping_mul(31).wrapping_add(b as i32);
            }
            h
        });
        // surrogate pairs count as two code units
        assert_eq!(java_string_hash("\u{1F600}"), 0xD83D * 31 + 0xDE00);
        assert_eq!(kotlin_hex(255), "ff");
        assert_eq!(kotlin_hex(-255), "-ff");
        assert_eq!(kotlin_hex(i32::MIN), "-80000000");
    }
}
