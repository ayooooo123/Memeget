"""Build tools/eval/golden.json from the LOCAL basedmemes+KYM archive.

This is the local counterpart to build_golden.py (which pulls from memedepot in
CI). It embeds the held-out eval slice with MobileCLIP-S2 (the app's real model)
on-device — no network, no memedepot — turning our own corpus into the search-
quality accept-gate the fine-tune needs.

Leakage-free by construction (fixes the issues noted in the last review of
build_golden.py):
  - retrieval is pure CROSS-MODAL: memes carry only imageVec (captionVec=null,
    searchText=""), queries carry terms=[] so scoreEntry == dot(queryVec, imageVec).
    The query text (a tag caption) is NEVER copied into the meme haystack.
  - aspect queries get the FULL relevant set (every eval meme carrying the tag),
    not a single expectedId.
  - object-valued tags are already unwrapped in dataset.load_records.

The eval memes are the hash-holdout slice (dataset.is_eval) — provably disjoint
from the fine-tune training set.

Usage:
  python tools/eval/build_golden_local.py [--data-dir DIR] [--out tools/eval/golden.json]
                                          [--ckpt merged.pt] [--min-aspect 6] [--limit N]
"""
from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "finetune"))
import clipmodel  # noqa: E402
import dataset  # noqa: E402
from textviews import primary_caption  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=dataset.DEFAULT_DATA_DIR)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "golden.json"))
    ap.add_argument("--ckpt", default=None, help="fine-tuned merged state_dict (default: stock)")
    ap.add_argument("--min-aspect", type=int, default=6, help="min eval memes for a tag to become an aspect query")
    ap.add_argument("--limit", type=int, default=0, help="cap eval memes (0 = all holdout)")
    ap.add_argument("--buckets", type=int, default=dataset.EVAL_BUCKETS)
    a = ap.parse_args()

    recs = dataset.load_records(a.data_dir)
    _, evalset = dataset.split(recs, a.buckets)
    if a.limit:
        evalset = evalset[: a.limit]
    if not evalset:
        print("no eval records — check --data-dir", file=sys.stderr)
        return 1
    print(f"eval memes: {len(evalset)} (holdout bucket 0/{a.buckets})")

    model, preprocess, tokenizer, device = clipmodel.load(a.ckpt)
    print(f"model: {clipmodel.MODEL_NAME} on {device}{' + ckpt' if a.ckpt else ' (stock)'}")

    paths = [r.path for r in evalset]
    img_vecs, ok = clipmodel.embed_images(model, preprocess, device, paths)
    kept = [r for r, k in zip(evalset, ok) if k]
    img_vecs = img_vecs[ok]
    print(f"embedded {len(kept)} images ({(~ok).sum()} unreadable dropped)")

    captions = [primary_caption(r.tags) for r in kept]
    q_vecs = clipmodel.embed_texts(model, tokenizer, device, captions)

    memes = [
        {"id": r.id, "imageVec": [round(float(x), 6) for x in v], "captionVec": None, "searchText": ""}
        for r, v in zip(kept, img_vecs)
    ]
    queries = [
        {"query": cap, "queryVec": [round(float(x), 6) for x in v], "expectedId": r.id, "terms": []}
        for r, cap, v in zip(kept, captions, q_vecs)
    ]

    # Aspect queries: single tags carried by >= min-aspect eval memes; relevant
    # set = every eval meme with that tag. Dense-only (terms=[]) so it grades the
    # encoder, not lexical text.
    from collections import defaultdict

    tag_memes: dict[str, list[str]] = defaultdict(list)
    for r in kept:
        for t in r.tags:
            tag_memes[t].append(r.id)
    aspect_tags = sorted([t for t, ids in tag_memes.items() if len(ids) >= a.min_aspect])
    aspects = []
    if aspect_tags:
        a_vecs = clipmodel.embed_texts(model, tokenizer, device, aspect_tags)
        aspects = [
            {"query": t, "queryVec": [round(float(x), 6) for x in v], "relevantIds": tag_memes[t], "terms": []}
            for t, v in zip(aspect_tags, a_vecs)
        ]

    out = {
        "_source": "basedmemes.lol + knowyourmeme.com (local corpus)",
        "_model": clipmodel.MODEL_NAME + (":ckpt" if a.ckpt else ":stock"),
        "memes": memes,
        "queries": queries,
        "aspects": aspects,
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f)
    print(f"wrote {a.out}: {len(memes)} memes, {len(queries)} retrieval queries, {len(aspects)} aspect queries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
