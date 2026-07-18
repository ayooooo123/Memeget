#!/usr/bin/env python3
"""Build a real search-quality golden set for the eval harness (src/evalCore.ts).

The harness ranks a golden set with the app's own scoreEntry and reports
Recall@k / MRR — but it needs PRECOMPUTED CLIP vectors, which can't be produced
in the dev sandbox (no torch, Hugging Face egress-blocked). This script runs
where CLIP works (CI or Colab): it pulls real memes from memedepot's depots,
embeds each image + a query with **CLIP ViT-B/32** (the same space the app ships
via react-native-executorch), and writes `golden.json` in the schema evalCore
consumes.

The eval it encodes: "does searching a format's NAME retrieve that format's
memes?" — each depot contributes memes (expected results) and its name (the
query). That's a faithful, self-labeling retrieval test.

PRIVACY: writes vectors + ids only, never the images. Images are downloaded to a
temp dir and discarded.

Usage (CI or Colab):
    pip install open_clip_torch torch pillow requests
    python build_golden.py --out golden.json --depots 25 --per-depot 8
"""
import argparse, json, io, os, sys, time, urllib.parse
import requests
from PIL import Image
import torch, open_clip

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
BASE = "https://memedepot.com"


def get_json(url):
    r = requests.get(url, headers={"User-Agent": UA, "Accept": "application/json"}, timeout=25)
    r.raise_for_status()
    return r.json()


def as_list(j, *keys):
    if isinstance(j, list):
        return j
    if isinstance(j, dict):
        for k in keys:
            if isinstance(j.get(k), list):
                return j[k]
        for v in j.values():
            if isinstance(v, list):
                return v
    return []


def depot_name(d):
    for k in ("name", "title", "displayName", "label"):
        if isinstance(d.get(k), str) and d[k].strip():
            return d[k].strip()
    s = d.get("slug")
    return s.replace("-", " ").strip() if isinstance(s, str) else ""


IMG_EXT = (".jpg", ".jpeg", ".png", ".webp", ".gif")


def _walk_urls(obj, out):
    """Collect every http(s) string anywhere in a nested dict/list."""
    if isinstance(obj, str):
        if obj.startswith("http"):
            out.append(obj)
    elif isinstance(obj, dict):
        for v in obj.values():
            _walk_urls(v, out)
    elif isinstance(obj, list):
        for v in obj:
            _walk_urls(v, out)


def meme_image_url(m):
    """Find a meme's image URL. memedepot's field is unknown and may be nested,
    so recursively collect every URL in the object and prefer an image
    extension; fall back to any URL (a video → Image.open fails → skipped, which
    is fine, we only want images)."""
    urls = []
    _walk_urls(m, urls)
    imgs = [u for u in urls if u.lower().split("?")[0].endswith(IMG_EXT)]
    return (imgs or urls or [None])[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="golden.json")
    ap.add_argument("--depots", type=int, default=25, help="how many depots to sample")
    ap.add_argument("--per-depot", type=int, default=8, help="memes per depot")
    ap.add_argument("--delay", type=float, default=1.0)
    args = ap.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"loading CLIP ViT-B/32 (openai) on {device}…")
    model, _, preprocess = open_clip.create_model_and_transforms("ViT-B-32", pretrained="openai")
    tokenizer = open_clip.get_tokenizer("ViT-B-32")
    model = model.to(device).eval()

    def embed_image(img):
        with torch.no_grad():
            t = preprocess(img).unsqueeze(0).to(device)
            v = model.encode_image(t)[0]
            return (v / v.norm()).cpu().tolist()

    def embed_text(txt):
        with torch.no_grad():
            v = model.encode_text(tokenizer([txt]).to(device))[0]
            return (v / v.norm()).cpu().tolist()

    # 1) sample depots
    depots = []
    for page in range(0, 50):
        batch = as_list(get_json(f"{BASE}/api/depots?page={page}&limit=50"), "depots", "data")
        if not batch:
            break
        depots += batch
        if len(depots) >= args.depots:
            break
        time.sleep(args.delay)
    depots = depots[: args.depots]
    print(f"depots: {len(depots)}")

    memes, queries = [], []
    for d in depots:
        slug = d.get("slug")
        name = depot_name(d)
        if not slug or not name:
            continue
        try:
            items = as_list(
                get_json(f"{BASE}/api/memes?depotSlug={urllib.parse.quote(slug)}&limit=50&page=1"),
                "memes", "data",
            )
        except Exception as e:
            print(f"  ! {slug}: {e}")
            continue
        if items and not getattr(main, "_dumped", False):
            main._dumped = True
            print(f"  [diag] first meme keys: {list(items[0].keys())}")
            print(f"  [diag] first meme sample: {json.dumps(items[0])[:500]}")
        got = 0
        for m in items:
            if got >= args.per_depot:
                break
            url = meme_image_url(m)
            if not url:
                continue
            try:
                r = requests.get(url, headers={"User-Agent": UA}, timeout=25)
                img = Image.open(io.BytesIO(r.content)).convert("RGB")
                mid = f"{slug}:{m.get('id', got)}"
                memes.append({"id": mid, "imageVec": [round(x, 5) for x in embed_image(img)], "searchText": name})
                queries.append({"query": name, "queryVec": [round(x, 5) for x in embed_text(name)], "expectedId": mid})
                got += 1
            except Exception as e:
                print(f"  ! image {url[:60]}: {e}")
        print(f"  {name}: {got} memes")
        time.sleep(args.delay)

    out = {"model": "clip-vit-base-patch32", "memes": memes, "queries": queries}
    with open(args.out, "w") as f:
        json.dump(out, f)
    print(f"\nwrote {len(memes)} memes / {len(queries)} queries → {args.out}")
    if not memes:
        print("NO DATA — check meme_image_url() against a sample /api/memes response.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
