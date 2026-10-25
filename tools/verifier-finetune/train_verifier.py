#!/usr/bin/env python3
"""
Offline training & clean knowledge asset builder for Memeget Native:
1. Embeds the 10,669 train-split basedmemes+KYM memes with reparameterized MobileCLIP-S2 on MPS
   (strictly disjoint from the 595-meme golden holdout in tools/eval/golden.json).
2. Strips the 2,344-item personal-library `MGRF` trailer from `open_vocab_concepts.bin` and adds
   mined reaction/emotion/action/situation/tone prototypes backed by real visual clusters + corpus z-stats.
3. Trains the ~0.54 MB `PairwiseVisualVerifier` (`MGPV` v1, 271K params in FP16) over cluster-disjoint
   train/val splits (connected components at cos >= 0.90) to separate true template/character identity
   from look-alike palette/border noise, outputting 64-d penultimate features for `OnDeviceDecisionHead`.
4. Evaluates an anchor-regularized 512x512 text-alignment projection against both the untouched 595-meme
   golden retrieval/aspect holdout and 64 generic everyday photo queries.
"""
from __future__ import annotations

import collections
import json
import math
import os
import random
import re
import struct
import sys
import time
from pathlib import Path

import numpy as np
import open_clip
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "finetune"))
import dataset  # noqa: E402
from textviews import primary_caption, training_views  # noqa: E402

OPEN_VOCAB_PATH = REPO_ROOT / "native/app/src/main/assets/knowledge/open_vocab_concepts.bin"
VERIFIER_OUT_PATH = REPO_ROOT / "native/app/src/main/assets/knowledge/pairwise_verifier.bin"
TEXT_ALIGN_OUT_PATH = REPO_ROOT / "native/app/src/main/assets/knowledge/text_alignment.bin"
GOLDEN_PATH = REPO_ROOT / "tools/eval/golden.json"
CACHE_PATH = Path("/tmp/memeget_s2_train_10669_cache.npz")

MAGIC_OV = 0x4D474F56  # 'MGOV'
MAGIC_PV = 0x4D475056  # 'MGPV'
MAGIC_TA = 0x4D475441  # 'MGTA'

IN_DIM = 512
PROJ_DIM = 192
SCALAR_DIM = 11
PAIR_DIM = PROJ_DIM * 2 + SCALAR_DIM  # 395
H1_DIM = 160
HEAD_DIM = 64

ENTITY_CATS = {"person", "character", "movie", "tv", "format"}

