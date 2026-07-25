"""Shared loader for the local basedmemes.lol + KnowYourMeme archive.

The archive is NOT in the repo (it's a local, developer-only corpus of scraped
memes — see docs/memedepot-corpus.md on why raw memes stay local). Point
`--data-dir` at it. Default matches where it currently lives on this machine.

Two source files, merged by image *filename*:
  - dataset.jsonl        {"image": "<file>", "prefix": ..., "suffix": "t1, t2, ..."}
  - meme_dataset_kym.json [{"image": <url>, "tags": [...], "file": "images/<file>"}]

Images live under images_only/ (preferred) or images/. A record is kept only if
its image file actually exists on disk.

Train/eval split is a deterministic hash of the image id, so the eval golden set
is provably DISJOINT from the fine-tune training set (the accept-gate must never
see a meme it trained on — docs/memedepot-finetune.md).
"""
from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass, field

DEFAULT_DATA_DIR = os.path.expanduser("~/projects/basedmemes_archive/www.basedmemes.lol")
IMAGE_SUBDIRS = ("images_only", "images")
EVAL_BUCKETS = 20  # 1/20 of the corpus (~5%) is held out for eval by default


@dataclass
class MemeRecord:
    id: str  # image filename (stable, dedupes across the two sources)
    path: str  # absolute path to the image file on disk
    tags: list[str] = field(default_factory=list)


def _basename(p: str) -> str:
    return os.path.basename(p.strip()) if isinstance(p, str) else ""


def _coerce_tag(v) -> str:
    """KYM tags are usually strings but occasionally objects; mirror harvest.mjs's
    jsonTerm so an object like {name: 'wojak'} doesn't stringify to '[object Object]'."""
    if isinstance(v, str):
        return v.strip()
    if isinstance(v, dict):
        for k in ("name", "title", "label", "slug", "tag", "text", "value"):
            if isinstance(v.get(k), str) and v[k].strip():
                return v[k].strip()
    return ""


def _resolve_image(data_dir: str, filename: str) -> str | None:
    for sub in IMAGE_SUBDIRS:
        p = os.path.join(data_dir, sub, filename)
        if os.path.isfile(p):
            return p
    return None


def load_records(data_dir: str = DEFAULT_DATA_DIR) -> list[MemeRecord]:
    """Merge both sources by image filename, union tags, resolve+require the image."""
    tags_by_image: dict[str, set[str]] = {}

    jsonl = os.path.join(data_dir, "dataset.jsonl")
    if os.path.isfile(jsonl):
        with open(jsonl, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    continue
                img = _basename(o.get("image", ""))
                if not img:
                    continue
                bag = tags_by_image.setdefault(img, set())
                for t in (o.get("suffix") or "").split(","):
                    t = t.strip().lower()
                    if t:
                        bag.add(t)

    kym = os.path.join(data_dir, "meme_dataset_kym.json")
    if os.path.isfile(kym):
        with open(kym, encoding="utf-8") as f:
            try:
                arr = json.load(f)
            except json.JSONDecodeError:
                arr = []
        for m in arr if isinstance(arr, list) else []:
            img = _basename(m.get("file", "")) or _basename(m.get("image", ""))
            if not img:
                continue
            bag = tags_by_image.setdefault(img, set())
            for t in m.get("tags", []) or []:
                t = _coerce_tag(t).lower()
                if t:
                    bag.add(t)

    records: list[MemeRecord] = []
    for img, bag in tags_by_image.items():
        if not bag:
            continue
        path = _resolve_image(data_dir, img)
        if not path:
            continue
        records.append(MemeRecord(id=img, path=path, tags=sorted(bag)))
    records.sort(key=lambda r: r.id)
    return records


def is_eval(meme_id: str, buckets: int = EVAL_BUCKETS) -> bool:
    """Deterministic holdout: bucket 0 of `buckets` is eval, the rest train."""
    h = int(hashlib.sha1(meme_id.encode("utf-8")).hexdigest(), 16)
    return (h % buckets) == 0


def split(records: list[MemeRecord], buckets: int = EVAL_BUCKETS):
    train = [r for r in records if not is_eval(r.id, buckets)]
    evalset = [r for r in records if is_eval(r.id, buckets)]
    return train, evalset


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=DEFAULT_DATA_DIR)
    ap.add_argument("--buckets", type=int, default=EVAL_BUCKETS)
    a = ap.parse_args()
    recs = load_records(a.data_dir)
    tr, ev = split(recs, a.buckets)
    n_tags = sum(len(r.tags) for r in recs)
    print(f"records with image+tags: {len(recs)}")
    print(f"  train: {len(tr)}   eval(holdout): {len(ev)}")
    print(f"  avg tags/meme: {n_tags / max(1, len(recs)):.1f}")
    if recs:
        print(f"  sample: {recs[0].id} -> {recs[0].tags[:8]}")
