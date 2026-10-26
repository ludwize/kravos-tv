#!/usr/bin/env python3
"""Probe round 4: n1nova.live (United Media) player + K1/RTV official embeds. Saves raw HTML/JS and tests found streams."""
import json, os, re, sys, time, urllib.request, urllib.error
from urllib.parse import urljoin

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
log = open(os.path.join(OUT, "index.txt"), "w")


def fetch(url, headers=None, limit=4_000_000, timeout=20):
    h = {"User-Agent": UA}
    h.update(headers or {})
    t = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
            return r.status, r.read(limit).decode("utf-8", "replace"), r.geturl(), time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:3000].decode("utf-8", "replace"), url, time.time() - t
    except Exception as e:
        return -1, str(e), url, time.time() - t


PAT = re.compile(r'https?:\\?/\\?/[^"\'\s<>`]+')
streams = set()
pages = ["https://n1nova.live/", "https://n1nova.live/n1-uzivo.html", "https://n1nova.live/novas-uzivo.html",
         "https://cdn.maksnet.tv/em/minacordmedia-secure/?streamname=k1studio&bg=bg_cover.jpg"]
seen = set()
i = 0
while pages and i < 25:
    u = pages.pop(0)
    if u in seen:
        continue
    seen.add(u)
    i += 1
    code, body, final, dt = fetch(u, {"Referer": "https://n1nova.live/"})
    name = re.sub(r"[^A-Za-z0-9]+", "_", u)[-80:]
    open(os.path.join(OUT, name + ".txt"), "w").write(body[:400000])
    log.write(f"{code} {len(body):>8} {u}\n")
    for m in PAT.findall(body):
        m = m.replace("\\/", "/").rstrip("\\);,")
        if re.search(r"\.m3u8|\.mpd|united\.cloud|/stream\?|player=m3u8|hls", m):
            streams.add(m)
    # follow scripts / iframes of the n1nova pages
    if "n1nova" in u or "maksnet" in u:
        for s in re.findall(r'<(?:script|iframe)[^>]+src="([^"]+)"', body):
            full = urljoin(final, s)
            if "googletagmanager" not in full and "google-analytics" not in full:
                pages.append(full)

log.write("\nSTREAMS:\n")
tests = []
for s in sorted(streams)[:30]:
    code, body, final, dt = fetch(s, {"Referer": "https://n1nova.live/", "Origin": "https://n1nova.live"}, limit=200000, timeout=15)
    hs = [int(x) for x in re.findall(r"RESOLUTION=\d+x(\d+)", body)]
    tests.append({"url": s, "code": code, "final": final, "maxH": max(hs) if hs else None, "head": body[:400]})
    log.write(f"  {code} h={max(hs) if hs else '-'} {s[:220]}\n")
json.dump(tests, open(os.path.join(OUT, "streams.json"), "w"), indent=1)
log.close()
print(open(os.path.join(OUT, "index.txt")).read())
