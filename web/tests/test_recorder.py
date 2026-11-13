# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Server-side recording: timing, space rules, stream-slot rules, and a real copy of a
(synthetic) MPEG-TS source into a seekable HLS-of-byte-ranges recording."""
import functools
import http.server
import os
import pathlib
import shutil
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import recorder as REC  # noqa: E402
from tvbro_server.streams import StreamRegistry  # noqa: E402
from tvbro_server.tsindex import read_index, recording_playlist  # noqa: E402


def ts_source(seconds=4, fps=25, gop=25):
    """PAT + one video PID; a PES start with PTS every frame, keyframe flag every gop."""
    out = bytearray()
    for f in range(seconds * fps):
        if f % gop == 0:
            out += bytes([0x47, 0x40, 0x00, 0x10]) + bytes(184)                      # PAT (PID 0)
        pts = int((10 + f / fps) * 90000)
        pes = bytes([0, 0, 1, 0xE0, 0, 0, 0x80, 0x80, 5,
                     0x21 | ((pts >> 29) & 0x0E), (pts >> 22) & 0xFF, 0x01 | ((pts >> 14) & 0xFE),
                     (pts >> 7) & 0xFF, 0x01 | ((pts << 1) & 0xFE)])
        if f % gop == 0:     # adaptation field with random_access_indicator
            pkt = bytes([0x47, 0x41, 0x00, 0x30, 1, 0x40]) + pes
        else:
            pkt = bytes([0x47, 0x41, 0x00, 0x10]) + pes
        out += pkt + bytes(188 - len(pkt))
        out += bytes([0x47, 0x01, 0x00, 0x10]) + bytes(184)                          # payload continuation
    return bytes(out)


class Timing(unittest.TestCase):
    def test_programme_with_padding(self):
        self.assertEqual(REC.plan_times(1000, 2000, 5000, 1, 5), (1940, 5300, False))

    def test_running_programme_records_from_now(self):
        self.assertEqual(REC.plan_times(3000, 2000, 5000, 1, 5), (3000, 5300, True))

    def test_record_now_without_guide_is_two_hours(self):
        self.assertEqual(REC.plan_times(3000, None, None, 1, 5)[1], 3000 + 7200 + 300)

    def test_capped_length(self):
        a, b, _ = REC.plan_times(0, 10, 10 + 24 * 3600, 0, 0)
        self.assertEqual(b - a, REC.MAX_HOURS * 3600)

    def test_space(self):
        self.assertIsNone(REC.space_problem(5 << 30, 1 << 30, 1 << 30, 3 << 30))
        self.assertIn("floor", REC.space_problem(500 << 20, 0, 1 << 30, 3 << 30))
        self.assertIn("cap", REC.space_problem(5 << 30, 3 << 30, 1 << 30, 3 << 30))


class Slots(unittest.TestCase):
    def test_recording_is_never_evicted_by_a_viewer(self):
        t = [1000.0]
        r = StreamRegistry(1, lambda: t[0])
        self.assertEqual(r.start("viewer"), [])
        t[0] += 1
        self.assertEqual(r.start("rec:1", "Recording", "News", pinned=True), ["viewer"])
        self.assertEqual(r.why("viewer"), "recording")
        t[0] += 1
        self.assertIsNone(r.start("viewer2"))                 # every slot holds a recording
        self.assertIsNone(r.touch("viewer2"))
        self.assertEqual(r.why("viewer2"), "recording")
        r.stop("rec:1")
        self.assertEqual(r.start("viewer2"), [])              # the slot is free again
        self.assertTrue(r.status()["count"] == 1)


class Copy(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.src = os.path.join(self.dir, "src.ts")
        with open(self.src, "wb") as f:
            f.write(ts_source())
        self.streams = StreamRegistry(1)
        self.settings = {"recMaxGB": 1, "recMinFreeMB": 1, "recPadBeforeMin": 0, "recPadAfterMin": 0}
        # the source is served over http on loopback (allowed only for the tests)
        self._env = os.environ.get("TVBRO_ALLOW_LOOPBACK_SOURCES")
        os.environ["TVBRO_ALLOW_LOOPBACK_SOURCES"] = "1"
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=self.dir)
        handler.log_message = lambda *a: None
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.urls = {"k1": f"http://127.0.0.1:{self.httpd.server_address[1]}/src.ts",
                     "kfile": pathlib.Path(self.src).as_uri()}
        self.rec = REC.Recorder(self.dir, lambda k: {"name": "Test One", "logo": None, "url": self.urls[k]} if k in self.urls else None,
                                self.streams, lambda: self.settings)

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        if self._env is None:
            os.environ.pop("TVBRO_ALLOW_LOOPBACK_SOURCES", None)
        else:
            os.environ["TVBRO_ALLOW_LOOPBACK_SOURCES"] = self._env
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_file_url_is_never_copied(self):
        """C1: a playlist entry naming a local file must not be recorded (and so served)."""
        now = int(time.time())
        item = self.rec.add("kfile", "Secrets", now - 60, now + 2)
        self.rec.tick()
        for _ in range(60):
            if self.rec.get(item["id"])["status"] != "recording":
                break
            time.sleep(0.1)
        x = self.rec.get(item["id"])
        size = os.path.getsize(self.rec.file(x["id"])) if os.path.exists(self.rec.file(x["id"])) else 0
        self.assertEqual(size, 0)

    def test_record_now_copies_and_indexes(self):
        now = int(time.time())
        item = self.rec.add("k1", "Show", now - 60, now + 3)
        self.assertEqual(item["status"], "scheduled")
        with self.assertRaises(ValueError):
            self.rec.add("k1", "Show again", now - 60, now + 3)          # overlapping duplicate
        with self.assertRaises(ValueError):
            self.rec.add("nope", "x", None, None)
        self.rec.tick()
        self.assertEqual(self.rec.get(item["id"])["status"], "recording")
        self.assertTrue(self.streams.status()["active"][0]["recording"])
        for _ in range(100):
            if self.rec.get(item["id"])["status"] != "recording":
                break
            time.sleep(0.1)
        x = self.rec.get(item["id"])
        self.assertEqual(x["status"], "done", x)
        self.assertGreater(x["size"], 0)
        self.assertEqual(self.streams.status()["count"], 0)            # slot released
        cuts, end = read_index(self.rec.idx(x["id"]))
        self.assertGreaterEqual(len(cuts), 1)
        pl = recording_playlist("/rec/x.ts", cuts, end, x["size"], True)
        self.assertIn("#EXT-X-BYTERANGE:", pl)
        self.assertIn("#EXT-X-ENDLIST", pl)
        self.assertTrue(self.rec.delete(x["id"]))
        self.assertFalse(os.path.exists(self.rec.file(x["id"])))
        self.assertEqual(self.rec.list(), [])

    def test_scheduled_can_be_cancelled_and_survives_restart(self):
        now = int(time.time())
        item = self.rec.add("k1", "Later", now + 3600, now + 7200)
        again = REC.Recorder(self.dir, lambda k: None, self.streams, lambda: self.settings)
        self.assertEqual([x["id"] for x in again.list()], [item["id"]])
        self.assertTrue(self.rec.stop(item["id"]))
        self.assertEqual(self.rec.list(), [])

    def test_space_floor_refuses(self):
        self.settings["recMinFreeMB"] = 1 << 40
        with self.assertRaises(ValueError):
            self.rec.add("k1", "Show", None, None)


if __name__ == "__main__":
    unittest.main()
