// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! Outbound URL guard (port of the web server's net.py; the same rules for every platform).
//!
//! - Only `http` and `https`, no whitespace or control characters, a host, ports 80, 443 and 1024-65535.
//! - The host is resolved and EVERY address is checked: loopback, link-local (incl. the 169.254.169.254 metadata
//!   address), multicast, unspecified, broadcast and reserved addresses are always refused; private ranges
//!   (RFC 1918, CGNAT 100.64/10, IPv6 ULA and the other non-global special ranges) only with `allow_lan`.
//!   IPv4-mapped, NAT64 and 6to4 IPv6 addresses are judged by the IPv4 address inside.
//! - The caller connects to the checked addresses only (no second lookup, so DNS rebinding cannot redirect it) and
//!   runs every redirect through [UrlGuard::redirect] (relative locations resolved, at most `max_redirects`).
//!
//! Resolution uses the platform resolver (`std::net::ToSocketAddrs`); networking itself stays on the platform.

use crate::error::Error;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs};
use url::{Host, Url};

/// How the address check judged an IP.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AddrClass {
    Global,
    /// Private / local-use: allowed only with LAN sources.
    Private,
    Loopback,
    /// Link-local, multicast, unspecified, broadcast, reserved: never.
    Never,
}

fn v4_class(ip: Ipv4Addr) -> AddrClass {
    let o = ip.octets();
    let n = u32::from(ip);
    let in_net = |base: u32, bits: u32| (n ^ base) >> (32 - bits) == 0;
    if o[0] == 127 {
        return AddrClass::Loopback;
    }
    if o[0] == 0 || in_net(0xA9FE_0000, 16) || o[0] >= 224 {
        // this-network, link-local + metadata, multicast 224/4, reserved 240/4, broadcast
        return AddrClass::Never;
    }
    let private = in_net(0x0A00_0000, 8)
        || in_net(0xAC10_0000, 12)
        || in_net(0xC0A8_0000, 16)
        || in_net(0x6440_0000, 10) // CGNAT
        || in_net(0xC000_0000, 24) // IETF protocol assignments
        || in_net(0xC000_0200, 24) // TEST-NET-1
        || in_net(0xC633_6400, 24) // TEST-NET-2
        || in_net(0xCB00_7100, 24) // TEST-NET-3
        || in_net(0xC612_0000, 15); // benchmarking
    if private { AddrClass::Private } else { AddrClass::Global }
}

/// The IPv4 address an IPv6 address carries (mapped ::ffff:0:0/96, NAT64 64:ff9b::/96, 6to4 2002::/16).
fn embedded_v4(ip: Ipv6Addr) -> Option<Ipv4Addr> {
    let s = ip.segments();
    if let Some(v4) = ip.to_ipv4_mapped() {
        return Some(v4);
    }
    if s[0] == 0x64 && s[1] == 0xff9b && s[2..6].iter().all(|&x| x == 0) {
        return Some(Ipv4Addr::new((s[6] >> 8) as u8, s[6] as u8, (s[7] >> 8) as u8, s[7] as u8));
    }
    if s[0] == 0x2002 {
        return Some(Ipv4Addr::new((s[1] >> 8) as u8, s[1] as u8, (s[2] >> 8) as u8, s[2] as u8));
    }
    None
}

fn v6_class(ip: Ipv6Addr) -> AddrClass {
    if let Some(v4) = embedded_v4(ip) {
        return v4_class(v4);
    }
    if ip.is_loopback() {
        return AddrClass::Loopback;
    }
    let s = ip.segments();
    let n = u128::from(ip);
    let in_net = |base: u128, bits: u32| (n ^ base) >> (128 - bits) == 0;
    if ip.is_unspecified() || s[0] & 0xffc0 == 0xfe80 || s[0] & 0xff00 == 0xff00 {
        return AddrClass::Never; // unspecified, link-local, multicast
    }
    if s[0] & 0xfe00 == 0xfc00
        || in_net(0x0064_ff9b_0001 << 80, 48) // local-use NAT64
        || in_net(0x0100 << 112, 64) // discard-only
        || in_net(0x2001_0db8 << 96, 32) // documentation
        || in_net(0x2001 << 112, 23)
    {
        return AddrClass::Private;
    }
    if s[0] & 0xe000 == 0x2000 { AddrClass::Global } else { AddrClass::Never } // outside 2000::/3: reserved
}

