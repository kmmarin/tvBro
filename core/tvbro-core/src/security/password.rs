// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Password and PIN hashing: salted PBKDF2-HMAC-SHA256, 600 000 iterations, constant-time compare.
//!
//! Stored form (the web server's): `pbkdf2_sha256$<iterations>$<salt hex>$<hash hex>`; hashes the web server made
//! (admin / viewer passwords at 600k, parental PINs at 120k) verify unchanged. The Android app's older PIN hash
//! (unsalted SHA-256 of "tvbro:" + PIN, 64 hex characters) also verifies, and [needs_rehash] tells the platform to
//! store a new-format hash after the next right PIN.

use super::{ct_eq, random_bytes};
use sha2::{Digest, Sha256};

pub const ITERATIONS: u32 = 600_000;
/// Iteration counts outside this range are refused (a tampered store must not stall the device).
pub const MAX_ITERATIONS: u32 = 10_000_000;
pub const SALT_LEN: usize = 16;
pub const MIN_PASSWORD: usize = 8;
pub const MAX_PASSWORD: usize = 200;

fn derive(pw: &str, salt: &[u8], iterations: u32, out: &mut [u8]) {
    pbkdf2::pbkdf2_hmac::<Sha256>(pw.as_bytes(), salt, iterations, out);
}

/// Hash with an explicit salt and iteration count.
pub fn hash_with(pw: &str, salt: &[u8], iterations: u32) -> String {
    let mut dk = [0u8; 32];
    derive(pw, salt, iterations, &mut dk);
    format!("pbkdf2_sha256${iterations}${}${}", hex::encode(salt), hex::encode(dk))
}

/// New hash with a random salt and the default iteration count.
pub fn hash(pw: &str) -> crate::Result<String> {
    Ok(hash_with(pw, &random_bytes(SALT_LEN)?, ITERATIONS))
}

fn legacy_android(pin: &str) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(b"tvbro:");
    h.update(pin.as_bytes());
    h.finalize().into()
}

fn is_legacy(stored: &str) -> bool {
    // lower-case hex only: the Android app wrote it so and compares it as text
    stored.len() == 64 && stored.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// True when `pw` matches `stored`. Malformed or unknown formats never match.
pub fn verify(pw: &str, stored: &str) -> bool {
    if is_legacy(stored) {
        return hex::decode(stored).is_ok_and(|want| ct_eq(&legacy_android(pw), &want));
    }
    let parts: Vec<&str> = stored.split('$').collect();
    let [algo, it, salt, want] = parts[..] else { return false };
    if algo != "pbkdf2_sha256" {
        return false;
    }
    let (Ok(it), Ok(salt), Ok(want)) = (it.parse::<u32>(), hex::decode(salt), hex::decode(want)) else { return false };
    if it == 0 || it > MAX_ITERATIONS || salt.is_empty() || want.is_empty() || want.len() > 64 {
        return false;
    }
    let mut dk = vec![0u8; want.len()];
    derive(pw, &salt, it, &mut dk);
    ct_eq(&dk, &want)
}

/// The stored hash is an old format or weaker than today's default: store a new one after a right password.
pub fn needs_rehash(stored: &str) -> bool {
    if is_legacy(stored) {
        return true;
    }
    let parts: Vec<&str> = stored.split('$').collect();
    !(parts.len() == 4 && parts[0] == "pbkdf2_sha256" && parts[1].parse::<u32>().is_ok_and(|i| i >= ITERATIONS) && parts[2].len() >= 2 * SALT_LEN)
}

/// Iterations for parental PINs: the web server's and the Android app's count. A 4-8 digit PIN is protected by the
/// wrong-PIN limiter, not by the hash cost, and the PIN is checked on slow TV sticks (armv7) on every unlock.
pub const PIN_ITERATIONS: u32 = 120_000;

/// New PIN hash (random salt, [PIN_ITERATIONS]).
pub fn hash_pin(pin: &str) -> crate::Result<String> {
    Ok(hash_with(pin, &random_bytes(SALT_LEN)?, PIN_ITERATIONS))
}

/// The stored PIN hash is the unsalted legacy form or weaker than [PIN_ITERATIONS]: store [hash_pin] after the next
/// right PIN.
pub fn pin_needs_rehash(stored: &str) -> bool {
    if is_legacy(stored) {
        return true;
    }
    let parts: Vec<&str> = stored.split('$').collect();
    !(parts.len() == 4 && parts[0] == "pbkdf2_sha256" && parts[1].parse::<u32>().is_ok_and(|i| i >= PIN_ITERATIONS) && parts[2].len() >= 2 * SALT_LEN)
}

/// A parental PIN: 4 to 8 digits.
pub fn valid_pin(pin: &str) -> bool {
    (4..=8).contains(&pin.len()) && pin.bytes().all(|b| b.is_ascii_digit())
}

/// A sign-in password: 8 to 200 characters.
pub fn valid_password(pw: &str) -> bool {
    (MIN_PASSWORD..=MAX_PASSWORD).contains(&pw.chars().count())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn web_password_hash() {
        let h = hash_with("correct horse", b"0123456789abcdef", 1000);
        assert!(h.starts_with("pbkdf2_sha256$1000$30313233"));
        assert!(verify("correct horse", &h));
        assert!(!verify("correct hors", &h));
        assert!(needs_rehash(&h));
        assert_eq!(ITERATIONS, 600_000);
        assert!(!valid_password("short"));
        assert!(valid_password("long enough"));
    }

    #[test]
    fn matches_python_hashlib() {
        // hashlib.pbkdf2_hmac("sha256", b"password", b"salt", 1).hex() (RFC 7914 / well-known vector)
        assert_eq!(hash_with("password", b"salt", 1), "pbkdf2_sha256$1$73616c74$120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");
        assert_eq!(hash_with("password", b"salt", 4096).rsplit('$').next().unwrap(), "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a");
    }

    #[test]
    fn default_hash_round_trip_and_salted() {
        let a = hash("1234").unwrap();
        let b = hash("1234").unwrap();
        assert_ne!(a, b);
        assert!(verify("1234", &a));
        assert!(!verify("0000", &a));
        assert!(!needs_rehash(&a));
    }

    #[test]
    fn android_legacy_pin() {
        let legacy = hex::encode(legacy_android("1234"));
        assert!(verify("1234", &legacy));
        assert!(!verify("0000", &legacy));
        assert!(needs_rehash(&legacy));
        assert!(pin_needs_rehash(&legacy));
        assert!(!verify("", ""));
    }

    #[test]
    fn pin_hashes() {
        let h = hash_pin("2468").unwrap();
        assert!(h.starts_with("pbkdf2_sha256$120000$"));
        assert!(verify("2468", &h) && !verify("2469", &h));
        assert!(!pin_needs_rehash(&h));
        assert!(pin_needs_rehash(&hash_with("2468", b"0123456789abcdef", 1000)));
        assert!(pin_needs_rehash(&hash_with("2468", b"short", PIN_ITERATIONS)));
        assert!(pin_needs_rehash("garbage"));
    }

    #[test]
    fn malformed_never_matches() {
        for s in ["", "x", "pbkdf2_sha256$0$00$00", "pbkdf2_sha256$99999999$00$00", "md5$1$00$00", "pbkdf2_sha256$1$zz$00", "pbkdf2_sha256$1$00$", "a$b$c"] {
            assert!(!verify("pw", s), "{s}");
        }
        assert!(valid_pin("0042") && !valid_pin("123") && !valid_pin("123456789") && !valid_pin("12a4"));
    }
}
