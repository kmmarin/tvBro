# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server.streams import ACTIVE_SEC, StreamRegistry  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class ReplaceOldest(unittest.TestCase):
    def setUp(self):
        self.c = Clock()
        self.r = StreamRegistry(1, self.c)

    def test_second_tab_replaces_first(self):
        self.assertEqual(self.r.start("A", "tab A", "Ch 1"), [])
        g = self.r.touch("A")
        self.c.t += 5
        self.assertEqual(self.r.start("B", "tab B", "Ch 2"), ["A"])
        self.assertIsNone(self.r.touch("A"))          # A's media requests -> 409
        self.assertFalse(self.r.alive("A", g))        # A's open relay closes
        self.assertIsNotNone(self.r.touch("B"))
        # "Play here" in tab A takes it back and stops B
        self.c.t += 5
        self.assertEqual(self.r.start("A"), ["B"])
        self.assertIsNone(self.r.touch("B"))

    def test_oldest_of_several_is_stopped(self):
        self.r.set_max(2)
        self.r.start("A"); self.c.t += 1
        self.r.start("B"); self.c.t += 1
        self.assertEqual(self.r.start("C"), ["A"])
        self.assertEqual(self.r.status()["count"], 2)

    def test_same_tab_restart_does_not_evict_itself(self):
        self.r.start("A", what="preview")
        self.assertEqual(self.r.start("A", what="Ch 5 full screen"), [])
        self.assertEqual(self.r.status()["active"][0]["what"], "Ch 5 full screen")

    def test_idle_sessions_do_not_count(self):
        self.r.start("A")
        self.c.t += ACTIVE_SEC + 1
        self.assertEqual(self.r.start("B"), [])
        self.assertIsNotNone(self.r.touch("A"))       # idle, not replaced

    def test_released_session_frees_the_slot(self):
        self.r.start("A"); self.r.stop("A")
        self.assertEqual(self.r.start("B"), [])

    def test_unknown_sid_starts_implicitly(self):
        self.assertIsNotNone(self.r.touch("X"))
        self.assertEqual(self.r.status()["count"], 1)
        self.assertEqual(self.r.touch(None), 0)


if __name__ == "__main__":
    unittest.main()
