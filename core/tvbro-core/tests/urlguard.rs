// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! URL guard address rules with private-range inputs (web tests/test_security.py). Kept under tests/ so the
//! publish check (tools/check_private.py) treats the private / LAN addresses as test inputs.

use std::net::{IpAddr, SocketAddr};
use tvbro_core::security::urlguard::{UrlGuard, ip_allowed};

fn ip(s: &str) -> IpAddr {
    s.parse().unwrap()
}

#[test]
fn address_rules() {
    let never = [
        "127.0.0.1", "127.1.2.3", "::1", "169.254.169.254", "169.254.0.1", "fe80::1", "0.0.0.0", "::", "224.0.0.1", "255.255.255.255", "240.0.0.1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
        "64:ff9b::7f00:1", "2002:7f00:1::1", "ff02::1", "fec0::1", "::7f00:1",
    ];
    let lan = ["10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.1.1", "100.64.0.1", "fd00::1", "::ffff:192.168.0.1", "192.0.2.10", "2001:db8::1"];
    for a in never {
        assert!(!ip_allowed(ip(a), false, false), "{a}");
        assert!(!ip_allowed(ip(a), true, false), "{a} (even with LAN sources allowed)");
    }
    for a in lan {
        assert!(!ip_allowed(ip(a), false, false), "{a}");
        assert!(ip_allowed(ip(a), true, false), "{a} with LAN sources allowed");
    }
    for a in ["8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "172.32.0.1", "192.169.0.1", "100.128.0.1", "::ffff:8.8.8.8", "64:ff9b::808:808"] {
        assert!(ip_allowed(ip(a), false, false), "{a}");
    }
    assert!(ip_allowed(ip("127.0.0.1"), false, true));
    assert!(!ip_allowed(ip("169.254.169.254"), true, true));
}

fn fake(addrs: &'static [&'static str]) -> impl FnOnce(&str, u16) -> std::io::Result<Vec<SocketAddr>> {
    move |_, p| Ok(addrs.iter().map(|a| SocketAddr::new(a.parse().unwrap(), p)).collect())
}

#[test]
fn resolution_checks_every_address() {
    let g = UrlGuard::default();
    let ok = g.check_with("https://example.com/x", fake(&["93.184.215.14"])).unwrap();
    assert_eq!((ok.host.as_str(), ok.port, ok.addrs.len()), ("example.com", 443, 1));
    // DNS answer with one public and one internal address: refused (rebinding / split answers)
    assert!(g.check_with("http://example.com/", fake(&["93.184.215.14", "127.0.0.1"])).is_err());
    assert!(g.check_with("http://example.com/", fake(&[])).is_err());
    assert!(g.check_with("http://example.com/", |_, _| Err(std::io::Error::other("nx"))).is_err());
    assert!(g.check_with("http://lan.example/", fake(&["192.168.1.5"])).is_err());
    assert!(UrlGuard::new(true).check_with("http://lan.example/", fake(&["192.168.1.5"])).is_ok());
    // IP literals are checked without DNS, including the WHATWG forms
    assert!(g.check_with("http://127.0.0.1:8080/", fake(&["8.8.8.8"])).is_err());
    assert!(g.check_with("http://0x7f.1/", fake(&["8.8.8.8"])).is_err());
    assert!(g.check_with("http://2130706433/", fake(&["8.8.8.8"])).is_err());
    assert!(g.check_with("http://[::ffff:169.254.169.254]/", fake(&["8.8.8.8"])).is_err());
    assert!(g.check_with("http://192.0.2.1:8080/", fake(&[])).is_err());
}

#[test]
fn redirects_are_checked() {
    let g = UrlGuard::default();
    let first = g.check_with("http://example.com/a/b", fake(&["93.184.215.14"])).unwrap();
    let rel = g.redirect_with(&first.url, "../c?x=1", 1, fake(&["93.184.215.14"])).unwrap();
    assert_eq!(rel.url.as_str(), "http://example.com/c?x=1");
    assert!(g.redirect_with(&first.url, "http://169.254.169.254/latest/meta-data", 1, fake(&[])).is_err());
    assert!(g.redirect_with(&first.url, "file:///etc/passwd", 1, fake(&[])).is_err());
    assert!(g.redirect_with(&first.url, "http://inside.example/", 1, fake(&["10.0.0.1"])).is_err());
    assert!(g.redirect_with(&first.url, "/ok", 7, fake(&["93.184.215.14"])).is_err());
}
