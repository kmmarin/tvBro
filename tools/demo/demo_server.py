#!/usr/bin/env python3
# Copyright 2026 The tvBro authors
# SPDX-License-Identifier: Apache-2.0
"""A fully fictional demo IPTV provider for screenshots, demos and manual tests.

Everything is made up: channel names, programmes, films, series, artwork (generated here) and video
(an ffmpeg test pattern). Nothing refers to real broadcasters, films or people.

It speaks the Xtream-style API (player_api.php, xmltv.php, /live, /movie, /series, /timeshift) and also
serves an M3U playlist, so every tvBro platform can use it:

  python tools/demo/demo_server.py [--port 8790] [--host-name 10.0.2.2]
    Xtream: server http://<host>:8790  user demo  password demo
    M3U:    http://<host>:8790/get.php?username=demo&password=demo&type=m3u_plus   guide: /xmltv.php?...

Needs ffmpeg on PATH for the artwork and the test video (cached in tools/demo/cache, gitignored).
Stops with Ctrl+C.
"""
import argparse, datetime as dt, hashlib, json, os, random, shutil, subprocess, sys, threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
USER = PASS = "demo"

GROUPS = [
    ("1", "News", ["Northwind News", "Harbor 24", "Meridian World", "Civic Desk", "Daybreak Live"]),
    ("2", "Sport", ["Pitchside", "Velocity Sport", "Courtline", "Summit Outdoors", "Arena Plus"]),
    ("3", "Movies", ["Silver Screen", "Midnight Reel", "Cinema Nova", "Matinee"]),
    ("4", "Kids", ["Tiny Planet", "Doodle TV", "Sprout Lane"]),
    ("5", "Documentary", ["Field Notes", "Deep Blue", "Atlas Explorer", "Time Capsule"]),
    ("6", "Music", ["Tempo", "Vinyl Lounge", "Chorus"]),
    ("7", "Lifestyle", ["Kitchen Table", "Open House", "Green Thumb"]),
]
PALETTE = ["0x2E7D9A", "0x8E44AD", "0xC0392B", "0x16A085", "0xD35400", "0x2C3E50", "0x27AE60", "0x7F8C8D",
           "0x1F618D", "0xB03A2E", "0x117A65", "0x6C3483", "0xA04000", "0x283747"]
