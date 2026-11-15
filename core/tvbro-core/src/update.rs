// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! In-app updater logic (port of the Android UpdateLogic.kt, generalised to every platform's asset): version
//! compare, GitHub `releases/latest` JSON, asset choice, and the user's "skip this version".

use regex::Regex;
use serde_json::Value;
use std::sync::LazyLock;

/// "v1.2.3", "1.2", "1.2.3-beta1" -> [1, 2, 3, 0]; None when it is not a version (at most 4 numeric parts).
pub fn parse_version(v: &str) -> Option<[u64; 4]> {
    let t = v.trim();
    let t = t.strip_prefix('v').or_else(|| t.strip_prefix('V')).unwrap_or(t);
    let core = t.split('-').next()?.split('+').next()?;
    let parts: Vec<&str> = core.split('.').collect();
    if parts.is_empty() || parts.len() > 4 {
        return None;
    }
    let mut out = [0u64; 4];
    for (i, p) in parts.iter().enumerate() {
        if p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        out[i] = p.parse().ok()?;
    }
    Some(out)
}

/// True when `candidate` is strictly newer than `current`. Unparseable candidates are never newer; an unparseable
/// current version is always older.
pub fn is_newer(candidate: &str, current: &str) -> bool {
    match (parse_version(candidate), parse_version(current)) {
        (None, _) => false,
        (Some(_), None) => true,
        (Some(a), Some(b)) => a > b,
    }
}

/// Which download a platform installs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Platform {
    Android,
    WindowsX64,
    LinuxAarch64,
    LinuxX64,
}

impl Platform {
    fn accepts(self, name: &str) -> bool {
        let n = name.to_lowercase();
        match self {
            Platform::Android => n.ends_with(".apk") && !n.contains("debug"),
            Platform::WindowsX64 => n.ends_with(".zip") && n.contains("windows") && !n.contains("arm64"),
            Platform::LinuxAarch64 => (n.ends_with(".tar.gz") || n.ends_with(".tgz")) && (n.contains("aarch64") || n.contains("arm64")),
            Platform::LinuxX64 => (n.ends_with(".tar.gz") || n.ends_with(".tgz")) && n.contains("linux") && (n.contains("x64") || n.contains("x86_64") || n.contains("amd64")),
        }
    }

    fn ext(self) -> &'static str {
        match self {
            Platform::Android => "apk",
            Platform::WindowsX64 => "zip",
            Platform::LinuxAarch64 | Platform::LinuxX64 => "tar.gz",
        }
    }
}

/// A published release that carries an installable asset for the platform.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReleaseInfo {
    pub version: String,
    pub tag: String,
    pub asset_name: String,
    pub asset_url: String,
    /// -1 when GitHub announces no size.
    pub asset_size: i64,
    /// Lower-case hex SHA-256 from the GitHub asset "digest" field, when present.
    pub sha256: Option<String>,
    pub notes: String,
    pub page_url: String,
}

