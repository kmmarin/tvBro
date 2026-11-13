# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Series recording rules (upcoming airings of a title on a channel are scheduled from the
guide, once each) and the recordings folder: picker, test, per-recording folder, share steps."""
import os
import shutil
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import recorder as REC  # noqa: E402
from tvbro_server import storage as STO  # noqa: E402
from tvbro_server.secretstore import SecretStore  # noqa: E402
from tvbro_server.streams import StreamRegistry  # noqa: E402


class Rules(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.settings = {"recMaxGB": 1, "recMinFreeMB": 1, "recPadBeforeMin": 0, "recPadAfterMin": 0}
        self.rec = REC.Recorder(self.d, lambda k: {"name": "One", "logo": None, "url": "x"} if k == "k1" else None,
                                StreamRegistry(1), lambda: self.settings)
        now = int(time.time())
        self.guide = [(now + 3600, now + 5400, "The News"), (now + 7200, now + 9000, "Quiz"),
                      (now + 90000, now + 91800, "the news "), (now - 7200, now - 3600, "The News")]
        self.rec.find_progs = lambda key, frm, to: [p for p in self.guide if p[1] > frm and p[0] < to] if key == "k1" else []

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def test_rule_schedules_each_airing_once(self):
        r = self.rec.add_rule("k1", "The News")
        items = self.rec.list()
        self.assertEqual(len(items), 2)                               # two upcoming airings, not the quiz / the past one
        self.assertTrue(all(x["rule"] == r["id"] and x["status"] == "scheduled" for x in items))
        self.assertEqual(self.rec.apply_rules(), 0)                    # nothing twice
        self.assertEqual(self.rec.add_rule("k1", "the news")["id"], r["id"])    # same rule
        again = REC.Recorder(self.d, lambda k: None, StreamRegistry(1), lambda: self.settings)
        self.assertEqual([x["title"] for x in again.rules], ["The News"])
        self.assertTrue(self.rec.delete_rule(r["id"]))
        self.assertEqual(self.rec.list(), [])                         # scheduled ones cancelled with it
        with self.assertRaises(ValueError):
            self.rec.add_rule("nope", "X")

    def test_recordings_go_to_the_chosen_folder(self):
        other = os.path.join(self.d, "share")
        os.makedirs(other)
        self.rec.set_dir(other)
        now = int(time.time())
        it = self.rec.add("k1", "Later", now + 3600, now + 7200)
        self.assertEqual(os.path.dirname(self.rec.file(it["id"])), other)
        self.rec.set_dir(None)
        self.assertEqual(os.path.dirname(self.rec.file(it["id"])), other)   # existing ones stay


class Storage(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def test_picker_and_test(self):
        os.makedirs(os.path.join(self.d, "a", "b"))
        top = STO.list_dirs("", self.d)
        self.assertIn(os.path.realpath(self.d), [x["path"] for x in top["dirs"]])
        sub = STO.list_dirs(self.d, self.d)
        self.assertIn("a", [x["name"] for x in sub["dirs"]])
        with self.assertRaises(ValueError):
            STO.list_dirs(os.path.dirname(os.path.realpath(self.d)) + "/../..", self.d)
        t = STO.test_dir(os.path.join(self.d, "a"), self.d)
        self.assertTrue(t["ok"] and t["writable"] and t["free"] > 0)
        self.assertEqual(os.listdir(os.path.join(self.d, "a")), ["b"])      # the probe is removed
        self.assertFalse(STO.test_dir(os.path.join(self.d, "missing"), self.d)["ok"])
        self.assertFalse(STO.test_dir("relative/path", self.d)["ok"])

    def test_share_login_is_encrypted_and_never_returned(self):
        sh = STO.ShareStore(self.d, SecretStore(self.d))
        pub = sh.save({"type": "smb", "server": "nas.example.com", "share": "tv", "user": "bob", "pass": "pa55word!", "mountpoint": "/mnt/recordings"})
        self.assertTrue(pub["hasPassword"])
        self.assertNotIn("pass", pub)
        with open(os.path.join(self.d, "share.enc"), "rb") as f:
            raw = f.read()
        self.assertNotIn(b"pa55word", raw)
        self.assertNotIn(b"nas.example.com", raw)
        sh.save({"type": "smb", "server": "nas.example.com", "share": "tv", "user": "bob", "pass": ""})   # keeps the password
        self.assertEqual(sh.get()["pass"], "pa55word!")
        steps = "\n".join(STO.host_steps(sh.get(), 345, uid=105, gid=107))
        self.assertNotIn("pa55word", steps)
        self.assertIn("//nas.example.com/tv", steps)
        self.assertIn("uid=100105", steps)
        self.assertIn("pct set 345 -mp0", steps)
        with self.assertRaises(ValueError):
            sh.save({"type": "smb", "server": "x;rm -rf /", "share": "tv"})
        with self.assertRaises(ValueError):
            sh.save({"type": "ftp", "server": "x", "share": "y"})


if __name__ == "__main__":
    unittest.main()
