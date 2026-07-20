"""Catastrophic-forgetting guard for the meme text-adapter (docs/memedepot-finetune.md
"Accept gate": no regression on generic, non-meme queries).

Because the fine-tune ONLY adapts the text tower (folded into text_projection) and
leaves the IMAGE tower frozen, image embeddings are byte-identical stock vs
fine-tuned. So generic image retrieval can only change through how much a generic
TEXT query's embedding moves. This guard measures exactly that:

  drift(prompt) = cosine( stock_text(prompt), finetuned_text(prompt) )

High drift-cosine on GENERIC prompts (≈1.0) => generic queries still land where
they did => no forgetting. We also check top-1 nearest-neighbor preservation of
the generic text similarity structure. Meme prompts SHOULD move more (that's the
learned gain) — reported alongside to show the adapter is selective, not global.

  python tools/finetune/forgetting_guard.py --ckpt tools/finetune/mobileclip_s2_memeft.pt
"""
from __future__ import annotations

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import clipmodel  # noqa: E402
import dataset  # noqa: E402
from textviews import primary_caption  # noqa: E402

# ~90 generic, non-meme concepts spanning animals, objects, scenes, food, actions.
GENERIC = [
    "a photo of a dog", "a photo of a cat", "a horse in a field", "a bird on a branch",
    "an elephant", "a lion", "a goldfish in a tank", "a butterfly on a flower",
    "a red sports car", "a bicycle leaning on a wall", "an airplane in the sky",
    "a sailboat on the ocean", "a train at a station", "a city bus", "a motorcycle",
    "a cup of coffee", "a slice of pizza", "a bowl of ramen", "a bunch of bananas",
    "a chocolate cake", "a glass of orange juice", "a plate of sushi", "a fresh salad",
    "a snow-capped mountain", "a sandy beach at sunset", "a dense forest", "a desert dune",
    "a waterfall", "a calm lake", "a rocky coastline", "a green meadow",
    "a modern office building", "a cozy living room", "a kitchen with wooden cabinets",
    "a library full of books", "a hospital hallway", "a classroom with desks",
    "a person running on a track", "a child playing with a ball", "a chef cooking",
    "a doctor in a white coat", "a musician playing guitar", "a painter at an easel",
    "a scientist in a laboratory", "a farmer in a field", "a construction worker",
    "a laptop on a desk", "a smartphone", "a pair of headphones", "a wristwatch",
    "a camera", "a potted plant", "a stack of books", "a wooden chair", "a table lamp",
    "a mountain bike trail", "a starry night sky", "a rainbow after rain",
    "a lightning storm", "autumn leaves on the ground", "cherry blossoms in spring",
    "a cup of green tea", "a loaf of bread", "a basket of apples", "a jar of honey",
    "a golden retriever puppy", "a tabby cat sleeping", "a flock of sheep",
    "a herd of cattle", "a school of fish", "a spider web", "a honeybee",
    "a red brick house", "a lighthouse by the sea", "a suspension bridge",
    "a subway platform", "an airport terminal", "a farmers market",
    "a violin", "a grand piano", "a set of drums", "a trumpet",
    "a soccer ball", "a basketball hoop", "a tennis racket", "a baseball glove",
    "a birthday party", "a wedding ceremony", "a graduation cap",
]


def cos_rows(a, b):
    return (a * b).sum(1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=dataset.DEFAULT_DATA_DIR)
    ap.add_argument("--ckpt", default=os.path.join(os.path.dirname(__file__), "mobileclip_s2_memeft.pt"))
    ap.add_argument("--meme-sample", type=int, default=90)
    ap.add_argument("--pass-threshold", type=float, default=0.90, help="min mean generic drift-cosine to pass")
    a = ap.parse_args()

    if not os.path.isfile(a.ckpt):
        print(f"checkpoint not found: {a.ckpt} (run finetune.py first)", file=sys.stderr)
        return 2

    recs = dataset.load_records(a.data_dir)
    _, held = dataset.split(recs)
    meme_prompts = [primary_caption(r.tags) for r in held[: a.meme_sample]]

    stock_m, _, stock_tok, dev = clipmodel.load()
    ft_m, _, ft_tok, _ = clipmodel.load(a.ckpt)

    def emb(model, tok, texts):
        return clipmodel.embed_texts(model, tok, dev, texts)  # L2-normalized

    gs, gf = emb(stock_m, stock_tok, GENERIC), emb(ft_m, ft_tok, GENERIC)
    ms, mf = emb(stock_m, stock_tok, meme_prompts), emb(ft_m, ft_tok, meme_prompts)

    gdrift = cos_rows(gs, gf)
    mdrift = cos_rows(ms, mf)

    # Nearest-neighbor preservation of the generic text structure (stock vs ft).
    def nn_top1(x):
        s = x @ x.T
        np.fill_diagonal(s, -1)
        return s.argmax(1)

    keep = float((nn_top1(gs) == nn_top1(gf)).mean())

    print(f"generic prompts: {len(GENERIC)}   meme prompts: {len(meme_prompts)}", flush=True)
    print(f"generic drift-cosine  mean {gdrift.mean():.4f}  min {gdrift.min():.4f}", flush=True)
    print(f"meme    drift-cosine  mean {mdrift.mean():.4f}  min {mdrift.min():.4f}   (should move MORE)", flush=True)
    print(f"generic NN-top1 preserved: {keep*100:.1f}%", flush=True)
    ok = gdrift.mean() >= a.pass_threshold and keep >= 0.90
    print(f"\nGUARD: {'PASS' if ok else 'REVIEW'} "
          f"(generic barely moves & structure preserved; meme moves more => selective)", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
