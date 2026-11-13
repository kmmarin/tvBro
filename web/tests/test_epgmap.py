# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Manual channel-to-guide mapping: parser feeding several keys, the guide's channel list,
key_for with the mapping, the search index, and the mapping file."""
import io
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))

from tvbro_server import parsers as P                                  # noqa: E402
from tvbro_server import searchidx as S                                # noqa: E402
from tvbro_server.epgdb import EpgWriter, EpgDb, key_for, NO_GUIDE    # noqa: E402
from tvbro_server.epgmap import EpgMap                                 # noqa: E402

NOW = int(time.time()) // 60 * 60


def xt(t):
    return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))


XML = f"""<?xml version="1.0"?><tv>
<channel id="alpha"><display-name>Alpha</display-name></channel>
<channel id="Lima.Guide"><display-name>Lima Television</display-name><display-name>Lima</display-name></channel>
<programme start="{xt(NOW - 600)}" stop="{xt(NOW + 1200)}" channel="alpha"><title>Alpha show</title></programme>
<programme start="{xt(NOW - 600)}" stop="{xt(NOW + 1200)}" channel="Lima.Guide"><title>Lima quiz</title></programme>
</tv>"""


def ch(key, name, tvg=None):
    return P.Channel(key, "p0", name, None, "G", "http://x/" + key, tvg, 0, None, 0)


class Mapping(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.alpha = ch("p0|a", "Alpha", "alpha")
        self.lima = ch("p0|l", "Lima TV")                 # neither tvg-id nor name matches the guide
        self.beta = ch("p0|b", "Beta")                     # mapped onto Alpha's guide

    def build(self, emap: dict):
        """What Provider._sync_epg does: automatic matches + the guide channels picked by hand."""
        wanted = {c.id_key for c in (self.alpha, self.lima, self.beta) if c.id_key} | {c.name_key for c in (self.alpha, self.lima, self.beta)}
        mapped = {f"p0|{g.lower()}" for g in emap.values() if g != NO_GUIDE}

        def resolve(attr, names):
            k = f"p0|{attr.lower()}"
            auto = k if k in wanted else next((f"p0|{n.lower()}" for n in names or () if f"p0|{n.lower()}" in wanted), None)
            if k in mapped and auto != k:
                return [auto, k] if auto else k
            return auto
        self.n = getattr(self, "n", 0) + 1                 # a new file per build (Windows keeps open ones)
        path = os.path.join(self.dir, f"epg{self.n}.db")
        w = EpgWriter(path)
        names = P.parse_xmltv(io.BytesIO(XML.encode()), resolve, NOW - 86400, NOW + 86400, w.add)
        w.guide_channels(names)
        info = w.finish()
        db = EpgDb(path)
        db.keys = info["keys"]
        return db

    def test_unmatched_channel_gets_its_guide_once_mapped(self):
        db = self.build({})
        self.assertIsNone(key_for(db, self.lima))
        self.assertNotIn("p0|lima.guide", db.keys, "an unmatched guide channel is not stored")
        chans = db.guide_channels("p0")
        self.assertEqual([c["id"] for c in chans], ["alpha", "Lima.Guide"])
        self.assertEqual(chans[1]["name"], "Lima Television / Lima")
        self.assertFalse(chans[1]["hasGuide"])
        self.assertEqual([c["id"] for c in db.guide_channels("p0", "televis")], ["Lima.Guide"])
        # mapped (the guide sync keeps it now)
        emap = {"p0|l": "Lima.Guide"}
        db = self.build(emap)
        self.assertEqual(key_for(db, self.lima, emap), "p0|lima.guide")
        self.assertEqual([p.title for p in db.window(key_for(db, self.lima, emap), NOW - 3600, NOW + 3600)], ["Lima quiz"])

    def test_one_guide_channel_for_two_playlist_channels(self):
        emap = {"p0|b": "alpha"}
        db = self.build(emap)
        self.assertEqual(key_for(db, self.alpha, emap), "p0|alpha", "the automatic match stays")
        self.assertEqual(key_for(db, self.beta, emap), "p0|alpha")
        self.assertIsNone(key_for(db, self.beta), "without the mapping Beta has no guide")

    def test_no_guide_overrides_a_wrong_automatic_match(self):
        db = self.build({})
        self.assertEqual(key_for(db, self.alpha), "p0|alpha")
        self.assertIsNone(key_for(db, self.alpha, {"p0|a": NO_GUIDE}))
        self.assertIsNone(key_for(db, self.alpha, {"p0|a": "nothing.like.it"}), "mapped to a guide channel without programmes")

    def test_search_finds_the_mapped_channel(self):
        emap = {"p0|l": "Lima.Guide"}
        db = self.build(emap)
        idx_path = os.path.join(self.dir, "search.db")
        key_of = lambda c: None if c.key not in emap else ("" if emap[c.key] == NO_GUIDE else f"p0|{emap[c.key].lower()}")
        S.build(idx_path + ".new", [self.alpha, self.lima, self.beta], [], [], db.path, key_of)
        idx = S.SearchIndex(idx_path)
        idx.swap_in(idx_path + ".new", {})
        rows = idx.programmes(S.fts_query("lima quiz"), NOW)
        self.assertTrue(rows, "programme found")
        self.assertEqual({k for k, _ in S.group_programmes(rows, lambda k: True)}, {"p0|l"})

    def test_mapping_file(self):
        path = os.path.join(self.dir, "epgmap.json")
        m = EpgMap(path)
        m.set("p0|l", "Lima.Guide")
        m.set("p1|x", NO_GUIDE)
        self.assertEqual(EpgMap(path).for_source("p0"), {"p0|l": "Lima.Guide"})
        m.set("p0|l", None)
        self.assertIsNone(EpgMap(path).get("p0|l"))
        with self.assertRaises(ValueError):
            m.set("", "x")


if __name__ == "__main__":
    unittest.main()
