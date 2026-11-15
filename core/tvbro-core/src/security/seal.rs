// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Secrets at rest (AES-256-GCM) and signed tokens (HMAC-SHA256).
//!
//! The 32-byte key is supplied by the platform: Android Keystore (a wrapped key), Windows DPAPI, libsecret, or the
//! server's key file / systemd credential. Sealed format: `0x01 | 12-byte random nonce | ciphertext | 16-byte tag`.
//! `context` is bound as associated data, so a blob sealed for one purpose ("provider-login") does not open as
//! another ("channel-cache").

use super::{ct_eq, random_bytes};
use crate::error::Error;
use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit};
use hmac::{Hmac, Mac};
use sha2::Sha256;

pub const KEY_LEN: usize = 32;
const VERSION: u8 = 1;
const NONCE_LEN: usize = 12;

/// A new random key (for platforms that store it themselves).
pub fn generate_key() -> crate::Result<Vec<u8>> {
    random_bytes(KEY_LEN)
}

fn cipher(key: &[u8]) -> crate::Result<Aes256Gcm> {
    if key.len() != KEY_LEN {
        return Err(Error::Invalid("the key must be 32 bytes".into()));
    }
    Aes256Gcm::new_from_slice(key).map_err(|_| Error::Invalid("key".into()))
}

pub fn seal(key: &[u8], context: &str, plaintext: &[u8]) -> crate::Result<Vec<u8>> {
    let c = cipher(key)?;
    let nonce_bytes: [u8; NONCE_LEN] = random_bytes(NONCE_LEN)?.try_into().map_err(|_| Error::Crypto)?;
    let nonce = aes_gcm::Nonce::from(nonce_bytes);
    let ct = c.encrypt(&nonce, Payload { msg: plaintext, aad: context.as_bytes() }).map_err(|_| Error::Crypto)?;
    let mut out = Vec::with_capacity(1 + NONCE_LEN + ct.len());
    out.push(VERSION);
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ct);
    Ok(out)
}

pub fn open(key: &[u8], context: &str, sealed: &[u8]) -> crate::Result<Vec<u8>> {
    let c = cipher(key)?;
    if sealed.len() < 1 + NONCE_LEN + 16 || sealed[0] != VERSION {
        return Err(Error::Crypto);
    }
    let nonce_bytes: [u8; NONCE_LEN] = sealed[1..1 + NONCE_LEN].try_into().map_err(|_| Error::Crypto)?;
    let nonce = aes_gcm::Nonce::from(nonce_bytes);
    c.decrypt(&nonce, Payload { msg: &sealed[1 + NONCE_LEN..], aad: context.as_bytes() }).map_err(|_| Error::Crypto)
}

/// HMAC-SHA256 of `msg`, hex.
pub fn hmac_hex(key: &[u8], msg: &str) -> String {
    let mut m = <Hmac<Sha256> as hmac::KeyInit>::new_from_slice(key).expect("HMAC takes any key length");
    m.update(msg.as_bytes());
    hex::encode(m.finalize().into_bytes())
}

/// The web server's CSRF token for a session hash.
pub fn csrf_token(key: &[u8], session_hash: &str) -> String {
    hmac_hex(key, &format!("csrf:{session_hash}"))[..32].to_string()
}

pub fn csrf_ok(key: &[u8], session_hash: &str, token: &str) -> bool {
    !token.is_empty() && ct_eq(csrf_token(key, session_hash).as_bytes(), token.as_bytes())
}

/// Parental unlock token "exp.gen.sig" (web server's Parental.token): valid until `exp`, for PIN generation `gen`.
pub fn unlock_token(key: &[u8], exp: i64, generation: i64) -> String {
    let sig = &hmac_hex(key, &format!("parental:{exp}:{generation}"))[..32];
    format!("{exp}.{generation}.{sig}")
}

pub fn unlock_valid(key: &[u8], tok: &str, now: i64, generation: i64) -> bool {
    let parts: Vec<&str> = tok.split('.').collect();
    let [e, g, sig] = parts[..] else { return false };
    let (Ok(exp), Ok(tg)) = (e.parse::<i64>(), g.parse::<i64>()) else { return false };
    if exp < now || tg != generation {
        return false;
    }
    ct_eq(sig.as_bytes(), &hmac_hex(key, &format!("parental:{e}:{g}")).as_bytes()[..32])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seal_round_trip_and_tamper() {
        let k = generate_key().unwrap();
        let s = seal(&k, "provider-login", b"{\"user\":\"u\"}").unwrap();
        assert_eq!(s.len(), 1 + 12 + 12 + 16);
        assert_eq!(open(&k, "provider-login", &s).unwrap(), b"{\"user\":\"u\"}");
        assert_ne!(seal(&k, "provider-login", b"x").unwrap(), seal(&k, "provider-login", b"x").unwrap());
        assert!(matches!(open(&k, "channel-cache", &s), Err(Error::Crypto)));
        let other = generate_key().unwrap();
        assert!(open(&other, "provider-login", &s).is_err());
        for i in 0..s.len() {
            let mut t = s.clone();
            t[i] ^= 1;
            assert!(open(&k, "provider-login", &t).is_err(), "byte {i}");
        }
        assert!(open(&k, "provider-login", &s[..10]).is_err());
        assert!(seal(&k[..16], "x", b"y").is_err());
    }

    #[test]
    fn hmac_vectors() {
        // RFC 4231 test case 2
        assert_eq!(hmac_hex(b"Jefe", "what do ya want for nothing?"), "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    #[test]
    fn tokens() {
        let k = b"server-key";
        let t = csrf_token(k, "abc");
        assert_eq!(t.len(), 32);
        assert!(csrf_ok(k, "abc", &t) && !csrf_ok(k, "abd", &t) && !csrf_ok(k, "abc", ""));
        let u = unlock_token(k, 2000, 3);
        assert!(unlock_valid(k, &u, 1999, 3));
        assert!(!unlock_valid(k, &u, 2001, 3));
        assert!(!unlock_valid(k, &u, 1999, 4));
        assert!(!unlock_valid(b"other", &u, 1999, 3));
        assert!(!unlock_valid(k, "2000.3", 1, 3));
        assert!(!unlock_valid(k, "x.3.y", 1, 3));
    }
}
