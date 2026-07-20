"""Build tools/eval/golden.json from the LOCAL basedmemes+KYM archive.

Local counterpart to build_golden.py (which pulls from memedepot in CI). Embeds
the held-out eval slice with MobileCLIP-S2 (the app's real model) on-device —
no network, no memedepot — turning our own corpus into the search-quality
accept-gate. Pass --ckpt to score a fine-tuned checkpoint (tools/finetune).

Leakage-free by construction: retrieval is pure CROSS-MODAL (memes carry only
imageVec; captionVec=null, searchText=""; queries carry terms=[]), so
scoreEntry == dot(queryVec, imageVec) and the query text is never in the meme
haystack. Aspect queries get the FULL relevant set. Eval memes are the hash
holdout (dataset.is_eval) — disjoint from fine-tune training.

  python tools/eval/build_golden_local.py [--out tools/eval/golden.json] [--ckpt merged.pt] [--limit N]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "finetune"))
import clipmodel  # noqa: E402
import dataset  # noqa: E402
from textviews import primary_caption  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=dataset.DEFAULT_DATA_DIR)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "golden.json"))
    ap.add_argument("--ckpt", default=None)
    ap.add_argument("--min-aspect", type=int, default=6)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()

    recs = dataset.load_records(a.data_dir)
    _, evalset = dataset.split(recs)
    if a.limit:
        evalset = evalset[: a.limit]
    if not evalset:
        print("no eval records — check --data-dir", file=sys.stderr)
        return 1

    model, preprocess, tokenizer, device = clipmodel.load(a.ckpt)
    print(f"{clipmodel.MODEL_NAME} on {device}{' + ckpt' if a.ckpt else ' (stock)'}; eval memes {len(evalset)}", flush=True)

    paths = [r.path for r in evalset]
    img_vecs, ok = clipmodel.embed_images(model, preprocess, device, paths)
    kept = [r for r, k in zip(evalset, ok) if k]
    img_vecs = img_vecs[ok]

    captions = [primary_caption(r.tags) for r in kept]
    q_vecs = clipmodel.embed_texts(model, tokenizer, device, captions)

    memes = [{"id": r.id, "imageVec": [round(float(x), 6) for x in v], "captionVec": None, "searchText": ""}
             for r, v in zip(kept, img_vecs)]
    queries = [{"query": c, "queryVec": [round(float(x), 6) for x in v], "expectedId": r.id, "terms": []}
               for r, c, v in zip(kept, captions, q_vecs)]

    tag_memes: dict[str, list[str]] = defaultdict(list)
    for r in kept:
        for t in r.tags:
            tag_memes[t].append(r.id)
    aspect_tags = sorted([t for t, ids in tag_memes.items() if len(ids) >= a.min_aspect])
    aspects = []
    if aspect_tags:
        av = clipmodel.embed_texts(model, tokenizer, device, aspect_tags)
        aspects = [{"query": t, "queryVec": [round(float(x), 6) for x in v], "relevantIds": tag_memes[t], "terms": []}
                   for t, v in zip(aspect_tags, av)]

    out = {"_source": "basedmemes.lol + knowyourmeme.com (local)",
           "_model": clipmodel.MODEL_NAME + (":ckpt" if a.ckpt else ":stock"),
           "memes": memes, "queries": queries, "aspects": aspects}
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f)
    print(f"wrote {a.out}: {len(memes)} memes, {len(queries)} queries, {len(aspects)} aspects", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
