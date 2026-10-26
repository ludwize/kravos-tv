#!/usr/bin/env python3
"""Probe round 2: FAST services (Pluto TV, Samsung TV Plus, Plex, Roku) playlists and the epgshare01 file list."""
import gzip, json, os, re, sys, time, urllib.request, urllib.error
from urllib.parse import urljoin

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/129 Mobile Safari/537.36"
log = open(os.path.join(OUT, "index.txt"), "w")


def fetch(url, headers=None, limit=None, timeout=25):
    h = {"User-Agent": UA}
    h.update(headers or {})
    t = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
            body = r.read(limit) if limit else r.read()
            return r.status, body, r.geturl(), time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:3000], url, time.time() - t
    except Exception as e:
        return -1, str(e).encode(), url, time.time() - t


def text(b):
    if b[:2] == b"\x1f\x8b":
        try:
            b = gzip.decompress(b)
        except Exception:
            pass
    return b.decode("utf-8", "replace")


res = {}
cands = [
    ("mjh_root", "https://i.mjh.nz/"),
    ("mjh_pluto_dir", "https://i.mjh.nz/PlutoTV/"),
    ("mjh_samsung_dir", "https://i.mjh.nz/SamsungTVPlus/"),
    ("mjh_pluto_us", "https://i.mjh.nz/PlutoTV/us.m3u8"),
    ("mjh_pluto_de", "https://i.mjh.nz/PlutoTV/de.m3u8"),
    ("mjh_pluto_gb", "https://i.mjh.nz/PlutoTV/gb.m3u8"),
    ("mjh_pluto_ca", "https://i.mjh.nz/PlutoTV/ca.m3u8"),
    ("mjh_samsung_us", "https://i.mjh.nz/SamsungTVPlus/us.m3u8"),
    ("mjh_samsung_de", "https://i.mjh.nz/SamsungTVPlus/de.m3u8"),
    ("mjh_samsung_gb", "https://i.mjh.nz/SamsungTVPlus/gb.m3u8"),
    ("mjh_samsung_app", "https://i.mjh.nz/SamsungTVPlus/.app.json"),
    ("mjh_pluto_app", "https://i.mjh.nz/PlutoTV/.app.json"),
    ("mjh_plex", "https://i.mjh.nz/Plex/all.m3u8"),
    ("mjh_plex_us", "https://i.mjh.nz/Plex/us.m3u8"),
    ("mjh_roku", "https://i.mjh.nz/Roku/all.m3u8"),
    ("mjh_rakuten", "https://i.mjh.nz/Rakuten/all.m3u8"),
    ("pluto_api_boot", "https://boot.pluto.tv/v4/start?appName=web&appVersion=9&deviceVersion=1&deviceModel=web&deviceMake=chrome&deviceType=web&clientID=00000000-0000-0000-0000-000000000000&clientModelNumber=1.0.0&serverSideAds=false"),
    ("epgshare_dir", "https://epgshare01.online/epgshare01/"),
    ("epgshare_home", "https://epgshare01.online/"),
]
for name, url in cands:
    code, body, final, dt = fetch(url, limit=30_000_000)
    t = text(body) if code == 200 else body.decode("utf-8", "replace")
    info = {"code": code, "bytes": len(body), "final": final, "sec": round(dt, 1), "head": t[:2500]}
    if "#EXTM3U" in t[:100]:
        info["entries"] = t.count("#EXTINF")
        urls = [l for l in t.splitlines() if l.startswith("http")]
        info["urls_sample"] = urls[:5]
        info["groups"] = sorted(set(re.findall(r'group-title="([^"]*)"', t)))[:80]
        info["header"] = t.splitlines()[0][:400]
        open(os.path.join(OUT, name + ".m3u"), "w").write(t[:300000])
        # test 3 streams from it
        tests = []
        for u in urls[:3]:
            c2, b2, f2, d2 = fetch(u, limit=100000, timeout=15)
            tt = b2.decode("utf-8", "replace")
            hs = [int(h) for h in re.findall(r"RESOLUTION=\d+x(\d+)", tt)]
            tests.append({"url": u[:150], "code": c2, "final": f2[:200], "max_h": max(hs) if hs else None, "head": tt[:300]})
        info["tests"] = tests
    if name.endswith("_dir") or name.endswith("root") or name == "epgshare_home":
        info["links"] = sorted(set(re.findall(r'href="([^"?#]+)"', t)))[:600]
    res[name] = info
    log.write(f"{code}  {len(body):>10}  {dt:5.1f}s  {name}  {url}  entries={info.get('entries')}\n")
    log.flush()

json.dump(res, open(os.path.join(OUT, "fast.json"), "w"), indent=1, ensure_ascii=False)
log.close()
print(open(os.path.join(OUT, "index.txt")).read())