fn s(o: &serde_json::Map<String, Value>, k: &str) -> Option<String> {
    match o.get(k)? {
        Value::String(v) => Some(v.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

static REPO: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").unwrap());

/// Only https://github.com/<owner>/<repo>/releases/download/... of the app's own repository.
pub fn is_allowed_asset_url(url: &str, repo: &str) -> bool {
    if !REPO.is_match(repo) {
        return false;
    }
    let prefix = format!("https://github.com/{repo}/releases/download/");
    url.len() > prefix.len() && url.is_char_boundary(prefix.len()) && url[..prefix.len()].eq_ignore_ascii_case(&prefix) && !url.contains("..") && !url.contains(['?', '#', '@'])
}

/// Parses GET /repos/{repo}/releases/latest. Drafts, pre-releases, non-version tags, releases without an asset for
/// `platform` and assets outside the repository are refused. Prefers an asset whose name contains "tvbro".
pub fn parse_latest(body: &str, repo: &str, platform: Platform) -> Option<ReleaseInfo> {
    let v: Value = serde_json::from_str(body).ok()?;
    let o = v.as_object()?;
    if s(o, "draft").as_deref() == Some("true") || s(o, "prerelease").as_deref() == Some("true") {
        return None;
    }
    let tag = s(o, "tag_name")?;
    let version = tag.strip_prefix('v').or_else(|| tag.strip_prefix('V')).unwrap_or(&tag).to_string();
    parse_version(&version)?;
    let assets: Vec<&serde_json::Map<String, Value>> = o.get("assets")?.as_array()?.iter().filter_map(|a| a.as_object()).filter(|a| platform.accepts(&s(a, "name").unwrap_or_default())).collect();
    let a = assets.iter().find(|a| s(a, "name").unwrap_or_default().to_lowercase().contains("tvbro")).or_else(|| assets.first())?;
    let url = s(a, "browser_download_url")?;
    if !is_allowed_asset_url(&url, repo) {
        return None;
    }
    let digest = s(a, "digest").and_then(|d| d.strip_prefix("sha256:").map(|x| x.to_lowercase())).filter(|d| d.len() == 64 && d.bytes().all(|c| c.is_ascii_hexdigit()));
    Some(ReleaseInfo {
        version,
        tag: tag.clone(),
        asset_name: s(a, "name")?,
        asset_url: url,
        asset_size: s(a, "size").and_then(|x| x.parse().ok()).unwrap_or(-1),
        sha256: digest,
        notes: crate::text::take_chars(&s(o, "body").unwrap_or_default(), 2000),
        page_url: s(o, "html_url").unwrap_or_default(),
    })
}

/// Download cap: the announced size plus 1 MB, or 300 MB (100 MB for an APK) when none is announced.
pub fn max_download_bytes(r: &ReleaseInfo, platform: Platform) -> i64 {
    if r.asset_size > 0 {
        r.asset_size + 1_048_576
    } else if platform == Platform::Android {
        100 * 1_048_576
    } else {
        300 * 1_048_576
    }
}

static UNSAFE_CHARS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[^0-9A-Za-z._-]").unwrap());

/// Safe local file name for the download.
pub fn file_name(r: &ReleaseInfo, platform: Platform) -> String {
    format!("tvbro-{}.{}", UNSAFE_CHARS.replace_all(&r.version, "_"), platform.ext())
}

/// What to show the user.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Decision {
    Available,
    /// Newer, but the user chose "skip this version" for exactly this one.
    Skipped,
    UpToDate,
}

/// `skipped`: the version the user skipped (a later release is offered again).
pub fn decide(release_version: &str, current: &str, skipped: Option<&str>) -> Decision {
    if !is_newer(release_version, current) {
        return Decision::UpToDate;
    }
    match (skipped.and_then(parse_version), parse_version(release_version)) {
        (Some(a), Some(b)) if a == b => Decision::Skipped,
        _ => Decision::Available,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const REPO_: &str = "o/r";

    fn sample() -> String {
        format!(
            r#"{{"tag_name":"v0.2.0","draft":false,"prerelease":false,"html_url":"https://github.com/o/r/releases/tag/v0.2.0",
         "body":"Fixes","assets":[
           {{"name":"tvbro-0.2.0-debug.apk","size":1,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/tvbro-0.2.0-debug.apk"}},
           {{"name":"tvbro-windows-x64-0.2.0.zip","size":2,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/tvbro-windows-x64-0.2.0.zip"}},
           {{"name":"tvbro-pi-arm64.tar.gz","size":4,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/tvbro-pi-arm64.tar.gz"}},
           {{"name":"tvbro-0.2.0.apk","size":3,"digest":"sha256:{}","browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/tvbro-0.2.0.apk"}}]}}"#,
            "ab".repeat(32)
        )
    }

    #[test]
    fn kotlin_versions() {
        assert!(is_newer("v0.2.0", "0.1.0"));
        assert!(is_newer("1.0", "0.9.9"));
        assert!(is_newer("0.1.10", "0.1.9"));
        assert!(!is_newer("0.1.0", "0.1.0"));
        assert!(!is_newer("v0.1.0", "0.2.0"));
        assert!(!is_newer("nightly", "0.1.0"));
        assert!(!is_newer("0.2.0-beta1", "0.2.0"));
        assert!(is_newer("0.0.1", "dev"));
        assert!(parse_version("1.2.3.4.5").is_none());
        assert!(parse_version("1..2").is_none());
        assert_eq!(parse_version("V1.2+build"), Some([1, 2, 0, 0]));
    }

    #[test]
    fn kotlin_parses_latest() {
        let r = parse_latest(&sample(), REPO_, Platform::Android).unwrap();
        assert_eq!(r.version, "0.2.0");
        assert_eq!(r.asset_name, "tvbro-0.2.0.apk");
        assert_eq!(r.asset_size, 3);
        assert_eq!(r.sha256.as_deref(), Some("ab".repeat(32).as_str()));
        assert_eq!(file_name(&r, Platform::Android), "tvbro-0.2.0.apk");
        assert_eq!(max_download_bytes(&r, Platform::Android), 3 + 1_048_576);
        assert_eq!(r.notes, "Fixes");
        let w = parse_latest(&sample(), REPO_, Platform::WindowsX64).unwrap();
        assert_eq!(w.asset_name, "tvbro-windows-x64-0.2.0.zip");
        assert_eq!(file_name(&w, Platform::WindowsX64), "tvbro-0.2.0.zip");
        assert_eq!(parse_latest(&sample(), REPO_, Platform::LinuxAarch64).unwrap().asset_size, 4);
        assert!(parse_latest(&sample(), REPO_, Platform::LinuxX64).is_none());
    }

    #[test]
    fn kotlin_rejects_prerelease_no_asset_and_foreign_urls() {
        assert!(parse_latest(&sample().replace("\"prerelease\":false", "\"prerelease\":true"), REPO_, Platform::Android).is_none());
        assert!(parse_latest(r#"{"tag_name":"v1.0.0","assets":[]}"#, REPO_, Platform::Android).is_none());
        assert!(parse_latest(&sample(), "other/repo", Platform::Android).is_none());
        assert!(parse_latest("not json", REPO_, Platform::Android).is_none());
        assert!(parse_latest(&sample().replace("v0.2.0\",\"draft", "nightly\",\"draft"), REPO_, Platform::Android).is_none());
        for u in [
            "http://github.com/o/r/releases/download/v1/tvbro.apk",
            "https://evil.example/o/r/releases/download/v1/tvbro.apk",
            "https://github.com/o/r2/releases/download/v1/tvbro.apk",
            "https://github.com/o/r/releases/download/../../x/tvbro.apk",
            "https://github.com/o/r/archive/tvbro.apk",
            "https://github.com/o/r/releases/download/v1/tvbro.apk?x=1",
        ] {
            assert!(!is_allowed_asset_url(u, REPO_), "{u}");
        }
        assert!(is_allowed_asset_url("https://github.com/O/R/releases/download/v1/tvbro.apk", REPO_));
        assert!(!is_allowed_asset_url("https://github.com/o/r/releases/download/v1/x", "bad repo"));
    }

    #[test]
    fn skip_version() {
        assert_eq!(decide("0.3.0", "0.2.0", None), Decision::Available);
        assert_eq!(decide("v0.3.0", "0.2.0", Some("0.3.0")), Decision::Skipped);
        assert_eq!(decide("0.3.1", "0.2.0", Some("0.3.0")), Decision::Available);
        assert_eq!(decide("0.2.0", "0.2.0", None), Decision::UpToDate);
        assert_eq!(decide("0.1.0", "0.2.0", Some("0.1.0")), Decision::UpToDate);
    }
}
