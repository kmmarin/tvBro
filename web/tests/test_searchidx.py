# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import parsers as P  # noqa: E402
from tvbro_server import searchidx as S  # noqa: E402
from tvbro_server.epgdb import EpgWriter  # noqa: E402

NOW = int(time.time()) // 60 * 60


def ch(n, name, tvg=None, days=0):
    return P.Channel(f"p0|{n}", "p0", name, None, "G", f"http://x/{n}", tvg, n,
                     P.Catchup("xc", None) if days else None, days)


def vod(kind, i, name, year=None):
    return P.VodItem(f"{'m' if kind == 'movie' else 's'}|p0|{i}", "p0", kind, str(i), name, None, "C", None, "mp4", year=year)


class Index(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        epg = os.path.join(cls.dir, "epg.db")
        w = EpgWriter(epg)
        H = 3600
        progs = [
            # channel 1 (catch-up 3 days): past within archive, now, future
            ("p0|bbc1", NOW - 5 * H, NOW - 4 * H, "BBC News at Six", None),
            ("p0|bbc1", NOW - 10 * 60, NOW + 20 * 60, "Barcelona v Real Madrid", "La Liga"),
            ("p0|bbc1", NOW + 2 * H, NOW + 3 * H, "Football Focus", "Barça special"),
            ("p0|bbc1", NOW - 5 * 86400, NOW - 5 * 86400 + H, "Barcelona Old", None),   # outside the 3-day archive
            # channel 2 (no catch-up): past is dropped
            ("p0|sky", NOW - 3 * H, NOW - 2 * H, "Barcelona Highlights", None),
            ("p0|sky", NOW + H, NOW + 2 * H, "Barcelona Highlights", None),
            ("p0|sky", NOW + 3 * H, NOW + 4 * H, "Pokémon", None),
            # a guide key nobody uses
            ("p0|orphan", NOW, NOW + H, "Barcelona Orphan", None),
        ]
        for key, s, e, t, sub in progs:
            w.add(P.Programme(key, s, e, t, "a long description", sub, "S1 E2" if sub else None))
        w.finish()
        cls.chans = [ch(1, "BBC One HD", "bbc1", days=3), ch(2, "Sky Sports", "sky"), ch(3, "Barça TV"),
                     ch(4, "Telemundo Barcelona")]
        movies = [vod("movie", 1, "Jack Reacher", "2012"), vod("movie", 2, "Jack Reacher: Never Go Back", "2016"),
                  vod("movie", 3, "The Reacher Files"), vod("movie", 4, "Amélie", "2001")]
        series = [vod("series", 5, "Reacher", "2022")]
        cls.path = os.path.join(cls.dir, "search.db")
        tmp = cls.path + ".new"
        cls.counts = S.build(tmp, cls.chans, movies, series, epg)
        cls.idx = S.SearchIndex(cls.path)
        cls.idx.swap_in(tmp, cls.counts)

    @classmethod
    def tearDownClass(cls):
        cls.idx.close_thread_conn()

    def q(self, text):
        return S.fts_query(text), S._first_word(text)

    def test_counts(self):
        self.assertEqual(self.counts["channels"], 4)
        self.assertEqual(self.counts["vod"], 5)
        self.assertEqual(self.counts["programmes"], 7)        # the orphan guide key is not indexed

    def test_fts_query(self):
        self.assertEqual(S.fts_query("Barc  real"), '"barc"* "real"*')
        self.assertIsNone(S.fts_query(" -- "))
        self.assertEqual(S.fts_query('x"y'), '"x"* "y"*')     # quotes cannot break out

    def test_prefix(self):
        fq, w0 = self.q("barc")
        self.assertEqual(self.idx.channels(fq, w0, 30), ["p0|3", "p0|4"])    # starts-with first
        fq, w0 = self.q("reach")
        self.assertEqual(self.idx.vod("series", fq, w0, 20), ["s|p0|5"])
        self.assertEqual(len(self.idx.vod("movie", fq, w0, 20)), 3)

    def test_diacritics(self):
        for text in ("barca", "barça", "BARÇA"):
            fq, w0 = self.q(text)
            self.assertIn("p0|3", self.idx.channels(fq, w0, 30), text)
        fq, w0 = self.q("amelie")
        self.assertEqual(self.idx.vod("movie", fq, w0, 20), ["m|p0|4"])
        fq, _ = self.q("pokemon")
        self.assertEqual([r[2] for r in self.idx.programmes(fq, NOW)], ["Pokémon"])
        fq, _ = self.q("barca")                                # episode title (sub) is indexed too
        self.assertEqual([r[2] for r in self.idx.programmes(fq, NOW)], ["Football Focus"])

    def test_ranking(self):
        fq, w0 = self.q("reacher")
        got = self.idx.vod("movie", fq, w0, 20)
        self.assertEqual(got[0], "m|p0|1")                     # "Jack Reacher": short, exact word
        self.assertEqual(set(got), {"m|p0|1", "m|p0|2", "m|p0|3"})
        fq, w0 = self.q("reacher 2016")                        # the year is searchable
        self.assertEqual(self.idx.vod("movie", fq, w0, 20), ["m|p0|2"])
        fq, w0 = self.q("jack never")                          # all words must match
        self.assertEqual(self.idx.vod("movie", fq, w0, 20), ["m|p0|2"])

    def test_programme_classification(self):
        fq, _ = self.q("barcelona")
        rows = self.idx.programmes(fq, NOW)
        got = [(S.ST_NAMES[st], key, title, start) for st, key, title, sub, ep, start, stop in rows]
        self.assertEqual(got, [
            ("now", "p0|1", "Barcelona v Real Madrid", NOW - 600),
            ("future", "p0|2", "Barcelona Highlights", NOW + 3600),
        ])                                                     # sky's past airing has no catch-up; the 5-day-old one is outside the archive
        fq, _ = self.q("news six")
        rows = self.idx.programmes(fq, NOW)
        self.assertEqual([(S.ST_NAMES[r[0]], r[2]) for r in rows], [("catchup", "BBC News at Six")])

    def test_grouping(self):
        rows = [(1, "a", "T1", "", None, 100, 200), (2, "b", "T2", "s", "S1 E1", 300, 400),
                (2, "a", "T3", "", None, 500, 600), (2, "a", "T3", "", None, 500, 600),     # duplicate start
                (3, "c", "T4", "", None, 1, 2), (2, "hidden", "T5", "", None, 1, 2)]
        g = S.group_programmes(rows, keep=lambda k: k != "hidden", max_channels=2, per_channel=5)
        self.assertEqual([k for k, _ in g], ["a", "b"])
        self.assertEqual([p["title"] for p in g[0][1]], ["T1", "T3"])
        self.assertEqual(g[1][1][0], {"start": 300, "stop": 400, "title": "T2", "st": "future", "sub": "s", "ep": "S1 E1"})
        g = S.group_programmes(rows, max_channels=10, per_channel=1)
        self.assertEqual([len(v) for _, v in g], [1, 1, 1, 1])
        # a week of repeats does not push out the catch-up airings
        rows = [(1, "a", "N", "", None, 0, 1)] + [(2, "a", "F", "", None, 10 + i, 11 + i) for i in range(9)] + \
               [(3, "a", "C", "", None, -10 - i, -9 - i) for i in range(3)]
        (_, items), = S.group_programmes(rows)
        self.assertEqual([p["st"] for p in items], ["now", "future", "future", "catchup", "catchup"])
        (_, items), = S.group_programmes([r for r in rows if r[0] != 2])
        self.assertEqual([p["st"] for p in items], ["now", "catchup", "catchup", "catchup"])

    def test_rebuild_swaps(self):
        tmp = self.path + ".new"
        S.build(tmp, [ch(9, "Nine")], [], [], None)
        idx = S.SearchIndex(os.path.join(self.dir, "other.db"))
        idx.swap_in(tmp)
        fq, w0 = self.q("nin")
        self.assertEqual(idx.channels(fq, w0, 5), ["p0|9"])
        idx.load_counts()
        self.assertEqual(idx.counts["channels"], 1)
        idx.close_thread_conn()


if __name__ == "__main__":
    unittest.main()
