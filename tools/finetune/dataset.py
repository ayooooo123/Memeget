"""Shared loader for the local basedmemes.lol + KnowYourMeme archive.

The archive is NOT in the repo (large image corpus, kept out of git). Point
`--data-dir` at it.

Two source files, merged by image *filename*:
  - dataset.jsonl        {"image": "<file>", "suffix": "t1, t2, ..."}
  - meme_dataset_kym.json [{"tags": [...], "file": "images/<file>"}]

Train/eval split is a deterministic hash of the image id, so the eval golden set
is provably DISJOINT from the fine-tune training set.
"""
from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass, field

DEFAULT_DATA_DIR = os.path.expanduser("~/projects/basedmemes_archive/www.basedmemes.lol")
IMAGE_SUBDIRS = ("images_only", "images")
EVAL_BUCKETS = 20


@dataclass
class MemeRecord:
    id: str
    path: str
    tags: list[str] = field(default_factory=list)


def _basename(p: str) -> str:
    return os.path.basename(p.strip()) if isinstance(p, str) else ""


def _coerce_tag(v) -> str:
    if isinstance(v, str):
        return v.strip()
    if isinstance(v, dict):
        for k in ("name", "title", "label", "slug", "tag", "text", "value"):
            if isinstance(v.get(k), str) and v[k].strip():
                return v[k].strip()
    return ""


def _resolve_image(dirs: list[str], filename: str) -> str | None:
    for d in dirs:
        for sub in IMAGE_SUBDIRS:
            p = os.path.join(d, sub, filename)
            if os.path.isfile(p):
                return p
    return None


# Recognized collection files per source dir: KYM, and a generic drop-in for any
# other source (e.g. a memedepot export) — an array of {file|image, tags|suffix}.
_COLLECTION_JSON = ("meme_dataset_kym.json", "collection.json")


def _ingest_dir(data_dir: str, tags_by_image: dict[str, set[str]]) -> None:
    """Fold one source dir into the tag map: the line-delimited dataset.jsonl
    ({image, suffix}) plus recognized collection JSON arrays. Explicit filenames
    (not a blind *.json scan) so unrelated json in a dir can't corrupt the corpus;
    drop a memedepot/other export in as `collection.json` to add it."""
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

    for name in _COLLECTION_JSON:
        path = os.path.join(data_dir, name)
        if not os.path.isfile(path):
            continue
        try:
            arr = json.load(open(path, encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        for m in arr if isinstance(arr, list) else []:
            if not isinstance(m, dict):
                continue
            img = _basename(m.get("file", "")) or _basename(m.get("image", ""))
            if not img:
                continue
            bag = tags_by_image.setdefault(img, set())
            raw = m.get("tags")
            if isinstance(raw, list):
                for t in raw:
                    t = _coerce_tag(t).lower()
                    if t:
                        bag.add(t)
            for t in (m.get("suffix") or "").split(","):
                t = t.strip().lower()
                if t:
                    bag.add(t)


def load_records(data_dir: str = DEFAULT_DATA_DIR, extra_dirs: list[str] | None = None) -> list[MemeRecord]:
    """Merge one or more source dirs (basedmemes + KYM today; add memedepot etc.
    via `extra_dirs`). Records are keyed by image filename; tags union across
    sources; the image must exist on disk in one of the dirs."""
    dirs = [data_dir] + list(extra_dirs or [])
    tags_by_image: dict[str, set[str]] = {}
    for d in dirs:
        _ingest_dir(d, tags_by_image)

    records: list[MemeRecord] = []
    for img, bag in tags_by_image.items():
        if not bag:
            continue
        path = _resolve_image(dirs, img)
        if path:
            records.append(MemeRecord(id=img, path=path, tags=sorted(bag)))
    records.sort(key=lambda r: r.id)
    return records


def is_eval(meme_id: str, buckets: int = EVAL_BUCKETS) -> bool:
    return (int(hashlib.sha1(meme_id.encode("utf-8")).hexdigest(), 16) % buckets) == 0


def split(records, buckets: int = EVAL_BUCKETS):
    return [r for r in records if not is_eval(r.id, buckets)], [r for r in records if is_eval(r.id, buckets)]


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=DEFAULT_DATA_DIR)
    a = ap.parse_args()
    recs = load_records(a.data_dir)
    tr, ev = split(recs)
    print(f"records {len(recs)}  train {len(tr)}  eval-holdout {len(ev)}  avg tags {sum(len(r.tags) for r in recs)/max(1,len(recs)):.1f}")
