// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Security primitives. Keys come from the platform (Android Keystore, Windows DPAPI, libsecret, the server's key
//! file / systemd credential); the core never stores them.

pub mod password;
pub mod ratelimit;
pub mod seal;
pub mod urlguard;

/// Cryptographically secure random bytes from the operating system.
pub fn random_bytes(n: usize) -> crate::Result<Vec<u8>> {
    let mut b = vec![0u8; n];
    getrandom::fill(&mut b).map_err(|e| crate::Error::Invalid(format!("no system randomness: {e}")))?;
    Ok(b)
}

/// Constant-time equality of two byte strings (length is not secret).
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    use subtle::ConstantTimeEq;
    a.len() == b.len() && bool::from(a.ct_eq(b))
}
