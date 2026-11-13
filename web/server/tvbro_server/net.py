# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Every outbound fetch the server makes for a playlist goes through here (playlist, guide,
provider API, images, streams, HLS segments, recordings).

- Only ``http`` and ``https``: the opener has no file, ftp or data handler, and every URL
  (also each redirect hop) is checked before it is opened.
- Ports 80, 443 and 1024-65535 only.
- The host is resolved here and every address is checked: loopback, link-local (incl. the
  169.254.169.254 metadata address), multicast, unspecified and reserved addresses are always
  refused; private ranges (RFC 1918, CGNAT 100.64/10, IPv6 ULA) only when the admin allowed LAN
  sources (Settings, or ``TVBRO_ALLOW_PRIVATE_SOURCES=1``). The connection then goes to the
  address that was checked, so a DNS answer that changes in between (DNS rebinding) cannot
  redirect it, and a redirect is checked again the same way.
- ``TVBRO_ALLOW_LOOPBACK_SOURCES=1`` exists only for the test suite's mock provider.
"""
from __future__ import annotations

import http.client
import ipaddress
import os
import socket
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable, Optional

UA = "tvBro-web/1.0"
SCHEMES = ("http", "https")
_CGNAT = ipaddress.ip_network("100.64.0.0/10")
_NAT64 = ipaddress.ip_network("64:ff9b::/96")

_allow_private_setting: Callable[[], bool] = lambda: False


class Blocked(urllib.error.URLError):
    """A URL or address the server will not fetch."""

    def __init__(self, why: str):
        super().__init__(why)
        self.why = why

    def __str__(self):
        return f"blocked: {self.why}"


def set_allow_private(fn: Callable[[], bool]):
    """The admin setting "Allow sources on the local network" (read at every fetch)."""
    global _allow_private_setting
    _allow_private_setting = fn


def allow_private() -> bool:
    if os.environ.get("TVBRO_ALLOW_PRIVATE_SOURCES") == "1":
        return True
    try:
        return bool(_allow_private_setting())
    except Exception:                       # noqa: BLE001
        return False


def _allow_loopback() -> bool:
    return os.environ.get("TVBRO_ALLOW_LOOPBACK_SOURCES") == "1"


def is_blocked(e: BaseException) -> bool:
    """True for Blocked itself or a URLError that wraps one (urllib wraps connect errors)."""
    while e is not None:
        if isinstance(e, Blocked):
            return True
        e = getattr(e, "reason", None) if isinstance(e, urllib.error.URLError) else None
    return False


def ip_allowed(addr: str, private_ok: Optional[bool] = None) -> bool:
    try:
        ip = ipaddress.ip_address(addr.split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 6:
        if ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        elif ip in _NAT64:
            ip = ipaddress.IPv4Address(int(ip) & 0xFFFFFFFF)
        elif ip.sixtofour:
            ip = ip.sixtofour
    if ip.is_loopback:
        return _allow_loopback()
    if ip.is_link_local or ip.is_multicast or ip.is_unspecified or ip.is_reserved:
        return False
    if ip.is_global and ip not in _CGNAT:
        return True
    private = ip.is_private or ip in _CGNAT
    if private_ok is None:
        private_ok = allow_private()
    return private and private_ok


def check_url(url: str) -> urllib.parse.ParseResult:
    """Scheme, host and port rules (no DNS). Raises Blocked."""
    if not isinstance(url, str) or not url or any(c in url for c in "\r\n\t\x00 "):
        raise Blocked("bad url")
    try:
        u = urllib.parse.urlparse(url)
        port = u.port
    except ValueError:
        raise Blocked("bad url")
    if u.scheme.lower() not in SCHEMES:
        raise Blocked("only http and https are allowed")
    if not u.hostname:
        raise Blocked("no host")
    if port is not None and not (port in (80, 443) or 1024 <= port <= 65535):
        raise Blocked("port not allowed")
    return u


def resolve(host: str, port: int) -> list[tuple]:
    """getaddrinfo, refusing the host when any of its addresses is not allowed."""
    try:
        infos = socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM)
    except socket.gaierror as e:
        raise urllib.error.URLError(e)
    if not infos:
        raise Blocked("host does not resolve")
    private_ok = allow_private()
    for info in infos:
        if not ip_allowed(info[4][0], private_ok):
            raise Blocked("address not allowed (local network, loopback or link-local)")
    return infos


def check_host(url: str) -> None:
    """check_url plus the address check (for ffmpeg / ffprobe, which connect by themselves)."""
    u = check_url(url)
    resolve(u.hostname, u.port or (443 if u.scheme.lower() == "https" else 80))


def _safe_create_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None, *a, **k):
    host, port = address
    err = None
    for family, socktype, proto, _c, sa in resolve(host, port):
        sock = None
        try:
            sock = socket.socket(family, socktype, proto)
            if timeout is not socket._GLOBAL_DEFAULT_TIMEOUT:
                sock.settimeout(timeout)
            sock.connect(sa)                 # the checked address, not a second lookup
            return sock
        except OSError as e:
            err = e
            if sock is not None:
                sock.close()
    raise err or OSError("connect failed")


class _HTTPConnection(http.client.HTTPConnection):
    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self._create_connection = _safe_create_connection


class _HTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self._create_connection = _safe_create_connection


class _HTTPHandler(urllib.request.HTTPHandler):
    def http_open(self, req):
        return self.do_open(_HTTPConnection, req)


class _HTTPSHandler(urllib.request.HTTPSHandler):
    def https_open(self, req):
        return self.do_open(_HTTPSConnection, req, context=self._context)


class _RedirectHandler(urllib.request.HTTPRedirectHandler):
    max_redirections = 6

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_url(newurl)                    # the address is checked again when it connects
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _build():
    od = urllib.request.OpenerDirector()
    for h in (_HTTPHandler(), _HTTPSHandler(), _RedirectHandler(), urllib.request.HTTPDefaultErrorHandler(),
              urllib.request.HTTPErrorProcessor(), urllib.request.UnknownHandler()):
        od.add_handler(h)
    return od


_OPENER = _build()


def open_url(url: str, timeout: float = 30, headers: Optional[dict] = None):
    """urlopen for provider / playlist / image URLs, with the rules above. Raises Blocked
    (or a URLError wrapping it), urllib.error.HTTPError, or other URLErrors."""
    check_url(url)
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    return _OPENER.open(req, timeout=timeout)


def read_capped(resp, limit: int) -> bytes:
    """The whole body, refusing more than ``limit`` bytes."""
    data = resp.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"the answer is larger than {limit // (1024 * 1024)} MB")
    return data
