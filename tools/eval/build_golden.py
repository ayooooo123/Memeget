#!/usr/bin/env python3
"""Build a real search-quality golden set for the eval harness (src/evalCore.ts).

The harness ranks a golden set with the app's own scoreEntry and reports
Recall@k / MRR — but it needs PRECOMPUTED vectors in the SAME embedding space the
app ships, which can't be produced in the dev sandbox (no torch, Hugging Face
egress-blocked). This script runs where the model works (CI or Colab): it pulls
real memes from memedepot's depots, embeds each image + a query with
**MobileCLIP-S2** (`PRIMARY_EMBEDDING_MODEL` — the model the app actually runs
on-device via react-native-executorch; a teaching-pack export confirmed the
shipped model is `mobileclip-s2`, dim 512), and writes `golden.json` in the
schema evalCore consumes.

We use open_clip's MobileCLIP-S2 / datacompdr checkpoint (Apple's official
weights) as the reproducible stand-in for the app's executorch export. It's the
correct SPACE — exact parity with the on-device export isn't guaranteed, but it's
vastly closer than the old ViT-B/32 build, whose vectors were a different model
entirely and made the eval measure the wrong space.

The eval it encodes: "does searching a format's NAME retrieve that format's
memes?" — each depot contributes memes (expected results) and its name (the
query). That's a faithful, self-labeling retrieval test.

PRIVACY: writes vectors + ids only, never the images. Images are downloaded to a
temp dir and discarded.

Usage (CI or Colab):
    pip install open_clip_torch timm torch pillow requests
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


# memedepot has ALREADY dissected each meme with AI. The per-meme object exposes:
#   tags             — user tags (usually empty)
#   extracted_labels — ML vision labels: the real per-meme aspects
#                      ("angry expression", "soccer", "Israeli flag", "cartoon")
#   ai_template_match— the meme format/template name
#   ai_description   — a full natural-language caption
#   ai_cultural_context — what it means / when it's used
#   extracted_text   — OCR lines
# The aspect ground truth comes from the label-ish fields; the caption/OCR feed
# the lexical + dense text channels.
ASPECT_FIELDS = ("tags", "extracted_labels")
FORMAT_FIELD = "ai_template_match"


def norm_tag(s):
    """Lowercase, de-slug, collapse whitespace; drop junk. Mirrors how the app's
    searchText is normalized (lowercased) so a query 'smug' matches 'Smug'."""
    if not isinstance(s, str):
        return ""
    t = " ".join(s.replace("-", " ").replace("_", " ").strip().lower().split())
    if len(t) < 3 or len(t) > 40:
        return ""
    if len(t.split()) > 5:  # tags are short phrases, not sentences
        return ""
    return t


def _str_list(v):
    """Coerce a field into a list of strings (handles str, list, or absent)."""
    if isinstance(v, str):
        return [v]
    if isinstance(v, list):
        return [x for x in v if isinstance(x, str)]
    return []


def meme_tags(m):
    """Deduped, order-preserving aspect tags from the AI-extracted fields."""
    raw = []
    for f in ASPECT_FIELDS:
        raw += _str_list(m.get(f))
    fmt = m.get(FORMAT_FIELD)
    if isinstance(fmt, str):
        raw.append(fmt)
    seen, uniq = set(), []
    for s in raw:
        t = norm_tag(s)
        if t and t not in seen:
            seen.add(t)
            uniq.append(t)
    return uniq


def meme_caption(m):
    """memedepot's AI caption (+ cultural context) — a real description, so the
    caption channel measures something independent of the depot-name query."""
    parts = [m.get("ai_description"), m.get("ai_cultural_context")]
    return " ".join(p for p in parts if isinstance(p, str) and p.strip()).strip()


def meme_ocr(m):
    """OCR text lines memedepot already extracted."""
    return " ".join(_str_list(m.get("extracted_text"))).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="golden.json")
    ap.add_argument("--depots", type=int, default=25, help="how many depots to sample")
    ap.add_argument("--per-depot", type=int, default=8, help="memes per depot")
    ap.add_argument("--delay", type=float, default=1.0)
    ap.add_argument("--min-tag-memes", type=int, default=3,
                    help="a tag becomes an aspect query only if >= this many memes carry it")
    ap.add_argument("--max-aspects", type=int, default=80, help="cap on aspect (single-word) queries")
    args = ap.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"loading MobileCLIP-S2 (datacompdr) on {device}…")
    model, _, preprocess = open_clip.create_model_and_transforms("MobileCLIP-S2", pretrained="datacompdr")
    tokenizer = open_clip.get_tokenizer("MobileCLIP-S2")
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
    tag_memes = {}  # normalized tag -> list of meme ids that carry it (aspect ground truth)
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
            print(f"  [diag] first meme tags: {meme_tags(items[0])}")
            print(f"  [diag] first meme caption: {meme_caption(items[0])[:160]!r}")
            print(f"  [diag] first meme ocr: {meme_ocr(items[0])[:120]!r}")
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
                tags = meme_tags(m)
                caption = meme_caption(m)
                ocr = meme_ocr(m)
                title = m.get("title") if isinstance(m.get("title"), str) else ""
                # searchText = the lexical haystack the app builds (ocr + name +
                # caption + tags), lowercased exactly like db.ts's rowSearchText,
                # so single-word queries hit it via scoreEntry's .includes().
                search_text = " ".join([ocr, title, name, caption, " ".join(tags)]).lower()
                # captionVec = CLIP text vector of the meme's REAL description
                # (memedepot's AI caption), independent of the depot-name query so
                # the dense text channel isn't just grading its own hint.
                cap_src = caption or (f"{name}. {', '.join(tags)}" if tags else name)
                memes.append({
                    "id": mid,
                    "imageVec": [round(x, 5) for x in embed_image(img)],
                    "captionVec": [round(x, 5) for x in embed_text(cap_src)],
                    "searchText": search_text,
                    "tags": tags,
                })
                queries.append({"query": name, "queryVec": [round(x, 5) for x in embed_text(name)], "expectedId": mid})
                for t in tags:
                    tag_memes.setdefault(t, []).append(mid)
                got += 1
            except Exception as e:
                print(f"  ! image {url[:60]}: {e}")
        print(f"  {name}: {got} memes")
        time.sleep(args.delay)

    # Aspect (single-word) queries: every tag carried by >= min-tag-memes memes
    # becomes a query whose relevant set is exactly those memes. This is the
    # "type one word, find the memes with that aspect" benchmark.
    aspects = []
    common = sorted(
        ((t, ids) for t, ids in tag_memes.items() if len(ids) >= args.min_tag_memes),
        key=lambda kv: len(kv[1]),
        reverse=True,
    )[: args.max_aspects]
    for t, ids in common:
        aspects.append({
            "query": t,
            "queryVec": [round(x, 5) for x in embed_text(t)],
            "relevantIds": ids,
        })
    print(f"\naspect queries: {len(aspects)} (from {len(tag_memes)} distinct tags)")

    out = {"model": "mobileclip-s2", "memes": memes, "queries": queries, "aspects": aspects}
    with open(args.out, "w") as f:
        json.dump(out, f)
    print(f"wrote {len(memes)} memes / {len(queries)} queries / {len(aspects)} aspects → {args.out}")
    if not memes:
        print("NO DATA — check meme_image_url() against a sample /api/memes response.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
