# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Encryption-key rotation (rotate_key.py): round trip, a crash at every step then a re-run,
roll back before anything changed, wrong / missing / invalid key refused, the server refusing to
start mid-rotation or with a wrong key, permissions, --end-sessions, no key material printed."""
import contextlib
import io
import json
import os
import shutil
import socket
import sqlite3
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "server"))
from cryptography.fernet import Fernet, InvalidToken  # noqa: E402

from tvbro_server import catcache  # noqa: E402
from tvbro_server import parsers as P  # noqa: E402
from tvbro_server import rotate_key as RK  # noqa: E402
from tvbro_server import secretstore as SS  # noqa: E402

POSIX = os.name == "posix"
STEPS = ["backup", "marker", "newkey", "staged", "swap", "verified", "oldkept", "switched",
         "before-retire", "retired"]


class Crash(Exception):
    pass


class Base(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.data = os.path.join(self.root, "data")
        self.cred = os.path.join(self.root, "credstore")
        self.bak = os.path.join(self.root, "backup")
        os.makedirs(self.data)
        os.makedirs(self.cred, mode=0o700)
        self.key_path = os.path.join(self.cred, "tvbro.key")
        self.key0 = Fernet.generate_key()
        with open(self.key_path, "wb") as f:
            f.write(self.key0 + b"\n")
        os.chmod(self.key_path, 0o600)
        self._env = {k: os.environ.get(k) for k in ("CREDENTIALS_DIRECTORY", "TVBRO_KEY", "TVBRO_KEY_FILE")}
        os.environ.pop("CREDENTIALS_DIRECTORY", None)
        os.environ.pop("TVBRO_KEY", None)
        os.environ["TVBRO_KEY_FILE"] = self.key_path
        self.fill()

    def tearDown(self):
        for k, v in self._env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        shutil.rmtree(self.root, ignore_errors=True)

    def fill(self):
        """A data folder like the server's: two logins, a share login, stored lists, state.db."""
        st = SS.SecretStore(self.data)
        st.save_provider("xtream", "http://prov.example:8080", "alice", "s3cret-pw")
        SS.SecretStore(self.data, "provider-p1.enc").save_provider("m3u", "", "", "", "", "http://h/b.m3u?pw=x")
        with open(os.path.join(self.data, "share.enc"), "wb") as f:
            f.write(st.seal_bytes(json.dumps({"type": "smb", "user": "u", "pass": "sharepw"}).encode()))
        ch = [P.Channel("p0|1", "p0", "One", None, "G", "http://prov.example:8080/live/alice/s3cret-pw/1.ts", None, 1, None, 0)]
        catcache.save(os.path.join(self.data, "channels.enc"), st.seal_bytes, catcache.encode(channels=(ch, [])))
        catcache.save(os.path.join(self.data, "vod.enc"), st.seal_bytes,
                      catcache.encode(vod={"movie_cats": [], "series_cats": [], "movies": [], "series": []}))
        db = sqlite3.connect(os.path.join(self.data, "state.db"))
        db.execute("CREATE TABLE kv (scope TEXT, key TEXT, value TEXT, updated INTEGER)")
        db.execute("INSERT INTO kv VALUES ('mylist', 'x', '[1]', 1)")
        db.commit()
        db.close()
        with open(os.path.join(self.data, "sign.key"), "wb") as f:
            f.write(b"A" * 32)
        with open(os.path.join(self.data, "sessions.json"), "w") as f:
            f.write("{}")
        self.plain = self.plaintexts(self.key0)

    def plaintexts(self, key):
        fe = Fernet(key)
        out = {}
        for n in RK.sealed_files(self.data):
            with open(os.path.join(self.data, n), "rb") as f:
                out[n] = fe.decrypt(f.read())
        return out

    def cur_key(self):
        with open(self.key_path, "rb") as f:
            return f.read().strip()

    def rotate(self, **kw):
        kw.setdefault("log", lambda s: None)
        return RK.rotate(self.data, self.key_path, self.bak, **kw)

    def assert_done(self, old_key):
        """Rotated and finished: one new key opens everything with the same content, the old key
        is retired into the backup, no leftovers, the server would start."""
        new = self.cur_key()
        self.assertNotEqual(new, old_key)
        self.assertEqual(self.plaintexts(new), self.plain)
        fe_old = Fernet(old_key)
        for n in RK.sealed_files(self.data):
            with open(os.path.join(self.data, n), "rb") as f, self.assertRaises(InvalidToken):
                fe_old.decrypt(f.read())
        for suf in (".new", ".old"):
            self.assertFalse(os.path.exists(self.key_path + suf), suf)
        self.assertFalse(os.path.exists(os.path.join(self.data, SS.MARKER)))
        self.assertFalse([n for n in os.listdir(self.data) if n.endswith(RK.STAGE) or n.endswith(".tmp")])
        retired = [os.path.join(dp, "tvbro.key.retired") for dp, _, fs in os.walk(self.bak) if "tvbro.key.retired" in fs]
        got = []
        for p in retired:
            with open(p, "rb") as f:
                got.append(f.read().strip())
        self.assertTrue(old_key in got, "the old key is not retired into the backup")
        SS.startup_check(self.data)
        self.assertEqual(SS.SecretStore(self.data).get_provider()["pass"], "s3cret-pw")

    def assert_recoverable(self):
        """At any moment: every sealed file opens with one of the keys kept next to the key file."""
        keys = [k for k in (self.key_path, self.key_path + ".new", self.key_path + ".old") if os.path.exists(k)]
        fes = []
        for k in keys:
            with open(k, "rb") as f:
                fes.append(Fernet(f.read().strip()))
        for n in RK.sealed_files(self.data):
            with open(os.path.join(self.data, n), "rb") as f:
                raw = f.read()
            got = None
            for fe in fes:
                try:
                    got = fe.decrypt(raw)
                    break
                except InvalidToken:
                    pass
            self.assertEqual(got, self.plain[n], n)


