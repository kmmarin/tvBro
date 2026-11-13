# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""On-the-fly remux decisions: which films are remuxed, what is copied / converted, the
ffmpeg command line and the playlist handed to the browser."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import remux as R  # noqa: E402

IOS = {"ios": True, "hevc": True, "ac3": True, "eac3": True}
CHROME = {"ios": False, "hevc": False, "ac3": False, "eac3": False}
URL = "http://tv.example:8080/movie/alice/s3cret/9.mkv"


def probe(v="h264", audio=(("aac", 2, "eng", True),), subs=(), duration="4753.2"):
    st = [{"index": 0, "codec_type": "video", "codec_name": v}]
    for c, ch, lang, default in audio:
        st.append({"index": len(st), "codec_type": "audio", "codec_name": c, "channels": ch,
                   "tags": {"language": lang}, "disposition": {"default": int(default)}})
    for c, lang, title in subs:
        st.append({"index": len(st), "codec_type": "subtitle", "codec_name": c, "tags": {"language": lang, "title": title}})
    return {"streams": st, "format": {"duration": duration}}


class Mode(unittest.TestCase):
    def test_iphone_remuxes_everything_but_mp4(self):
        for ext in ("mkv", "avi", "ts", "MKV", "webm"):
            self.assertEqual(R.vod_mode(ext, IOS), "remux", ext)
        for ext in ("mp4", "m4v", "mov"):
            self.assertEqual(R.vod_mode(ext, IOS), "file", ext)

    def test_desktop_tries_mkv_itself_and_can_force(self):
        self.assertEqual(R.vod_mode("mkv", CHROME), "file")
        self.assertEqual(R.vod_mode("avi", CHROME), "remux")
        self.assertEqual(R.vod_mode("mkv", CHROME, force_remux=True), "remux")


class Plan(unittest.TestCase):
    def test_h264_aac_copied(self):
        p = R.plan(probe(), IOS)
        self.assertTrue(p["ok"])
        self.assertIsNone(p["video"]["tag"])
        self.assertTrue(p["a"]["copy"])
        self.assertEqual(p["notes"], [])
        self.assertAlmostEqual(p["duration"], 4753.2)

    def test_hevc_tagged_hvc1_or_refused(self):
        self.assertEqual(R.plan(probe("hevc"), IOS)["video"]["tag"], "hvc1")
        p = R.plan(probe("hevc"), CHROME)
        self.assertFalse(p["ok"])
        self.assertIn("HEVC", p["why"])

    def test_other_video_needs_reencoding(self):
        for v in ("mpeg4", "mpeg2video", "vc1"):
            p = R.plan(probe(v), IOS)
            self.assertFalse(p["ok"], v)
            self.assertIn("re-encoding", p["why"])

    def test_audio_copy_or_aac(self):
        p = R.plan(probe(audio=(("ac3", 6, "eng", True),)), IOS)
        self.assertTrue(p["a"]["copy"])                                   # iPhone plays AC-3
        p = R.plan(probe(audio=(("ac3", 6, "eng", True),)), CHROME)
        self.assertFalse(p["a"]["copy"])
        self.assertEqual(p["a"]["out_channels"], 6)                       # 5.1 kept as AAC 5.1
        for c, ch, want in (("dts", 6, 6), ("truehd", 8, 6), ("flac", 2, 2), ("opus", 2, 2), ("mp3", 2, 2)):
            a = R.plan(probe(audio=((c, ch, "eng", True),)), IOS)["a"]
            self.assertFalse(a["copy"], c)
            self.assertEqual(a["out_channels"], want, c)

    def test_default_audio_track_and_choice(self):
        pr = probe(audio=(("aac", 2, "deu", False), ("ac3", 6, "eng", True)))
        p = R.plan(pr, IOS)
        self.assertEqual(p["a"]["pos"], 1)
        self.assertEqual(p["a"]["index"], 2)
        self.assertEqual(R.plan(pr, IOS, 0)["a"]["index"], 1)
        self.assertEqual(R.plan(pr, IOS, 9)["a"]["pos"], 1)                # out of range: default
        self.assertEqual(len(p["audio"]), 2)

    def test_text_subtitles_kept_picture_subtitles_skipped(self):
        p = R.plan(probe(subs=(("subrip", "eng", ""), ("ass", "fre", "Forced"), ("hdmv_pgs_subtitle", "eng", "SDH"))), IOS)
        self.assertEqual([s["codec"] for s in p["subs"]], ["subrip", "ass"])
        self.assertEqual(p["subs"][1]["label"], "Forced (fre)")
        self.assertEqual(len(p["skipped"]), 1)
        self.assertTrue(any("picture subtitle" in n for n in p["notes"]))

    def test_no_video(self):
        self.assertFalse(R.plan({"streams": [{"index": 0, "codec_type": "audio", "codec_name": "aac"}]}, IOS)["ok"])


