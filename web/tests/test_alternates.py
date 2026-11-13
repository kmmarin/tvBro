# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Alternate sources of a live channel: name normalisation and the index."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))

from tvbro_server import alternates as A          # noqa: E402
from tvbro_server.parsers import Channel          # noqa: E402


def ch(key, name, tvg=None, src="p0", url=None):
    return Channel(key=key, source_id=src, name=name, logo=None, group="G", url=url or f"http://x/{key}.ts",
                   tvg_id=tvg, number=0, catchup=None, catchup_days=0)


class NormName(unittest.TestCase):
    def test_quality_words_and_brackets_go(self):
        for n in ("BBC One HD", "BBC One FHD", "BBC One (Backup)", "BBC ONE [1080p 50fps]", "bbc one hevc", "BBC One"):
            self.assertEqual(A.norm_name(n), "bbc one", n)

    def test_numbered_copies(self):
        self.assertEqual(A.norm_name("BBC One HD 2"), "bbc one")
        self.assertEqual(A.norm_name("Sky Sports 1 HD"), "sky sports 1")      # the 1 is part of the name
        self.assertNotEqual(A.norm_name("Sky Sports 1"), A.norm_name("Sky Sports 2"))

    def test_what_changes_the_content_stays(self):
        self.assertNotEqual(A.norm_name("Film4 +1"), A.norm_name("Film4"))
        self.assertNotEqual(A.norm_name("UK: Fox"), A.norm_name("US: Fox"))
        self.assertEqual(A.norm_name("BBC One (Wales)"), "bbc one wales")

    def test_accents_and_case(self):
        self.assertEqual(A.norm_name("Télé Monte-Carlo"), A.norm_name("TELE MONTE CARLO HD"))

    def test_nothing_left(self):
        self.assertEqual(A.norm_name("HD"), "")
        self.assertEqual(A.norm_name(""), "")


class Index(unittest.TestCase):
    def setUp(self):
        self.chans = [
            ch("p0|a", "Kilo Movies", "kilo"),
            ch("p0|b", "Kilo Movies HD", "kilo"),          # same id and name
            ch("p0|c", "KILO MOVIES (backup)"),             # same name, no id
            ch("p1|d", "Kilo Movies FHD", src="p1"),        # another playlist
            ch("p0|e", "Kilo Movies +1"),                   # timeshift: not the same
            ch("p0|f", "Other", "kilo"),                    # same tvg-id, other name
            ch("p0|g", "Kilo Movies", url="http://x/p0|a.ts"),   # the very same stream: useless
        ]
        self.ix = A.AltIndex(self.chans)

    def test_order_and_scope(self):
        alts = self.ix.alternates("p0|a")
        self.assertEqual(alts[:2], ["p0|b", "p0|f"])       # tvg-id matches first (own playlist)
        self.assertIn("p0|c", alts)
        self.assertIn("p1|d", alts)
        self.assertLess(alts.index("p0|c"), alts.index("p1|d"))   # own playlist before the others
        self.assertNotIn("p0|e", alts)
        self.assertNotIn("p0|g", alts)
        self.assertNotIn("p0|a", alts)

    def test_usable_filter_and_limit(self):
        self.assertNotIn("p0|b", self.ix.alternates("p0|a", usable=lambda k: k != "p0|b"))
        self.assertEqual(len(self.ix.alternates("p0|a", limit=2)), 2)

    def test_unknown_or_lonely(self):
        self.assertEqual(self.ix.alternates("nope"), [])
        self.assertEqual(self.ix.alternates("p0|e"), [])


if __name__ == "__main__":
    unittest.main()
