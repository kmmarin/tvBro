# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Backend parser tests. Run: python -m unittest discover -s web/tests"""
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import parsers as P  # noqa: E402


class EpisodeLabel(unittest.TestCase):
    def test_xmltv_ns_zero_based(self):
        self.assertEqual(P.episode_label([("xmltv_ns", "1.3.0/1")]), "S2 E4")
        self.assertEqual(P.episode_label([("xmltv_ns", "0 . 9 . ")]), "S1 E10")
        self.assertEqual(P.episode_label([("xmltv_ns", ".4.")]), "E5")

    def test_onscreen(self):
        self.assertEqual(P.episode_label([("onscreen", "S02E04")]), "S2 E4")
        self.assertIsNone(P.episode_label([("dd_progid", "EP0123")]))


class XmltvEpisode(unittest.TestCase):
    def test_parse_sub_and_episode(self):
        xml = b"""<tv><channel id="c1"><display-name>One</display-name></channel>
        <programme channel="c1" start="20260101100000 +0000" stop="20260101110000 +0000">
          <title>Show</title><sub-title>Pilot</sub-title><episode-num system="xmltv_ns">0.0.</episode-num>
        </programme></tv>"""
        got = []
        P.parse_xmltv(io.BytesIO(xml), lambda c, n: "p0|c1", 0, 1 << 40, got.append)
        self.assertEqual(len(got), 1)
        j = got[0].to_json()
        self.assertEqual((j["sub"], j["ep"]), ("Pilot", "S1 E1"))


class CleanTitle(unittest.TestCase):
    def test_strip_series_year_prefix(self):
        self.assertEqual(P.clean_title("The Show", "The Show (2019) - S01E03 - The Return"), "The Return")
        self.assertEqual(P.clean_title("The Show", "S01E03 - The Return"), "The Return")
        self.assertEqual(P.clean_title("X", "Episode 4"), "Episode 4")
        self.assertEqual(P.clean_title("The Show", "The Show S2E10"), "The Show S2E10")  # nothing left -> original


class Classify(unittest.TestCase):
    def test_states(self):
        ch = P.Channel("p0|1", "p0", "A", None, "G", "http://x", None, 1, P.Catchup("xc", None), 2)
        now = 1_000_000
        self.assertEqual(P.classify(ch, now + 10, now + 100, now), "FUTURE")
        self.assertEqual(P.classify(ch, now - 10, now + 100, now), "NOW")
        self.assertEqual(P.classify(ch, now - 3600, now - 60, now), "PAST_CATCHUP")
        self.assertEqual(P.classify(ch, now - 3 * 86400, now - 3 * 86400 + 60, now), "PAST")


if __name__ == "__main__":
    unittest.main()