class Command(unittest.TestCase):
    def test_ffmpeg_args(self):
        p = R.plan(probe("hevc", audio=(("dts", 6, "eng", True),), subs=(("subrip", "eng", ""),)), IOS)
        a = R.ffmpeg_args(URL, p, "/d", 125.5)
        self.assertEqual(a[:3], ["nice", "-n", "10"])
        self.assertEqual(a.count(URL), 1)
        self.assertEqual(a[a.index("-i") + 1], URL)
        self.assertLess(a.index("-ss"), a.index("-i"))                   # input seek
        self.assertLess(a.index("-copyts"), a.index("-i"))               # film timestamps kept
        self.assertEqual(a[a.index("-ss") + 1], "125.500")
        self.assertIn("-loglevel", a)
        self.assertEqual(a[a.index("-loglevel") + 1], "error")
        self.assertEqual(a[a.index("-c:v") + 1], "copy")
        self.assertEqual(a[a.index("-tag:v") + 1], "hvc1")
        self.assertEqual(a[a.index("-c:a") + 1], "aac")
        self.assertEqual(a[a.index("-ac") + 1], "6")
        self.assertEqual(a[a.index("-hls_segment_type") + 1], "fmp4")
        self.assertEqual(a[a.index("-hls_playlist_type") + 1], "event")
        self.assertIn(os.path.join("/d", "index.m3u8"), a)
        self.assertIn(os.path.join("/d", "sub0.vtt"), a)
        self.assertEqual(a[a.index("-c:s") + 1], "webvtt")

    def test_no_seek_and_copy_audio(self):
        a = R.ffmpeg_args(URL, R.plan(probe(), IOS), "/d", 0)
        self.assertNotIn("-ss", a)
        self.assertEqual(a[a.index("-c:a") + 1], "copy")
        self.assertNotIn("-tag:v", a)

    def test_errors_are_scrubbed(self):
        self.assertEqual(R.scrub(f"{URL}: Server returned 404 Not Found"), "<url> Server returned 404 Not Found")


def box(t, *parts):
    body = b"".join(parts)
    return (8 + len(body)).to_bytes(4, "big") + t.encode() + body


def u32(n):
    return n.to_bytes(4, "big")


def track(tid, handler, scale, empty_ms=None):
    tkhd = box("tkhd", u32(0), u32(0), u32(0), u32(tid), bytes(60))
    mdhd = box("mdhd", u32(0), u32(0), u32(0), u32(scale), u32(0), u32(0))
    hdlr = box("hdlr", u32(0), u32(0), handler, bytes(12))
    kids = [tkhd]
    if empty_ms is not None:                 # empty edit, then the media from 0
        kids.append(box("edts", box("elst", u32(0), u32(2), u32(empty_ms), (-1).to_bytes(4, "big", signed=True), u32(0x10000),
                                    u32(0), u32(0), u32(0x10000))))
    kids.append(box("mdia", mdhd, hdlr))
    return box("trak", *kids)


class StartTime(unittest.TestCase):
    """With -copyts the film time where the remux starts is in the init segment's edit list
    (ffmpeg 6+) or in the first fragment's tfdt (older ffmpeg)."""
    def frag(self, tid, base):
        return box("moof", box("traf", box("tfhd", u32(0x20000), u32(tid)), box("tfdt", bytes([1, 0, 0, 0]), base.to_bytes(8, "big"))))

    def test_empty_edit(self):
        init = box("ftyp", b"iso5") + box("moov", box("mvhd", u32(0), u32(0), u32(0), u32(1000)),
                                          track(2, b"soun", 48000, 10978), track(1, b"vide", 16000, 2998996))
        seg = box("styp", b"msdh") + self.frag(2, 0) + self.frag(1, 0)
        self.assertEqual(R.first_video_time(init, seg), 2998.996)

    def test_tfdt(self):
        init = box("moov", box("mvhd", u32(0), u32(0), u32(0), u32(1000)), track(1, b"vide", 90000))
        self.assertEqual(R.first_video_time(init, self.frag(1, 90000 * 125)), 125.0)
        self.assertIsNone(R.first_video_time(b"junk", b""))


