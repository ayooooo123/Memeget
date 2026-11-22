"""Rank the app's format prompts against held-out images with released CPU models."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import ijson
import numpy as np

HERE = Path(__file__).resolve().parent
PROMPTS = HERE.parents[1] / "native/app/src/main/assets/knowledge/label-prompts.json"
sys.path.insert(0, str(HERE.parent / "finetune"))
import dataset


def golden_ids(path: Path) -> list[str]:
    # Project only the image identifiers. No image, query or label vector is an input.
    with path.open("rb") as source:
        ids = list(ijson.items(source, "memes.item.id", use_float=True))
    if not ids or len(set(ids)) != len(ids):
        raise ValueError("expected a nonempty golden set with unique image IDs")
    if any(not isinstance(i, str) or not i or i in {".", ".."} or Path(i).name != i for i in ids):
        raise ValueError("expected golden image IDs to be filenames")
    return ids


def format_prompts(path: Path) -> dict[str, str]:
    with path.open(encoding="utf-8") as f:
        rows = [row for row in json.load(f)["labels"] if row["category"] == "format"]
    prompts = {row["label"].strip().casefold(): row["prompt"] for row in rows}
    if not rows or len(prompts) != len(rows) or any(not k or not v.strip() for k, v in prompts.items()):
        raise ValueError("expected distinct format names with nonempty prompts")
    return prompts


def labelled_images(ids: list[str], data_dir: Path, labels: list[str]):
    for name in ("dataset.jsonl", "meme_dataset_kym.json"):
        if not (data_dir / name).is_file():
            raise ValueError(f"missing corpus annotations: {name}")
    wanted = set(ids)
    records = {r.id: r for r in dataset.load_records(str(data_dir)) if r.id in wanted}
    index = {label: i for i, label in enumerate(labels)}
    paths, targets, missing = [], [], []
    for image_id in ids:
        record = records.get(image_id)
        if record is None:
            # The shared loader omits both missing images and images without tags.
            if not any((data_dir / sub / image_id).is_file() for sub in dataset.IMAGE_SUBDIRS):
                missing.append(image_id)
            continue
        matches = sorted({index[t] for tag in record.tags if (t := tag.casefold()) in index})
        if matches:
            paths.append(Path(record.path))
            targets.append(matches)
    return paths, targets, missing


def metrics(scores: np.ndarray, targets: list[list[int]]) -> np.ndarray:
    # One trial per annotated image/format pair, including both tags on a multi-tag image.
    order = np.argsort(-scores, axis=1, kind="stable")
    rows = np.repeat(np.arange(len(targets)), [len(t) for t in targets])
    columns = np.concatenate(targets)
    ranks = (order[rows] == columns[:, None]).argmax(axis=1) + 1
    return np.array([(ranks <= k).mean() for k in (1, 3, 5)] + [(1 / ranks).mean()])


def report(name: str, m: np.ndarray) -> None:
    print(f"{name}: top-1={m[0]:.2%} top-3={m[1]:.2%} top-5={m[2]:.2%} MRR={m[3]:.4f}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True, help="corpus annotations and images_only/ or images/")
    parser.add_argument("--models-dir", type=Path, required=True, help="directory containing the models-v1 release files")
    parser.add_argument("--text-weights", type=Path, help="replacement MobileCLIP-S2 text .pte (same tokenizer and output space)")
    parser.add_argument("--golden", type=Path, default=HERE / "golden.json", help="holdout membership (memes[].id only)")
    parser.add_argument("--shuffle-labels", action="store_true", help="permute prompt vectors across labels; keep targets fixed")
    args = parser.parse_args()
    prompts = format_prompts(PROMPTS)
    labels = sorted(prompts)  # also the tie-break order, independent of either model
    ids = golden_ids(args.golden)
    paths, targets, missing = labelled_images(ids, args.data_dir, labels)
    for image_id in missing:
        print(f"missing: {image_id}", file=sys.stderr)
    print(f"golden={len(ids)} missing={len(missing)} no_format_tag={len(ids) - len(missing) - len(paths)}", flush=True)
    if not paths:
        parser.exit(1, "no available golden images have a matching format tag\n")

    from format_encoder import encode_images, encode_labels

    image_model = args.models_dir / "mobileclip_s2_image_xnnpack_fp32.pte"
    text_model = args.text_weights or args.models_dir / "mobileclip_s2_text_xnnpack_fp32.pte"
    print(f"image={image_model.name} text={text_model.name} device=cpu", flush=True)
    text = encode_labels(text_model, args.models_dir / "mobileclip_s2_tokenizer.json", [prompts[key] for key in labels])
    images, kept = encode_images(image_model, paths)
    unreadable = sorted(set(range(len(paths))).difference(kept))
    for i in unreadable:
        print(f"unreadable: {paths[i].name}", file=sys.stderr)
    if not kept:
        parser.exit(1, "no readable golden images with format tags\n")
    targets = [targets[i] for i in kept]
    print(f"images={len(images)} pairs={sum(map(len, targets))} labels={len(labels)} unreadable={len(unreadable)}")
    scores = images @ text.T  # cosine: both encoders return unit vectors
    if not args.shuffle_labels:
        report("rank", metrics(scores, targets))
        return

    # A single global permutation has high variance on a small, imbalanced holdout.
    # Permuting score columns is exactly the same as reassigning text vectors before the dot product.
    rng = np.random.default_rng(0)
    draws = [metrics(scores[:, rng.permutation(len(labels))], targets) for _ in range(256)]
    report("shuffle first (seed=0)", draws[0])
    report("shuffle mean (256 draws, seed=0)", np.mean(draws, axis=0))


if __name__ == "__main__":
    main()