pub fn classify(ip: IpAddr) -> AddrClass {
    match ip {
        IpAddr::V4(v) => v4_class(v),
        IpAddr::V6(v) => v6_class(v),
    }
}

/// Address rule: global always; private with `allow_lan`; loopback only with `allow_loopback` (test suites).
pub fn ip_allowed(ip: IpAddr, allow_lan: bool, allow_loopback: bool) -> bool {
    match classify(ip) {
        AddrClass::Global => true,
        AddrClass::Private => allow_lan,
        AddrClass::Loopback => allow_loopback,
        AddrClass::Never => false,
    }
}

/// Scheme, host and port rules (no DNS).
pub fn check_url(url: &str) -> crate::Result<Url> {
    let bad = || Error::Blocked("bad url".into());
    if url.is_empty() || url.len() > 8192 || url.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err(bad());
    }
    let u = Url::parse(url).map_err(|_| bad())?;
    if !matches!(u.scheme(), "http" | "https") {
        return Err(Error::Blocked("only http and https are allowed".into()));
    }
    match u.host() {
        None => return Err(Error::Blocked("no host".into())),
        Some(Host::Domain("")) => return Err(Error::Blocked("no host".into())),
        _ => {}
    }
    if let Some(p) = u.port()
        && !(p == 80 || p == 443 || p >= 1024)
    {
        return Err(Error::Blocked("port not allowed".into()));
    }
    Ok(u)
}

/// A URL that passed every check, with the addresses the caller must connect to.
#[derive(Clone, Debug)]
pub struct Checked {
    pub url: Url,
    pub host: String,
    pub port: u16,
    pub addrs: Vec<SocketAddr>,
}

#[derive(Clone, Copy, Debug)]
pub struct UrlGuard {
    pub allow_lan: bool,
    /// Only for test suites with a local mock provider.
    pub allow_loopback: bool,
    pub max_redirects: u32,
}

impl Default for UrlGuard {
    fn default() -> Self {
        UrlGuard { allow_lan: false, allow_loopback: false, max_redirects: 6 }
    }
}

impl UrlGuard {
    pub fn new(allow_lan: bool) -> Self {
        UrlGuard { allow_lan, ..Default::default() }
    }

    /// Checks every resolved address; refuses the host when ANY of them is not allowed.
    pub fn check_addrs(&self, addrs: &[SocketAddr]) -> crate::Result<()> {
        if addrs.is_empty() {
            return Err(Error::Blocked("host does not resolve".into()));
        }
        if addrs.iter().all(|a| ip_allowed(a.ip(), self.allow_lan, self.allow_loopback)) {
            Ok(())
        } else {
            Err(Error::Blocked("address not allowed (local network, loopback or link-local)".into()))
        }
    }

    /// URL rules plus resolution with `resolve` (injectable for tests and platform resolvers).
    pub fn check_with(&self, url: &str, resolve: impl FnOnce(&str, u16) -> std::io::Result<Vec<SocketAddr>>) -> crate::Result<Checked> {
        let u = check_url(url)?;
        let port = u.port_or_known_default().unwrap_or(80);
        let (host, addrs) = match u.host() {
            Some(Host::Ipv4(ip)) => (ip.to_string(), vec![SocketAddr::new(IpAddr::V4(ip), port)]),
            Some(Host::Ipv6(ip)) => (ip.to_string(), vec![SocketAddr::new(IpAddr::V6(ip), port)]),
            Some(Host::Domain(d)) => {
                let d = d.to_string();
                let a = resolve(&d, port).map_err(|_| Error::Blocked("host does not resolve".into()))?;
                (d, a)
            }
            None => return Err(Error::Blocked("no host".into())),
        };
        self.check_addrs(&addrs)?;
        Ok(Checked { url: u, host, port, addrs })
    }

