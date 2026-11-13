#!/usr/bin/env python3
# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Builds the Raspberry Pi (linux arm64) app image tarball on any OS, keeping Unix file modes.

usage: pack_pi.py <lib dir from :desktop:piLibs> <Temurin aarch64 JRE .tar.gz> <NOTICE> <LICENSE> <out.tar.gz> [docs...]
Layout: tvbro/tvbro.sh, tvbro/lib/*.jar, tvbro/jre/..., tvbro/NOTICE, tvbro/LICENSE, tvbro/<doc names>
(docs: docs/INSTALL.md and docs/THIRD_PARTY.md). libmpv is never packed: the Pi uses the system libmpv2 package.
"""
import io, os, re, sys, tarfile

LAUNCHER = b"""#!/bin/sh
# tvBro launcher (Raspberry Pi / linux arm64). Uses the system libmpv (Debian: sudo apt install libmpv2);
# without it tvBro starts with a "Video library missing" screen.
D="$(cd "$(dirname "$0")" && pwd)"
# AWT talks X11; under a Wayland compositor (labwc) that is XWayland.
[ -z "$DISPLAY" ] && [ -n "$WAYLAND_DISPLAY" ] && export DISPLAY=:0
exec "$D/jre/bin/java" -Xmx768m -Dfile.encoding=UTF-8 -Dtvbro.home="$D" -cp "$D/lib/*" io.github.kmmarin.tvbro.desktop.MainKt "$@"
"""

LIBMPV = re.compile(r"(?i)(^|/)(lib)?mpv[^/]*\.(dll|so(\.[0-9]+)*|dylib)$")


def main(lib, jre, notice, lic, out, *docs):
    names = []
    with tarfile.open(out, "w:gz") as t:
        def add_bytes(name, data, mode):
            ti = tarfile.TarInfo(name); ti.size = len(data); ti.mode = mode; ti.mtime = 0
            t.addfile(ti, io.BytesIO(data)); names.append(name)
        add_bytes("tvbro/tvbro.sh", LAUNCHER, 0o755)
        for f in sorted(os.listdir(lib)):
            with open(os.path.join(lib, f), "rb") as fh:
                add_bytes("tvbro/lib/" + f, fh.read(), 0o644)
        for src, name in [(notice, "NOTICE"), (lic, "LICENSE")] + [(d, os.path.basename(d)) for d in docs]:
            with open(src, "rb") as fh:
                add_bytes("tvbro/" + name, fh.read(), 0o644)
        with tarfile.open(jre, "r:gz") as j:
            for m in j.getmembers():
                parts = m.name.split("/", 1)
                if len(parts) < 2 or not parts[1]:
                    continue
                m2 = tarfile.TarInfo("tvbro/jre/" + parts[1])
                m2.type, m2.mode, m2.mtime, m2.linkname, m2.size = m.type, m.mode, m.mtime, m.linkname, m.size
                t.addfile(m2, j.extractfile(m) if m.isfile() else None); names.append(m2.name)
    bad = [n for n in names if LIBMPV.search(n)]
    if bad:
        os.remove(out)
        sys.exit("libmpv must not be in the Pi tarball: %s" % bad)


if __name__ == "__main__":
    if len(sys.argv) < 6:
        sys.exit(__doc__)
    main(*sys.argv[1:])