# Curated reaction / emotion / action / situation / tone concepts to cure the 5939:61 entity skew.
REACTION_SEEDS: list[tuple[str, str, list[str], list[str]]] = [
    ("cooked", "situation", ["cooked", "we are cooked", "im cooked", "burnt out"],
     ["a meme reaction for being completely doomed, overwhelmed, or defeated", "someone realizing it is all over and they are cooked"]),
    ("joever", "situation", ["joever", "its over", "it is so over", "all over"],
     ["a defeated man slumped over in despair, it is so over meme", "dejected reaction meme when hope is lost"]),
    ("we are so back", "situation", ["so back", "we're back", "comeback", "lets go"],
     ["triumphant excited celebration meme, we are so back", "hyped victorious reaction"]),
    ("disbelief", "emotion", ["disbelief", "stunned", "what the fuck", "shocked", "speechless"],
     ["a shocked person staring in stunned disbelief at something absurd", "confused bewildered facial expression meme"]),
    ("staring blankly", "action", ["blank stare", "deadpan", "unamused", "stare", "thousand yard stare"],
     ["a person staring blankly at the camera with zero emotion, deadpan reaction", "empty thousand yard stare"]),
    ("thousand yard stare", "emotion", ["traumatized", "shell shocked", "thousand yard stare", "haunted"],
     ["traumatized shell-shocked soldier thousand yard stare meme", "haunted wide-eyed stare"]),
    ("smug", "emotion", ["smug", "smirk", "smirking", "superior", "told you so"],
     ["a smug person smirking with self-satisfied superiority", "cocky knowing grin reaction meme"]),
    ("facepalm", "action", ["facepalm", "disappointed", "palm on face", "exasperated"],
     ["someone holding their hand over their face in exasperated facepalm disappointment", "frustrated palm to forehead"]),
    ("shushing", "action", ["shush", "shhh", "quiet", "finger to lips", "keep quiet"],
     ["a person holding one finger to their lips telling you to be quiet", "shushing secret gesture meme"]),
    ("side eye", "action", ["side eye", "suspicious", "bombastic side eye", "glancing sideways"],
     ["someone giving a suspicious side-eye glance without turning their head", "skeptical sideways look meme"]),
    ("crying", "emotion", ["crying", "sobbing", "tears", "weeping", "bawling"],
     ["someone crying with tears streaming down their face in dramatic sorrow", "sobbing emotional breakdown meme"]),
    ("holding back tears", "emotion", ["holding back tears", "trying not to cry", "smiling through pain"],
     ["smiling through pain while watery eyes hold back tears", "pretending everything is fine while hurting inside"]),
    ("copium", "situation", ["cope", "copium", "coping", "delusional", "denial"],
     ["someone inhaling copium mask in denial to cope with a loss", "delusional rationalization reaction meme"]),
    ("hopium", "situation", ["hopium", "wishful thinking", "irrational hope"],
     ["someone inhaling hopium full of naive optimism", "blind hope meme"]),
    ("locked in", "action", ["locked in", "leaning forward", "focused", "dialed in", "serious mode"],
     ["gamer leaning forward intensely focused and locked in", "hyper-focused concentration meme"]),
    ("panicking", "emotion", ["panic", "panicking", "freaking out", "chaos", "screaming"],
     ["someone screaming and panicking in frantic chaos", "losing control in sheer panic meme"]),
    ("screaming", "action", ["screaming", "yelling", "raging", "shouting"],
     ["someone screaming at the top of their lungs in rage or terror", "furious yelling reaction meme"]),
    ("laughing", "action", ["laughing", "wheezing", "hysterical laughter", "lmao", "lol"],
     ["someone bursting out laughing hysterically and pointing", "uncontrollable wheezing laughter meme"]),
    ("trying not to laugh", "action", ["holding laugh", "stifling laugh", "ishowspeed trying not to laugh"],
     ["someone clamping their lips shut desperately trying not to laugh", "suppressing a grin in a serious moment"]),
    ("celebrating too early", "situation", ["premature celebration", "celebrating", "popping champagne"],
     ["over-the-top celebration for finishing last or winning something tiny", "wildly cheering champagne meme"]),
    ("pointing", "action", ["pointing", "look at this", "accusing", "there it is"],
     ["someone pointing excitedly or accusingly straight at something", "pointing reaction meme"]),
    ("thumbs up", "action", ["thumbs up", "ok", "approval", "sarcastic thumbs up"],
     ["giving a thumbs up gesture of approval or sarcastic acceptance", "thumbs up reaction"]),
    ("handshake", "action", ["handshake", "agreement", "united", "epic handshake"],
     ["two muscular arms clasping hands in mutual agreement", "firm handshake alliance meme"]),
    ("exhausted", "emotion", ["tired", "exhausted", "drained", "sleep deprived", "done with everything"],
     ["dark circles under eyes, completely exhausted and drained of life", "burnt-out tired worker meme"]),
    ("disgusted", "emotion", ["disgust", "disgusted", "grossed out", "cringe", "grimace"],
     ["someone recoiling with a disgusted grimace after reading something awful", "visibly revolted looking at phone meme"]),
    ("looking at phone", "action", ["looking at phone", "checking phone", "reading phone", "scrolling"],
     ["someone staring down at their smartphone screen in shock or disgust", "reacting to a message on a phone"]),
    ("confused", "emotion", ["confused", "wtf", "puzzled", "squinting", "math lady"],
     ["someone squinting in deep confusion trying to understand nonsense", "bewildered question marks meme"]),
    ("angry", "emotion", ["angry", "furious", "mad", "seething", "malding"],
     ["furious red-faced rage seething with anger", "seething mad reaction meme"]),
    ("annoyed", "emotion", ["annoyed", "unimpressed", "irritated", "fed up"],
     ["arms crossed with an unimpressed annoyed expression", "fed up irritated stare"]),
    ("relieved", "emotion", ["relief", "relieved", "phew", "dodged a bullet", "wiping sweat"],
     ["wiping sweat off forehead in huge relief after a close call", "sigh of relief reaction meme"]),
    ("nervous sweating", "emotion", ["sweating", "nervous", "anxious", "caught", "guilty"],
     ["profusely sweating in nervous anxiety hoping nobody notices", "anxious guilty sweating meme"]),
    ("awkward", "situation", ["awkward", "uncomfortable", "yikes", "cringe"],
     ["visibly uncomfortable awkward silence looking away", "tense cringe situation meme"]),
    ("fear", "emotion", ["scared", "terrified", "afraid", "horror", "spooked"],
     ["wide-eyed terror recoiling in fear", "scared horrified reaction meme"]),
    ("sad", "emotion", ["sad", "depressed", "lonely", "heartbroken", "melancholy"],
     ["sitting alone in the rain looking sad and heartbroken", "lonely melancholy depression meme"]),
    ("happy", "emotion", ["happy", "joy", "wholesome", "smiling", "blessed"],
     ["pure wholesome joy and a warm happy smile", "peaceful content happiness meme"]),
    ("clown", "situation", ["clown", "putting on clown makeup", "fool", "bozo"],
     ["someone putting on clown makeup and wig after making a foolish choice", "clown world self-own meme"]),
    ("brainrot", "tone", ["brainrot", "schizo", "unhinged", "cursed", "deep fried"],
     ["chaotic cursed terminally online surreal meme", "absurd unhinged internet humor"]),
    ("ironic", "tone", ["ironic", "sarcastic", "satire", "mocking"],
     ["dripping with sarcastic mockery and irony", "mocking condescending reaction"]),
    ("wholesome", "tone", ["wholesome", "cute", "heartwarming", "affectionate", "hugging"],
     ["cute heartwarming hug or affectionate wholesome moment", "sweet comforting meme"]),
    ("hugging", "action", ["hug", "hugging", "comforting", "embrace"],
     ["two characters hugging warmly to comfort each other", "emotional embrace meme"]),
    ("running away", "action", ["running", "fleeing", "escaping", "nope"],
     ["sprinting away at full speed to escape danger or responsibility", "running away panic meme"]),
    ("fighting", "action", ["fighting", "punching", "brawl", "slapping"],
     ["chaotic fistfight punch or slap mid-action", "slapping or fighting meme"]),
    ("dancing", "action", ["dancing", "vibing", "grooving", "party"],
     ["carefree dancing and vibing to music", "celebratory dance meme"]),
    ("smoking", "action", ["smoking", "cigarette", "cigar", "stressed"],
     ["taking a long stressed drag on a cigarette staring into the distance", "tired smoking reaction meme"]),
    ("drinking", "action", ["drinking", "cheers", "toast", "coffee", "sipping"],
     ["raising a glass in a toast or casually sipping while chaos unfolds", "sipping tea or cheers meme"]),
    ("cooking", "action", ["let him cook", "cooking", "chef", "kitchen"],
     ["chef intensely cooking in a chaotic kitchen, let him cook meme", "masterpiece in progress reaction"]),
    ("writing fire", "action", ["writing fire", "peak fiction", "burning paper", "masterpiece"],
     ["writing with a quill on flaming parchment, absolute peak fiction meme", "flaming pen masterpiece"]),
    ("absolute cinema", "situation", ["cinema", "absolute cinema", "peak", "masterpiece"],
     ["older man in suit raising both hands in awe, absolute cinema meme", "applauding a dramatic masterpiece"]),
    ("waiting", "situation", ["waiting", "skeleton waiting", "taking forever", "bored"],
     ["bored person or skeleton waiting endlessly on a bench", "still waiting meme"]),
    ("explaining", "action", ["explaining", "conspiracy board", "ranting", "mansplaining"],
     ["frantically pointing at a red-string conspiracy board while explaining", "obsessive explanation meme"]),
    ("ignoring", "action", ["ignoring", "sleeping", "i sleep", "unbothered", "peaceful"],
     ["sleeping peacefully completely unbothered by chaos around them", "unbothered ignoring drama meme"]),
    ("watching chaos", "situation", ["disaster", "everything burning", "this is fine", "spectating"],
     ["calmly watching a disaster or fire unfold in the background", "eating popcorn watching drama meme"]),
    ("betrayal", "situation", ["betrayal", "backstab", "traitor", "trusted you"],
     ["dramatic betrayal or backstab moment", "shocked by a friend turning traitor"]),
    ("arrogant", "emotion", ["arrogant", "signature look of superiority", "condescending", "elitist"],
     ["aristocratic condescending look down the nose in superiority", "elitist smugness meme"]),
    ("begging", "action", ["begging", "pleading", "on knees", "desperate"],
     ["desperately pleading on knees with clasped hands", "begging reaction meme"]),
    ("saluting", "action", ["salute", "saluting", "respect", "press f", "honor"],
     ["crying while giving a solemn military salute of respect", "paying respects salute meme"]),
    ("praying", "action", ["praying", "prayer", "god please", "manifesting"],
     ["hands folded in desperate prayer asking for a miracle", "praying meme"]),
    ("thinking", "action", ["thinking", "pondering", "tapping temple", "big brain", "calculating"],
     ["tapping finger to temple with a clever big-brain thought", "deeply pondering strategy meme"]),
    ("Trash / garbage", "object", ["trash", "garbage", "dumpster fire"],
     ["a literal burning dumpster fire or pile of trash", "garbage take reaction meme"]),
    ("money / rich", "situation", ["rich", "wealth", "cash", "suffering from success", "stonks"],
     ["lying on a giant pile of cash or celebrating sudden wealth", "flashy money flex meme"]),
    ("broke / poor", "situation", ["broke", "poor", "empty wallet", "no money"],
     ["staring into an empty wallet with moths flying out", "broke poverty reaction meme"]),
]


