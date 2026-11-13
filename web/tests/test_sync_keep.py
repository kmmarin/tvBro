# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""A sync that gets nothing keeps the previous data, records lastError and is retried with
backoff; the last good lists are stored (encrypted) and served right after a restart."""
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server import app as A  # noqa: E402
from tvbro_server import catcache  # noqa: E402
from tvbro_server import parsers as P  # noqa: E402
from tvbro_server.secretstore import SecretStore  # noqa: E402


def chans(n):
    return [P.Channel(f"p0|{i}", "p0", f"Chan {i}", None, "G", f"http://h/live/alice/s3cret/{i}.ts", None, i,
                      P.Catchup("xc", None) if i == 1 else None, 2 if i == 1 else 0) for i in range(1, n + 1)]


class FakeProvider(A.Provider):
    answer: list = []

    def _m3u_channels(self, p, job):
        return list(self.answer)

    def reindex(self):                       # the index has its own tests
        self.reindexed = getattr(self, "reindexed", 0) + 1


class KeepOld(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.store = SecretStore(self.d)
        self.store.save_provider("m3u", "", "", "", "", "http://h/list.m3u")

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def provider(self):
        return FakeProvider(SecretStore(self.d), "p0", dict(A.SETTINGS_DEFAULTS))

    def sync(self, prov):
        job = prov.jobs["channels"]
        job._run(prov._sync_channels)
        return job

    def test_empty_answer_keeps_the_list_and_backs_off(self):
        prov = self.provider()
        prov.answer = chans(3)
        job = self.sync(prov)
        self.assertEqual((len(prov.channels), job.fails, job.last_err), (3, 0, None))
        ok_at = prov._chan_at

        prov.answer = []                                   # the provider answers with nothing
        job = self.sync(prov)
        self.assertEqual(len(prov.channels), 3)            # kept
        self.assertEqual(prov._chan_at, ok_at)
        self.assertIn("0 channels", job.last_err["msg"])
        self.assertIn("kept", job.last_err["msg"])
        self.assertEqual(job.fails, 1)
        t0 = job.last_err["at"]
        self.assertAlmostEqual(job.retry_at - t0, 60, delta=2)
        self.assertFalse(job.retry_due(t0 + 30))
        self.assertTrue(job.retry_due(t0 + 61))
        st = prov.status()["channels"]
        self.assertEqual((st["channels"], st["failures"], st["nextAt"]), (3, 1, int(job.retry_at)))

        self.sync(prov)
        self.assertAlmostEqual(job.retry_at - job.last_err["at"], 300, delta=2)
        self.sync(prov)
        self.sync(prov)
        self.assertAlmostEqual(job.retry_at - job.last_err["at"], 900, delta=2)     # stays at 15 min

        prov.answer = chans(4)                             # back: success clears the backoff
        job = self.sync(prov)
        self.assertEqual((len(prov.channels), job.fails, job.retry_at), (4, 0, 0.0))

    def test_failed_first_sync_is_retried_by_the_scheduler(self):
        prov = self.provider()
        prov.answer = []
        job = self.sync(prov)
        self.assertEqual(len(prov.channels), 0)
        self.assertEqual(job.fails, 1)
        started = []
        prov.sync = lambda kind: started.append(kind)
        prov.tick(now=job.retry_at - 1)
        self.assertEqual(started, [])
        prov.tick(now=job.retry_at + 1)
        self.assertEqual(started, ["channels"])

    def test_restart_serves_the_stored_list_encrypted(self):
        prov = self.provider()
        prov.answer = chans(5)
        self.sync(prov)
        prov._set_vod({"movie_cats": [P.XtreamCategory("1", "Films")], "series_cats": [],
                       "movies": [P.VodItem("m|p0|7", "p0", "movie", "7", "Amélie", None, "1", "7.5", "mkv", year="2001")],
                       "series": []})
        prov._store(prov._vod_cache, vod=prov.vod)
        for path in (prov._chan_cache, prov._vod_cache):
            with open(path, "rb") as f:
                raw = f.read()
            self.assertNotIn(b"s3cret", raw)
            self.assertNotIn(b"alice", raw)

        again = self.provider()                            # a restart: nothing fetched yet
        self.assertEqual([c.key for c in again.channels], [f"p0|{i}" for i in range(1, 6)])
        self.assertEqual(again.channels[0].catchup.type, "xc")
        self.assertEqual(again.channels[0].catchup_days, 2)
        self.assertTrue(again._chan_at > 0)
        self.assertEqual(again.vod_by_key["m|p0|7"].name, "Amélie")
        self.assertEqual(again.vod["movie_cats"][0].category_name, "Films")
        again.ensure_channels()                            # no waiting for a download

    def test_empty_vod_answer_keeps_the_catalogue(self):
        prov = self.provider()
        prov.kind = lambda: "xtream"
        prov._get = lambda action, **kw: b"[]"
        prov._set_vod({"movie_cats": [], "series_cats": [], "series": [],
                       "movies": [P.VodItem("m|p0|1", "p0", "movie", "1", "Kept", None, "1", None, "mp4")]})
        job = prov.jobs["vod"]
        job._run(prov._sync_vod)
        self.assertEqual(prov.vod_by_key["m|p0|1"].name, "Kept")
        self.assertIn("0 movies", job.last_err["msg"])
        self.assertEqual(job.fails, 1)

    def test_unreadable_cache_is_ignored(self):
        prov = self.provider()
        with open(prov._chan_cache, "wb") as f:
            f.write(b"garbage")
        self.assertEqual(self.provider().channels, [])
        self.assertEqual(catcache.decode(b"not gzip"), {})

    def test_new_login_drops_the_stored_lists(self):
        prov = self.provider()
        prov.answer = chans(2)
        self.sync(prov)
        prov.invalidate()
        self.assertFalse(os.path.exists(prov._chan_cache))
        self.assertEqual(self.provider().channels, [])


if __name__ == "__main__":
    unittest.main()
