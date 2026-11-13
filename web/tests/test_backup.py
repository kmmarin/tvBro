# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Backup / restore: round trip, validation, nothing written from a bad file, no secrets."""
import copy
import json
import os
import sys
import tempfile
import types
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))

from tvbro_server import backup as BK                   # noqa: E402
from tvbro_server.state import StateStore              # noqa: E402
from tvbro_server.series import SeriesStore            # noqa: E402
from tvbro_server.layout import Layout                 # noqa: E402
from tvbro_server.parental import Parental             # noqa: E402
from tvbro_server.epgmap import EpgMap                 # noqa: E402


class FakeSettings(dict):
    def save(self, upd):
        for k, v in upd.items():
            if k in BK.SETTINGS_KEYS and isinstance(v, int):
                self[k] = v


class FakeRecorder:
    def __init__(self):
        self.rules = []

    def add_rule(self, key, title):
        if not key.startswith("p0|"):
            raise ValueError("no such channel")
        if not any(r["key"] == key and r["title"] == title for r in self.rules):
            self.rules.append({"id": str(len(self.rules)), "key": key, "title": title})


def make_app(d):
    st = StateStore(os.path.join(d, "state.db"))
    SeriesStore(st)                                      # creates the shows tables
    app = types.SimpleNamespace(state=st, layout=Layout(os.path.join(d, "layout.json")),
                                parental=Parental(os.path.join(d, "parental.json"), b"k" * 32),
                                settings=FakeSettings(epgHours=12, channelsHours=12, vodHours=24, adminPinHash="secret-hash"),
                                recorder=FakeRecorder(), epgmap=EpgMap(os.path.join(d, "epgmap.json")))
    return app


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.a = make_app(tempfile.mkdtemp())
        a = self.a
        a.state.put("mylist", "p0|ch1", {"key": "p0|ch1", "kind": "live", "name": "Alpha"})
        a.state.put("mylist", "m|p1|7", {"key": "m|p1|7", "kind": "movie", "name": "Film"})
        a.state.put("resume", "m|p1|7", {"secs": 120, "dur": 600})
        a.state.put("reminders", "p0|ch2@100", {"key": "p0|ch2", "start": 100, "title": "News"})
        a.state.put("prefs", "timeFormat", "24")
        a.state.db.execute("INSERT INTO series(id, name, watched_at, updated) VALUES ('501', 'Mock Show', 5, 5)")
        a.state.db.execute("INSERT INTO episodes(id, series_id, season, num, secs, dur, done, at) VALUES ('9001', '501', 1, 1, 200, 240, 1, 5)")
        a.layout.edit("group.create", {"name": "Kids"})
        a.layout.edit("channel.rename", {"key": "p0|ch1", "name": "A1"})
        a.parental.set_pin("1234")
        a.parental.lock("group", "Sport", True)
        a.recorder.add_rule("p0|ch3", "Match")
        a.epgmap.set("p0||lima", "lima.guide")

    def test_round_trip_into_an_empty_server(self):
        doc = json.loads(json.dumps(BK.export(self.a)))
        raw = json.dumps(doc)
        self.assertNotIn("secret-hash", raw)
        self.assertNotIn("pbkdf2", raw, "no PIN hash in a backup")
        b = make_app(tempfile.mkdtemp())
        got = BK.restore(b, BK.validate(doc))
        self.assertEqual(got["mylist"], 2)
        s = b.state.all()
        self.assertEqual([x["key"] for x in s["mylist"]], [x["key"] for x in self.a.state.all()["mylist"]], "order kept")
        self.assertEqual(s["resume"]["m|p1|7"]["secs"], 120)
        self.assertEqual(s["prefs"]["timeFormat"], "24")
        self.assertEqual(len(s["reminders"]), 1)
        self.assertEqual(b.state.db.execute("SELECT secs, done FROM episodes WHERE id='9001'").fetchone(), (200.0, 1))
        self.assertEqual(b.state.db.execute("SELECT stale FROM series WHERE id='501'").fetchone(), (1,), "recomputed later")
        self.assertEqual(b.layout.data["channels"]["p0|ch1"]["name"], "A1")
        self.assertEqual(len(b.layout.data["custom"]), 1)
        self.assertEqual(b.parental.locks()["groups"], ["Sport"])
        self.assertFalse(b.parental.has_pin(), "locks come back, the PIN is set again by hand")
        self.assertEqual(b.recorder.rules[0]["title"], "Match")
        self.assertEqual(b.epgmap.get("p0||lima"), "lima.guide")
        self.assertEqual(got["guideMapping"], 1)

    def test_restore_replaces_lists_and_keeps_existing_rules(self):
        doc = BK.export(self.a)
        self.a.state.put("mylist", "p0|new", {"key": "p0|new"})
        self.a.recorder.add_rule("p0|ch9", "Other")
        BK.restore(self.a, BK.validate(doc))
        self.assertNotIn("p0|new", [x["key"] for x in self.a.state.all()["mylist"]])
        self.assertEqual(len(self.a.recorder.rules), 2, "rules are added, none removed")

    def test_bad_files_write_nothing(self):
        good = BK.export(self.a)
        before = self.a.state.all()
        bad = [
            None, [], {"format": "something else"},
            {**good, "version": 99},
            {**good, "state": {"mylist": [{"value": 1}]}},                       # no key
            {**good, "state": {"passwords": []}},                                 # unknown list
            {**good, "layout": {"custom": {"c:1": {"name": "x", "channels": "nope"}}}},
            {**good, "parental": {"groups": [1, 2]}},
            {**good, "state": {"mylist": [{"key": "k", "value": "x" * 20000}]}},  # too large
            {"format": BK.FORMAT, "version": 1},                                   # nothing in it
        ]
        for doc in bad:
            with self.assertRaises(BK.BadBackup, msg=str(doc)[:80]):
                BK.validate(copy.deepcopy(doc))
        self.assertEqual(self.a.state.all(), before)

    def test_unknown_sections_and_settings_are_ignored(self):
        doc = BK.export(self.a)
        doc["future"] = {"x": 1}
        doc["settings"] = {"epgHours": 24, "adminPinHash": "evil", "maxStreams": 3}
        clean = BK.validate(doc)
        self.assertNotIn("future", clean)
        self.assertEqual(clean["settings"], {"epgHours": 24})


if __name__ == "__main__":
    unittest.main()
