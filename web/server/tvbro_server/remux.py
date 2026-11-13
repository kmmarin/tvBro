# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""On-the-fly remux of VOD files a browser cannot play as a file (MKV, AVI, TS… on iPhone)
into HLS with fMP4 segments. The video is never re-encoded: it is copied (HEVC tagged
``hvc1`` so Apple players accept it); audio is copied when the client plays the codec
(AAC everywhere, AC-3 / E-AC-3 where the browser says so) and otherwise converted to AAC
(stereo, or 5.1 for multichannel sources). Text subtitles (SRT/ASS/WebVTT) are written as
WebVTT next to the playlist; image subtitles (PGS, DVD) are skipped with a note.

One ffmpeg per stream session (browser tab). It is niced, stopped (SIGSTOP) when it runs
far ahead of the viewer and continued when the viewer catches up, killed when the session
idles for a minute, is replaced or starts another title. Old segments far behind the
viewer are deleted, so a long film never fills the disk. Seeking outside what has been
produced starts a new ffmpeg at that time (input seek, ``-ss``); the browser adds the
offset itself.

The upstream URL carries the provider login: it is passed to ffmpeg as an argument only,
ffmpeg runs with ``-loglevel error`` and anything it prints is scrubbed of URLs.
"""
from __future__ import annotations

import json
import logging
import os
import re
import secrets
import shutil
import signal
import subprocess
import threading
import time
from typing import Callable, Optional

from . import net as NET

LOG = logging.getLogger("tvbro")
UA = "tvBro-web/1.0"

# containers a browser plays as a plain progressive file
IOS_FILE_EXTS = ("mp4", "m4v", "mov")
MSE_FILE_EXTS = IOS_FILE_EXTS + ("webm", "mkv")     # desktop browsers try MKV themselves first

TEXT_SUBS = ("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")
IMAGE_SUBS = ("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub", "dvb_teletext")

HLS_TIME = 6
IDLE_SEC = 60                 # no request for this long: the remux is stopped
AHEAD_SEGS = 20               # pause ffmpeg when it is two minutes ahead of the viewer
RESUME_SEGS = 10              # ... and continue at one minute
BEHIND_SEGS = 50              # keep five minutes behind the viewer on disk
LOCAL_JOBS = 2                # remuxes of recordings (no provider stream; capped for CPU)

NAME_RE = re.compile(r"^(index\.m3u8|init\.mp4|s\d{5}\.m4s|sub\d{1,2}\.vtt|info\.json)$")
_URL_RE = re.compile(r"\w+://\S+")


def scrub(text: str) -> str:
    return _URL_RE.sub("<url>", text or "")


# --------------------------------------------------------------------------- decisions (pure)


def vod_mode(ext: str, client: dict, force_remux: bool = False) -> str:
    """'file' (the browser plays the provider's file as it is) or 'remux'."""
    ext = (ext or "").lower()
    if force_remux:
        return "remux"
    native = IOS_FILE_EXTS if client.get("ios") else MSE_FILE_EXTS
    return "file" if ext in native else "remux"


def _lang(st: dict) -> str:
    return ((st.get("tags") or {}).get("language") or "").lower()


def _label(st: dict, n: int, kind: str) -> str:
    tags = st.get("tags") or {}
    title, lang = (tags.get("title") or "").strip(), (tags.get("language") or "").strip()
    if title and lang and lang.lower() not in title.lower() and lang.lower() != "und":
        return f"{title} ({lang})"[:60]
    if title:
        return title[:60]
    if lang and lang.lower() != "und":
        return lang
    return f"{kind} {n}"


def plan(probe: dict, client: dict, audio_choice: Optional[int] = None) -> dict:
    """What to copy / convert, from ffprobe's JSON and the client's capabilities.

    client: {"ios": bool, "hevc": bool, "ac3": bool, "eac3": bool, "av1": bool}
    audio_choice: position in the returned ``audio`` list (None: the default track).
    Returns {"ok": True, ...} or {"ok": False, "why": "..."}."""
    streams = probe.get("streams") or []
    vids = [s for s in streams if s.get("codec_type") == "video"
            and not (s.get("disposition") or {}).get("attached_pic")]
    auds = [s for s in streams if s.get("codec_type") == "audio"]
    subs = [s for s in streams if s.get("codec_type") == "subtitle"]
    try:
        duration = float((probe.get("format") or {}).get("duration") or 0)
    except (TypeError, ValueError):
        duration = 0.0
    if not vids:
        return {"ok": False, "why": "the file has no video track"}
    v = vids[0]
    vc = (v.get("codec_name") or "").lower()
    tag = None
    if vc == "h264":
        pass
    elif vc == "hevc":
        if not client.get("hevc"):
            return {"ok": False, "why": "this browser cannot play HEVC (H.265) video"}
        tag = "hvc1"
    elif vc == "av1" and client.get("av1"):
        pass
    else:
        return {"ok": False, "why": f"its video ({vc.upper() or 'unknown'}) would need re-encoding"}
    notes = []
    audio = [{"index": s.get("index"), "codec": (s.get("codec_name") or "").lower(),
              "channels": int(s.get("channels") or 2), "lang": _lang(s), "label": _label(s, i + 1, "Track")}
             for i, s in enumerate(auds)]
    a = None
    if audio:
        if audio_choice is not None and 0 <= audio_choice < len(audio):
            pick = audio_choice
        else:
            pick = next((i for i, s in enumerate(auds) if (s.get("disposition") or {}).get("default")), 0)
        a = dict(audio[pick], pos=pick)
        c = a["codec"]
        a["copy"] = c == "aac" or (c == "ac3" and bool(client.get("ac3"))) or (c == "eac3" and bool(client.get("eac3")))
        if not a["copy"]:
            a["out_channels"] = 6 if a["channels"] > 2 else 2
            notes.append(f"audio {c.upper()} converted to AAC {'5.1' if a['out_channels'] == 6 else 'stereo'}")
    text, skipped = [], []
    for i, s in enumerate(subs):
        c = (s.get("codec_name") or "").lower()
        item = {"index": s.get("index"), "codec": c, "lang": _lang(s), "label": _label(s, i + 1, "Subtitles")}
        if c in TEXT_SUBS and len(text) < 12:
            text.append(item)
        else:
            skipped.append(item)
    if skipped:
        notes.append(f"{len(skipped)} picture subtitle track{'s' if len(skipped) > 1 else ''} cannot be shown")
    return {"ok": True, "video": {"index": v.get("index"), "codec": vc, "tag": tag}, "audio": audio, "a": a,
            "subs": text, "skipped": skipped, "notes": notes, "duration": duration}


NET_PROTOCOLS = "http,https,tcp,tls,crypto"


def input_protocols(url: str) -> list[str]:
    """-protocol_whitelist for an input: network protocols for an http(s) URL, only ``file`` for
    a recording on this server's disk (an absolute path). Anything else (file:, concat:,
    subfile:, data:, ftp:, …) is refused before ffmpeg / ffprobe starts."""
    if url.startswith(("http://", "https://")):
        return ["-protocol_whitelist", NET_PROTOCOLS]
    if (url.startswith("/") or os.path.isabs(url)) and not re.match(r"^[A-Za-z][A-Za-z0-9+.-]+:", url):
        return ["-protocol_whitelist", "file"]
    raise ValueError("only http(s) URLs or a local recording can be read")


def ffmpeg_args(url: str, p: dict, out_dir: str, ss: float = 0.0, ffmpeg: str = "ffmpeg") -> list[str]:
    """The ffmpeg command line for plan ``p`` (from plan()). ``url`` goes only into -i."""
    args = ["nice", "-n", "10", ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error"] + input_protocols(url)
    if url.startswith(("http://", "https://")):
        args += ["-user_agent", UA, "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10"]
    elif p.get("follow"):
        # a recording that is still being written: keep reading as it grows (gives up after
        # 30 s without new data, i.e. when the recording has ended)
        args += ["-follow", "1", "-rw_timeout", "30000000"]
    if ss and ss > 0:
        args += ["-ss", f"{ss:.3f}"]
    # keep the film's own timestamps: the browser learns where the copy really starts (the
    # keyframe at or before ss) from the first segment, and subtitle times stay absolute
    args += ["-copyts", "-start_at_zero", "-i", url, "-map", f"0:{p['video']['index']}"]
    a = p.get("a")
    if a:
        args += ["-map", f"0:{a['index']}"]
    args += ["-c:v", "copy"]
    if p["video"].get("tag"):
        args += ["-tag:v", p["video"]["tag"]]
    if a:
        if a["copy"]:
            args += ["-c:a", "copy"]
        else:
            ch = a.get("out_channels", 2)
            args += ["-c:a", "aac", "-ac", str(ch), "-b:a", "384k" if ch == 6 else "160k"]
    args += ["-map_metadata", "-1", "-map_chapters", "-1", "-max_muxing_queue_size", "4096",
             "-f", "hls", "-hls_segment_type", "fmp4", "-hls_time", str(HLS_TIME),
             "-hls_playlist_type", "event", "-hls_list_size", "0",
             "-hls_flags", "independent_segments+temp_file", "-hls_fmp4_init_filename", "init.mp4",
             "-hls_segment_filename", os.path.join(out_dir, "s%05d.m4s"), "-start_number", "0",
             os.path.join(out_dir, "index.m3u8")]
    for n, s in enumerate(p.get("subs") or []):
        args += ["-map", f"0:{s['index']}", "-c:s", "webvtt", "-flush_packets", "1", "-f", "webvtt",
                 os.path.join(out_dir, f"sub{n}.vtt")]
    return args


# --------------------------------------------------------------------------- live TV / catch-up / recordings
# Apple players take HEVC only from fMP4 segments, never from MPEG-TS. Live channels and
# recordings are MPEG-TS, so an HEVC one is remuxed (copied, tagged hvc1) for a client
# without Media Source Extensions; everything else keeps the zero-cost relay / byte ranges.

LIVE_HLS_TIME = 4
LIVE_LIST_SIZE = 10
COPY_AUDIO = ("aac", "ac3", "eac3")         # every Apple device plays these from fMP4


def codec_summary(probe: dict) -> Optional[dict]:
    """ffprobe JSON -> {"v": video codec, "a": audio codec or "", "ach": channels}; None
    when there is no video."""
    streams = probe.get("streams") or []
    v = next((s for s in streams if s.get("codec_type") == "video"
              and not (s.get("disposition") or {}).get("attached_pic")), None)
    if v is None:
        return None
    a = next((s for s in streams if s.get("codec_type") == "audio"), None)
    return {"v": (v.get("codec_name") or "").lower(), "a": ((a or {}).get("codec_name") or "").lower(),
            "ach": int((a or {}).get("channels") or 2)}


def live_mode(want_fmt: str, info: Optional[dict]) -> str:
    """'remux' (fMP4 HLS through ffmpeg) or 'relay' (the stream as the provider sends it).

    want_fmt is the client's hint: 'hls' = no Media Source Extensions (every iPhone / iPad
    browser), which plays HLS natively and so HEVC only in fMP4."""
    return "remux" if want_fmt == "hls" and info and info.get("v") == "hevc" else "relay"


def rec_mode(client: dict, vcodec: str, force_remux: bool = False) -> str:
    """A recording (MPEG-TS on disk): 'file' (HLS of byte ranges, as recorded) or 'remux'."""
    if force_remux:
        return "remux"
    return "remux" if client.get("ios") and vcodec == "hevc" else "file"


def live_probe_args(url: str, ffprobe: str = "ffprobe") -> list[str]:
    """A quick look at a live stream's codecs (a couple of seconds of it)."""
    args = [ffprobe, "-v", "error", "-print_format", "json", "-show_streams",
            "-probesize", "3000000", "-analyzeduration", "3000000"] + input_protocols(url)
    if url.startswith(("http://", "https://")):
        args += ["-user_agent", UA, "-rw_timeout", "10000000"]
    return args + [url]


def stream_args(url: str, info: dict, out_dir: str, mode: str = "live", start_number: int = 0,
                ffmpeg: str = "ffmpeg") -> list[str]:
    """ffmpeg for a channel: mode 'live' = a sliding window of the live stream (old segments
    deleted), 'event' = a catch-up programme from its start (a growing playlist). The video
    is copied (HEVC tagged hvc1), audio copied when Apple plays it, else AAC."""
    args = ["nice", "-n", "10", ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error"] + input_protocols(url)
    if url.startswith(("http://", "https://")):
        args += ["-user_agent", UA, "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "10",
                 "-rw_timeout", "15000000"]
    args += ["-fflags", "nobuffer", "-probesize", "1000000", "-analyzeduration", "1000000",
             "-i", url, "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "copy"]
    if info.get("v") == "hevc":
        args += ["-tag:v", "hvc1"]
    if not info.get("a") or info.get("a") in COPY_AUDIO:
        args += ["-c:a", "copy"]
    else:
        ch = 6 if int(info.get("ach") or 2) > 2 else 2
        args += ["-c:a", "aac", "-ac", str(ch), "-b:a", "384k" if ch == 6 else "160k"]
    args += ["-sn", "-dn", "-map_metadata", "-1", "-max_muxing_queue_size", "4096",
             "-f", "hls", "-hls_segment_type", "fmp4", "-hls_time", str(LIVE_HLS_TIME)]
    if mode == "live":
        args += ["-hls_list_size", str(LIVE_LIST_SIZE),
                 "-hls_flags", "delete_segments+independent_segments+temp_file"]
    else:
        args += ["-hls_playlist_type", "event", "-hls_list_size", "0",
                 "-hls_flags", "independent_segments+temp_file"]
    args += ["-hls_fmp4_init_filename", "init.mp4",
             "-hls_segment_filename", os.path.join(out_dir, "s%05d.m4s"), "-start_number", str(int(start_number)),
             os.path.join(out_dir, "index.m3u8")]
    return args


def steady_target(text: str, floor: int) -> tuple[str, int]:
    """HLS forbids EXT-X-TARGETDURATION to change, but ffmpeg recomputes it over its sliding
    window. Serve max(floor, ffmpeg's) and remember it (the caller keeps the returned value)."""
    m = re.search(r"#EXT-X-TARGETDURATION:(\d+)", text)
    t = max(floor, int(m.group(1)) if m else 0)
    return re.sub(r"#EXT-X-TARGETDURATION:\d+", f"#EXT-X-TARGETDURATION:{t}", text), t


def live_start_number(now: float) -> int:
    """Media sequence of a new live remux: grows with the clock, so a player that reloads the
    playlist after a restart never sees the sequence go back (5 digits, ~55 h of room)."""
    return (int(now) // LIVE_HLS_TIME) % 50000


class CodecCache:
    """Video / audio codec per channel, from one quick ffprobe; kept on disk (codecs.json) so a
    restart does not probe again. Refreshed after OK_TTL, or at once when a remux of it fails;
    a failed probe is remembered for FAIL_TTL (the stream is then relayed as it is)."""
    OK_TTL = 3 * 86400
    FAIL_TTL = 600

    def __init__(self, path: Optional[str], clock: Callable[[], float] = time.time):
        self.path, self.clock = path, clock
        self._lock = threading.Lock()
        self._d: dict[str, dict] = {}
        if path:
            try:
                with open(path, encoding="utf-8") as f:
                    self._d = {k: v for k, v in json.load(f).items() if isinstance(v, dict)}
            except (OSError, ValueError):
                self._d = {}

    def get(self, key: str) -> tuple[bool, Optional[dict]]:
        """(known, info): known False = probe now; info None = probe failed recently."""
        with self._lock:
            e = self._d.get(key)
        if not e:
            return False, None
        ttl = self.OK_TTL if e.get("info") else self.FAIL_TTL
        if self.clock() - float(e.get("at") or 0) > ttl:
            return False, None
        return True, e.get("info")

    def put(self, key: str, info: Optional[dict]):
        with self._lock:
            self._d[key] = {"info": info, "at": int(self.clock())}
            while len(self._d) > 5000:
                self._d.pop(next(iter(self._d)))
            self._save()

    def drop(self, key: str):
        with self._lock:
            if self._d.pop(key, None) is not None:
                self._save()

    def _save(self):
        if not self.path:
            return
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self._d, f)
            os.replace(tmp, self.path)
        except OSError:
            pass


def probe_args(url: str, ffprobe: str = "ffprobe") -> list[str]:
    args = [ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams"] + input_protocols(url)
    if url.startswith(("http://", "https://")):
        args += ["-user_agent", UA, "-reconnect", "1", "-reconnect_streamed", "1"]
    return args + [url]


def serve_playlist(text: str, sid: str, prefix: str = "", live: bool = False) -> str:
    """ffmpeg's playlist for the browser: play from the start (an EVENT playlist would
    otherwise start at its live edge; a live window starts at its edge as usual) and carry
    the stream session on every URI. ``prefix`` makes the URIs absolute (``/remux/<id>/``)
    for a playlist served from another path (live TV: the signed /stream URL)."""
    q = ("?sid=" + sid) if sid else ""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            continue
        if s.startswith("#EXT-X-MAP:"):
            s = re.sub(r'URI="([^"?]+)"', lambda m: f'URI="{prefix}{os.path.basename(m.group(1))}{q}"', s)
        elif not s.startswith("#"):
            s = prefix + os.path.basename(s) + q
        out.append(s)
        if s == "#EXTM3U" and not live:
            out.append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES")
    return "\n".join(out) + "\n"


def _boxes(buf: bytes, start: int = 0, end: Optional[int] = None):
    """ISO BMFF boxes in buf[start:end] -> (type, payload start, box end)."""
    end = len(buf) if end is None else end
    i = start
    while i + 8 <= end:
        size = int.from_bytes(buf[i:i + 4], "big")
        typ = buf[i + 4:i + 8].decode("latin-1")
        hdr = 8
        if size == 1 and i + 16 <= end:
            size, hdr = int.from_bytes(buf[i + 8:i + 16], "big"), 16
        elif size == 0:
            size = end - i
        if size < hdr or i + size > end:
            return
        yield typ, i + hdr, i + size
        i += size


def _find(buf: bytes, path: list[str], start: int = 0, end: Optional[int] = None):
    """All payload ranges reached by the box path (e.g. ['moov', 'trak'])."""
    out = [(start, len(buf) if end is None else end)]
    for name in path:
        out = [(s, e) for a, b in out for t, s, e in _boxes(buf, a, b) if t == name]
    return out


def video_track(init: bytes) -> Optional[tuple[int, int, float]]:
    """init.mp4 -> (video track id, media timescale, leading empty edit in seconds).

    With -copyts ffmpeg keeps the film's timestamps by putting an empty edit (the start
    offset) in the track's edit list and/or a non-zero tfdt in the first fragment."""
    movie_scale = 1000
    for t, ps, pe in _boxes(init):
        if t == "moov":
            for t2, qs, qe in _boxes(init, ps, pe):
                if t2 == "mvhd":
                    v = init[qs]
                    movie_scale = int.from_bytes(init[qs + (20 if v == 1 else 12):qs + (24 if v == 1 else 16)], "big") or 1000
    for s, e in _find(init, ["moov", "trak"]):
        tid = scale = None
        is_video = False
        empty = 0
        for t, ps, pe in _boxes(init, s, e):
            if t == "tkhd":
                v = init[ps]
                tid = int.from_bytes(init[ps + (20 if v == 1 else 12):ps + (24 if v == 1 else 16)], "big")
            elif t == "edts":
                for t2, qs, qe in _boxes(init, ps, pe):
                    if t2 != "elst":
                        continue
                    v = init[qs]
                    n = int.from_bytes(init[qs + 4:qs + 8], "big")
                    w = 8 if v == 1 else 4
                    i = qs + 8
                    for _ in range(min(n, 16)):
                        dur = int.from_bytes(init[i:i + w], "big")
                        mt = int.from_bytes(init[i + w:i + 2 * w], "big", signed=True)
                        i += 2 * w + 4
                        if mt != -1:
                            break
                        empty += dur
            elif t == "mdia":
                for t2, qs, qe in _boxes(init, ps, pe):
                    if t2 == "mdhd":
                        v = init[qs]
                        scale = int.from_bytes(init[qs + (20 if v == 1 else 12):qs + (24 if v == 1 else 16)], "big")
                    elif t2 == "hdlr":
                        is_video = init[qs + 8:qs + 12] == b"vide"
        if is_video and tid is not None and scale:
            return tid, scale, empty / movie_scale
    return None


def first_video_time(init: bytes, seg: bytes) -> Optional[float]:
    """Film time where the remux's video starts, in seconds (empty edit + first tfdt)."""
    vt = video_track(init)
    if not vt:
        return None
    for s, e in _find(seg, ["moof", "traf"]):
        tid = base = None
        for t, ps, pe in _boxes(seg, s, e):
            if t == "tfhd":
                tid = int.from_bytes(seg[ps + 4:ps + 8], "big")
            elif t == "tfdt":
                base = int.from_bytes(seg[ps + 4:ps + (12 if seg[ps] == 1 else 8)], "big")
        if tid == vt[0] and base is not None:
            return round(vt[2] + base / vt[1], 3)
    return None

def seg_number(name: str) -> Optional[int]:
    m = re.match(r"^s(\d{5})\.m4s$", name)
    return int(m.group(1)) if m else None


# --------------------------------------------------------------------------- running remuxes


class Job:
    def __init__(self, rid: str, sid: str, key: str, out_dir: str, ss: float, mode: str = "vod", local: bool = False):
        self.rid, self.sid, self.key, self.dir, self.ss = rid, sid, key, out_dir, ss
        # mode: 'vod' (a film / recording, seekable by restarting), 'live' (sliding window),
        # 'event' (catch-up from its start); local = reads a file on this server (a recording):
        # no provider stream, so it neither counts against nor is stopped by the stream limit
        self.mode, self.local = mode, local
        self.target = int(LIVE_HLS_TIME * 1.5)   # served EXT-X-TARGETDURATION (never lowered)
        self.proc: Optional[subprocess.Popen] = None
        self.started = self.last_access = time.time()
        self.last_seg = 0          # newest segment the viewer asked for
        self.paused = False
        self.killed = False
        self.err = ""
        self.start: Optional[float] = None    # film time where the copy really starts

    def start_time(self) -> Optional[float]:
        """From the first segment: the keyframe ffmpeg started at (<= ss)."""
        if self.start is None:
            try:
                with open(os.path.join(self.dir, "init.mp4"), "rb") as f:
                    init = f.read()
                with open(os.path.join(self.dir, "s00000.m4s"), "rb") as f:
                    seg = f.read(64 * 1024)
                self.start = first_video_time(init, seg)
            except OSError:
                pass
        return self.start

    def produced(self) -> int:
        """Number of finished segments (ffmpeg renames each when it is complete)."""
        best = -1
        try:
            for n in os.listdir(self.dir):
                k = seg_number(n)
                if k is not None and k > best:
                    best = k
        except OSError:
            pass
        return best + 1

    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def failed(self) -> bool:
        return self.proc is not None and self.proc.poll() not in (None, 0)


class RemuxManager:
    def __init__(self, data_dir: str, max_jobs: Callable[[], int], replaced: Callable[[str], bool]):
        self.root = os.path.join(data_dir, "remux")
        shutil.rmtree(self.root, ignore_errors=True)        # leftovers of an earlier run
        os.makedirs(self.root, exist_ok=True)
        self.max_jobs, self.replaced = max_jobs, replaced
        self.ffmpeg = shutil.which("ffmpeg") or "ffmpeg"
        self.ffprobe = shutil.which("ffprobe") or "ffprobe"
        self._lock = threading.Lock()
        self.jobs: dict[str, Job] = {}
        self._probes: dict[str, tuple[float, dict]] = {}
        threading.Thread(target=self._reaper, daemon=True, name="remux-reaper").start()

    def available(self) -> bool:
        return shutil.which("ffmpeg") is not None

    # ---- probe ----
    @staticmethod
    def _check_input(url: str, local: bool):
        """A provider URL must pass net.py's address rules before ffmpeg connects to it; a
        local file is allowed only where the caller says it is a recording."""
        if local:
            if url.startswith(("http://", "https://")) or not (url.startswith("/") or os.path.isabs(url)):
                raise ValueError("not a local recording")
        else:
            NET.check_host(url)

    def probe(self, key: str, url: str, local: bool = False) -> dict:
        hit = self._probes.get(key)
        if hit and time.time() - hit[0] < 3600:
            return hit[1]
        self._check_input(url, local)
        try:
            r = subprocess.run(probe_args(url, self.ffprobe), capture_output=True, timeout=30)
        except subprocess.TimeoutExpired:
            raise RuntimeError("the provider did not answer in time")
        if r.returncode != 0:
            raise RuntimeError("could not read the file: " + scrub(r.stderr.decode("utf-8", "replace").strip())[-160:])
        data = json.loads(r.stdout.decode("utf-8", "replace") or "{}")
        self._probes[key] = (time.time(), data)
        while len(self._probes) > 200:
            self._probes.pop(next(iter(self._probes)))
        return data

    def probe_live(self, url: str) -> Optional[dict]:
        """Codecs of a live stream (codec_summary) or None when it cannot be read now."""
        try:
            self._check_input(url, False)
        except Exception as e:                      # noqa: BLE001
            LOG.info("live probe refused: %s", e)
            return None
        try:
            r = subprocess.run(live_probe_args(url, self.ffprobe), capture_output=True, timeout=15)
            if r.returncode != 0:
                LOG.info("live probe failed: %s", scrub(r.stderr.decode("utf-8", "replace").strip())[-160:])
                return None
            return codec_summary(json.loads(r.stdout.decode("utf-8", "replace") or "{}"))
        except (subprocess.TimeoutExpired, ValueError, OSError):
            return None

    # ---- jobs ----
    def start(self, sid: str, key: str, url: str, p: dict, ss: float, local: bool = False) -> Job:
        """A film / recording (plan ``p`` from plan())."""
        self._check_input(url, local)
        return self._spawn(sid, key, ss, "vod", local, lambda d: ffmpeg_args(url, p, d, ss, self.ffmpeg))

    def start_stream(self, sid: str, key: str, url: str, info: dict, mode: str = "live") -> Job:
        """A live channel ('live') or catch-up programme ('event'), from codec_summary() info."""
        self._check_input(url, False)
        n = live_start_number(time.time()) if mode == "live" else 0
        return self._spawn(sid, key, 0.0, mode, False, lambda d: stream_args(url, info, d, mode, n, self.ffmpeg))

    def _spawn(self, sid: str, key: str, ss: float, mode: str, local: bool, make_args) -> Job:
        with self._lock:
            for j in [j for j in self.jobs.values() if j.sid == sid]:
                self._kill(j)
            same = sorted((j for j in self.jobs.values() if j.local == local), key=lambda j: j.started)
            cap = LOCAL_JOBS if local else max(1, self.max_jobs())
            while same and len(same) >= cap:
                self._kill(same.pop(0))
            rid = secrets.token_urlsafe(12)
            d = os.path.join(self.root, rid)
            os.makedirs(d)
            j = Job(rid, sid, key, d, ss, mode, local)
            j.proc = subprocess.Popen(make_args(d), cwd=d, stdin=subprocess.DEVNULL,
                                      stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            self.jobs[rid] = j
        threading.Thread(target=self._drain, args=(j,), daemon=True, name="remux-log").start()
        LOG.info("remux %s started (%s %s at %ds)", rid[:6], mode, key, int(ss))
        return j

    def find(self, sid: str, key: str) -> Optional[Job]:
        """This session's remux of ``key`` (live TV: the player reloads the playlist)."""
        with self._lock:
            return next((j for j in self.jobs.values() if j.sid == sid and j.key == key), None)

    def stop_job(self, j: Job):
        with self._lock:
            if self.jobs.get(j.rid) is j:
                self._kill(j)

    def _drain(self, j: Job):
        """Keep ffmpeg's last error line (scrubbed); never log the input URL."""
        try:
            for raw in j.proc.stderr:
                line = scrub(raw.decode("utf-8", "replace").strip())
                if line:
                    j.err = line[-200:]
        except (OSError, ValueError):
            pass
        rc = j.proc.wait()
        if rc != 0 and not j.killed:                 # ffmpeg answers SIGTERM with 255
            LOG.warning("remux %s ended (%s): %s", j.rid[:6], rc, j.err)

    def get(self, rid: str) -> Optional[Job]:
        with self._lock:
            return self.jobs.get(rid)

    def stop_sid(self, sid: str):
        with self._lock:
            for j in [j for j in self.jobs.values() if j.sid == sid]:
                self._kill(j)

    def _kill(self, j: Job):
        """Caller holds the lock."""
        self.jobs.pop(j.rid, None)
        j.killed = True
        if j.proc and j.proc.poll() is None:
            try:
                if j.paused:
                    j.proc.send_signal(signal.SIGCONT)
                j.proc.terminate()
                j.proc.wait(5)
            except Exception:                           # noqa: BLE001
                try:
                    j.proc.kill()
                except Exception:                       # noqa: BLE001
                    pass
        shutil.rmtree(j.dir, ignore_errors=True)
        LOG.info("remux %s stopped", j.rid[:6])

    def seen(self, j: Job, seg: Optional[int] = None):
        j.last_access = time.time()
        if seg is not None and seg > j.last_seg:
            j.last_seg = seg
        self._throttle(j)

    def _throttle(self, j: Job):
        if not j.running() or j.mode == "live":       # live TV runs at the broadcast's pace
            return
        ahead = j.produced() - j.last_seg
        try:
            if not j.paused and ahead > AHEAD_SEGS:
                j.proc.send_signal(signal.SIGSTOP)
                j.paused = True
            elif j.paused and ahead < RESUME_SEGS:
                j.proc.send_signal(signal.SIGCONT)
                j.paused = False
        except (OSError, AttributeError):
            pass

    def _prune(self, j: Job):
        keep_from = j.last_seg - BEHIND_SEGS
        if keep_from <= 0 or j.mode == "live":        # ffmpeg deletes a live window's old segments
            return
        try:
            for n in os.listdir(j.dir):
                k = seg_number(n)
                if k is not None and k < keep_from:
                    os.remove(os.path.join(j.dir, n))
        except OSError:
            pass

    def _reaper(self):
        while True:
            time.sleep(5)
            try:
                now = time.time()
                with self._lock:
                    for j in list(self.jobs.values()):
                        if now - j.last_access > IDLE_SEC or (not j.local and self.replaced(j.sid)):
                            self._kill(j)
                for j in list(self.jobs.values()):
                    self._throttle(j)
                    self._prune(j)
            except Exception:                           # noqa: BLE001
                LOG.exception("remux reaper")

    def status(self) -> list[dict]:
        with self._lock:
            return [{"id": j.rid[:6], "key": j.key, "mode": j.mode, "local": j.local, "ss": int(j.ss),
                     "segments": j.produced(), "viewerAt": j.last_seg,
                     "paused": j.paused, "running": j.running()} for j in self.jobs.values()]