class Playlist(unittest.TestCase):
    SRC = ("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:EVENT\n"
           '#EXT-X-MAP:URI="init.mp4"\n#EXTINF:6.0,\ns00000.m4s\n#EXTINF:6.0,\ns00001.m4s\n')

    def test_starts_at_the_beginning_and_carries_the_sid(self):
        out = R.serve_playlist(self.SRC, "abc")
        lines = out.splitlines()
        self.assertEqual(lines[0], "#EXTM3U")
        self.assertTrue(lines[1].startswith("#EXT-X-START:TIME-OFFSET=0"))
        self.assertIn('#EXT-X-MAP:URI="init.mp4?sid=abc"', lines)
        self.assertIn("s00001.m4s?sid=abc", lines)
        self.assertNotIn("#EXT-X-ENDLIST", out)

    def test_names(self):
        for ok in ("index.m3u8", "init.mp4", "s00012.m4s", "sub3.vtt"):
            self.assertTrue(R.NAME_RE.match(ok), ok)
        for bad in ("../x", "s1.m4s", "index.m3u8/..", "sub.vtt", "a.ts"):
            self.assertFalse(R.NAME_RE.match(bad), bad)
        self.assertEqual(R.seg_number("s00012.m4s"), 12)
        self.assertIsNone(R.seg_number("init.mp4"))

    def test_live_playlist_has_absolute_uris_and_no_start_override(self):
        src = self.SRC.replace("#EXT-X-PLAYLIST-TYPE:EVENT\n", "#EXT-X-MEDIA-SEQUENCE:100\n")
        out = R.serve_playlist(src, "abc", prefix="/remux/RID/", live=True)
        self.assertNotIn("#EXT-X-START", out)
        self.assertIn('#EXT-X-MAP:URI="/remux/RID/init.mp4?sid=abc"', out)
        self.assertIn("/remux/RID/s00001.m4s?sid=abc", out.splitlines())
        self.assertIn("#EXT-X-MEDIA-SEQUENCE:100", out)


LIVE_URL = "http://tv.example:8080/alice/s3cret/647660"


