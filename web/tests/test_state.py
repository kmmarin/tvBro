# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server.state import StateStore  # noqa: E402


class State(unittest.TestCase):
    def test_roundtrip_and_bounds(self):
        d = tempfile.mkdtemp()
        s = StateStore(os.path.join(d, "s.db"))
        self.assertTrue(s.put("mylist", "m|1", {"key": "m|1", "name": "A"}))
        s.put("resume", "m|1", {"secs": 10})
        s.put("prefs", "solid", True)
        for i in range(25):
            s.put("history", f"q{i}", {"q": f"q{i}"})
            time.sleep(0.001)
        a = s.all()
        self.assertEqual(a["mylist"][0]["name"], "A")
        self.assertEqual(a["resume"]["m|1"]["secs"], 10)
        self.assertIs(a["prefs"]["solid"], True)
        self.assertEqual(len(a["history"]), 20)
        self.assertEqual(a["history"][0]["q"], "q24")      # newest first
        self.assertFalse(s.put("nope", "x", 1))
        s.delete("mylist", "m|1")
        s.clear("history")
        a = s.all()
        self.assertEqual((a["mylist"], a["history"]), ([], []))


if __name__ == "__main__":
    unittest.main()
