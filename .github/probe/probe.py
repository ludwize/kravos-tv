#!/usr/bin/env python3
"""Probe round 3: official free live streams of Serbian broadcasters (their own sites + YouTube live)."""
import json, os, re, sys, time, urllib.request, urllib.error

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
log = open(os.path.join(OUT, "index.txt"), "w")


def fetch(url, headers=None, limit=3_000_000, timeout=20):
    h = {"User-Agent": UA, "Accept-Language": "sr,en;q=0.8"}
    h.update(headers or {})
    t = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
            return r.status, r.read(limit).decode("utf-8", "replace"), r.geturl(), time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:2000].decode("utf-8", "replace"), url, time.time() - t
    except Exception as e:
        return -1, str(e), url, time.time() - t


SITES = {
    "rts": ["https://www.rts.rs/page/tv/sr/live.html", "https://www.rts.rs/live/", "https://rtsplaneta.rs/", "https://www.rts.rs/tv/"],
    "rtv": ["https://media.rtv.rs/sr_lat/uzivo/rtv1", "https://www.rtv.rs/sr_lat/uzivo", "https://media.rtv.rs/sr_lat/live"],
    "n1": ["https://n1info.rs/uzivo/", "https://n1info.rs/live/", "https://n1info.rs/"],
    "euronews": ["https://www.euronews.rs/uzivo", "https://www.euronews.rs/"],
    "informer": ["https://informer.rs/informer-tv", "https://informer.rs/uzivo", "https://informer.rs/"],
    "kurir": ["https://www.kurir.rs/kurir-tv", "https://www.kurir.rs/uzivo", "https://www.kurir.rs/"],
    "blic": ["https://www.blic.rs/blic-tv", "https://www.blic.rs/uzivo"],
    "prva": ["https://www.prva.rs/uzivo", "https://www.prva.rs/live", "https://www.prva.rs/"],
    "b92": ["https://www.b92.net/uzivo", "https://o2tv.rs/uzivo", "https://www.b92.net/"],
    "pink": ["https://www.pink.rs/uzivo", "https://pink.rs/live", "https://www.pink.rs/"],
    "happy": ["https://www.happytv.rs/uzivo", "https://www.happytv.rs/live", "https://www.happytv.rs/"],
    "studiob": ["https://www.studiob.rs/uzivo", "https://www.studiob.rs/live", "https://www.studiob.rs/"],
    "nova": ["https://nova.rs/uzivo", "https://nova.rs/nova-tv-uzivo"],
    "insajder": ["https://insajder.net/uzivo", "https://insajder.net/"],
    "k1": ["https://k1info.rs/uzivo", "https://k1info.rs/"],
    "tvhram": ["https://tvhram.rs/uzivo"],
    "rtvbg": ["https://www.tvbeograd.rs/uzivo"],
}
PAT = re.compile(r'https?:\\?/\\?/[^"\'\s<>]+?\.(?:m3u8|mpd)[^"\'\s<>]*')
YT = re.compile(r'(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=|live_stream\?channel=)|youtu\.be/)([A-Za-z0-9_-]{11}|UC[A-Za-z0-9_-]{22})')
IFR = re.compile(r'<iframe[^>]+src="([^"]+)"', re.I)
res = {}
for name, urls in SITES.items():
    out = []
    for u in urls:
        code, body, final, dt = fetch(u)
        found = sorted(set(m.replace("\\/", "/") for m in PAT.findall(body)))[:20]
        yts = sorted(set(YT.findall(body)))[:10]
        ifr = sorted(set(IFR.findall(body)))[:15]
        scripts = sorted(set(re.findall(r'<script[^>]+src="([^"]+)"', body)))[:25]
        out.append({"url": u, "code": code, "final": final, "len": len(body), "m3u8": found, "youtube": yts, "iframes": ifr,
                    "players": sorted(set(re.findall(r'(jwplayer|videojs|video\.js|flowplayer|clappr|hls\.js|bitmovin|theoplayer|shaka|dash\.js|vimeo|brightcove|player\.[a-z]+\.[a-z]+)', body, re.I)))[:10],
                    "scripts": scripts})
        log.write(f"{code} {len(body):>8} {name} {u} m3u8={len(found)} yt={len(yts)} ifr={len(ifr)}\n")
        log.flush()
    res[name] = out
json.dump(res, open(os.path.join(OUT, "sites.json"), "w"), indent=1, ensure_ascii=False)

HANDLES = ["N1info", "N1Srbija", "n1info", "euronewssrbija", "EuronewsSrbija", "InformerTV", "informer", "informer_rs", "KurirTV", "kurirtv",
           "blictv", "BlicTV", "NovaS", "novas", "insajder", "InsajderTV", "rtsrs", "RTSrs", "rtsmedia", "PrvaTV", "prvatv", "TVPrva",
           "rtvpink", "PinkTV", "TVHappy", "happytv", "HappyTV", "tvb92", "B92", "o2televizija", "rtvvojvodina", "RTVVojvodina",
           "studiob", "StudioB", "k1televizija", "K1Info", "tvinsajder", "TVInfo", "NovaRS", "RTSTV", "rtsplaneta"]
yt = {}
for h in HANDLES:
    code, body, final, dt = fetch(f"https://www.youtube.com/@{h}/live", {"Cookie": "CONSENT=YES+1; SOCS=CAI"})
    cid = re.search(r'"(?:externalId|channelId|browseId)":"(UC[A-Za-z0-9_-]{22})"', body)
    title = re.search(r'<meta property="og:title" content="([^"]*)"', body)
    vid = re.search(r'"videoId":"([A-Za-z0-9_-]{11})"', body)
    live = '"isLive":true' in body or '"isLiveNow":true' in body
    canon = re.search(r'<link rel="canonical" href="([^"]+)"', body)
    yt[h] = {"code": code, "channelId": cid.group(1) if cid else None, "title": title.group(1) if title else None,
             "videoId": vid.group(1) if vid else None, "live": live, "canonical": canon.group(1) if canon else None}
    log.write(f"yt {code} @{h} {yt[h]['channelId']} live={live} {yt[h]['title']}\n")
    log.flush()
json.dump(yt, open(os.path.join(OUT, "youtube.json"), "w"), indent=1, ensure_ascii=False)
log.close()
print(open(os.path.join(OUT, "index.txt")).read())
