# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Keyframe index for a recorded MPEG-TS file, and the HLS playlist built from it.

A recording is the provider's transport stream copied to disk byte for byte (no transcoding).
To make that one file seekable in every browser — hls.js on desktop, native HLS on iPhone —
it is published as an HLS playlist whose segments are byte ranges of the file. While the
stream is copied, TsIndexer looks only at packet headers to note where a segment may start:
a video keyframe (random_access_indicator on the first packet of a video PES), preferably
the PAT just before it, at least ``min_gap`` seconds after the previous cut. Timing comes
from the PES presentation timestamps, so segment durations are exact.

Written from the MPEG-TS (ISO/IEC 13818-1) and HLS (RFC 8216) specifications. Pure logic.
"""
from __future__ import annotations

from typing import Optional

PKT = 188
SYNC = 0x47
PTS_WRAP = 1 << 33          # 33-bit timestamps at 90 kHz


class TsIndexer:
    """feed() the bytes exactly as they are written to the file; read ``cuts``.

    cuts: list of (file offset, pts in seconds, discontinuity flag)."""

    def __init__(self, min_gap: float = 6.0, pos: int = 0):
        self.min_gap = min_gap
        self.pos = pos                  # file offset of the next byte fed
        self._carry = b""
        self._synced = False
        self.cuts: list[tuple[int, float, bool]] = []
        self._pat_off = -1              # file offset of the latest PAT packet
        self._last_cut_pts: Optional[float] = None
        self._first_vpts: Optional[float] = None
        self.last_vpts: Optional[float] = None      # latest video PTS seen (end of the file so far)
        self._loose = False             # stream never flags keyframes: cut at any video PES start
        self._disc = False

    def discontinuity(self):
        """The source was reconnected: timestamps restart, the next cut begins a new timeline."""
        self._disc = True
        self._carry = b""
        self._synced = False
        self._pat_off = -1

    def feed(self, data: bytes) -> int:
        """Returns how many cuts were added."""
        before = len(self.cuts)
        buf = self._carry + data if self._carry else data
        base = self.pos - len(self._carry)       # file offset of buf[0]
        self.pos += len(data)
        n = len(buf)
        i = 0
        while True:
            if not self._synced:
                i = self._find_sync(buf, i)
                if i < 0:
                    self._carry = buf[-(3 * PKT):] if n > 3 * PKT else buf
                    return len(self.cuts) - before
                self._synced = True
            if i + PKT > n:
                break
            if buf[i] != SYNC:
                self._synced = False
                i += 1
                continue
            b1 = buf[i + 1]
            pid = ((b1 & 0x1F) << 8) | buf[i + 2]
            if pid == 0:
                self._pat_off = base + i
            elif b1 & 0x40:                       # payload_unit_start: maybe a PES header
                afc = (buf[i + 3] >> 4) & 3
                p = i + 4
                rai = False
                if afc & 2:
                    afl = buf[p]
                    rai = afl > 0 and bool(buf[p + 1] & 0x40)
                    p += 1 + afl
                if afc & 1 and p + 14 <= i + PKT and buf[p] == 0 and buf[p + 1] == 0 and buf[p + 2] == 1 \
                        and 0xE0 <= buf[p + 3] <= 0xEF and buf[p + 7] & 0x80:
                    pts = (((buf[p + 9] >> 1) & 7) << 30 | buf[p + 10] << 22 | (buf[p + 11] >> 1) << 15
                           | buf[p + 12] << 7 | buf[p + 13] >> 1) / 90000.0
                    self._video_start(base + i, pts, rai)
            i += PKT
        self._carry = buf[i:]
        return len(self.cuts) - before

    @staticmethod
    def _find_sync(buf: bytes, start: int) -> int:
        n = len(buf)
        i = buf.find(SYNC, start)
        while 0 <= i and i + 2 * PKT < n:
            if buf[i + PKT] == SYNC and buf[i + 2 * PKT] == SYNC:
                return i
            i = buf.find(SYNC, i + 1)
        return -1

    def _video_start(self, off: int, pts: float, key: bool):
        if self._first_vpts is None:
            self._first_vpts = pts
        self.last_vpts = pts
        if not key and not self._loose:
            # no keyframe flag for 15 s of video: this muxer does not set it
            if not self.cuts and abs(pts - self._first_vpts) > 15:
                self._loose = True
            else:
                return
        last = self._last_cut_pts
        disc = self._disc
        if last is not None and not disc:
            d = pts - last
            if d < -PTS_WRAP / 90000.0 / 2:
                d += PTS_WRAP / 90000.0          # 33-bit wrap (every 26.5 h)
            if d < 0 or d > 120:
                disc = True                       # the stream's clock jumped
            elif d < self.min_gap:
                return
        # start the segment at the PAT when the muxer wrote PAT/PMT right before the keyframe
        cut = self._pat_off if 0 <= off - self._pat_off <= 4 * PKT else off
        self.cuts.append((cut, pts, disc))
        self._last_cut_pts = pts
        self._disc = False


def read_index(path: str) -> tuple[list[tuple[int, float, bool]], Optional[tuple[int, float]]]:
    """Index sidecar -> (cuts, end). Lines: ``<offset> <pts> [D]`` and a final ``E <offset> <pts>``."""
    cuts, end = [], None
    try:
        with open(path, encoding="ascii") as f:
            for line in f:
                p = line.split()
                try:
                    if len(p) >= 3 and p[0] == "E":
                        end = (int(p[1]), float(p[2]))
                    elif len(p) >= 2:
                        cuts.append((int(p[0]), float(p[1]), len(p) > 2 and p[2] == "D"))
                except ValueError:
                    continue
    except OSError:
        pass
    return cuts, end


def index_line(cut: tuple[int, float, bool]) -> str:
    return f"{cut[0]} {cut[1]:.4f}{' D' if cut[2] else ''}\n"


def recording_segments(cuts, end, file_size: int, finished: bool, fallback: float = 6.0):
    """[(offset, length, duration, discontinuity)] for the playlist.

    Every segment runs from one cut to the next. The part after the last cut is published
    only once the recording has finished (its length is not final before that)."""
    segs = []
    for i, (off, pts, disc) in enumerate(cuts):
        if i + 1 < len(cuts):
            nxt_off, nxt_pts, nxt_disc = cuts[i + 1]
            d = nxt_pts - pts
            if d < -PTS_WRAP / 90000.0 / 2:
                d += PTS_WRAP / 90000.0
            if nxt_disc or d <= 0 or d > 120:
                d = fallback
        elif finished:
            nxt_off = end[0] if end and end[0] > off else file_size
            d = (end[1] - pts) if end else fallback
            if d <= 0 or d > 120:
                d = fallback
        else:
            break
        if nxt_off > off:
            segs.append((off, nxt_off - off, d, disc))
    return segs


def recording_playlist(uri: str, cuts, end, file_size: int, finished: bool) -> str:
    """HLS playlist over ONE .ts file: VOD when finished, a growing EVENT list while recording."""
    segs = recording_segments(cuts, end, file_size, finished)
    if not segs and finished and file_size > 0:
        segs = [(0, file_size, 6.0, False)]          # nothing indexable: offer the file whole
    longest = max((s[2] for s in segs), default=6.0)
    out = ["#EXTM3U", "#EXT-X-VERSION:4", f"#EXT-X-TARGETDURATION:{max(1, -(-int(longest * 1000) // 1000))}",
           "#EXT-X-MEDIA-SEQUENCE:0", f"#EXT-X-PLAYLIST-TYPE:{'VOD' if finished else 'EVENT'}",
           "#EXT-X-START:TIME-OFFSET=0", "#EXT-X-INDEPENDENT-SEGMENTS"]
    for off, ln, d, disc in segs:
        if disc:
            out.append("#EXT-X-DISCONTINUITY")
        out += [f"#EXTINF:{d:.3f},", f"#EXT-X-BYTERANGE:{ln}@{off}", uri]
    if finished:
        out.append("#EXT-X-ENDLIST")
    return "\n".join(out) + "\n"


def duration_of(cuts, end, finished: bool) -> float:
    return sum(s[2] for s in recording_segments(cuts, end, end[0] if end else 0, finished)) if cuts else 0.0
