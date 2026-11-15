import sys
sys.path.insert(0, sys.argv[1])
import tvbro_core as t
print("version", t.version())
ch, epg = t.parse_m3u('#EXTM3U url-tvg="http://example.com/g.xml"\n#EXTINF:-1 tvg-id="a",One, Two\nhttp://example.com/1.ts\n', "p0")
print(ch, epg)
assert ch[0][1] == "One, Two" and epg == ["http://example.com/g.xml"]
assert not t.verify_password("x", "bad")
try:
    t.check_url("http://127.0.0.1/")
    raise SystemExit("not blocked")
except ValueError as e:
    print("blocked ok:", e)
print("PYO3 SMOKE OK")