class LiveTv(unittest.TestCase):
    """Live channels / recordings: HEVC goes to fMP4 only for clients that need HLS."""

    def test_codec_summary(self):
        self.assertEqual(R.codec_summary(probe("hevc", (("eac3", 2, "eng", True),))), {"v": "hevc", "a": "eac3", "ach": 2})
        self.assertEqual(R.codec_summary(probe("h264", ())), {"v": "h264", "a": "", "ach": 2})
        self.assertIsNone(R.codec_summary({"streams": [{"codec_type": "audio", "codec_name": "aac"}]}))

    def test_only_hevc_for_hls_clients_is_remuxed(self):
        hevc, avc = {"v": "hevc", "a": "aac"}, {"v": "h264", "a": "aac"}
        self.assertEqual(R.live_mode("hls", hevc), "remux")       # iPhone / iPad: HEVC needs fMP4
        self.assertEqual(R.live_mode("hls", avc), "relay")        # H.264 keeps the zero-cost relay
        self.assertEqual(R.live_mode("ts", hevc), "relay")        # MSE browsers: mpegts.js as before
        self.assertEqual(R.live_mode("", hevc), "relay")
        self.assertEqual(R.live_mode("hls", None), "relay")       # unknown (probe failed): relay

    def test_recordings(self):
        self.assertEqual(R.rec_mode(IOS, "hevc"), "remux")
        self.assertEqual(R.rec_mode(IOS, "h264"), "file")
        self.assertEqual(R.rec_mode(CHROME, "hevc"), "file")
        self.assertEqual(R.rec_mode(CHROME, "h264", force_remux=True), "remux")

    def test_live_command(self):
        a = R.stream_args(LIVE_URL, {"v": "hevc", "a": "eac3", "ach": 2}, "/tmp/r", "live", 1234)
        self.assertEqual(a[:3], ["nice", "-n", "10"])
        self.assertEqual(a.count(LIVE_URL), 1)
        self.assertEqual(a[a.index(LIVE_URL) - 1], "-i")
        self.assertIn("nobuffer", a)
        self.assertEqual(a[a.index("-tag:v") + 1], "hvc1")
        self.assertEqual(a[a.index("-c:v") + 1], "copy")
        self.assertEqual(a[a.index("-c:a") + 1], "copy")
        self.assertEqual(a[a.index("-hls_segment_type") + 1], "fmp4")
        self.assertEqual(a[a.index("-hls_time") + 1], "4")
        self.assertEqual(a[a.index("-hls_list_size") + 1], "10")
        self.assertIn("delete_segments", a[a.index("-hls_flags") + 1])
        self.assertIn("independent_segments", a[a.index("-hls_flags") + 1])
        self.assertEqual(a[a.index("-start_number") + 1], "1234")
        self.assertEqual(a[a.index("-loglevel") + 1], "error")
        self.assertNotIn("-hls_playlist_type", a)

    def test_audio_apple_does_not_play_is_converted(self):
        a = R.stream_args(LIVE_URL, {"v": "hevc", "a": "mp2", "ach": 2}, "/tmp/r")
        self.assertEqual(a[a.index("-c:a") + 1], "aac")
        self.assertEqual(a[a.index("-ac") + 1], "2")
        a = R.stream_args(LIVE_URL, {"v": "hevc", "a": "dts", "ach": 6}, "/tmp/r")
        self.assertEqual(a[a.index("-ac") + 1], "6")
        for c in ("aac", "ac3", "eac3"):
            a = R.stream_args(LIVE_URL, {"v": "hevc", "a": c, "ach": 2}, "/tmp/r")
            self.assertEqual(a[a.index("-c:a") + 1], "copy", c)

    def test_catchup_command_keeps_the_whole_programme(self):
        a = R.stream_args(LIVE_URL, {"v": "hevc", "a": "aac"}, "/tmp/r", "event")
        self.assertEqual(a[a.index("-hls_playlist_type") + 1], "event")
        self.assertEqual(a[a.index("-hls_list_size") + 1], "0")
        self.assertNotIn("delete_segments", a[a.index("-hls_flags") + 1])
        self.assertEqual(a[a.index("-start_number") + 1], "0")

    def test_growing_recording_is_followed(self):
        p = R.plan(probe("hevc"), IOS)
        p["follow"] = True
        a = R.ffmpeg_args("/var/lib/tvbro/recordings/abc.ts", p, "/tmp/r")
        self.assertEqual(a[a.index("-follow") + 1], "1")
        self.assertLess(a.index("-follow"), a.index("-i"))
        self.assertNotIn("-follow", R.ffmpeg_args(URL, p, "/tmp/r"))       # never on a provider URL

    def test_target_duration_never_changes_down(self):
        text, t = R.steady_target("#EXTM3U\n#EXT-X-TARGETDURATION:4\n", 6)
        self.assertEqual((t, text), (6, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n"))
        text, t = R.steady_target("#EXTM3U\n#EXT-X-TARGETDURATION:8\n", t)
        self.assertEqual(t, 8)
        text, t = R.steady_target("#EXTM3U\n#EXT-X-TARGETDURATION:5\n", t)
        self.assertIn("#EXT-X-TARGETDURATION:8", text)

    def test_live_sequence_grows_with_the_clock(self):
        self.assertLess(R.live_start_number(1_000_000), R.live_start_number(1_000_100))
        self.assertLess(R.live_start_number(2_000_000_000), 50000)

    def test_codec_cache(self):
        import tempfile
        t = [1000.0]
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "codecs.json")
            c = R.CodecCache(path, clock=lambda: t[0])
            self.assertEqual(c.get("k"), (False, None))
            c.put("k", {"v": "hevc", "a": "aac", "ach": 2})
            c.put("bad", None)
            self.assertEqual(c.get("k"), (True, {"v": "hevc", "a": "aac", "ach": 2}))
            self.assertEqual(c.get("bad"), (True, None))                     # failed probe: relay for now
            c2 = R.CodecCache(path, clock=lambda: t[0])                      # survives a restart
            self.assertEqual(c2.get("k")[1]["v"], "hevc")
            t[0] += R.CodecCache.FAIL_TTL + 1
            self.assertEqual(c.get("bad"), (False, None))                    # probe again
            self.assertTrue(c.get("k")[0])
            t[0] += R.CodecCache.OK_TTL
            self.assertEqual(c.get("k"), (False, None))                      # refreshed after a while
            c.put("k", {"v": "h264"})
            c.drop("k")                                                      # a failed remux: look again
            self.assertEqual(c.get("k"), (False, None))


if __name__ == "__main__":
    unittest.main()