SHOW_WORDS = {
    "News": ["Morning Briefing", "The Midday Report", "World Tonight", "Business Hour", "Weather Watch", "Late Edition"],
    "Sport": ["Matchday Live", "The Final Whistle", "Track and Field Weekly", "Court Report", "Mountain Trails", "Goal Rush"],
    "Movies": ["The Lighthouse Keeper", "Paper Kites", "A Quiet Harbor", "Night Train to Alder", "The Last Orchard", "Glass Rivers"],
    "Kids": ["Pip and the Clouds", "Robot Garden", "Lulu's Lantern", "Sock Puppet Science", "The Busy Bees"],
    "Documentary": ["Secrets of the Reef", "Desert Bloom", "Engines of Change", "The Salt Road", "Winter Wolves", "Ancient Skies"],
    "Music": ["Top Tracks", "Acoustic Sessions", "Live at the Hall", "Retro Rewind", "Chill Mix"],
    "Lifestyle": ["Sunday Roast", "Small Spaces", "Garden Rescue", "Market Day", "Fix It Friday"],
}
FILM_CATS = [("10", "Drama"), ("11", "Adventure"), ("12", "Comedy"), ("13", "Family")]
FILMS = [
    ("The Lighthouse Keeper", "10", 2021, "A retired keeper restores an old lighthouse and finds letters hidden in its walls."),
    ("Paper Kites", "13", 2019, "Two siblings build kites for a seaside contest and learn to work together."),
    ("Night Train to Alder", "11", 2022, "A conductor and a lost traveller race to reach a mountain village before the snow."),
    ("A Quiet Harbor", "10", 2020, "A fisherman's daughter takes over the family boat for one stormy season."),
    ("The Last Orchard", "10", 2018, "Neighbours unite to save the last apple orchard in their valley."),
    ("Glass Rivers", "11", 2023, "Cartographers map a frozen river system that changes every winter."),
    ("Second Helpings", "12", 2021, "A chef with no sense of taste enters a cooking competition."),
    ("Uncle Bert's Balloon", "13", 2017, "A homemade hot-air balloon lifts a family's summer holiday."),
    ("The Clockmaker's Apprentice", "11", 2024, "An apprentice must repair the town clock before the festival."),
    ("Wrong Number", "12", 2020, "Two strangers keep calling each other by mistake and become friends."),
    ("Copper Canyon", "11", 2019, "Rangers guide a group of students through a desert canyon."),
    ("Snowed In", "12", 2022, "Five relatives, one cabin, a week of snow."),
    ("Small Wonders", "13", 2023, "A school science fair goes delightfully wrong."),
    ("Echoes of Spring", "10", 2021, "A pianist returns to her hometown to play one last concert."),
    ("Midnight Market", "12", 2018, "Stallholders at a night market try to win the annual prize."),
    ("The Map Room", "11", 2020, "A librarian discovers a map room beneath the old library."),
]
SERIES_CATS = [("20", "Drama series"), ("21", "Documentary series"), ("22", "Kids series")]
SERIES = [
    ("Harbor Lights", "20", 2022, 3, 6, "Life and secrets in a small harbour town."),
    ("The Greenhouse", "20", 2021, 2, 8, "A family runs a struggling plant nursery."),
    ("Wild Coasts", "21", 2023, 2, 5, "The animals and people of rugged coastlines."),
    ("Robot Garden", "22", 2020, 3, 10, "Two robots learn how to grow a garden."),
    ("Station Eleven Road", "20", 2024, 1, 8, "Commuters whose lives cross at one bus stop."),
    ("Engines of Change", "21", 2019, 2, 6, "How simple machines changed everyday life."),
]


def ffmpeg(*args):
    exe = shutil.which("ffmpeg")
    if not exe:
        return False
    r = subprocess.run([exe, "-hide_banner", "-loglevel", "error", "-y", *args], capture_output=True)
    return r.returncode == 0


def font_opt(font):
    if not font:
        return ""
    f = font.replace("\\", "/").replace(":", "\\:")
    return f"fontfile='{f}':"


def text_escape(t):
    return t.replace("\\", "\\\\").replace("'", "\u2019").replace(":", "\\:").replace(",", "\\,").replace("%", "\\%")


def wrap(title, width=14):
    words, lines, cur = title.split(), [], ""
    for w in words:
        if cur and len(cur) + 1 + len(w) > width:
            lines.append(cur); cur = w
        else:
            cur = (cur + " " + w).strip()
    lines.append(cur)
    return lines[:3]


def make_image(path, w, h, color, title, sub, font, size):
    if os.path.exists(path):
        return
    fo = font_opt(font)
    lines = [title] if size >= 80 else wrap(title)
    parts = [f"drawbox=x=0:y=ih*0.70:w=iw:h=ih*0.30:color=black@0.30:t=fill"]
    top = h * (0.5 if size >= 80 else 0.36) - (len(lines) - 1) * size * 0.6
    for i, ln in enumerate(lines):
        y = int(top + i * size * 1.2)
        parts.append(f"drawtext={fo}text='{text_escape(ln)}':fontcolor=white:fontsize={size}:x=(w-text_w)/2:y={y}-text_h/2")
    if sub:
        parts.append(f"drawtext={fo}text='{text_escape(sub)}':fontcolor=white@0.85:fontsize=22:x=(w-text_w)/2:y=h*0.85-text_h/2")
    c2 = "0x0E1116"
    ffmpeg("-f", "lavfi", "-i", f"gradients=s={w}x{h}:c0={color}:c1={c2}:x0=0:y0=0:x1={w}:y1={h}:nb_colors=2:speed=0.00001",
           "-vf", ",".join(parts), "-frames:v", "1", path) or \
        ffmpeg("-f", "lavfi", "-i", f"color=c={color}:s={w}x{h}", "-vf", ",".join(parts), "-frames:v", "1", path)