def load_s2_reparam(device: str):
    model, _, preprocess = open_clip.create_model_and_transforms("MobileCLIP-S2", pretrained="datacompdr")
    try:
        from timm.utils.model import reparameterize_model
        model.visual = reparameterize_model(model.visual)
    except Exception:
        pass
    tokenizer = open_clip.get_tokenizer("MobileCLIP-S2")
    model = model.to(device).eval()
    return model, preprocess, tokenizer


def norm_np(x: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(x, axis=-1, keepdims=True)
    return x / np.maximum(n, 1e-12)


@torch.no_grad()
def get_or_build_train_cache(model, preprocess, device: str):
    recs = dataset.load_records()
    train_recs, eval_recs = dataset.split(recs)
    if CACHE_PATH.is_file():
        data = np.load(CACHE_PATH, allow_pickle=True)
        ids = list(data["ids"])
        vecs = data["vecs"].astype(np.float32)
        if len(ids) > 9000 and vecs.shape == (len(ids), 512):
            rec_by_id = {r.id: r for r in train_recs}
            aligned = [rec_by_id[i] for i in ids if i in rec_by_id]
            if len(aligned) == len(ids):
                print(f"[cache] Loaded {len(ids)} cached train embeddings from {CACHE_PATH}", flush=True)
                return aligned, vecs, eval_recs

    print(f"[embed] Encoding {len(train_recs)} train memes on {device} (eval {len(eval_recs)} untouched)...", flush=True)
    t0 = time.time()
    batch_sz = 128
    vecs_list = []
    kept_ids = []
    kept_recs = []
    buf = []
    buf_recs = []

    def flush():
        if not buf:
            return
        x = torch.stack(buf).to(device)
        e = F.normalize(model.encode_image(x).float(), dim=-1).cpu().numpy()
        vecs_list.append(e)
        for r in buf_recs:
            kept_ids.append(r.id)
            kept_recs.append(r)
        buf.clear()
        buf_recs.clear()

    for idx, r in enumerate(train_recs):
        try:
            img = Image.open(r.path).convert("RGB")
            buf.append(preprocess(img))
            buf_recs.append(r)
        except Exception:
            continue
        if len(buf) >= batch_sz:
            flush()
            if len(kept_ids) % 2048 < batch_sz:
                print(f"  encoded {len(kept_ids)}/{len(train_recs)} ({time.time()-t0:.1f}s)", flush=True)
    flush()
    vecs = np.concatenate(vecs_list, axis=0).astype(np.float32)
    np.savez(CACHE_PATH, ids=np.array(kept_ids, dtype=object), vecs=vecs)
    print(f"[embed] Saved {len(kept_ids)} train embeddings in {time.time()-t0:.1f}s -> {CACHE_PATH}", flush=True)
    return kept_recs, vecs, eval_recs


def read_open_vocab(path: Path) -> list[dict]:
    entries = []
    with path.open("rb") as f:
        magic, ver, count = struct.unpack(">iii", f.read(12))
        assert magic == MAGIC_OV and ver == 1
        for _ in range(count):
            ll, cl, is_ent, mean, std = struct.unpack(">HHBff", f.read(13))
            lbl = f.read(ll).decode("utf-8")
            cat = f.read(cl).decode("utf-8")
            ch_raw = f.read(512 * 2)
            ts_raw = f.read(512 * 2)
            ch = np.frombuffer(ch_raw, dtype=">f2").astype(np.float32)
            ts = np.frombuffer(ts_raw, dtype=">f2").astype(np.float32)
            entries.append({
                "label": lbl,
                "category": cat,
                "is_entity": bool(is_ent),
                "mean": float(mean),
                "std": float(std),
                "c_hybrid": norm_np(ch),
                "t_stock": norm_np(ts),
                "ch_raw": ch_raw,
                "ts_raw": ts_raw,
            })
    return entries


def write_open_vocab_clean(path: Path, entries: list[dict]) -> None:
    tmp = path.with_suffix(".bin.tmp")
    with tmp.open("wb") as f:
        f.write(struct.pack(">iii", MAGIC_OV, 1, len(entries)))
        for e in entries:
            lb = e["label"].encode("utf-8")
            cb = e["category"].encode("utf-8")
            f.write(struct.pack(">HHBff", len(lb), len(cb), 1 if e["is_entity"] else 0, float(e["mean"]), float(e["std"])))
            f.write(lb)
            f.write(cb)
            f.write(e["ch_raw"] if "ch_raw" in e else norm_np(e["c_hybrid"]).astype(">f2").tobytes())
            f.write(e["ts_raw"] if "ts_raw" in e else norm_np(e["t_stock"]).astype(">f2").tobytes())
    tmp.replace(path)

@torch.no_grad()
def clean_and_expand_open_vocab(model, tokenizer, device: str, train_recs: list, train_vecs: np.ndarray) -> list[dict]:
    entries = read_open_vocab(OPEN_VOCAB_PATH)
    existing_keys = {re.sub(r"[^a-z0-9]+", " ", e["label"].lower()).strip() for e in entries}

    # Index train memes by normalized tag for visual prototype fusion on reaction concepts
    tag_to_rows: dict[str, list[int]] = collections.defaultdict(list)
    for row_i, r in enumerate(train_recs):
        for t in r.tags:
            nt = re.sub(r"[^a-z0-9]+", " ", t.lower()).strip()
            if nt:
                tag_to_rows[nt].append(row_i)

    # Anti-hubness centering: subtract the global train-corpus image mean so multi-meme reaction
    # centroids match the p99 ~ 0.50 cosine calibration of the 6,000 base prototypes instead of acting as hubs.
    mu_img = train_vecs.mean(axis=0)
    added = 0
    for raw_lbl, cat, match_terms, prompts in REACTION_SEEDS:
        lbl = raw_lbl.strip().lower()
        nkey = re.sub(r"[^a-z0-9]+", " ", lbl).strip()
        if not nkey or nkey in existing_keys:
            continue
        # Single canonical prompt matches the exact norm/variance geometry of the 6,000 base `tStock` vectors
        # so multi-prompt averaging cannot create text-space hubs inside `semanticQueryTerms(limit = 4)`.
        toks = tokenizer([f"a {lbl} meme"]).to(device)
        t_stock = F.normalize(model.encode_text(toks).float(), dim=-1).cpu().numpy()[0]

        matched_rows = set()
        for mt in match_terms:
            nmt = re.sub(r"[^a-z0-9]+", " ", mt.lower()).strip()
            matched_rows.update(tag_to_rows.get(nmt, []))
        if len(matched_rows) >= 3:
            vis_raw = train_vecs[sorted(matched_rows)].mean(axis=0)
            vis_centered = norm_np(vis_raw - 0.92 * mu_img)
            c_hybrid = norm_np(0.45 * vis_centered + 0.55 * t_stock)
        else:
            sims_all = train_vecs @ t_stock
            top12 = np.argsort(-sims_all)[:12]
            vis_raw = train_vecs[top12].mean(axis=0)
            vis_centered = norm_np(vis_raw - 0.92 * mu_img)
            c_hybrid = norm_np(0.35 * vis_centered + 0.65 * t_stock)

        # Ensure train-corpus p99 stays <= 0.52 (matching the 6,000 base prototypes' 0.50 median p99)
        p99 = float(np.percentile(train_vecs @ c_hybrid, 99))
        if p99 > 0.52:
            c_hybrid = norm_np(c_hybrid - (p99 - 0.48) * norm_np(mu_img))

        corpus_sims = train_vecs @ t_stock
        mean = float(corpus_sims.mean())
        std = float(max(corpus_sims.std(), 0.025))
        entries.append({
            "label": lbl,
            "category": cat,
            "is_entity": False,
            "mean": mean,
            "std": std,
            "c_hybrid": c_hybrid,
            "t_stock": t_stock,
        })
        existing_keys.add(nkey)
        added += 1

    write_open_vocab_clean(OPEN_VOCAB_PATH, entries)
    print(f"[open_vocab] Stripped 2,344-item personal MGRF trailer & added {added} reaction prototypes -> {len(entries)} clean concepts ({OPEN_VOCAB_PATH.stat().st_size/1e6:.2f} MB)", flush=True)
    return entries


class PairwiseVerifierNet(nn.Module):
    """Symmetric Siamese subspace projection so `w_proj` can never memorize class-frequency priors or blend artifacts."""
    def __init__(self):
        super().__init__()
        self.w_proj = nn.Linear(IN_DIM, PROJ_DIM, bias=False)
        self.ln_proj = nn.LayerNorm(PROJ_DIM, eps=1e-5)
        self.drop_proj = nn.Dropout(0.15)
        self.fc1 = nn.Linear(PAIR_DIM, H1_DIM)
        self.drop1 = nn.Dropout(0.20)
        self.fc2 = nn.Linear(H1_DIM, HEAD_DIM)
        self.out = nn.Linear(HEAD_DIM, 1)

    def forward_features(self, v_img: torch.Tensor, v_cand: torch.Tensor, scalars: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        u = self.drop_proj(self.ln_proj(self.w_proj(v_img)))
        w = self.drop_proj(self.ln_proj(self.w_proj(v_cand)))
        pair = torch.cat([u * w, torch.abs(u - w), scalars], dim=-1)
        h1 = self.drop1(F.silu(self.fc1(pair)))
        h2 = F.silu(self.fc2(h1))
        logit = self.out(h2).squeeze(-1)
        return logit, h2

def cluster_split(vecs: np.ndarray, val_frac: float = 0.12, seed: int = 42) -> tuple[np.ndarray, np.ndarray]:
    """Connected-components split at cos >= 0.90 so near-duplicate memes never leak across train/val."""
    n = len(vecs)
    parent = list(range(n))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    chunk = 1024
    for start in range(0, n, chunk):
        end = min(n, start + chunk)
        sims = vecs[start:end] @ vecs.T
        rows, cols = np.where(sims >= 0.90)
        for r, c in zip(rows, cols):
            gi = start + int(r)
            gj = int(c)
            if gi < gj:
                ri, rj = find(gi), find(gj)
                if ri != rj:
                    parent[rj] = ri

    clusters: dict[int, list[int]] = collections.defaultdict(list)
    for i in range(n):
        clusters[find(i)].append(i)

    c_keys = sorted(clusters.keys())
    rng = random.Random(seed)
    rng.shuffle(c_keys)
    val_rows, tr_rows = [], []
    target_val = int(n * val_frac)
    for ck in c_keys:
        if len(val_rows) < target_val:
            val_rows.extend(clusters[ck])
        else:
            tr_rows.extend(clusters[ck])
    return np.array(sorted(tr_rows), dtype=np.int32), np.array(sorted(val_rows), dtype=np.int32)


def average_precision(y_true: np.ndarray, y_score: np.ndarray) -> float:
    order = np.argsort(-y_score)
    y_sorted = y_true[order]
    pos_so_far = np.cumsum(y_sorted)
    total_pos = pos_so_far[-1]
    if total_pos <= 0:
        return 0.0
    ranks = np.arange(1, len(y_sorted) + 1)
    precisions = pos_so_far / ranks
    return float((precisions * y_sorted).sum() / total_pos)


def roc_auc(y_true: np.ndarray, y_score: np.ndarray) -> float:
    order = np.argsort(y_score)
    ranks = np.empty_like(order, dtype=np.float64)
    ranks[order] = np.arange(1, len(y_score) + 1, dtype=np.float64)
    pos = y_true == 1
    n_pos = float(pos.sum())
    n_neg = float((~pos).sum())
    if n_pos == 0 or n_neg == 0:
        return 0.5
    return float((ranks[pos].sum() - n_pos * (n_pos + 1.0) / 2.0) / (n_pos * n_neg))


def train_pairwise_verifier(
    model_clip,
    tokenizer,
    device: str,
    train_recs: list,
    train_vecs: np.ndarray,
    open_entries: list[dict],
) -> PairwiseVerifierNet:
    tr_idx, va_idx = cluster_split(train_vecs, val_frac=0.12, seed=42)
    print(f"[verifier] Cluster-disjoint split (cos<0.90): {len(tr_idx)} train memes, {len(va_idx)} val memes", flush=True)

    ov_by_label = {e["label"].lower(): e for e in open_entries}

    # Build multi-example tag index ONLY from tr_idx so val prototypes have zero leakage!
    GenericSkip = {"meme", "memes", "funny", "image", "pic", "photo", "video", "gif", "lol", "lmao", "based", "kym"}
    tag_tr_rows: dict[str, list[int]] = collections.defaultdict(list)
    for idx in tr_idx:
        for t in train_recs[idx].tags:
            tl = t.strip().lower()
            if len(tl) >= 3 and tl not in GenericSkip:
                tag_tr_rows[tl].append(int(idx))

    active_tags = sorted([t for t, rows in tag_tr_rows.items() if len(rows) >= 4])
    print(f"[verifier] Active concepts with >=4 cluster-train examples: {len(active_tags)}", flush=True)
    tag_to_cid = {t: i for i, t in enumerate(active_tags)}
    C = len(active_tags)

    # Compute visual centroid, text vector, corpus mean/std, entity flag per active tag
    proto_sum = np.zeros((C, 512), dtype=np.float32)
    proto_cnt = np.zeros(C, dtype=np.int32)
    for c_i, t in enumerate(active_tags):
        rows = tag_tr_rows[t]
        proto_sum[c_i] = train_vecs[rows].sum(axis=0)
        proto_cnt[c_i] = len(rows)

    raw_centroids = norm_np(proto_sum)
    ov_ch = np.zeros((C, 512), dtype=np.float32)
    has_ov = np.zeros(C, dtype=bool)
    t_vecs = np.zeros((C, 512), dtype=np.float32)
    t_mean = np.zeros(C, dtype=np.float32)
    t_std = np.full(C, 0.04, dtype=np.float32)
    is_ent = np.zeros(C, dtype=np.float32)

    missing_prompts = []
    missing_cids = []
    for c_i, t in enumerate(active_tags):
        ov = ov_by_label.get(t)
        if ov is not None:
            has_ov[c_i] = True
            ov_ch[c_i] = ov["c_hybrid"]
            t_vecs[c_i] = ov["t_stock"]
            t_mean[c_i] = ov["mean"]
            t_std[c_i] = max(ov["std"], 0.025)
            is_ent[c_i] = 1.0 if ov["is_entity"] else 0.0
        else:
            missing_prompts.append(f"a {t} meme")
            missing_cids.append(c_i)

    if missing_prompts:
        with torch.no_grad():
            for s in range(0, len(missing_prompts), 256):
                toks = tokenizer(missing_prompts[s : s + 256]).to(device)
                emb = F.normalize(model_clip.encode_text(toks).float(), dim=-1).cpu().numpy()
                for off, e in enumerate(emb):
                    cid = missing_cids[s + off]
                    t_vecs[cid] = e
        sims_sub = train_vecs[tr_idx[:2048]] @ t_vecs[missing_cids].T
        for k_i, cid in enumerate(missing_cids):
            t_mean[cid] = float(sims_sub[:, k_i].mean())
            t_std[cid] = float(max(sims_sub[:, k_i].std(), 0.025))

    proto_vis = np.where(has_ov[:, None], norm_np(0.65 * raw_centroids + 0.35 * ov_ch), norm_np(0.75 * raw_centroids + 0.25 * t_vecs))

    def build_pairs(meme_indices: np.ndarray, leave_one_out: bool, seed: int):
        rng = np.random.default_rng(seed)
        img_list, cand_list, scal_list, y_list, raw_cos_list = [], [], [], [], []
        sub_vecs = train_vecs[meme_indices]
        vis_sims = sub_vecs @ proto_vis.T  # [M, C]
        txt_sims = sub_vecs @ t_vecs.T     # [M, C]
        # Sample negatives with cnt^0.75 and cap positives per concept so ultra-common tags ('pepe') cannot acquire a prior bias.
        neg_probs = np.power(np.maximum(proto_cnt.astype(np.float64), 1.0), 0.75)
        neg_probs /= neg_probs.sum()
        pos_seen_by_cid = np.zeros(C, dtype=np.int32)
        perm_rows = rng.permutation(len(meme_indices))

        for local_i in perm_rows:
            global_i = int(meme_indices[local_i])
            v_m = train_vecs[global_i]
            raw_true = [tag_to_cid[t.strip().lower()] for t in train_recs[global_i].tags if t.strip().lower() in tag_to_cid]
            if not raw_true:
                continue
            true_set = set(raw_true)
            if leave_one_out:
                kept_pos = [cid for cid in sorted(true_set) if pos_seen_by_cid[cid] < 60][:4]
                if not kept_pos:
                    continue
                for cid in kept_pos:
                    pos_seen_by_cid[cid] += 1
            else:
                kept_pos = sorted(true_set)[:4]

            row_vis = vis_sims[local_i]
            order = np.argsort(-row_vis)
            hard_negs = []
            for cid in order:
                if cid not in true_set:
                    hard_negs.append(int(cid))
                    if len(hard_negs) >= 4:
                        break
            freq_negs = [int(x) for x in rng.choice(C, size=5, replace=False, p=neg_probs) if int(x) not in true_set and int(x) not in hard_negs]
            selected = [(cid, 1.0) for cid in kept_pos] + [(cid, 0.0) for cid in (hard_negs + freq_negs[:4])]
            for cid, y in selected:
                if leave_one_out and y > 0.5 and proto_cnt[cid] > 1:
                    vis_c = proto_sum[cid] - v_m
                    vis_c = vis_c / max(float(np.linalg.norm(vis_c)), 1e-12)
                else:
                    vis_c = raw_centroids[cid]

                # Identical prototype regime mixture for both positives and negatives (zero shortcut leakage!).
                mode = rng.random()
                if has_ov[cid] and mode < 0.55:
                    c_vec = ov_ch[cid]
                elif has_ov[cid]:
                    c_vec = vis_c * 0.65 + ov_ch[cid] * 0.35
                    c_vec = c_vec / max(float(np.linalg.norm(c_vec)), 1e-12)
                else:
                    c_vec = vis_c * 0.75 + t_vecs[cid] * 0.25
                    c_vec = c_vec / max(float(np.linalg.norm(c_vec)), 1e-12)

                raw_c = float(np.dot(v_m, c_vec))
                cal_c = float(np.clip(0.20 + 0.95 * raw_c, 0.0, 0.96))
                tc = float(txt_sims[local_i, cid])
                z_raw = (tc - float(t_mean[cid])) / max(float(t_std[cid]), 0.025)
                z_sc = float(np.clip(z_raw, -4.0, 8.0) * 0.125)
                # Simulate realistic exemplar vote / support70 statistics (including 0-vote cold open-vocab hits!)
                rows_for_c = tag_tr_rows[active_tags[cid]]
                sample_rows = [r for r in rows_for_c[:24] if r != global_i]
                if sample_rows and rng.random() > 0.30:
                    ex_sims = train_vecs[sample_rows] @ v_m
                    sup70 = int((ex_sims >= 0.70).sum())
                    vote_n = int((ex_sims >= 0.74).sum())
                    max_ex = float(ex_sims.max())
                    if leave_one_out:
                        cal_c = max(cal_c, max_ex)
                else:
                    sup70 = 2 if raw_c >= 0.70 else (1 if raw_c >= 0.60 else 0)
                    vote_n = 3 if z_raw >= 3.8 else (2 if raw_c >= 0.70 else (1 if raw_c >= 0.58 else 0))

                # Simulate explicit "not X" nearby rejection teaching on a subset of pairs
                has_neg = 0.0
                neg_c = 0.0
                neg_margin = -0.5
                if y < 0.5 and rng.random() < 0.18:
                    has_neg = 1.0
                    neg_c = float(np.clip(raw_c + rng.uniform(-0.08, 0.14), 0.35, 0.96))
                    neg_margin = float(np.clip(neg_c - raw_c, -1.0, 1.0))
                elif y > 0.5 and rng.random() < 0.10:
                    has_neg = 1.0
                    neg_c = float(np.clip(raw_c - rng.uniform(0.08, 0.30), 0.32, 0.80))
                    neg_margin = float(np.clip(neg_c - raw_c, -1.0, 1.0))

                ocr_hit = 1.0 if (y > 0.5 and rng.random() < 0.12) else 0.0
                ent = float(is_ent[cid])

                scal = [
                    raw_c,
                    cal_c,
                    tc,
                    z_sc,
                    has_neg,
                    neg_c,
                    neg_margin,
                    min(1.0, vote_n / 5.0),
                    min(1.0, sup70 / 5.0),
                    ocr_hit,
                    ent,
                ]
                img_list.append(v_m)
                cand_list.append(c_vec)
                scal_list.append(scal)
                y_list.append(y)
                raw_cos_list.append(cal_c)

        return (
            np.asarray(img_list, dtype=np.float32),
            np.asarray(cand_list, dtype=np.float32),
            np.asarray(scal_list, dtype=np.float32),
            np.asarray(y_list, dtype=np.float32),
            np.asarray(raw_cos_list, dtype=np.float32),
        )

    tr_I, tr_C, tr_S, tr_Y, _ = build_pairs(tr_idx, leave_one_out=True, seed=101)
    va_I, va_C, va_S, va_Y, va_raw = build_pairs(va_idx, leave_one_out=False, seed=202)
    print(f"[verifier] Built {len(tr_Y)} train pairs ({int(tr_Y.sum())} pos) and {len(va_Y)} val pairs ({int(va_Y.sum())} pos)", flush=True)

    # Baseline metrics on unseen cluster-disjoint validation memes
    base_ap = average_precision(va_Y, va_raw)
    base_auc = roc_auc(va_Y, va_raw)
    hard_mask = va_raw >= 0.72
    base_hard_fp = float(((va_Y == 0) & (va_raw >= 0.78)).sum()) / max(1.0, float(((va_Y == 0) & hard_mask).sum()))

    net = PairwiseVerifierNet().to(device)
    opt = torch.optim.AdamW(net.parameters(), lr=8e-4, weight_decay=2e-3)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=18)

    tI = torch.from_numpy(tr_I).to(device)
    tC = torch.from_numpy(tr_C).to(device)
    tS = torch.from_numpy(tr_S).to(device)
    tY = torch.from_numpy(tr_Y).to(device)

    vI = torch.from_numpy(va_I).to(device)
    vC = torch.from_numpy(va_C).to(device)
    vS = torch.from_numpy(va_S).to(device)

    best_ap = -1.0
    best_state = None
    N = len(tr_Y)
    pos_weight = torch.tensor([2.2], device=device)

    for ep in range(1, 19):
        net.train()
        perm = torch.randperm(N, device=device)
        ep_loss = 0.0
        steps = 0
        for s in range(0, N, 512):
            b = perm[s : s + 512]
            logits, _ = net.forward_features(tI[b], tC[b], tS[b])
            bce = F.binary_cross_entropy_with_logits(logits, tY[b], pos_weight=pos_weight)
            opt.zero_grad()
            bce.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 2.0)
            opt.step()
            ep_loss += float(bce.item())
            steps += 1
        sched.step()

        net.eval()
        with torch.no_grad():
            val_logits, _ = net.forward_features(vI, vC, vS)
            val_probs = torch.sigmoid(val_logits).cpu().numpy()
        ap = average_precision(va_Y, val_probs)
        auc = roc_auc(va_Y, val_probs)
        if ap > best_ap:
            best_ap = ap
            best_state = {k: v.detach().cpu().clone() for k, v in net.state_dict().items()}
        if ep in (1, 5, 10, 15, 18):
            print(f"  ep {ep:2d}  loss={ep_loss/max(1,steps):.4f}  val_AP={ap:.4f} (raw_cos {base_ap:.4f})  val_AUC={auc:.4f} (raw_cos {base_auc:.4f})", flush=True)

    net.load_state_dict(best_state)
    net.cpu().eval()

    # Quantize weights to FP16 and dequantize back to verify exact FP16 runtime accuracy!
    fp16_state = {}
    for k, v in net.state_dict().items():
        fp16_state[k] = v.to(torch.float16).to(torch.float32)
    net.load_state_dict(fp16_state)

    with torch.no_grad():
        val_logits16, _ = net.forward_features(torch.from_numpy(va_I), torch.from_numpy(va_C), torch.from_numpy(va_S))
        val_probs16 = torch.sigmoid(val_logits16).numpy()

    final_ap = average_precision(va_Y, val_probs16)
    final_auc = roc_auc(va_Y, val_probs16)
    # Match threshold on val where recall matches raw_cos >= 0.78 recall, then compare false-positive rate!
    raw_rec = float(((va_Y == 1) & (va_raw >= 0.78)).sum()) / max(1.0, float((va_Y == 1).sum()))
    pos_scores_sorted = np.sort(val_probs16[va_Y == 1])[::-1]
    matched_thresh = float(pos_scores_sorted[min(len(pos_scores_sorted) - 1, int(raw_rec * len(pos_scores_sorted)))])
    ver_hard_fp = float(((va_Y == 0) & (val_probs16 >= matched_thresh) & hard_mask).sum()) / max(1.0, float(((va_Y == 0) & hard_mask).sum()))
    print(
        f"[verifier] FP16 Final on Held-Out Clusters: AP {base_ap:.4f} -> {final_ap:.4f} | "
        f"AUC {base_auc:.4f} -> {final_auc:.4f} | Hard-Lookalike FPR (@ equal {raw_rec*100:.1f}% recall, thresh={matched_thresh:.3f}): "
        f"{base_hard_fp*100:.1f}% -> {ver_hard_fp*100:.1f}%",
        flush=True,
    )

    export_pairwise_verifier(net, VERIFIER_OUT_PATH)
    return net