    /// URL rules plus, for an IP-literal host (incl. the WHATWG forms), the address rule; no DNS. For HTTP clients
    /// that resolve host names themselves and pass the answer to [UrlGuard::check_addrs] (Android's OkHttp `Dns`).
    pub fn check_static(&self, url: &str) -> crate::Result<Url> {
        let u = check_url(url)?;
        let port = u.port_or_known_default().unwrap_or(80);
        match u.host() {
            Some(Host::Ipv4(ip)) => self.check_addrs(&[SocketAddr::new(IpAddr::V4(ip), port)])?,
            Some(Host::Ipv6(ip)) => self.check_addrs(&[SocketAddr::new(IpAddr::V6(ip), port)])?,
            // RFC 6761: "localhost" and its subdomains are loopback by definition, whatever the resolver says
            Some(Host::Domain(d)) if d == "localhost" || d.ends_with(".localhost") || d == "localhost." || d.ends_with(".localhost.") => {
                return Err(Error::Blocked("address not allowed (local network, loopback or link-local)".into()));
            }
            _ => {}
        }
        Ok(u)
    }

    /// URL rules plus system DNS resolution.
    pub fn check(&self, url: &str) -> crate::Result<Checked> {
        self.check_with(url, |h, p| Ok((h, p).to_socket_addrs()?.collect()))
    }

    /// The next hop of a redirect: `location` resolved against `current`, checked like a new URL. `hop` counts from 1.
    pub fn redirect_with(&self, current: &Url, location: &str, hop: u32, resolve: impl FnOnce(&str, u16) -> std::io::Result<Vec<SocketAddr>>) -> crate::Result<Checked> {
        if hop > self.max_redirects {
            return Err(Error::Blocked("too many redirects".into()));
        }
        let next = current.join(location.trim()).map_err(|_| Error::Blocked("bad url".into()))?;
        self.check_with(next.as_str(), resolve)
    }

    pub fn redirect(&self, current: &Url, location: &str, hop: u32) -> crate::Result<Checked> {
        self.redirect_with(current, location, hop, |h, p| Ok((h, p).to_socket_addrs()?.collect()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // web tests/test_security.py
    #[test]
    fn only_http_and_https() {
        for u in [
            "file:///etc/passwd",
            "file://localhost/var/lib/tvbro/key.bin",
            "ftp://example.com/x",
            "gopher://example.com:70/_x",
            "data:text/html,<script>",
            "concat:/etc/passwd|/etc/shadow",
            "dict://example.com:11211/",
            "jar:http://x/!/",
            "/etc/passwd",
            "",
            "http://",
            "http://example.com:22/",
            "http://example.com:25/",
            "http://exa mple.com/",
            "http://example.com/\r\nX: y",
        ] {
            assert!(check_url(u).is_err(), "{u}");
        }
        for u in ["http://example.com/a.m3u", "https://example.com:8443/x", "http://example.com:80/", "HTTP://EXAMPLE.COM/"] {
            assert!(check_url(u).is_ok(), "{u}");
        }
    }

    #[test]
    fn static_check_without_dns() {
        let g = UrlGuard::new(true);
        for u in ["http://127.0.0.1:8765/x", "http://localhost:8765/", "http://a.localhost/", "http://LOCALHOST./", "http://0x7f.1/", "http://[::1]/", "http://169.254.169.254/", "http://[::ffff:127.0.0.1]/", "ftp://example.com/", "http://example.com:22/"] {
            assert!(g.check_static(u).is_err(), "{u}");
        }
        // names are left to the resolver hook; LAN literals pass with allow-LAN only
        assert!(g.check_static("http://localhost.example/x").is_ok());
        assert!(g.check_static("http://10.0.2.2:8765/playlist.m3u").is_ok());
        assert!(UrlGuard::default().check_static("http://10.0.2.2:8765/playlist.m3u").is_err());
    }

    #[test]
    fn localhost_by_name_is_refused() {
        // real resolver: "localhost" resolves to loopback on every platform
        assert!(UrlGuard::default().check("http://localhost:8080/x").is_err());
    }
}
