"""Harvest a LOCAL (image, tags) corpus from memedepot — the 3rd fine-tuning source.

memedepot's API (memedepot.com, now reachable) exposes per-meme AI dissection:
extracted_labels (real per-meme aspects), ai_template_match (format), ai_description
(caption), extracted_text (OCR). Images are Cloudflare Images, fetched via the
same-domain path https://memedepot.com/cdn-cgi/imagedelivery/<hash>/<cf_asset_id>/<variant>
(imagedelivery.net direct is NOT reachable here; the zone path is).

Writes a source dir in the multi-source loader's schema:
    <out>/images_only/<cf_asset_id>.jpg
    <out>/collection.json   # [{ "file": "<cf_asset_id>.jpg", "tags": [...], "ai_description": ... }]
so tools/finetune/dataset.load_records(extra_dirs=[<out>]) folds it in.

Corpus stays LOCAL (copyrighted user memes — docs/memedepot-corpus.md); never shipped.
"""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.request

BASE = "https://memedepot.com"
CF_HASH = "naCPMwxXX46-hrE49eZovw"  # memedepot's Cloudflare Images account hash
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120 Safari/537.36"


def get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def get_bytes(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def as_list(j, *keys):
    if isinstance(j, list):
        return j
    if isinstance(j, dict):
        for k in keys:
            if isinstance(j.get(k), list):
                return j[k]
    return []


def norm_tag(s):
    if not isinstance(s, str):
        return ""
    t = " ".join(s.replace("-", " ").replace("_", " ").strip().lower().split())
    return t if 2 < len(t) <= 40 and len(t.split()) <= 5 else ""


def meme_tags(m):
    raw = list(m.get("extracted_labels") or []) + list(m.get("tags") or [])
    if isinstance(m.get("ai_template_match"), str):
        raw.append(m["ai_template_match"])
    seen, out = set(), []
    for s in raw:
        t = norm_tag(s)
        if t and t not in seen:
            seen.add(t)
            out.append(t)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.expanduser("~/projects/basedmemes_archive/memedepot"))
    ap.add_argument("--depots", type=int, default=80)
    ap.add_argument("--per-depot", type=int, default=40)
    ap.add_argument("--max-images", type=int, default=2500)
    ap.add_argument("--variant", default="w=512")
    ap.add_argument("--delay", type=float, default=0.15)
    a = ap.parse_args()

    img_dir = os.path.join(a.out, "images_only")
    os.makedirs(img_dir, exist_ok=True)

    depots = []
    for page in range(0, 50):
        batch = as_list(get_json(f"{BASE}/api/depots?page={page}&limit=50"), "depots", "data")
        if not batch:
            break
        depots += batch
        if len(depots) >= a.depots:
            break
        time.sleep(a.delay)
    depots = depots[: a.depots]
    print(f"depots: {len(depots)}", flush=True)

    coll, seen, n_img = [], set(), 0
    for d in depots:
        slug = d.get("slug")
        if not slug:
            continue
        try:
            items = as_list(get_json(f"{BASE}/api/memes?depotSlug={slug}&limit=50&page=1"), "memes", "data")
        except Exception as e:
            print(f"  ! {slug}: {str(e)[:80]}", flush=True)
            continue
        got = 0
        for m in items:
            if got >= a.per_depot or n_img >= a.max_images:
                break
            cf = m.get("cf_asset_id")
            if not cf or cf in seen:
                continue
            tags = meme_tags(m)
            if not tags:
                continue
            fname = f"{cf}.jpg"
            fpath = os.path.join(img_dir, fname)
            if not os.path.isfile(fpath):
                try:
                    data = get_bytes(f"{BASE}/cdn-cgi/imagedelivery/{CF_HASH}/{cf}/{a.variant}")
                except Exception:
                    continue
                if len(data) < 512:
                    continue
                with open(fpath, "wb") as fh:
                    fh.write(data)
            seen.add(cf)
            coll.append({"file": fname, "tags": tags, "ai_description": m.get("ai_description") or ""})
            got += 1
            n_img += 1
            time.sleep(a.delay)
        print(f"  {slug}: +{got}  (total {n_img})", flush=True)
        if n_img >= a.max_images:
            break

    with open(os.path.join(a.out, "collection.json"), "w", encoding="utf-8") as fh:
        json.dump(coll, fh)
    print(f"\nwrote {len(coll)} memes -> {a.out}/collection.json + images_only/", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