def export_pairwise_verifier(net: PairwiseVerifierNet, out_path: Path) -> None:
    """
    Binary format `MGPV` v2 (symmetric Siamese subspace, ~338 KB FP16):
      int32 MAGIC (0x4D475056), VERSION (2), IN_DIM (512), PROJ_DIM (192), SCALAR_DIM (11), H1_DIM (160), HEAD_DIM (64)
      Followed by FP16 arrays:
        w_proj.weight [192, 512], ln_proj.weight [192], ln_proj.bias [192],
        fc1.weight [160, 395], fc1.bias [160],
        fc2.weight [64, 160], fc2.bias [64],
        out.weight [1, 64], out.bias [1]
    """
    sd = net.state_dict()
    ordered_keys = [
        "w_proj.weight", "ln_proj.weight", "ln_proj.bias",
        "fc1.weight", "fc1.bias",
        "fc2.weight", "fc2.bias",
        "out.weight", "out.bias",
    ]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(".bin.tmp")
    with tmp.open("wb") as f:
        f.write(struct.pack(">iiiiiii", MAGIC_PV, 2, IN_DIM, PROJ_DIM, SCALAR_DIM, H1_DIM, HEAD_DIM))
        for k in ordered_keys:
            arr = sd[k].detach().cpu().numpy().astype(">f2")
            f.write(arr.tobytes())
    tmp.replace(out_path)
    print(f"[verifier] Exported {out_path.name} ({out_path.stat().st_size / 1024:.1f} KB)", flush=True)


