// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! tvBro shared core.
//!
//! One library for every tvBro platform (Android, desktop, web server). The platform UIs stay native and thin; the
//! logic that must behave the same everywhere lives here: playlist and guide parsing, the provider API models,
//! channel / group organisation, the guide time window, full-text search, the guide store, and the security
//! primitives (URL guard, password hashing, rate limiting, sealing secrets at rest).
//!
//! Behaviour follows the Kotlin `shared/` module, the Android app and the web server's Python port; where those
//! three differ, the module documentation says which one is followed and why (see docs/RUST_CORE.md).
#![forbid(unsafe_code)]
#![warn(missing_debug_implementations)]

pub mod catchup;
pub mod channels;
pub mod epgload;
pub mod epgmap;
pub mod epgstore;
pub mod error;
pub mod guide;
pub mod gz;
pub mod javahash;
pub mod lines;
pub mod m3u;
pub mod model;
pub mod search;
pub mod security;
pub mod text;
pub mod timefmt;
pub mod update;
pub mod xmltv;
pub mod xtream;

pub use error::{Error, Result};
pub use model::{Catchup, Channel, EpgIndex, Programme};

/// The crate version, for diagnostics and the bindings' smoke tests.
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
