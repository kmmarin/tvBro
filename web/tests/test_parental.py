# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Parental PIN: hashing, attempt limit, unlock tokens, locks and the file on disk."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))

from tvbro_server import parental as PA          # noqa: E402


class Clock:
    def __init__(self):
        self.t = 1_000_000.0

    def __call__(self):
        return self.t


class ParentalTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "parental.json")
        self.clock = Clock()
        self.p = PA.Parental(self.path, b"k" * 32, self.clock)

    def test_hash_is_salted_and_never_the_pin(self):
        a, b = PA.hash_pin("1234"), PA.hash_pin("1234")
        self.assertNotEqual(a, b)
        self.assertTrue(PA.verify_pin("1234", a) and PA.verify_pin("1234", b))
        self.assertFalse(PA.verify_pin("1235", a))
        self.p.set_pin("4321")
        raw = open(self.path, encoding="utf-8").read()
        self.assertNotIn("4321", raw)
        self.assertTrue(json.loads(raw)["pin"].startswith("pbkdf2_sha256$"))

    def test_set_change_remove(self):
        self.assertFalse(self.p.has_pin())
        self.p.set_pin("1234")                              # first time: no PIN needed
        with self.assertRaises(PermissionError):
            self.p.set_pin("5555", "0000")
        with self.assertRaises(ValueError):
            self.p.set_pin("12", "1234")
        self.p.set_pin("5555", "1234")
        self.assertTrue(self.p.check("5555"))
        self.p.set_pin("", True)                            # an unlocked browser may remove it
        self.assertFalse(self.p.has_pin())

    def test_wrong_pins_wait(self):
        self.p.set_pin("1234")
        for _ in range(5):
            self.assertFalse(self.p.check("0000"))
        with self.assertRaises(PA.Locked) as cm:
            self.p.check("1234")                            # even the right one waits now
        self.assertGreater(cm.exception.retry_after, 0)
        self.clock.t += 31
        self.assertTrue(self.p.check("1234"))
        self.assertFalse(self.p.check("0000"))              # the count started again

    def test_tokens(self):
        self.p.set_pin("1234")
        tok = self.p.token()
        self.assertTrue(self.p.valid(tok))
        self.assertFalse(self.p.valid(tok[:-1] + ("0" if tok[-1] != "0" else "1")))
        self.assertFalse(self.p.valid("garbage"))
        self.clock.t += PA.TOKEN_TTL + 1
        self.assertFalse(self.p.valid(tok), "expired")
        tok = self.p.token()
        self.p.set_pin("9999", "1234")
        self.assertFalse(self.p.valid(tok), "a PIN change ends every unlock")

    def test_locks_need_a_pin_to_bite_and_survive_restart(self):
        self.p.lock("group", "News", True)
        self.p.lock("channel", "p0|x", True)
        self.p.lock("vodcat", "11", True)
        self.assertFalse(self.p.active(), "no PIN: nothing is locked")
        self.p.set_pin("1234")
        self.assertTrue(self.p.active())
        self.p.lock("channel", "p0|x", False)
        again = PA.Parental(self.path, b"k" * 32, self.clock)
        self.assertEqual(again.locks(), {"groups": ["News"], "channels": [], "vodCats": ["11"]})
        self.assertTrue(again.check("1234"))
        with self.assertRaises(ValueError):
            again.lock("planet", "x", True)
        again.set_locks({"groups": [], "channels": ["a", "b"], "vodCats": None})
        self.assertEqual(again.locks()["channels"], ["a", "b"])
        self.assertTrue(again.has_pin(), "restoring locks keeps the PIN")


if __name__ == "__main__":
    unittest.main()
