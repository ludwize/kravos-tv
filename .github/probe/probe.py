#!/usr/bin/env python3
"""Probe candidate TV sources from a GitHub runner.

1. iptv-org API (channels/streams/feeds/logos/countries/categories/guides/blocklist) - shapes + counts.
2. For focus countries, actually open each stream: HTTP status, master vs media playlist,
   best RESOLUTION / BANDWIDTH, and whether the first segment downloads.
3. Candidate EPG (XMLTV) sources.
"""
import gzip, json, os, re, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/129 Mobile Safari/537.36"
log = open(os.path.join(OUT, "index.txt"), "w")


def fetch(url, headers=None, limit=None, timeout=20):
    h = {"User-Agent": UA}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read(limit) if limit else r.read()
            return r.status, body, r.geturl(), time.time() - t, dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:2000], url, time.time() - t, {}
    except Exception as e:
        return -1, str(e).encode(), url, time.time() - t, {}


def save_json(name, url, keep_sample=True):
    code, body, _, dt, hdr = fetch(url, timeout=60)
    log.write(f"{code}  {len(body):>10}  {dt:5.1f}s  {name}  {url}\n")
    log.flush()
    if code != 200:
        return None
    try:
        data = json.loads(body)
    except Exception:
        return None
    if keep_sample and isinstance(data, list):
        json.dump({"count": len(data), "sample": data[:5]}, open(os.path.join(OUT, f"api_{name}.json"), "w"), indent=1, ensure_ascii=False)
    return data


API = "https://iptv-org.github.io/api"
channels = save_json("channels", f"{API}/channels.json") or []
streams = save_json("streams", f"{API}/streams.json") or []
feeds = save_json("feeds", f"{API}/feeds.json") or []
logos = save_json("logos", f"{API}/logos.json") or []
countries = save_json("countries", f"{API}/countries.json") or []
categories = save_json("categories", f"{API}/categories.json") or []
guides = save_json("guides", f"{API}/guides.json") or []
blocklist = save_json("blocklist", f"{API}/blocklist.json") or []
save_json("languages", f"{API}/languages.json")
save_json("regions", f"{API}/regions.json")

# playlist variants
for name, url in [
    ("pl_rs", "https://iptv-org.github.io/iptv/countries/rs.m3u"),
    ("pl_index", "https://iptv-org.github.io/iptv/index.m3u"),
]:
    code, body, _, dt, _ = fetch(url, timeout=60)
    log.write(f"{code}  {len(body):>10}  {dt:5.1f}s  {name}  {url}\n")
    if code == 200:
        open(os.path.join(OUT, f"{name}.m3u"), "wb").write(body[:400000])

ch_by_id = {c.get("id"): c for c in channels}
blocked = {b.get("channel") for b in blocklist}

# stats per country
stats = {}
for s in streams:
    c = ch_by_id.get(s.get("channel"))
    cc = (c or {}).get("country", "??")
    stats[cc] = stats.get(cc, 0) + 1
json.dump(dict(sorted(stats.items(), key=lambda kv: -kv[1])), open(os.path.join(OUT, "streams_per_country.json"), "w"), indent=1)

# keep compact full list of channels with streams for focus countries for local inspection
FOCUS = ["RS", "BA", "ME", "HR", "MK", "SI"]
SAMPLE = {"DE": 25, "GB": 25, "US": 40, "CA": 20, "FR": 15, "IT": 15, "ES": 15, "AT": 10, "GR": 10, "NL": 10}


def resolution_of(text, base):
    """Return (best_height, best_bandwidth, variants, media_url) for an m3u8 body."""
    best_h, best_bw, variants, media = 0, 0, 0, None
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        if ln.startswith("#EXT-X-STREAM-INF"):
            variants += 1
            m = re.search(r"RESOLUTION=(\d+)x(\d+)", ln)
            h = int(m.group(2)) if m else 0
            b = re.search(r"[^-]BANDWIDTH=(\d+)", ln)
            bw = int(b.group(1)) if b else 0
            nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
            if (h, bw) > (best_h, best_bw):
                best_h, best_bw = h, bw
                media = urljoin(base, nxt)
    return best_h, best_bw, variants, media