def initials(name):
    return "".join(p[0] for p in name.split()[:2]).upper()


def build_assets(font):
    os.makedirs(CACHE, exist_ok=True)
    n = 0
    for _, _, chans in GROUPS:
        for c in chans:
            make_image(os.path.join(CACHE, f"logo-{slug(c)}.png"), 256, 256, PALETTE[n % len(PALETTE)], initials(c), "", font, 110)
            n += 1
    for i, (t, _, y, _) in enumerate(FILMS):
        make_image(os.path.join(CACHE, f"poster-film-{i}.png"), 300, 450, PALETTE[(i * 5) % len(PALETTE)], t, str(y), font, 36)
    for i, (t, _, y, *_r) in enumerate(SERIES):
        make_image(os.path.join(CACHE, f"poster-series-{i}.png"), 300, 450, PALETTE[(i * 3 + 1) % len(PALETTE)], t, f"Series - {y}", font, 36)
    for i, (t, _, y, seasons, eps, _p) in enumerate(SERIES):
        for s in range(1, seasons + 1):
            for e in range(1, eps + 1):
                make_image(os.path.join(CACHE, f"still-{i}-{s}-{e}.png"), 480, 270, PALETTE[(i * 3 + s + e) % len(PALETTE)], f"Chapter {e}", f"{t} - S{s} E{e}", font, 40)
    video = os.path.join(CACHE, "demo.ts")
    if not os.path.exists(video):
        fo = font_opt(font)
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=1280x720:r=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
               "-t", "120", "-vf", f"drawtext={fo}text='tvBro demo':fontcolor=white:fontsize=64:x=(w-text_w)/2:y=h-120:box=1:boxcolor=black@0.5",
               "-c:v", "libx264", "-preset", "veryfast", "-g", "50", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-f", "mpegts", video)
    mp4 = os.path.join(CACHE, "demo.mp4")
    if not os.path.exists(mp4) and os.path.exists(video):
        ffmpeg("-i", video, "-c", "copy", "-movflags", "+faststart", mp4)


def slug(s):
    return "".join(ch.lower() if ch.isalnum() else "-" for ch in s).strip("-")


