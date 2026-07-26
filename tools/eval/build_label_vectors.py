"""Embed the app's zero-shot vocabulary into tools/eval/label-vectors.json.

The recognition eval (`npm run recognition`) grades the classifier's ability to
tell "I recognize this meme" from "I have no idea" — which needs the same text
vectors the phone uses: every `MEME_LABELS` prompt plus every `NEGATIVE_ANCHORS`
phrase, embedded with MobileCLIP-S2 (`PRIMARY_EMBEDDING_MODEL`). Committing them
next to golden.json keeps that eval deterministic and torch-free in CI, exactly
like the golden set itself.

Re-run whenever a prompt, a label, or an anchor changes — the eval refuses to
score a stale file rather than quietly measuring the wrong vocabulary.

Usage:
  python tools/eval/build_label_vectors.py [--out tools/eval/label-vectors.json]
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "finetune"))
import clipmodel  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# The vocabulary lives in TypeScript (and is assembled at import time from the
# curated core + two harvested baselines), so we ask Node for it rather than
# re-implementing that composition in Python and letting the two drift.
DUMP = """
const { MEME_LABELS, NEGATIVE_ANCHORS } = require(process.argv[1]);
process.stdout.write(JSON.stringify({
  labels: MEME_LABELS.map((d) => ({ label: d.label, prompt: d.prompt })),
  anchors: NEGATIVE_ANCHORS,
}));
"""


def read_vocabulary() -> dict:
    with tempfile.TemporaryDirectory() as out:
        subprocess.run(
            ["npx", "tsc", "--outDir", out, "--module", "commonjs", "--target", "es2020",
             "--moduleResolution", "node", "--resolveJsonModule", "--esModuleInterop",
             "--skipLibCheck", "src/memeLabels.ts"],
            cwd=ROOT, check=True,
        )
        dumped = subprocess.run(
            ["node", "-e", DUMP, os.path.join(out, "memeLabels.js")],
            cwd=ROOT, check=True, capture_output=True, text=True,
        )
    return json.loads(dumped.stdout)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "label-vectors.json"))
    a = ap.parse_args()

    vocab = read_vocabulary()
    labels, anchors = vocab["labels"], vocab["anchors"]
    print(f"vocabulary: {len(labels)} labels + {len(anchors)} negative anchors")

    model, _, tokenizer, device = clipmodel.load(None)
    texts = [d["prompt"] for d in labels] + list(anchors)
    vecs = clipmodel.embed_texts(model, tokenizer, device, texts)
    print(f"embedded with {clipmodel.MODEL_NAME} (stock) on {device}")

    def row(v) -> list[float]:
        return [round(float(x), 5) for x in v]

    payload = {
        "_model": f"{clipmodel.MODEL_NAME}:stock",
        "labels": [
            {"label": d["label"], "prompt": d["prompt"], "vec": row(v)}
            for d, v in zip(labels, vecs[: len(labels)])
        ],
        "anchors": [
            {"text": t, "vec": row(v)} for t, v in zip(anchors, vecs[len(labels):])
        ],
    }
    with open(a.out, "w") as f:
        json.dump(payload, f)
    print(f"wrote {a.out} ({os.path.getsize(a.out) / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