class RoundTrip(Base):
    def test_round_trip(self):
        r = self.rotate()
        self.assertFalse(r["resumed"])
        self.assertEqual(r["files"], 5)
        self.assert_done(self.key0)
        # the backup: old key, state.db and the sealed files as they were
        b = r["backup"]
        with open(os.path.join(b, "tvbro.key.old"), "rb") as f:
            self.assertEqual(f.read().strip(), self.key0)
        for n, p in self.plain.items():
            with open(os.path.join(b, "data", n), "rb") as f:
                self.assertEqual(Fernet(self.key0).decrypt(f.read()), p)
        db = sqlite3.connect(os.path.join(b, "data", "state.db"))
        self.assertEqual(db.execute("SELECT value FROM kv").fetchone()[0], "[1]")
        db.close()

    def test_twice(self):
        self.rotate()
        k1 = self.cur_key()
        self.rotate()
        self.assert_done(k1)
        self.assertEqual(len(os.listdir(self.bak)), 2)

    def test_damaged_cache_moved_to_backup(self):
        with open(os.path.join(self.data, "vod.enc"), "wb") as f:
            f.write(b"not a token")
        del self.plain["vod.enc"]
        r = self.rotate()
        self.assertFalse(os.path.exists(os.path.join(self.data, "vod.enc")))
        self.assertTrue(os.path.exists(os.path.join(r["backup"], "unreadable", "vod.enc")))
        self.assert_done(self.key0)

    def test_end_sessions(self):
        r = self.rotate(end_sessions=True)
        with open(os.path.join(self.data, "sign.key"), "rb") as f:
            sk = f.read()
        self.assertEqual(len(sk), 32)
        self.assertNotEqual(sk, b"A" * 32)
        self.assertEqual(sk, sk.strip())
        self.assertFalse(os.path.exists(os.path.join(self.data, "sessions.json")))
        with open(os.path.join(r["backup"], "data", "sign.key"), "rb") as f:
            self.assertEqual(f.read(), b"A" * 32)
        self.assert_done(self.key0)

    def test_cli_and_run_py(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = RK.main(["--data", self.data, "--key", self.key_path, "--backup-dir", self.bak, "--port", "0"])
        self.assertEqual(rc, 0)
        k1 = self.cur_key()
        self.assert_done(self.key0)
        # run.py rotate-key goes to the same command
        p = subprocess.run([sys.executable, "run.py", "rotate-key", "--data", self.data, "--key", self.key_path,
                            "--backup-dir", self.bak, "--port", "0"], cwd=os.path.join(HERE, "..", "server"),
                           capture_output=True, timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr)
        k2 = self.cur_key()
        self.assertNotEqual(k1, k2)
        # no key material, no login in anything printed
        printed = out.getvalue().encode() + err.getvalue().encode() + p.stdout + p.stderr
        for secret in (self.key0, k1, k2, b"s3cret-pw", b"sharepw"):
            self.assertNotIn(secret, printed)
        self.assertIn(b"backup:", printed)


class CrashRecovery(Base):
    def test_crash_at_every_step_then_rerun(self):
        for step in STEPS:
            with self.subTest(step=step):
                self.tearDown()
                self.setUp()

                def crash(s, step=step):
                    if s == step:
                        raise Crash(s)
                with self.assertRaises(Crash):
                    self.rotate(_crash=crash)
                self.assert_recoverable()
                marker = os.path.exists(os.path.join(self.data, SS.MARKER))
                if marker:      # mid-rotation: the server must not start on these files
                    with self.assertRaises(RuntimeError):
                        SS.startup_check(self.data)
                    with self.assertRaises(RuntimeError):
                        SS.SecretStore(self.data)
                if step == "staged":                # nothing swapped yet: rolled back by itself
                    self.assertFalse(marker)
                    self.assertFalse(os.path.exists(self.key_path + ".new"))
                    self.assertEqual(self.cur_key(), self.key0)
                    self.assertEqual(self.plaintexts(self.key0), self.plain)
                r = self.rotate()
                self.assert_done(self.key0)
                if step in ("backup", "marker", "staged"):   # nothing had changed: a fresh rotation
                    self.assertFalse(r["resumed"])
                else:
                    self.assertTrue(r["resumed"])

    def test_crash_twice(self):
        def crash_swap(s):
            if s == "swap":
                raise Crash(s)

        def crash_old(s):
            if s == "oldkept":
                raise Crash(s)
        with self.assertRaises(Crash):
            self.rotate(_crash=crash_swap)
        with self.assertRaises(Crash):
            self.rotate(_crash=crash_old)
        self.assert_recoverable()
        self.rotate()
        self.assert_done(self.key0)
        self.assertEqual(len(os.listdir(self.bak)), 1)       # one rotation, one backup

    def test_stale_staged_copies_are_removed(self):
        with open(os.path.join(self.data, "provider.enc" + RK.STAGE), "wb") as f:
            f.write(b"half")
        self.rotate()
        self.assert_done(self.key0)


class Refusals(Base):
    def unchanged(self):
        self.assertEqual(self.cur_key(), self.key0)
        self.assertEqual(self.plaintexts(self.key0), self.plain)
        self.assertFalse(os.path.exists(os.path.join(self.data, SS.MARKER)))
        self.assertFalse(os.path.exists(self.key_path + ".new"))
        self.assertFalse(os.path.exists(self.bak) and os.listdir(self.bak))

    def test_wrong_key(self):
        with open(self.key_path, "wb") as f:
            f.write(Fernet.generate_key())
        with self.assertRaisesRegex(RK.RotateError, "wrong key"):
            self.rotate()
        with open(self.key_path, "wb") as f:
            f.write(self.key0)
        self.unchanged()

    def test_missing_key(self):
        os.remove(self.key_path)
        with self.assertRaisesRegex(RK.RotateError, "missing"):
            self.rotate()
        with open(self.key_path, "wb") as f:
            f.write(self.key0)
        self.unchanged()

    def test_invalid_key(self):
        for bad in (b"", b"short", b"x" * 44):
            with open(self.key_path, "wb") as f:
                f.write(bad)
            with self.assertRaises(RK.RotateError):
                self.rotate()
        with open(self.key_path, "wb") as f:
            f.write(self.key0)
        self.unchanged()

    def test_login_that_does_not_open(self):
        with open(os.path.join(self.data, "provider-p1.enc"), "wb") as f:
            f.write(Fernet(Fernet.generate_key()).encrypt(b"other"))
        with self.assertRaisesRegex(RK.RotateError, "provider-p1.enc"):
            self.rotate()
        self.assertEqual(self.cur_key(), self.key0)
        self.assertFalse(os.path.exists(os.path.join(self.data, SS.MARKER)))

    def test_server_running(self):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        s.listen(1)
        try:
            with self.assertRaisesRegex(RK.RotateError, "running"):
                self.rotate(port=s.getsockname()[1])
        finally:
            s.close()
        self.unchanged()

    def test_key_from_env_only(self):
        os.environ.pop("TVBRO_KEY_FILE")
        os.environ["TVBRO_KEY"] = self.key0.decode()
        if os.path.exists(RK.DEFAULT_CRED):
            self.skipTest("this machine has a real credential")
        with self.assertRaisesRegex(RK.RotateError, "TVBRO_KEY"):
            RK.default_key_path(self.data)

    def test_server_refuses_wrong_or_missing_key(self):
        SS.startup_check(self.data)                         # the right key: fine
        with open(self.key_path, "wb") as f:
            f.write(Fernet.generate_key())
        with self.assertRaisesRegex(RuntimeError, "wrong key"):
            SS.startup_check(self.data)
        os.environ.pop("TVBRO_KEY_FILE")                    # no key anywhere, encrypted data present
        with self.assertRaisesRegex(RuntimeError, "missing"):
            SS.startup_check(self.data)

    def test_server_process_refuses_mid_rotation(self):
        with open(os.path.join(self.data, SS.MARKER), "w") as f:
            f.write('{"backup": "x"}')
        env = dict(os.environ, TVBRO_DATA=self.data, TVBRO_PORT="0")
        p = subprocess.run([sys.executable, "run.py"], cwd=os.path.join(HERE, "..", "server"), env=env,
                           capture_output=True, timeout=60)
        self.assertEqual(p.returncode, 2)
        self.assertIn(b"rotation is unfinished", p.stderr)


@unittest.skipUnless(POSIX, "file modes are POSIX")
class Permissions(Base):
    def test_modes(self):
        os.chmod(self.cred, 0o700)
        r = self.rotate()
        mode = lambda p: stat.S_IMODE(os.stat(p).st_mode)  # noqa: E731
        self.assertEqual(mode(self.key_path), 0o600)
        self.assertEqual(mode(self.bak), 0o700)
        self.assertEqual(mode(r["backup"]), 0o700)
        for dp, ds, fs in os.walk(self.bak):
            for d in ds:
                self.assertEqual(mode(os.path.join(dp, d)), 0o700, d)
            for f in fs:
                self.assertEqual(mode(os.path.join(dp, f)), 0o600, f)
        for n in RK.sealed_files(self.data):
            self.assertEqual(mode(os.path.join(self.data, n)), 0o600, n)
            self.assertEqual(os.stat(os.path.join(self.data, n)).st_uid, os.getuid())

    def test_loose_backup_dir_is_tightened(self):
        os.makedirs(self.bak, mode=0o755)
        os.chmod(self.bak, 0o755)
        self.rotate()
        self.assertEqual(stat.S_IMODE(os.stat(self.bak).st_mode), 0o700)


if __name__ == "__main__":
    unittest.main()