class Catalogue:
    def __init__(self, base):
        self.base = base
        self.channels = []
        num = 1
        for cid, gname, chans in GROUPS:
            for c in chans:
                self.channels.append({"num": num, "name": c, "stream_id": 1000 + num, "group": gname, "category_id": cid,
                                      "epg_channel_id": slug(c) + ".demo", "logo": f"{base}/img/logo-{slug(c)}.png",
                                      "archive": 1 if num % 3 else 0})
                num += 1

    def programmes(self):
        now = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0)
        start0 = (now - dt.timedelta(hours=12)).replace(minute=0)
        end = now + dt.timedelta(hours=24)
        out = []
        for c in self.channels:
            rnd = random.Random(c["stream_id"])
            names = SHOW_WORDS[c["group"]]
            t = start0
            ep = rnd.randint(1, 20)
            while t < end:
                mins = rnd.choice([30, 30, 60, 60, 90, 120])
                title = rnd.choice(names)
                ep += 1
                out.append((c, t, t + dt.timedelta(minutes=mins), title, ep,
                            f"{title}: a made-up programme on {c['name']} for the tvBro demo. Episode {ep}."))
                t += dt.timedelta(minutes=mins)
        return out

    def xmltv(self):
        f = lambda t: t.strftime("%Y%m%d%H%M%S +0000")
        s = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="tvBro demo">']
        for c in self.channels:
            s.append(f'<channel id="{c["epg_channel_id"]}"><display-name>{escape(c["name"])}</display-name><icon src="{c["logo"]}"/></channel>')
        for c, a, b, title, ep, desc in self.programmes():
            s.append(f'<programme start="{f(a)}" stop="{f(b)}" channel="{c["epg_channel_id"]}"><title>{escape(title)}</title>'
                     f'<desc>{escape(desc)}</desc><episode-num system="onscreen">S1 E{ep}</episode-num></programme>')
        s.append("</tv>")
        return "\n".join(s).encode()

    def m3u(self):
        b = self.base
        s = [f'#EXTM3U url-tvg="{b}/xmltv.php?username={USER}&password={PASS}"']
        for c in self.channels:
            cu = f' catchup="xc" catchup-days="3"' if c["archive"] else ""
            s.append(f'#EXTINF:-1 tvg-id="{c["epg_channel_id"]}" tvg-name="{c["name"]}" tvg-logo="{c["logo"]}" tvg-chno="{c["num"]}" group-title="{c["group"]}"{cu},{c["name"]}')
            s.append(f"{b}/live/{USER}/{PASS}/{c['stream_id']}.ts")
        for i, (t, cat, y, _) in enumerate(FILMS):
            s.append(f'#EXTINF:-1 tvg-logo="{b}/img/poster-film-{i}.png" group-title="Films | {dict(FILM_CATS)[cat]}",{t} ({y})')
            s.append(f"{b}/movie/{USER}/{PASS}/{2000 + i}.mp4")
        return ("\n".join(s) + "\n").encode()

    def api(self, q):
        a = q.get("action", [""])[0]
        b = self.base
        if not a:
            now = int(dt.datetime.now().timestamp())
            return {"user_info": {"username": USER, "password": PASS, "auth": 1, "status": "Active", "exp_date": str(now + 365 * 86400),
                                  "is_trial": "0", "active_cons": "0", "max_connections": "2", "allowed_output_formats": ["ts", "m3u8"]},
                    "server_info": {"url": "demo", "port": "8790", "server_protocol": "http", "timezone": "UTC", "timestamp_now": now}}
        if a == "get_live_categories":
            return [{"category_id": cid, "category_name": g, "parent_id": 0} for cid, g, _ in GROUPS]
        if a == "get_live_streams":
            return [{"num": c["num"], "name": c["name"], "stream_type": "live", "stream_id": c["stream_id"], "stream_icon": c["logo"],
                     "epg_channel_id": c["epg_channel_id"], "category_id": c["category_id"], "tv_archive": c["archive"],
                     "tv_archive_duration": 3 if c["archive"] else 0} for c in self.channels]
        if a == "get_vod_categories":
            return [{"category_id": i, "category_name": n, "parent_id": 0} for i, n in FILM_CATS]
        if a == "get_vod_streams":
            cat = q.get("category_id", [None])[0]
            return [{"num": i + 1, "name": t, "stream_type": "movie", "stream_id": 2000 + i, "stream_icon": f"{b}/img/poster-film-{i}.png",
                     "rating": str(round(6 + (i % 7) * 0.5, 1)), "category_id": c, "container_extension": "mp4", "added": "1700000000"}
                    for i, (t, c, y, _) in enumerate(FILMS) if not cat or cat == c]
        if a == "get_vod_info":
            i = int(q.get("vod_id", ["2000"])[0]) - 2000
            t, c, y, plot = FILMS[max(0, min(i, len(FILMS) - 1))]
            return {"info": {"name": t, "plot": plot, "releasedate": f"{y}-05-01", "rating": "7.5", "genre": dict(FILM_CATS)[c],
                             "cast": "A. Example, B. Sample, C. Placeholder", "director": "D. Imaginary", "duration": "01:42:00",
                             "movie_image": f"{b}/img/poster-film-{i}.png", "backdrop_path": [f"{b}/img/poster-film-{i}.png"]},
                    "movie_data": {"stream_id": 2000 + i, "name": t, "container_extension": "mp4"}}
        if a == "get_series_categories":
            return [{"category_id": i, "category_name": n, "parent_id": 0} for i, n in SERIES_CATS]
        if a == "get_series":
            cat = q.get("category_id", [None])[0]
            return [{"num": i + 1, "name": t, "series_id": 3000 + i, "cover": f"{b}/img/poster-series-{i}.png", "plot": plot,
                     "releaseDate": f"{y}-01-01", "rating": "8.0", "category_id": c}
                    for i, (t, c, y, _s, _e, plot) in enumerate(SERIES) if not cat or cat == c]
        if a == "get_series_info":
            i = int(q.get("series_id", ["3000"])[0]) - 3000
            t, c, y, seasons, eps, plot = SERIES[max(0, min(i, len(SERIES) - 1))]
            episodes = {}
            for s in range(1, seasons + 1):
                episodes[str(s)] = [{"id": str(40000 + i * 1000 + s * 100 + e), "episode_num": e, "season": s,
                                     "title": f"{t} - S{s:02d}E{e:02d} - Chapter {e}", "container_extension": "mp4",
                                     "info": {"plot": f"Chapter {e} of season {s}. A made-up episode for the demo.", "duration": "00:44:00",
                                              "movie_image": f"{b}/img/still-{i}-{s}-{e}.png"}} for e in range(1, eps + 1)]
            return {"info": {"name": t, "plot": plot, "cover": f"{b}/img/poster-series-{i}.png", "releaseDate": f"{y}-01-01",
                             "genre": dict(SERIES_CATS)[c], "cast": "E. Fictional, F. Invented", "rating": "8.0",
                             "backdrop_path": [f"{b}/img/poster-series-{i}.png"]},
                    "seasons": [{"season_number": s, "name": f"Season {s}"} for s in range(1, seasons + 1)], "episodes": episodes}
        return []


