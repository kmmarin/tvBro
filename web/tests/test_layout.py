# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""The household's channel layout: custom groups, rename / hide / move groups and channels."""
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
from tvbro_server.layout import Layout, moved, ordered  # noqa: E402

PG = [{"id": "1", "name": "News", "count": 3}, {"id": "2", "name": "Sport", "count": 2},
      {"id": "p1:9", "name": "Second · Movies", "count": 4}]


class Order(unittest.TestCase):
    def test_ordered_keeps_unknown_after(self):
        self.assertEqual(ordered(["a", "b", "c", "d"], ["c", "a"]), ["c", "a", "b", "d"])
        self.assertEqual(ordered(["a", "b"], ["x", "b"]), ["b", "a"])

    def test_moved(self):
        self.assertEqual(moved(["a", "b", "c"], "c", -1), ["a", "c", "b"])
        self.assertEqual(moved(["a", "b", "c"], "a", -1), ["a", "b", "c"])     # clamped
        self.assertEqual(moved(["a", "b", "c"], "a", 5), ["b", "c", "a"])
        self.assertIsNone(moved(["a"], "z", 1))


class Edits(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "layout.json")
        self.L = Layout(self.path)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def ids(self, with_hidden=False):
        return [g["id"] for g in self.L.groups(PG, with_hidden)]

    def test_rename_and_hide_provider_group(self):
        self.L.edit("group.rename", {"id": "2", "name": "  Football  "})
        g = self.L.groups(PG)[1]
        self.assertEqual((g["name"], g["origName"]), ("Football", "Sport"))
        self.L.edit("group.hide", {"id": "1", "hidden": True})
        self.assertEqual(self.ids(), ["2", "p1:9"])
        self.assertTrue(self.L.groups(PG, True)[0]["hidden"])
        self.L.edit("group.hide", {"id": "1", "hidden": False})
        self.L.edit("group.rename", {"id": "2", "name": ""})           # back to the provider's name
        self.assertEqual(self.L.groups(PG)[1]["name"], "Sport")
        self.assertEqual(self.L.data["groups"], {})

    def test_custom_group_with_channels(self):
        gid = self.L.edit("group.create", {"name": "Kids"})["id"]
        self.assertEqual(self.ids()[0], gid)                             # new groups come first
        self.L.edit("channel.add", {"group": gid, "key": "p0|5"})
        self.L.edit("channel.add", {"group": gid, "key": "p1|x|1"})
        self.L.edit("channel.add", {"group": gid, "key": "p0|5"})       # no duplicates
        self.assertEqual(self.L.custom_keys(gid), ["p0|5", "p1|x|1"])
        self.L.edit("channel.move", {"group": gid, "key": "p1|x|1", "dir": -1}, channel_keys=self.L.custom_keys(gid))
        self.assertEqual(self.L.custom_keys(gid), ["p1|x|1", "p0|5"])
        self.L.edit("channel.remove", {"group": gid, "key": "p0|5"})
        self.assertEqual(self.L.custom_keys(gid), ["p1|x|1"])
        with self.assertRaises(ValueError):
            self.L.edit("channel.add", {"group": "1", "key": "p0|5"})     # only own groups take channels
        with self.assertRaises(ValueError):
            self.L.edit("group.delete", {"id": "1"})                      # provider groups are hidden, not deleted
        self.L.edit("group.delete", {"id": gid})
        self.assertNotIn(gid, self.ids())

    def test_move_groups_and_survive_reload(self):
        self.L.edit("group.move", {"id": "p1:9", "dir": -2}, group_ids=self.ids(True))
        self.assertEqual(self.ids(), ["p1:9", "1", "2"])
        again = Layout(self.path)
        self.assertEqual([g["id"] for g in again.groups(PG)], ["p1:9", "1", "2"])
        # a group the provider adds later appears after the ordered ones
        self.assertEqual([g["id"] for g in again.groups(PG + [{"id": "3", "name": "New", "count": 1}])], ["p1:9", "1", "2", "3"])

    def test_channels_rename_hide_order(self):
        self.L.edit("channel.rename", {"key": "p0|5", "name": "My BBC"})
        js = self.L.channel({"key": "p0|5", "name": "BBC One HD"})
        self.assertEqual((js["name"], js["origName"]), ("My BBC", "BBC One HD"))
        self.L.edit("channel.hide", {"key": "p0|6", "hidden": True})
        self.assertTrue(self.L.channel_hidden("p0|6"))
        self.assertTrue(self.L.channel({"key": "p0|6", "name": "x"}, True)["hidden"])
        self.L.edit("channel.move", {"group": "1", "key": "p0|7", "dir": -5}, channel_keys=["p0|5", "p0|6", "p0|7"])
        self.assertEqual(self.L.order_channels("1", ["p0|5", "p0|6", "p0|7", "p0|8"]), ["p0|7", "p0|5", "p0|6", "p0|8"])

    def test_bad_edits(self):
        for op, d in (("group.create", {"name": " "}), ("nope", {}), ("channel.hide", {}), ("group.move", {"id": "zz"})):
            with self.assertRaises(ValueError, msg=op):
                self.L.edit(op, d, group_ids=["1"])

    def test_reset(self):
        self.L.edit("group.hide", {"id": "1", "hidden": True})
        self.L.edit("reset", {})
        self.assertEqual(self.ids(), ["1", "2", "p1:9"])


if __name__ == "__main__":
    unittest.main()
