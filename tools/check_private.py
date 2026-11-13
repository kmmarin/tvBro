#!/usr/bin/env python3
# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""Fails when files contain things that must not be published: private network addresses, personal
Windows paths, private keys, keystores, local-network host names.

usage: check_private.py [--staged] [--extra FILE] [PATH ...]
  no PATH      every file tracked by git (or, with --staged, the staged files: pre-commit hook)
  --extra FILE more regular expressions, one per line (# comments). tools/private-patterns.txt is
               read automatically when it exists; it is gitignored, so personal names and domains in
               it never reach the repository.
Exit code 1 and a list of file:line: rule when something is found. Matches are not printed in full.
"""
import os, re, subprocess, sys

RULES = [
    # 10.0.2.2 is the Android emulator's fixed alias for the host machine, not a real network
    ("private IPv4 (10/8)", r"(?<![\d.])(?!10\.0\.2\.2(?![\d.]))10\.(?:\d{1,3})\.(?:\d{1,3})\.(?:\d{1,3})(?![\d.])"),
    ("private IPv4 (172.16/12)", r"(?<![\d.])172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}(?![\d.])"),
    ("private IPv4 (192.168/16)", r"(?<![\d.])192\.168\.\d{1,3}\.\d{1,3}(?![\d.])"),
    ("personal Windows path", r"(?i)\b[A-Z]:[\\/]+(?:Users[\\/]+(?!Public\b|<)[A-Za-z0-9._-]+|share[\\/])"),
    ("home directory path", r"/home/(?!user\b|runner\b|<)[a-z][a-z0-9_-]{1,31}/"),
    ("local-network host name", r"(?i)\b[a-z0-9-]+\.(?:lan|home\.arpa|internal|localdomain)\b"),
    ("private key", r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"),
    ("root login to a host", r"\broot@(?![a-z0-9.-]*example\.)[a-z0-9-]+\.[a-z]"),
]
ALLOW_LINE = re.compile(r"check_private: allow")
# Tests legitimately use private addresses and LAN-style names as inputs (e.g. SSRF / address-filter tests).
# There only the private-address and LAN-name rules are skipped; keys, paths and local patterns still apply.
TEST_PATH = re.compile(r"(^|[\\/])(tests?|src[\\/]test)[\\/]")
TEST_EXEMPT = ("private IPv4", "local-network host name")
SKIP_EXT = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".jar", ".apk", ".zip", ".gz", ".so", ".dll", ".ttf", ".woff2"}
BAD_FILES = re.compile(r"(?i)(\.jks|\.keystore|\.p12|keystore\.properties|private-patterns\.txt)$")


def load_extra(path):
    out = []
    if path and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for i, line in enumerate(f, 1):
                line = line.strip()
                if line and not line.startswith("#"):
                    out.append((f"local pattern #{i}", line))
    return out


def files_from_git(staged):
    cmd = ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"] if staged else ["git", "ls-files"]
    return [p for p in subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.splitlines() if p]


def walk(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, dirs, names in os.walk(p):
                dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "node_modules", "__pycache__", ".kotlin")]
                for n in names:
                    yield os.path.join(root, n)
        else:
            yield p


def main(argv):
    staged = "--staged" in argv
    args = [a for a in argv if a != "--staged"]
    extra_file = None
    if "--extra" in args:
        i = args.index("--extra"); extra_file = args[i + 1]; del args[i:i + 2]
    here = os.path.dirname(os.path.abspath(__file__))
    rules = [(n, re.compile(r)) for n, r in RULES]
    for src in (os.path.join(here, "private-patterns.txt"), extra_file):
        rules += [(n, re.compile(r, re.I)) for n, r in load_extra(src)]
    paths = list(walk(args)) if args else files_from_git(staged)
    hits = 0
    for p in paths:
        if BAD_FILES.search(p.replace("\\", "/")) and not p.endswith(".example"):
            print(f"{p}: file must not be published"); hits += 1; continue
        if os.path.splitext(p)[1].lower() in SKIP_EXT or not os.path.isfile(p):
            continue
        is_test = bool(TEST_PATH.search(p))
        try:
            with open(p, encoding="utf-8", errors="replace") as f:
                for ln, line in enumerate(f, 1):
                    if ALLOW_LINE.search(line):
                        continue
                    for name, rx in rules:
                        if is_test and name.startswith(TEST_EXEMPT):
                            continue
                        if any("example" not in m.group(0).lower() for m in rx.finditer(line)):
                            print(f"{p}:{ln}: {name}"); hits += 1
        except OSError as e:
            print(f"{p}: unreadable ({e})"); hits += 1
    if hits:
        print(f"check_private: {hits} finding(s). Replace them with placeholders (example.com, 192.0.2.x), "
              f"or move the file to a gitignored *.local file.")
        return 1
    print(f"check_private: {len(paths)} file(s) clean")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