def probe_stream(s):
    url = s["url"]
    hdr = {}
    if s.get("referrer"):
        hdr["Referer"] = s["referrer"]
    if s.get("user_agent"):
        hdr["User-Agent"] = s["user_agent"]
    code, body, final, dt, h = fetch(url, hdr, limit=200000, timeout=12)
    r = {"channel": s.get("channel"), "feed": s.get("feed"), "title": s.get("title"), "url": url, "quality": s.get("quality"),
         "label": s.get("label"), "ref": bool(s.get("referrer")), "ua": bool(s.get("user_agent")),
         "code": code, "ms": int(dt * 1000), "ctype": h.get("Content-Type", "")}
    txt = body.decode("utf-8", "replace") if code == 200 else ""
    if code == 200 and "#EXTM3U" in txt[:200]:
        bh, bbw, nv, media = resolution_of(txt, final)
        r.update(master=nv > 0, height=bh, bandwidth=bbw, variants=nv)
        mtxt, mbase = (txt, final) if nv == 0 else (None, None)
        if media:
            c2, b2, f2, _, _ = fetch(media, hdr, limit=200000, timeout=12)
            r["media_code"] = c2
            if c2 == 200:
                mtxt, mbase = b2.decode("utf-8", "replace"), f2
        if mtxt:
            segs = [l.strip() for l in mtxt.splitlines() if l.strip() and not l.startswith("#")]
            r["segs"] = len(segs)
            if segs:
                seg = urljoin(mbase, segs[-1])
                c3, b3, _, dt3, _ = fetch(seg, hdr, limit=3_000_000, timeout=15)
                r["seg_code"] = c3
                r["seg_kb"] = len(b3) // 1024
                r["seg_ms"] = int(dt3 * 1000)
                td = re.search(r"#EXTINF:([\d.]+)", mtxt)
                if td and c3 == 200:
                    r["seg_kbps"] = int(len(b3) * 8 / 1000 / max(float(td.group(1)), 0.5))
    elif code == 200:
        r["note"] = "not-hls " + txt[:60].replace("\n", " ")
    r["ok"] = code == 200 and r.get("seg_code") == 200
    return r


targets = []
for s in streams:
    c = ch_by_id.get(s.get("channel"))
    cc = (c or {}).get("country")
    if cc in FOCUS:
        targets.append((cc, s))
for cc, n in SAMPLE.items():
    pool = [s for s in streams if (ch_by_id.get(s.get("channel")) or {}).get("country") == cc]
    targets += [(cc, s) for s in pool[:n]]
log.write(f"probing {len(targets)} streams\n")
log.flush()

with ThreadPoolExecutor(24) as ex:
    results = list(ex.map(lambda t: dict(probe_stream(t[1]), cc=t[0]), targets))

for r in results:
    c = ch_by_id.get(r["channel"]) or {}
    r["name"] = c.get("name")
    r["categories"] = c.get("categories")
    r["is_nsfw"] = c.get("is_nsfw")
    r["closed"] = c.get("closed")
    r["blocked"] = r["channel"] in blocked
json.dump(results, open(os.path.join(OUT, "stream_probe.json"), "w"), indent=1, ensure_ascii=False)

summary = {}
for r in results:
    d = summary.setdefault(r["cc"], {"total": 0, "ok": 0, "hd720+": 0, "fhd1080": 0})
    d["total"] += 1
    if r["ok"]:
        d["ok"] += 1
        if (r.get("height") or 0) >= 720:
            d["hd720+"] += 1
        if (r.get("height") or 0) >= 1080:
            d["fhd1080"] += 1
json.dump(summary, open(os.path.join(OUT, "probe_summary.json"), "w"), indent=1)

