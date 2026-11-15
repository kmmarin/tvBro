// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! The core's error type. Messages never contain URLs, credentials or file contents.

use thiserror::Error as ThisError;

#[derive(Debug, ThisError)]
pub enum Error {
    #[error("i/o error: {0}")]
    Io(#[from] std::io::Error),
    #[error("input is larger than the allowed {limit} bytes")]
    TooLarge { limit: u64 },
    #[error("compressed data expands too much (more than {ratio}x)")]
    CompressionRatio { ratio: u64 },
    #[error("malformed XML: {0}")]
    Xml(String),
    #[error("malformed JSON: {0}")]
    Json(String),
    #[error("database error: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("blocked: {0}")]
    Blocked(String),
    #[error("invalid input: {0}")]
    Invalid(String),
    #[error("could not decrypt (wrong key or damaged data)")]
    Crypto,
    #[error("too many attempts: try again in {retry_after} s")]
    Throttled { retry_after: u64 },
}

pub type Result<T> = std::result::Result<T, Error>;
