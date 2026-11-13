# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Shows progress: watched threshold, next-episode choice, Continue watching order, caption
choice per series, watched / unwatched marks, the state.db migration and the HTTP endpoints."""
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import threading
import time
import types
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import series as S  # noqa: E402
from tvbro_server.state import StateStore  # noqa: E402

EPS = [  # deliberately out of order
    {"id": "201", "season": 2, "num": 1, "title": "S2E1", "ext": "mkv", "duration": "00:45:00"},
    {"id": "102", "season": 1, "num": 2, "title": "S1E2", "ext": "mp4", "duration": "00:45:00"},
    {"id": "101", "season": 1, "num": 1, "title": "S1E1", "ext": "mp4", "duration": "00:45:00"},
    {"id": "202", "season": 2, "num": 2, "title": "S2E2", "ext": "mkv", "duration": "00:45:00"},
]


class Rules(unittest.TestCase):
    def test_watched_threshold(self):
        self.assertFalse(S.is_watched(899, 1000))
        self.assertTrue(S.is_watched(900, 1000))
        self.assertTrue(S.is_watched(10, 1000, ended=True))
        self.assertFalse(S.is_watched(500, 0))          # unknown duration: only the end counts
        self.assertTrue(S.is_watched(0, 0, ended=True))

    def test_next_episode(self):
        self.assertEqual(S.next_episode(EPS, {})["ep"]["id"], "101")
        self.assertFalse(S.next_episode(EPS, {})["started"])
        n = S.next_episode(EPS, {"102": {"secs": 300, "dur": 2700, "at": 5}})
        self.assertEqual((n["ep"]["id"], n["resume"], n["secs"]), ("102", True, 300))
        # finished S1E2 -> into the next season
        n = S.next_episode(EPS, {"102": {"secs": 2700, "dur": 2700, "done": True, "at": 5}})
        self.assertEqual((n["ep"]["id"], n["resume"]), ("201", False))
        # already watched episodes after the last one are skipped
        n = S.next_episode(EPS, {"102": {"done": True, "at": 9}, "201": {"done": True, "at": 1}})
        self.assertEqual(n["ep"]["id"], "202")
        # the last episode done -> finished
        self.assertIsNone(S.next_episode(EPS, {"202": {"done": True, "at": 9}}))
        self.assertIsNone(S.next_episode([], {}))

    def test_pick_track(self):
        tr = [{"lang": "en", "label": "English"}, {"lang": "fr", "label": "Français"}]
        self.assertEqual(S.pick_track(tr, "fr"), 1)
        self.assertEqual(S.pick_track(tr, "English"), 0)
        self.assertEqual(S.pick_track(tr, "de"), 0)       # language missing: first text track
        self.assertEqual(S.pick_track(tr, "off"), -1)
        self.assertIsNone(S.pick_track(tr, None))
        self.assertIsNone(S.pick_track([], "en"))


class Store(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.calls = 0

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def fetch(self, sid):
        self.calls += 1
        if sid == "down":
            raise OSError("provider down")
        return "Show " + sid, "http://img/" + sid, [dict(e, id=sid + "-" + e["id"]) if sid != "s1" else dict(e) for e in EPS]

    def store(self):
        return S.SeriesStore(StateStore(os.path.join(self.d, "state.db")), self.fetch)

    def test_start_puts_series_in_continue_watching_newest_first(self):
        st = self.store()
        st.record("s1", "101", 0, 0)                     # just started
        time.sleep(0.01)
        st.record("s2", "s2-102", 600, 2700)
        rows = st.continue_rows()
        self.assertEqual([r["id"] for r in rows], ["s2", "s1"])
        self.assertEqual(rows[0]["name"], "Show s2")
        self.assertEqual((rows[0]["next"]["id"], rows[0]["next"]["secs"], rows[0]["next"]["resume"]), ("s2-102", 600, True))
        self.assertEqual((rows[1]["next"]["season"], rows[1]["next"]["num"]), (1, 1))
        time.sleep(0.01)
        st.record("s1", "101", 60, 2700)
        self.assertEqual([r["id"] for r in st.continue_rows()], ["s1", "s2"])
        self.assertEqual(self.calls, 2)                  # episode lists stored, not fetched again

    def test_ninety_percent_moves_on_and_finished_drops_off(self):
        st = self.store()
        s = st.record("s1", "101", 2440, 2700)           # > 90 %
        self.assertEqual(s["next"]["id"], "102")
        self.assertTrue(st.summary("s1")["episodes"]["101"]["done"])
        st.record("s1", "202", 100, 2700, ended=True)
        sm = st.summary("s1")
        self.assertTrue(sm["finished"])
        self.assertIsNone(sm["next"])
        self.assertEqual(st.continue_rows(), [])

    def test_marks(self):
        st = self.store()
        st.record("s1", "101", 100, 2700)
        sm = st.mark("s1", True, season=1)
        self.assertTrue(all(sm["episodes"][i]["done"] for i in ("101", "102")))
        self.assertEqual(sm["next"]["id"], "201")
        sm = st.mark("s1", False, ep_id="102")
        self.assertNotIn("102", sm["episodes"])
        sm = st.mark("s1", True)                          # whole series
        self.assertTrue(sm["finished"])
        self.assertEqual(st.continue_rows(), [])
        sm = st.mark("s1", False)                         # whole series unwatched: nothing started
        self.assertEqual(sm["episodes"], {})
        self.assertFalse(sm["finished"])
        self.assertEqual(sm["next"]["id"], "101")
        self.assertEqual(st.continue_rows(), [])

    def test_prefs_and_hide(self):
        st = self.store()
        st.record("s1", "101", 100, 2700)
        st.set_prefs("s1", subs="en")
        st.set_prefs("s1", audio="fr")
        self.assertEqual((st.summary("s1")["subs"], st.summary("s1")["audio"]), ("en", "fr"))
        st.set_prefs("s1", subs="off")
        self.assertEqual(st.summary("s1")["subs"], "off")
        st.hide("s1")
        self.assertEqual(st.continue_rows(), [])
        st.record("s1", "101", 200, 2700)                 # playing again brings it back
        self.assertEqual(len(st.continue_rows()), 1)

    def test_provider_down_uses_known_episodes(self):
        st = self.store()
        s = st.record("down", "e9", 100, 2700)
        self.assertEqual(s["next"]["id"], "e9")

    def test_migration_keeps_existing_state(self):
        path = os.path.join(self.d, "state.db")
        old = StateStore(path)
        old.put("mylist", "s|p0|s1", {"key": "s|p0|s1", "name": "Show"})
        old.put("resume", "m|5", {"secs": 50, "dur": 6000, "at": 1})
        old.put("resume", "e|101", {"secs": 2700, "dur": 2700, "at": 1700000000000, "done": True,
                                     "meta": {"kind": "episode", "title": "Show", "sub": "S1 E1", "seriesId": "s1", "poster": "p"}})
        old.put("resume", "e|102", {"secs": 300, "dur": 2700, "at": 1700000001000,
                                     "meta": {"kind": "episode", "title": "Show", "sub": "S1 E2", "seriesId": "s1"}})
        old.db.close()
        st = self.store()
        a = st.db.execute("PRAGMA user_version").fetchone()[0]
        self.assertEqual(a, S.SCHEMA)
        allst = StateStore(path).all()
        self.assertEqual(allst["mylist"][0]["name"], "Show")       # untouched
        self.assertIn("m|5", allst["resume"])                     # movies stay
        self.assertNotIn("e|101", allst["resume"])                # episodes moved
        sm = st.summary("s1")
        self.assertTrue(sm["episodes"]["101"]["done"])
        self.assertEqual(sm["episodes"]["102"]["secs"], 300)
        self.assertEqual((sm["next"]["id"], sm["next"]["secs"]), ("102", 300))
        self.assertEqual(st.continue_rows()[0]["id"], "s1")
        S.SeriesStore(StateStore(path), self.fetch)                # opening again is a no-op
        self.assertEqual(len(st.progress("s1")), 2)


class Endpoints(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from tvbro_server import app as A
        cls.d = tempfile.mkdtemp()
        store = S.SeriesStore(StateStore(os.path.join(cls.d, "state.db")),
                              lambda sid: ("Show", None, [dict(e) for e in EPS]))
        from tvbro_server import auth as AU
        AU.ITERATIONS = 1000                          # fast hashes in tests
        auth = AU.Auth(cls.d, b"test-key")
        sid = auth.setup("password1")
        cls.headers = {"Content-Type": "application/json", "Cookie": f"{AU.COOKIE}={sid}",
                       "X-CSRF-Token": auth.csrf(AU.Auth._h(sid))}
        A.APP = types.SimpleNamespace(series=store, auth=auth)
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), A.Handler)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = "http://127.0.0.1:%d" % cls.srv.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        shutil.rmtree(cls.d, ignore_errors=True)

    def call(self, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, headers=self.headers)
        try:
            with urllib.request.urlopen(r, timeout=5) as f:
                return f.status, json.loads(f.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_flow(self):
        code, j = self.call("/api/series/progress", {"seriesId": "7", "epId": "101", "secs": 0, "dur": 0, "name": "Show"})
        self.assertEqual(code, 200)
        self.assertEqual(j["next"]["id"], "101")
        code, j = self.call("/api/series/continue")
        self.assertEqual([x["id"] for x in j["items"]], ["7"])
        self.call("/api/series/progress", {"seriesId": "7", "epId": "101", "secs": 2700, "dur": 2700, "ended": True})
        code, j = self.call("/api/series/state?id=7")
        self.assertEqual((j["next"]["id"], j["episodes"]["101"]["done"]), ("102", True))
        self.assertEqual(self.call("/api/series/prefs", {"seriesId": "7", "subs": "en"})[0], 200)
        self.assertEqual(self.call("/api/series/state?id=7")[1]["subs"], "en")
        code, j = self.call("/api/series/mark", {"seriesId": "7", "watched": True, "epId": "102"})
        self.assertEqual(j["next"]["id"], "201")
        code, j = self.call("/api/series/mark", {"seriesId": "7", "watched": False, "season": 2})
        self.assertEqual(j["next"]["id"], "201")
        code, j = self.call("/api/series/mark", {"seriesId": "7", "watched": True, "season": 2})
        self.assertTrue(j["finished"])
        self.assertEqual(self.call("/api/series/continue")[1]["items"], [])
        self.assertEqual(self.call("/api/series/progress", {"seriesId": "7"})[0], 400)
        self.assertEqual(self.call("/api/series/state")[0], 400)
        self.assertEqual(self.call("/api/series/nope", {"seriesId": "7"})[0], 404)


if __name__ == "__main__":
    unittest.main()