def handler_for(default_base):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, code, body, ctype):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def file(self, path, ctype):
            if not os.path.exists(path):
                return self.send(404, b"not found", "text/plain")
            with open(path, "rb") as f:
                self.send(200, f.read(), ctype)

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            host = self.headers.get("Host")
            cat = Catalogue(f"http://{host}" if host else default_base)  # artwork URLs use the address the client used
            u = urlparse(self.path)
            q = parse_qs(u.query)
            p = u.path
            if p == "/player_api.php":
                if q.get("username", [""])[0] != USER or q.get("password", [""])[0] != PASS:
                    return self.send(200, json.dumps({"user_info": {"auth": 0}}).encode(), "application/json")
                return self.send(200, json.dumps(cat.api(q)).encode(), "application/json")
            if p == "/xmltv.php":
                return self.send(200, cat.xmltv(), "application/xml")
            if p in ("/get.php", "/playlist.m3u"):
                return self.send(200, cat.m3u(), "audio/x-mpegurl")
            if p.startswith("/img/"):
                name = os.path.basename(p)
                return self.file(os.path.join(CACHE, name), "image/png")
            if p.startswith(("/live/", "/timeshift/")):
                return self.file(os.path.join(CACHE, "demo.ts"), "video/mp2t")
            if p.startswith(("/movie/", "/series/")):
                return self.file(os.path.join(CACHE, "demo.mp4"), "video/mp4")
            if p == "/":
                return self.send(200, b"tvBro demo provider (fictional). Xtream user demo / password demo.\n", "text/plain")
            return self.send(404, b"not found", "text/plain")
    return H


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8790)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--host-name", default="127.0.0.1", help="host name the clients use (emulator: 10.0.2.2)")
    ap.add_argument("--font", default=next((f for f in ("C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf",
                                                        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf") if os.path.exists(f)), ""))
    ap.add_argument("--assets-only", action="store_true", help="only generate the artwork and video, then exit")
    a = ap.parse_args()
    build_assets(a.font)
    if a.assets_only:
        return
    srv = ThreadingHTTPServer((a.bind, a.port), handler_for(f"http://{a.host_name}:{a.port}"))
    print(f"demo provider on http://{a.host_name}:{a.port}  (Xtream user {USER} / password {PASS}); Ctrl+C stops", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
