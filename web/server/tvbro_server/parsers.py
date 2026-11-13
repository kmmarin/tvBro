# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Clean-room Python port of the tvBro shared data layer (shared/.../data/*.kt).

Only public specifications are used: the extended M3U playlist format, the Xtream
``player_api.php`` JSON API, and XMLTV. No third-party application's code was consulted.
The behaviour mirrors the Kotlin originals (Xtream.kt, Catchup.kt, Model.kt, Vod.kt,
M3uParser.kt, XmltvParser.kt, SearchLogic.kt) so the web backend matches the Android app.
"""
from __future__ import annotations

import json
import re
import urllib.parse
import zlib
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Iterable, Iterator, Optional

# --------------------------------------------------------------------------- models


@dataclass
class Catchup:
    type: str
    template: Optional[str]


@dataclass
class Channel:
    key: str
    source_id: str
    name: str
    logo: Optional[str]
    group: str
    url: str
    tvg_id: Optional[str]
    number: int
    catchup: Optional[Catchup]
    catchup_days: int

    @property
    def id_key(self) -> Optional[str]:
        if self.tvg_id and self.tvg_id.strip():
            return f"{self.source_id}|{self.tvg_id.lower()}"
        return None

    @property
    def name_key(self) -> str:
        return f"{self.source_id}|{self.name.lower()}"

    @property
    def stream_type(self) -> str:
        return "hls" if ".m3u8" in self.url.lower() else "ts"

    def to_json(self) -> dict:
        return {
            "key": self.key,
            "name": self.name,
            "logo": self.logo,
            "group": self.group,
            "tvgId": self.tvg_id,
            "number": self.number,
            "catchup": bool(self.catchup),
            "catchupDays": self.catchup_days,
            "streamType": self.stream_type,
        }


@dataclass(slots=True)
class Programme:
    key: str
    start: int  # epoch seconds
    stop: int
    title: str
    desc: Optional[str]
    sub: Optional[str] = None   # XMLTV <sub-title> (episode title)
    ep: Optional[str] = None    # "S2 E4" from <episode-num>, when the EPG has it

    def to_json(self) -> dict:
        d = {"start": self.start, "stop": self.stop, "title": self.title, "desc": self.desc}
        if self.sub:
            d["sub"] = self.sub
        if self.ep:
            d["ep"] = self.ep
        return d


_ONSCREEN_EP = re.compile(r"[Ss](\d{1,3})\s*[Ee](\d{1,4})")


def episode_label(nums: list) -> Optional[str]:
    """XMLTV <episode-num> values as (system, text) pairs -> "S2 E4" / "E4", or None.

    xmltv_ns is zero-based "season.episode.part" with optional "/total" parts; onscreen /
    SxxEyy text is used as given."""
    for system, text in nums:
        if not text:
            continue
        if (system or "").lower() == "xmltv_ns":
            parts = text.replace(" ", "").split(".")
            if len(parts) < 2:
                continue
            s_txt, e_txt = parts[0].split("/")[0], parts[1].split("/")[0]
            s = int(s_txt) + 1 if s_txt.isdigit() else None
            e = int(e_txt) + 1 if e_txt.isdigit() else None
            if s and e:
                return f"S{s} E{e}"
            if e:
                return f"E{e}"
            if s:
                return f"S{s}"
    for _system, text in nums:
        m = _ONSCREEN_EP.search(text or "")
        if m:
            return f"S{int(m.group(1))} E{int(m.group(2))}"
    return None


@dataclass
class VodItem:
    key: str
    source_id: str
    kind: str  # "movie" or "series"
    id: str
    name: str
    poster: Optional[str]
    category: str
    rating: Optional[str]
    ext: str
    url: Optional[str] = None
    year: Optional[str] = None
    genre: Optional[str] = None

    def to_json(self) -> dict:
        return {
            "key": self.key,
            "kind": self.kind,
            "id": self.id,
            "name": self.name,
            "poster": self.poster,
            "category": self.category,
            "rating": self.rating,
            "ext": self.ext,
            **({"year": self.year} if self.year else {}),
            **({"genre": self.genre} if self.genre else {}),
        }


@dataclass
class Episode:
    id: str
    season: int
    num: int
    title: str
    ext: str
    plot: Optional[str]
    duration: Optional[str]
    image: Optional[str] = None

    def to_json(self) -> dict:
        return {
            "id": self.id,
            "season": self.season,
            "num": self.num,
            "title": self.title,
            "ext": self.ext,
            "plot": self.plot,
            "duration": self.duration,
            "image": self.image,
        }


@dataclass
class VodDetail:
    plot: Optional[str] = None
    year: Optional[str] = None
    rating: Optional[str] = None
    cast: Optional[str] = None
    genre: Optional[str] = None
    trailer: Optional[str] = None
    duration: Optional[str] = None
    episodes: list = field(default_factory=list)
    director: Optional[str] = None
    ext: Optional[str] = None
    backdrop: Optional[str] = None
    name: Optional[str] = None
    cover: Optional[str] = None
    runtime: Optional[str] = None

    def to_json(self) -> dict:
        return {
            "plot": self.plot,
            "year": self.year,
            "rating": self.rating,
            "cast": self.cast,
            "genre": self.genre,
            "trailer": self.trailer,
            "duration": self.duration,
            "director": self.director,
            "ext": self.ext,
            "backdrop": self.backdrop,
            "name": self.name,
            "cover": self.cover,
            "runtime": self.runtime,
            "episodes": [e.to_json() for e in self.episodes],
        }


@dataclass
class XtreamCategory:
    category_id: str = ""
    category_name: str = ""


# --------------------------------------------------------------------------- helpers


def _str(v) -> Optional[str]:
    """Providers send strings, numbers, "" or null; coerce all to a clean string or None."""
    if v is None:
        return None
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        s = str(v)
        if isinstance(v, float) and s.endswith(".0"):
            s = s[:-2]
        return s.strip() or None
    if isinstance(v, str):
        s = v.strip()
        return s if s and s != "null" else None
    return None


def _s(o: dict, *keys: str) -> Optional[str]:
    for k in keys:
        v = _str(o.get(k))
        if v is not None:
            return v
    return None


# --------------------------------------------------------------------------- Xtream

_PLACEHOLDER = re.compile(r"\$?\{(\w+)}")


def normalize_server(s: str) -> str:
    t = s.strip().rstrip("/")
    if not t.lower().startswith("http://") and not t.lower().startswith("https://"):
        t = "http://" + t
    return t


def api_url(server: str, user: str, password: str, action: str, **extra) -> str:
    q = {"username": user, "password": password, "action": action}
    q.update({k: str(v) for k, v in extra.items() if v is not None})
    return f"{normalize_server(server)}/player_api.php?" + urllib.parse.urlencode(q)


def xmltv_url(server: str, user: str, password: str) -> str:
    q = {"username": user, "password": password}
    return f"{normalize_server(server)}/xmltv.php?" + urllib.parse.urlencode(q)


def live_url(server: str, user: str, password: str, stream_id: str) -> str:
    return f"{normalize_server(server)}/live/{user}/{password}/{stream_id}.ts"


def movie_url(server: str, user: str, password: str, vid: str, ext: str) -> str:
    return f"{normalize_server(server)}/movie/{user}/{password}/{vid}.{ext}"


def episode_url(server: str, user: str, password: str, eid: str, ext: str) -> str:
    return f"{normalize_server(server)}/series/{user}/{password}/{eid}.{ext}"


def parse_categories(raw: bytes, what: str = "categories") -> list[XtreamCategory]:
    data = _load_list(raw)
    out = []
    for o in data:
        if not isinstance(o, dict):
            continue
        cid = _s(o, "category_id", "id")
        if cid is None:
            continue
        out.append(XtreamCategory(cid, _s(o, "category_name", "name") or cid))
    return out


def to_channels(
    source_id: str, server: str, user: str, password: str,
    cats: list[XtreamCategory], streams: list[dict],
) -> list[Channel]:
    base = normalize_server(server)
    cat_map = {c.category_id: c.category_name for c in cats}
    order = {c.category_id: i for i, c in enumerate(cats)}
    indexed = list(enumerate(streams))
    indexed.sort(key=lambda it: (order.get(_s(it[1], "category_id") or "", 1 << 30), it[0]))
    out = []
    for _, s in indexed:
        if not isinstance(s, dict):
            continue
        sid = _s(s, "stream_id", "id")
        if sid is None:
            continue
        archive = _s(s, "tv_archive") == "1"
        name = _s(s, "name") or f"Channel {sid}"
        group = cat_map.get(_s(s, "category_id") or "", "") or "Other"
        days = 0
        catchup = None
        if archive:
            days = int(_s(s, "tv_archive_duration") or 3) if (_s(s, "tv_archive_duration") or "3").isdigit() else 3
            catchup = Catchup("xc", f"{base}/timeshift/{user}/{password}/{{duration}}/{{start}}/{sid}.ts")
        num = _s(s, "num")
        out.append(Channel(
            key=f"{source_id}|{sid}",
            source_id=source_id,
            name=name,
            logo=_s(s, "stream_icon"),
            group=group,
            url=f"{base}/live/{user}/{password}/{sid}.ts",
            tvg_id=_s(s, "epg_channel_id"),
            number=int(num) if num and num.lstrip("-").isdigit() else 0,
            catchup=catchup,
            catchup_days=days,
        ))
    return out


# --------------------------------------------------------------------------- stream format

_XC_TS = re.compile(r"^(https?://[^/?#]+/(?:live|timeshift)/[^?#]+)\.ts$", re.I)


def hls_variant(url: Optional[str]) -> Optional[str]:
    """The HLS form of an upstream stream URL, or None when the source has none.

    Browsers without Media Source Extensions (every iPhone browser) cannot play raw MPEG-TS,
    but play HLS natively. An Xtream panel serves the same live / timeshift stream as HLS when
    the ``.ts`` suffix is ``.m3u8``; a URL that already is a playlist is returned unchanged."""
    if not url:
        return None
    if url.split("?", 1)[0].split("#", 1)[0].lower().endswith(".m3u8"):
        return url
    m = _XC_TS.match(url)
    return m.group(1) + ".m3u8" if m else None


def choose_format(url: Optional[str], want: str) -> tuple[Optional[str], str]:
    """(upstream url, format actually served) for a client asking ``want`` = 'hls' | 'ts' | ''.

    'hls' is honoured when the source has an HLS form; otherwise the stream stays as it is and
    the caller is told so ('ts'), so the client can show a clear message instead of a black screen."""
    if not url:
        return None, "ts"
    native_hls = url.split("?", 1)[0].lower().endswith(".m3u8")
    if want == "hls":
        h = hls_variant(url)
        if h:
            return h, "hls"
    return url, "hls" if native_hls else "ts"


def ts_phase(head: bytes) -> int:
    """Offset of the first whole MPEG-TS packet in ``head`` (0..187): some panels serve
    timeshift files that begin mid-packet. 0 when no packet rhythm is found."""
    for o in range(min(188, len(head))):
        n = (len(head) - o + 187) // 188
        if n >= 3 and all(head[o + i * 188] == 0x47 for i in range(n)):
            return o
    return 0


def byterange_playlist(uri: str, total_bytes: int, duration_sec: float, target_sec: int = 6,
                       first: int = 0) -> str:
    """An HLS VOD playlist that slices ONE seekable MPEG-TS resource into byte ranges.

    For providers that serve catch-up only as a single .ts file (with HTTP Range): no
    transcoding and no server-side copy, the player just asks for consecutive ranges of the
    same URL. Slices are whole 188-byte TS packets; durations assume a constant bitrate (the
    player times the picture from the stream itself, the playlist only guides seeking).
    ``first`` is where the first whole packet starts (see ts_phase); bytes before it are skipped."""
    first = max(0, int(first))
    total = max(0, int(total_bytes) - first)
    duration = max(1.0, float(duration_sec))
    n = max(1, round(duration / target_sec))
    size = max(188, (total // n) // 188 * 188)
    out = ["#EXTM3U", "#EXT-X-VERSION:4", "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-MEDIA-SEQUENCE:0", ""]
    longest, off = 0.0, 0
    while off < total:
        ln = size if total - off - size >= size // 2 else total - off     # the tail joins the last slice
        d = duration * ln / total
        longest = max(longest, d)
        out += [f"#EXTINF:{d:.3f},", f"#EXT-X-BYTERANGE:{ln}@{first + off}", uri]
        off += ln
    out[4] = f"#EXT-X-TARGETDURATION:{max(1, -(-int(longest * 1000) // 1000))}"
    out.append("#EXT-X-ENDLIST")
    return "\n".join(out) + "\n"


# --------------------------------------------------------------------------- catch-up


def catchup_supports(ch: Channel, start_sec: int, now_sec: int) -> bool:
    return ch.catchup is not None and ch.catchup_days > 0 and start_sec >= now_sec - ch.catchup_days * 86400


def catchup_url(ch: Channel, start_sec: int, end_sec: int, now_sec: int) -> Optional[str]:
    c = ch.catchup
    if c is None:
        return None
    dur_sec = max(end_sec - start_sec, 60)

    def subst(t: str) -> str:
        def rep(m):
            g = m.group(1)
            if g in ("start", "utc"):
                return str(start_sec)
            if g in ("end", "utcend"):
                return str(end_sec)
            if g in ("timestamp", "lutc", "now"):
                return str(now_sec)
            if g == "offset":
                return str(now_sec - start_sec)
            if g == "duration":
                return str(end_sec - start_sec)
            return m.group(0)
        return _PLACEHOLDER.sub(rep, t)

    if c.type == "xc":
        if not c.template:
            return None
        start_fmt = datetime.fromtimestamp(start_sec, timezone.utc).strftime("%Y-%m-%d:%H-%M")
        return c.template.replace("{duration}", str(dur_sec // 60)).replace("{start}", start_fmt)
    if c.type == "append":
        return (ch.url + subst(c.template)) if c.template else None
    if c.type == "shift":
        sep = "&" if "?" in ch.url else "?"
        return f"{ch.url}{sep}utc={start_sec}&lutc={now_sec}"
    if not c.template:
        return None
    return subst(c.template)


# --------------------------------------------------------------------------- VOD


def is_vod_url(u: str) -> bool:
    return "/movie/" in u or "/series/" in u


def _load_list(raw: bytes):
    """Top-level JSON list, or a map-of-id-to-object; tolerant of a leading BOM/whitespace."""
    text = raw.decode("utf-8", "replace").lstrip("﻿ \t\r\n")
    try:
        root = json.loads(text) if text else []
    except json.JSONDecodeError:
        return []
    if isinstance(root, list):
        return root
    if isinstance(root, dict):
        return [v for v in root.values() if isinstance(v, dict) and (
            "name" in v or "category_id" in v or "stream_id" in v or "series_id" in v)]
    return []


def parse_movies(raw: bytes, source_id: str, cats: list[XtreamCategory], limit: int = 30000) -> list[VodItem]:
    cm = {c.category_id: c.category_name for c in cats}
    out = []
    for o in _load_list(raw):
        if not isinstance(o, dict):
            continue
        vid = _s(o, "stream_id", "id")
        if vid is None:
            continue
        out.append(VodItem(
            f"m|{source_id}|{vid}", source_id, "movie", vid, _s(o, "name", "title") or f"Movie {vid}",
            _s(o, "stream_icon", "cover", "movie_image"), cm.get(_s(o, "category_id") or "", "Other"),
            _s(o, "rating", "rating_5based"), _s(o, "container_extension") or "mp4",
            year=_year(o), genre=_s(o, "genre")))
        if len(out) >= limit:
            break
    return out


def parse_series_list(raw: bytes, source_id: str, cats: list[XtreamCategory], limit: int = 30000) -> list[VodItem]:
    cm = {c.category_id: c.category_name for c in cats}
    out = []
    for o in _load_list(raw):
        if not isinstance(o, dict):
            continue
        sid = _s(o, "series_id", "id")
        if sid is None:
            continue
        out.append(VodItem(
            f"s|{source_id}|{sid}", source_id, "series", sid, _s(o, "name", "title") or f"Series {sid}",
            _s(o, "cover", "stream_icon"), cm.get(_s(o, "category_id") or "", "Other"),
            _s(o, "rating", "rating_5based"), "", year=_year(o), genre=_s(o, "genre")))
        if len(out) >= limit:
            break
    return out


_YEAR_IN_NAME = re.compile(r"[(\[](19\d\d|20\d\d)[)\]]")


def _year(o: dict) -> Optional[str]:
    y = _s(o, "year", "releaseDate", "releasedate", "release_date")
    if y and y[:4].isdigit():
        return y[:4]
    m = _YEAR_IN_NAME.search(_s(o, "name", "title") or "")
    return m.group(1) if m else None


def _runtime(info: dict) -> Optional[str]:
    """Minutes as text from duration_secs, an "HH:MM:SS" duration or episode_run_time."""
    secs = _s(info, "duration_secs")
    if secs and secs.isdigit() and int(secs) > 0:
        return str(round(int(secs) / 60))
    d = _s(info, "duration")
    if d and re.fullmatch(r"\d{1,2}:\d{2}(:\d{2})?", d):
        p = [int(x) for x in d.split(":")]
        mins = p[0] * 60 + p[1] if len(p) == 3 else p[0]
        return str(mins) if mins else None
    r = _s(info, "episode_run_time")
    return r if r and r.isdigit() and r != "0" else None


def _detail(info: dict, eps: list, ext: Optional[str]) -> VodDetail:
    trailer = _s(info, "youtube_trailer", "trailer")
    if trailer and not trailer.startswith("http"):
        trailer = f"https://www.youtube.com/watch?v={trailer}"
    year = _s(info, "releasedate", "releaseDate", "release_date", "year")
    backdrop = None
    bp = info.get("backdrop_path")
    if isinstance(bp, list) and bp:
        backdrop = _str(bp[0])
    if backdrop is None:
        backdrop = _s(info, "backdrop_path", "cover_big", "movie_image")
    return VodDetail(
        plot=_s(info, "plot", "description"), year=year[:4] if year else None,
        rating=_s(info, "rating", "rating_5based"), cast=_s(info, "cast", "actors"),
        genre=_s(info, "genre"), trailer=trailer, duration=_s(info, "duration") or _s(info, "duration_secs"),
        episodes=eps, director=_s(info, "director"), ext=ext, backdrop=backdrop,
        name=_s(info, "name", "title", "o_name"), cover=_s(info, "cover", "movie_image", "cover_big"),
        runtime=_runtime(info))


def parse_vod_info(text: bytes) -> VodDetail:
    try:
        root = json.loads(text.decode("utf-8", "replace"))
    except json.JSONDecodeError:
        root = {}
    if not isinstance(root, dict):
        root = {}
    info = root.get("info") if isinstance(root.get("info"), dict) else {}
    md = root.get("movie_data") if isinstance(root.get("movie_data"), dict) else {}
    return _detail(info, [], _s(md, "container_extension"))


def _episode(o: dict, season: int) -> Optional[Episode]:
    eid = _s(o, "id")
    if eid is None:
        return None
    inf = o.get("info") if isinstance(o.get("info"), dict) else {}
    s = _s(o, "season")
    n = _s(o, "episode_num")
    return Episode(
        eid, int(float(s)) if s and _is_num(s) else season, int(float(n)) if n and _is_num(n) else 0,
        _s(o, "title") or "Episode", _s(o, "container_extension") or "mp4",
        _s(inf, "plot"), _s(inf, "duration", "duration_secs"), _s(inf, "movie_image", "cover_big"))


def _is_num(s: str) -> bool:
    try:
        float(s)
        return True
    except ValueError:
        return False


def parse_series_info(text: bytes) -> VodDetail:
    try:
        root = json.loads(text.decode("utf-8", "replace"))
    except json.JSONDecodeError:
        root = {}
    if not isinstance(root, dict):
        root = {}
    eps: list[Episode] = []
    node = root.get("episodes")
    if isinstance(node, dict):
        for season, arr in node.items():
            sn = int(season) if str(season).lstrip("-").isdigit() else 0
            if isinstance(arr, list):
                for x in arr:
                    if isinstance(x, dict):
                        e = _episode(x, sn)
                        if e:
                            eps.append(e)
            elif isinstance(arr, dict):
                e = _episode(arr, sn)
                if e:
                    eps.append(e)
    elif isinstance(node, list):
        for i, x in enumerate(node):
            if isinstance(x, list):
                for y in x:
                    if isinstance(y, dict):
                        e = _episode(y, i + 1)
                        if e:
                            eps.append(e)
            elif isinstance(x, dict):
                e = _episode(x, 1)
                if e:
                    eps.append(e)
    eps.sort(key=lambda e: (e.season, e.num))
    info = root.get("info") if isinstance(root.get("info"), dict) else {}
    return _detail(info, eps, None)


def clean_title(series: str, title: str) -> str:
    """Strip the series name, year and SxxEyy prefixes providers put in episode titles."""
    t = title.strip()
    if series.strip() and t.lower().startswith(series.lower()):
        t = t[len(series):]
    t = re.sub(r"\(\d{4}\)", " ", t)
    t = re.sub(r"^[\s\-–:._|]*[Ss]\d{1,2}\s*[Ee]\d{1,3}[\s\-–:._|]*", "", t)
    t = re.sub(r"^[\s\-–:._|]+", "", t)
    t = re.sub(r"[\s\-–:._|]+$", "", t)
    t = re.sub(r"\s+", " ", t)
    return t or title.strip()


# --------------------------------------------------------------------------- M3U


_ATTR = re.compile(r'([A-Za-z0-9_.-]+)="([^"]*)"')


def parse_m3u(text: str, source_id: str):
    """Returns (channels, epg_urls)."""
    out: list[Channel] = []
    epg: list[str] = []
    attrs: dict = {}
    name = ""
    grp = None
    have_ext = False
    for raw in text.splitlines():
        line = raw.strip().lstrip("﻿")
        if not line:
            continue
        low = line.lower()
        if low.startswith("#extm3u"):
            h = {m.group(1).lower(): m.group(2) for m in _ATTR.finditer(line)}
            src = h.get("url-tvg") or h.get("x-tvg-url")
            if src:
                epg += [x.strip() for x in src.split(",") if x.strip()]
        elif low.startswith("#extinf"):
            attrs = {m.group(1).lower(): m.group(2) for m in _ATTR.finditer(line)}
            comma = line.rfind(",")
            name = line[comma + 1:].strip() if comma >= 0 else ""
            have_ext = True
        elif low.startswith("#extgrp:"):
            grp = line[8:].strip()
        elif line.startswith("#"):
            continue
        else:
            at = attrs
            tvg_id = (at.get("tvg-id") or "").strip() or None
            title = name or at.get("tvg-name") or f"Channel {len(out) + 1}"
            ctype = (at.get("catchup") or at.get("catchup-type") or "").lower().strip() or None
            csrc = (at.get("catchup-source") or "").strip() or None
            days_s = at.get("catchup-days") or at.get("tvg-rec")
            days = int(days_s) if days_s and days_s.lstrip("-").isdigit() else (7 if ctype else 0)
            num_s = at.get("tvg-chno") or at.get("channel-number")
            out.append(Channel(
                key=f"{source_id}|{tvg_id or ''}|{zlib.crc32(line.encode('utf-8')):x}",   # stable across restarts
                source_id=source_id, name=title,
                logo=(at.get("tvg-logo") or "").strip() or None,
                group=(at.get("group-title") or grp or "").strip() or "Ungrouped",
                url=line, tvg_id=tvg_id,
                number=int(num_s) if num_s and num_s.lstrip("-").isdigit() else 0,
                catchup=Catchup(ctype, csrc) if ctype else None, catchup_days=days))
            attrs = {}
            name = ""
            grp = None
            have_ext = False
    return out, epg


# --------------------------------------------------------------------------- XMLTV


def _xmltv_time(s: str) -> int:
    """XMLTV time ("20240101120000 +0100") to epoch seconds, or -1 if malformed."""
    t = s.strip()
    if len(t) < 12:
        return -1
    try:
        year, mon, day = int(t[0:4]), int(t[4:6]), int(t[6:8])
        hh, mm = int(t[8:10]), int(t[10:12])
        has_sec = len(t) >= 14 and t[12].isdigit()
        sec = int(t[12:14]) if has_sec else 0
        rest = t[14:].strip() if has_sec else t[12:].strip()
        off = 0
        if len(rest) >= 5 and rest[0] in "+-":
            sign = -1 if rest[0] == "-" else 1
            off = sign * (int(rest[1:3]) * 3600 + int(rest[3:5]) * 60)
        dt = datetime(year, mon, day, hh, mm, sec, tzinfo=timezone.utc)
        return int(dt.timestamp()) - off
    except (ValueError, OverflowError):
        return -1


def parse_xmltv(source, resolve: Callable[[str, Optional[list]], Optional[str]],
                min_stop: int, max_start: int, sink: Callable[[Programme], None]) -> dict:
    """Streaming XMLTV parse. ``resolve(channel_attr, display_names) -> epg_key | [keys] | None``.

    ``source`` is a file-like object. Programmes outside [min_stop, max_start] or not
    resolved are dropped immediately, so memory stays flat.
    """
    import xml.etree.ElementTree as ET

    names: dict[str, list[str]] = {}
    for event, elem in ET.iterparse(source, events=("end",)):
        if elem.tag == "channel":
            cid = elem.get("id") or ""
            dn = [e.text.strip() for e in elem.findall("display-name") if e.text]
            if dn:
                names.setdefault(cid, []).extend(dn)
            elem.clear()
        elif elem.tag == "programme":
            ch = elem.get("channel") or ""
            start = _xmltv_time(elem.get("start") or "")
            stop = _xmltv_time(elem.get("stop") or "")
            if not (start < 0 or stop < 0 or stop < min_stop or start > max_start):
                key = resolve(ch, names.get(ch))
                if key:
                    title_el = elem.find("title")
                    desc_el = elem.find("desc")
                    title = (title_el.text or "").strip() if title_el is not None else ""
                    desc = (desc_el.text or "").strip()[:400] if desc_el is not None and desc_el.text else None
                    sub_el = elem.find("sub-title")
                    sub = (sub_el.text or "").strip()[:120] or None if sub_el is not None and sub_el.text else None
                    nums = [(e.get("system"), (e.text or "").strip()) for e in elem.findall("episode-num")]
                    ep = episode_label(nums) if nums else None
                    # one guide channel can feed several keys (its automatic match and a manual mapping)
                    for k in (key if isinstance(key, (list, tuple)) else (key,)):
                        sink(Programme(k, start, stop, title, desc, sub, ep))
            elem.clear()
    return names


# --------------------------------------------------------------------------- now/next


def now_next(progs: list[Programme], now: int):
    """Binary search for the last programme with start <= now; returns (current, next)."""
    if not progs:
        return None, None
    lo, hi, idx = 0, len(progs) - 1, -1
    while lo <= hi:
        mid = (lo + hi) >> 1
        if progs[mid].start <= now:
            idx = mid
            lo = mid + 1
        else:
            hi = mid - 1
    cur = progs[idx] if idx >= 0 and progs[idx].stop > now else None
    nxt = progs[idx + 1] if idx + 1 < len(progs) else None
    return cur, nxt


def classify(ch: Optional[Channel], start: int, stop: int, now: int) -> str:
    if start > now:
        return "FUTURE"
    if stop > now:
        return "NOW"
    if ch is not None and catchup_supports(ch, start, now):
        return "PAST_CATCHUP"
    return "PAST"