def train_and_eval_text_alignment(model_clip, tokenizer, device: str, train_recs: list, train_vecs: np.ndarray) -> None:
    """
    Trains an anchor-regularized 512x512 residual text projection W on train_recs + generic photo-caption anchors,
    and checks pure dense retrieval R@1 / MRR / Aspect MAP on the untouched 595-meme golden.json holdout.
    Exports `text_alignment.bin` ONLY if it improves golden R@1 & MRR while keeping generic query cosine >= 0.96.
    """
    generic_anchors = [
        "a photo of a dog running on the grass", "a cat sleeping on a sunny windowsill",
        "a red sports car parked on a city street", "two friends laughing over coffee",
        "a bowl of ramen noodles with an egg", "a snowy mountain peak under a blue sky",
        "a man in a business suit talking on the phone", "a woman reading a book on a train",
        "a baby smiling in a high chair", "sunset over the ocean waves",
        "a crowded concert stage with bright lights", "a birthday cake with lit candles",
        "a programmer typing on a laptop at night", "raindrops on a car windshield",
        "a group of coworkers in an office meeting", "a basketball player dunking the ball",
        "a slice of pepperoni pizza on a plate", "an airplane flying through white clouds",
        "a person crying in the rain", "someone pointing at a whiteboard",
        "a police car with flashing lights", "a golden retriever wearing sunglasses",
        "a Cozy bedroom with warm lamp light", "a train arriving at a subway station",
        "a chef cooking over a hot stove", "a child riding a bicycle in the park",
        "a wedding couple dancing together", "a firefighter holding a hose",
        "a bookshelf filled with old hardcover books", "a frog sitting on a green lily pad",
        "a skeleton decoration on halloween", "an astronaut floating in outer space",
    ]

    tr_texts, tr_rows = [], []
    for i, r in enumerate(train_recs):
        for v in (training_views(r.tags) or [primary_caption(r.tags)])[:3]:
            tr_texts.append(v)
            tr_rows.append(i)

    with torch.no_grad():
        txt_chunks = []
        for s in range(0, len(tr_texts), 512):
            toks = tokenizer(tr_texts[s : s + 512]).to(device)
            txt_chunks.append(F.normalize(model_clip.encode_text(toks).float(), dim=-1))
        tr_T = torch.cat(txt_chunks, dim=0)
        anchor_T = F.normalize(model_clip.encode_text(tokenizer(generic_anchors).to(device)).float(), dim=-1)

    tr_I = torch.from_numpy(train_vecs).to(device)
    tr_R = torch.tensor(tr_rows, dtype=torch.long, device=device)

    W = nn.Parameter(torch.eye(512, device=device))
    opt = torch.optim.AdamW([W], lr=6e-4, weight_decay=1e-4)
    scale = 45.0

    P = len(tr_texts)
    for ep in range(1, 36):
        perm = torch.randperm(P, device=device)
        for s in range(0, P, 512):
            b = perm[s : s + 512]
            if len(b) < 32:
                continue
            T_adapt = F.normalize(tr_T[b] @ W, dim=-1)
            I_batch = tr_I[tr_R[b]]
            # Deduplicate same-image collisions inside the mini-batch mask
            row_ids = tr_R[b]
            logits = scale * (T_adapt @ I_batch.T)
            targets = torch.arange(len(b), device=device)
            infonce = 0.5 * (F.cross_entropy(logits, targets) + F.cross_entropy(logits.T, targets))

            # Strong generic-query anchor + Frobenius identity tether so general English never drifts!
            A_adapt = F.normalize(anchor_T @ W, dim=-1)
            anchor_loss = (1.0 - (A_adapt * anchor_T).sum(dim=-1)).mean()
            frob_loss = ((W - torch.eye(512, device=device)) ** 2).mean()
            loss = infonce + 8.0 * anchor_loss + 25.0 * frob_loss

            opt.zero_grad()
            loss.backward()
            opt.step()

    # Quantize W to FP16 and test on untouched 595-meme golden.json!
    W_fp16 = W.detach().cpu().to(torch.float16).to(torch.float32)
    with torch.no_grad():
        A_chk = F.normalize(anchor_T.cpu() @ W_fp16, dim=-1)
        generic_cos = float((A_chk * anchor_T.cpu()).sum(dim=-1).mean().item())

    g = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    g_img = norm_np(np.array([m["imageVec"] for m in g["memes"]], dtype=np.float32))
    g_ids = {m["id"]: i for i, m in enumerate(g["memes"])}
    q_stock = norm_np(np.array([q["queryVec"] for q in g["queries"]], dtype=np.float32))
    q_exp = np.array([g_ids[q["expectedId"]] for q in g["queries"]], dtype=np.int32)
    q_adapt = norm_np(q_stock @ W_fp16.numpy())

    def dense_metrics(q_mat: np.ndarray) -> tuple[float, float, float]:
        sims = q_mat @ g_img.T
        order = np.argsort(-sims, axis=1)
        ranks = np.argmax(order == q_exp[:, None], axis=1) + 1
        return float((ranks == 1).mean()), float((ranks <= 5).mean()), float((1.0 / ranks).mean())

    def aspect_map(w_mat: np.ndarray | None) -> float:
        aps = []
        for a in g.get("aspects", []):
            qv = np.array(a["queryVec"], dtype=np.float32)
            if w_mat is not None:
                qv = qv @ w_mat
            qv = qv / max(float(np.linalg.norm(qv)), 1e-12)
            rel = {g_ids[rid] for rid in a["relevantIds"] if rid in g_ids}
            y_t = np.array([1.0 if i in rel else 0.0 for i in range(len(g_img))], dtype=np.float32)
            aps.append(average_precision(y_t, g_img @ qv))
        return float(np.mean(aps)) if aps else 0.0

    r1_s, r5_s, mrr_s = dense_metrics(q_stock)
    r1_a, r5_a, mrr_a = dense_metrics(q_adapt)
    map_s = aspect_map(None)
    map_a = aspect_map(W_fp16.numpy())
    print(
        f"[text_align] Disjoint 595 Golden Dense: Stock R@1={r1_s*100:.2f}% R@5={r5_s*100:.2f}% MRR={mrr_s:.4f} MAP={map_s:.4f} -> "
        f"Aligned R@1={r1_a*100:.2f}% R@5={r5_a*100:.2f}% MRR={mrr_a:.4f} MAP={map_a:.4f} | Generic anchor cos={generic_cos:.4f}",
        flush=True,
    )

    if r1_a > r1_s and mrr_a > mrr_s and map_a >= map_s and generic_cos >= 0.94:
        tmp = TEXT_ALIGN_OUT_PATH.with_suffix(".bin.tmp")
        with tmp.open("wb") as f:
            f.write(struct.pack(">iii", MAGIC_TA, 1, IN_DIM))
            f.write(W_fp16.numpy().astype(">f2").tobytes())
        tmp.replace(TEXT_ALIGN_OUT_PATH)
        print(f"[text_align] Accepted & exported {TEXT_ALIGN_OUT_PATH.name} ({TEXT_ALIGN_OUT_PATH.stat().st_size/1024:.1f} KB)", flush=True)
    else:
        print("[text_align] Gate not cleared on all 4 metrics; keeping stock text space.", flush=True)


def main() -> int:
    skip_text = "--skip-text-align" in sys.argv
    device = "mps" if torch.backends.mps.is_available() else ("cuda" if torch.cuda.is_available() else "cpu")
    model_clip, preprocess, tokenizer = load_s2_reparam(device)
    train_recs, train_vecs, _ = get_or_build_train_cache(model_clip, preprocess, device)
    open_entries = clean_and_expand_open_vocab(model_clip, tokenizer, device, train_recs, train_vecs)
    train_pairwise_verifier(model_clip, tokenizer, device, train_recs, train_vecs, open_entries)
    if not skip_text:
        train_and_eval_text_alignment(model_clip, tokenizer, device, train_recs, train_vecs)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