# focus country channels (all, with/without streams) for reference
focus_ch = [c for c in channels if c.get("country") in FOCUS]
json.dump(focus_ch, open(os.path.join(OUT, "focus_channels.json"), "w"), indent=1, ensure_ascii=False)
json.dump([g for g in guides if (ch_by_id.get(g.get("channel")) or {}).get("country") in FOCUS],
          open(os.path.join(OUT, "focus_guides.json"), "w"), indent=1, ensure_ascii=False)
json.dump([l for l in logos if (ch_by_id.get(l.get("channel")) or {}).get("country") in FOCUS][:400],
          open(os.path.join(OUT, "focus_logos.json"), "w"), indent=1, ensure_ascii=False)
json.dump([f for f in feeds if (ch_by_id.get(f.get("channel")) or {}).get("country") in FOCUS],
          open(os.path.join(OUT, "focus_feeds.json"), "w"), indent=1, ensure_ascii=False)

# EPG candidates (XMLTV). Read just the head to see channel ids + size.
EPG = [
    ("epgshare_rs", "https://epgshare01.online/epgshare01/epg_ripper_RS1.xml.gz"),
    ("epgshare_ba", "https://epgshare01.online/epgshare01/epg_ripper_BA1.xml.gz"),
    ("epgshare_hr", "https://epgshare01.online/epgshare01/epg_ripper_HR1.xml.gz"),
    ("epgshare_de", "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz"),
    ("epgshare_us", "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz"),
    ("epgshare_uk", "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz"),
    ("openepg_rs", "https://www.open-epg.com/files/serbia1.xml"),
    ("openepg_rs_gz", "https://www.open-epg.com/files/serbia1.xml.gz"),
    ("epgpw_rs", "https://epg.pw/xmltv/epg_RS.xml"),
    ("epgpw_rs_gz", "https://epg.pw/xmltv/epg_RS.xml.gz"),
    ("iptvorg_epg_raw", "https://raw.githubusercontent.com/iptv-org/epg/master/README.md"),
    ("pluto_epg", "https://i.mjh.nz/PlutoTV/all.xml.gz"),
    ("pluto_m3u", "https://i.mjh.nz/PlutoTV/all.m3u8"),
    ("samsung_m3u", "https://i.mjh.nz/SamsungTVPlus/all.m3u8"),
    ("samsung_epg", "https://i.mjh.nz/SamsungTVPlus/all.xml.gz"),
]
epg_info = {}
for name, url in EPG:
    code, body, final, dt, h = fetch(url, limit=40_000_000, timeout=60)
    info = {"code": code, "bytes": len(body), "sec": round(dt, 1), "final": final}
    if code == 200:
        raw = body
        if body[:2] == b"\x1f\x8b":
            try:
                raw = gzip.decompress(body)
            except Exception as e:
                info["gz_err"] = str(e)
        txt = raw.decode("utf-8", "replace")
        info["unzipped"] = len(raw)
        info["n_channels"] = txt.count("<channel ")
        info["n_programmes"] = txt.count("<programme ")
        info["channel_ids"] = re.findall(r'<channel id="([^"]+)"', txt)[:400]
        starts = re.findall(r'<programme[^>]*start="(\d{8})', txt)
        info["days"] = sorted(set(starts))[:20]
        info["head"] = txt[:1500]
        if name.endswith("m3u") or name.endswith("m3u8"):
            info["head"] = txt[:3000]
            info["n_entries"] = txt.count("#EXTINF")
    epg_info[name] = info
    log.write(f"{code}  {len(body):>10}  {dt:5.1f}s  epg:{name}  {url}\n")
    log.flush()
json.dump(epg_info, open(os.path.join(OUT, "epg_probe.json"), "w"), indent=1, ensure_ascii=False)

log.write("\nsummary: " + json.dumps(summary) + "\n")
log.close()
print(open(os.path.join(OUT, "index.txt")).read())
