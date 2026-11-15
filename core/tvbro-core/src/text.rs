// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Text folding shared by search, alternates and the guide picker.

use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

/// NFKD with combining marks removed, lower case ("Télé" -> "tele"). Alternates / GuideMap `fold`.
pub fn fold_nfkd(s: &str) -> String {
    s.nfkd().filter(|c| !is_combining_mark(*c)).collect::<String>().to_lowercase()
}

/// NFD with combining marks removed, lower case (the search index's first step).
pub fn fold_nfd(s: &str) -> String {
    s.nfd().filter(|c| !is_combining_mark(*c)).collect::<String>().to_lowercase()
}

/// Kotlin `Char.isLetterOrDigit` (JVM `Character.isLetterOrDigit`: general categories L* and Nd). Rust's
/// `is_alphabetic` is the wider Alphabetic property; the letter numbers (Nl: Roman numerals, CJK and runic numbers)
/// and the circled letters (So) it adds are not letters on the JVM.
pub fn is_letter_or_digit(c: char) -> bool {
    if decimal_value(c).is_some() {
        return true;
    }
    c.is_alphabetic() && !is_letter_number(c) && !matches!(c as u32, 0x24B6..=0x24E9 | 0x1F130..=0x1F149 | 0x1F150..=0x1F169 | 0x1F170..=0x1F189)
}

/// General category Nl (letter numbers), all of it.
fn is_letter_number(c: char) -> bool {
    matches!(c as u32,
        0x16EE..=0x16F0 | 0x2160..=0x2182 | 0x2185..=0x2188 | 0x3007 | 0x3021..=0x3029 | 0x3038..=0x303A | 0xA6E6..=0xA6EF
        | 0x10140..=0x10174 | 0x10341 | 0x1034A | 0x103D1..=0x103D5 | 0x12400..=0x1246E)
}

/// The zero of every run of Unicode decimal digits (Nd) in the BMP: each run is ten digits, zero to nine.
const DIGIT_ZEROS: [u32; 37] = [
    0x0030, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66, 0x0CE6, 0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090,
    0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80, 0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620, 0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0, 0xFF10,
];

/// Value of a Unicode decimal digit of the BMP (JVM `Character.digit(c, 10)` for those). Digits outside the BMP
/// are two UTF-16 units on the JVM, never one digit, so they are not digits here either.
fn decimal_value(c: char) -> Option<u32> {
    let u = c as u32;
    let i = DIGIT_ZEROS.partition_point(|&z| z <= u);
    let z = DIGIT_ZEROS[i.checked_sub(1)?];
    (u - z < 10).then_some(u - z)
}

/// Kotlin `String.toIntOrNull()`: optional sign, decimal digits (non-ASCII decimal digits too, like the JVM's
/// `Character.digit`), no whitespace, `None` on overflow.
pub fn kotlin_to_int(s: &str) -> Option<i32> {
    let mut it = s.chars().peekable();
    let first = *it.peek()?;
    let neg = if first == '-' || first == '+' {
        it.next();
        it.peek()?;
        first == '-'
    } else {
        false
    };
    let mut v: i64 = 0;
    for c in it {
        v = v * 10 + i64::from(decimal_value(c)?);
        if v > i64::from(i32::MAX) + 1 {
            return None;
        }
    }
    i32::try_from(if neg { -v } else { v }).ok()
}

/// Kotlin `Char.isWhitespace()` (JVM `Character.isWhitespace || isSpaceChar`): unlike Rust's `char::is_whitespace`
/// it includes U+001C..U+001F and excludes U+0085. Used where trimmed text feeds channel keys.
pub fn is_kotlin_ws(c: char) -> bool {
    matches!(c, '\t'..='\r' | '\u{1C}'..='\u{20}' | '\u{A0}' | '\u{1680}' | '\u{2000}'..='\u{200A}' | '\u{2028}' | '\u{2029}' | '\u{202F}' | '\u{205F}' | '\u{3000}')
}

/// Kotlin `String.trim()`.
pub fn kotlin_trim(s: &str) -> &str {
    s.trim_matches(is_kotlin_ws)
}

/// Kotlin `String.isBlank()`.
pub fn kotlin_blank(s: &str) -> bool {
    s.chars().all(is_kotlin_ws)
}

/// Splits on runs of whitespace (Kotlin `split(Regex("\\s+"))` after trim).
pub fn collapse_ws(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// First `n` chars (Kotlin `take(n)` on BMP text).
pub fn take_chars(s: &str, n: usize) -> String {
    match s.char_indices().nth(n) {
        Some((i, _)) => s[..i].to_string(),
        None => s.to_string(),
    }
}

/// Kotlin `String.take(n)`: the first `n` UTF-16 code units. A character outside the BMP that would be cut in half
/// is left out whole (Kotlin keeps its lone high surrogate, which is not valid text).
pub fn take_utf16(s: &str, n: usize) -> String {
    let mut used = 0;
    for (i, c) in s.char_indices() {
        used += c.len_utf16();
        if used > n {
            return s[..i].to_string();
        }
    }
    s.to_string()
}

/// Length in UTF-16 code units, the JVM's `String.length` (used where ordering must match Kotlin).
pub fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folds() {
        assert_eq!(fold_nfkd("Télé Monte-Carlo"), "tele monte-carlo");
        assert_eq!(fold_nfd("Pokémon"), "pokemon");
        assert_eq!(collapse_ws("  a \t b\n c "), "a b c");
        assert_eq!(take_chars("héllo", 2), "hé");
        assert_eq!(take_chars("hi", 5), "hi");
        assert_eq!(utf16_len("a\u{1F600}"), 3);
        assert!(is_letter_or_digit('é') && is_letter_or_digit('7') && !is_letter_or_digit('-'));
        // JVM categories: Nd digits of every script yes; Nl letter numbers and circled letters no
        assert!(is_letter_or_digit('\u{07C3}') && is_letter_or_digit('\u{1049}') && is_letter_or_digit('\u{ABF9}'));
        assert!(!is_letter_or_digit('\u{2163}') && !is_letter_or_digit('\u{3007}') && !is_letter_or_digit('\u{24B6}') && !is_letter_or_digit('\u{16EE}'));
        assert_eq!(kotlin_to_int("\u{0F22}\u{1814}"), Some(24));
        assert_eq!(kotlin_to_int("\u{1D7CE}"), None); // mathematical digit zero: outside the BMP
    }

    #[test]
    fn kotlin_rules() {
        for (s, v) in [("7", Some(7)), ("+5", Some(5)), ("-3", Some(-3)), ("007", Some(7)), ("\u{0663}", Some(3)), ("\u{FF11}\u{FF12}", Some(12)), ("2147483647", Some(i32::MAX)), ("-2147483648", Some(i32::MIN))] {
            assert_eq!(kotlin_to_int(s), v, "{s}");
        }
        for s in ["", "-", "+", " 7", "7 ", "2147483648", "-2147483649", "1.0", "x", "99999999999999999999"] {
            assert_eq!(kotlin_to_int(s), None, "{s}");
        }
        assert_eq!(kotlin_trim("\u{1F}\u{A0} a \u{3000}"), "a");
        assert_eq!(kotlin_trim("\u{85}a\u{85}"), "\u{85}a\u{85}");
        assert!(kotlin_blank("") && kotlin_blank(" \u{2028}\t") && !kotlin_blank("\u{85}"));
    }
}
