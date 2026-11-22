"""Fresh CPU inference with the released MobileCLIP-S2 ExecuTorch programs."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import torch
from executorch.runtime import Runtime
from PIL import Image
from tokenizers import Tokenizer

DIM = 512


def _load(path: Path):
    torch.set_num_threads(1)
    from executorch.extension.pybindings.portable_lib import _unsafe_reset_threadpool

    _unsafe_reset_threadpool(1)  # the ExecuTorch/XNNPACK pool, not the PyTorch pool
    return Runtime.get().load_program(str(path))


def _encode(method, inputs: list[torch.Tensor], out: np.ndarray) -> None:
    (result,) = method.execute(inputs)
    raw = np.asarray(result)
    if raw.shape != (1, DIM) or raw.dtype != np.float32:
        raise ValueError(f"expected a float32 [1, {DIM}] embedding, got {raw.dtype} {raw.shape}")
    norm = np.linalg.norm(raw)
    if not np.isfinite(norm) or norm <= 1e-12:
        raise ValueError("non-finite or zero model embedding")
    # Copy into an owned row before ExecuTorch reuses its output buffer.
    np.divide(raw[0], norm, out=out)


def encode_labels(text_model: Path, tokenizer: Path, prompts: list[str]) -> np.ndarray:
    tok = Tokenizer.from_file(str(tokenizer))
    # The release JSON pads with end tokens. The native ClipTokenizer pads with zeros.
    tok.enable_padding(length=77, pad_id=0)
    tok.enable_truncation(max_length=77)
    program = _load(text_model)
    method = program.load_method("forward")
    vectors = np.empty((len(prompts), DIM), dtype=np.float32)
    for prompt, row in zip(prompts, vectors):
        ids = torch.tensor([tok.encode(prompt).ids], dtype=torch.int64)
        _encode(method, [ids, (ids != 0).long()], row)
    return vectors


def encode_images(image_model: Path, paths: list[Path]) -> tuple[np.ndarray, list[int]]:
    program = _load(image_model)
    method = program.load_method("forward")
    vectors = np.empty((len(paths), DIM), dtype=np.float32)
    kept = []
    for index, path in enumerate(paths):
        try:
            with Image.open(path) as source:
                rgb = source.convert("RGB").resize((256, 256), Image.Resampling.BILINEAR)
        except (OSError, ValueError, SyntaxError, Image.DecompressionBombError):
            continue
        chw = np.ascontiguousarray(np.asarray(rgb).transpose(2, 0, 1), dtype=np.float32)
        chw /= 255
        _encode(method, [torch.from_numpy(chw[None])], vectors[len(kept)])
        kept.append(index)
    return vectors[:len(kept)], kept
