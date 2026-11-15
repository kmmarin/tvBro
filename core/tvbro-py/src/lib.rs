// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Python extension module `tvbro_core` for the web server (PyO3, abi3). Phase 1: a smoke-test surface.

use pyo3::prelude::*;

#[pyfunction]
fn version() -> &'static str {
    tvbro::version()
}

/// (channels, epg_urls) with channels as (key, name, group, url, tvg_id) tuples; keys use CRC-32 like the server.
#[pyfunction]
#[allow(clippy::type_complexity)]
fn parse_m3u(text: &str, source_id: &str) -> (Vec<(String, String, String, String, Option<String>)>, Vec<String>) {
    let opts = tvbro::m3u::M3uOptions { key_style: tvbro::m3u::KeyStyle::Crc32, ..Default::default() };
    let r = tvbro::m3u::parse_str(text, source_id, opts);
    (r.channels.into_iter().map(|c| (c.key, c.name, c.group, c.url, c.tvg_id)).collect(), r.epg_urls)
}

#[pyfunction]
fn verify_password(pw: &str, stored: &str) -> bool {
    tvbro::security::password::verify(pw, stored)
}

/// The addresses to connect to, or ValueError("blocked: ...").
#[pyfunction]
#[pyo3(signature = (url, allow_lan=false))]
fn check_url(url: &str, allow_lan: bool) -> PyResult<Vec<String>> {
    tvbro::security::urlguard::UrlGuard::new(allow_lan)
        .check(url)
        .map(|c| c.addrs.iter().map(|a| a.to_string()).collect())
        .map_err(|e| pyo3::exceptions::PyValueError::new_err(e.to_string()))
}

#[pymodule]
fn tvbro_core(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(version, m)?)?;
    m.add_function(wrap_pyfunction!(parse_m3u, m)?)?;
    m.add_function(wrap_pyfunction!(verify_password, m)?)?;
    m.add_function(wrap_pyfunction!(check_url, m)?)?;
    Ok(())
}
