"""Export MobileCLIP-S2 (image + text towers) to ExecuTorch .pte for Memeget.

Produces, in --out-dir:
  mobileclip_s2_image_xnnpack_fp32.pte   image tower, input  [1,3,256,256] f32
  mobileclip_s2_text_xnnpack_fp32.pte    text tower,  inputs [1,77] i64 ids + [1,77] i64 mask
  mobileclip_s2_tokenizer.json           HF-tokenizers CLIP BPE, pad/truncate to 77

Interface contract (verified against react-native-executorch 0.9.2 native code):
  * Image models receive RGB pixels scaled to [0,1] (pixel/255), CHW planar,
    resized by the runtime to the model's own declared input size. NO mean/std
    is applied by the runtime — any normalization must live INSIDE the graph.
    (MobileCLIP was trained on [0,1] inputs — mean 0 / std 1 — so its wrapper
    normalization is the identity; we still route through the generic wrapper
    so the baked constants are explicit and auditable.)
  * Text models receive (tokenIds int64 [1,N], attentionMask int64 [1,N]) and
    must return the final pooled embedding. The runtime builds the mask as
    `token != 0`; CLIP-style towers pool by argmax (EOT has the highest token
    id), so the wrapper accepts the mask and ignores it. The tokenizer json is
    configured to pad/truncate to exactly 77 so the exported static shape
    always matches.
  * Outputs are raw (unnormalized) float32 embeddings; the app L2-normalizes.
"""

import argparse
import pathlib

import torch


IMAGE_SIZE = 256
CONTEXT_LEN = 77
EMBED_DIM = 512


class ImageTower(torch.nn.Module):
    def __init__(self, model, mean, std):
        super().__init__()
        self.model = model
        self.register_buffer("mean", torch.tensor(mean).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(std).view(1, 3, 1, 1))

    def forward(self, pixels: torch.Tensor) -> torch.Tensor:
        # pixels: [1,3,H,W] in [0,1] (the runtime's contract)
        x = (pixels - self.mean) / self.std
        return self.model.encode_image(x)


class TextTower(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, token_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        # attention_mask is part of the runtime's calling convention but CLIP
        # towers pool via argmax over token ids; keep a no-op use so export
        # doesn't drop the input.
        _ = attention_mask.shape
        return self.model.encode_text(token_ids)


def export_pte(module: torch.nn.Module, example_inputs, out_path: pathlib.Path) -> None:
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower

    module.eval()
    with torch.no_grad():
        ep = torch.export.export(module, example_inputs)
    prog = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()]).to_executorch()
    out_path.write_bytes(prog.buffer)
    print(f"wrote {out_path} ({out_path.stat().st_size / 1e6:.1f} MB)")


def verify_pte(out_path: pathlib.Path, example_inputs, want_dim: int) -> None:
    """Load the exported program with the ExecuTorch python runtime and check
    the output shape — catches silent export breakage before anything ships."""
    try:
        from executorch.runtime import Runtime
    except Exception as e:  # pragma: no cover - depends on wheel contents
        print(f"WARNING: python runtime unavailable, skipping verify: {e}")
        return
    rt = Runtime.get()
    program = rt.load_program(str(out_path))
    method = program.load_method("forward")
    out = method.execute(list(example_inputs))[0]
    got = tuple(out.shape)
    assert got == (1, want_dim), f"{out_path.name}: output shape {got}, want (1, {want_dim})"
    print(f"verified {out_path.name}: output {got}")


def write_tokenizer(out_path: pathlib.Path) -> None:
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained("openai/clip-vit-base-patch32", use_fast=True)
    backend = tok.backend_tokenizer
    backend.enable_truncation(max_length=CONTEXT_LEN)
    backend.enable_padding(length=CONTEXT_LEN, pad_id=tok.pad_token_id or 0, pad_token=tok.pad_token or "<|endoftext|>")
    backend.save(str(out_path))
    print(f"wrote {out_path}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", type=pathlib.Path, default=pathlib.Path("dist"))
    args = ap.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    import open_clip

    model, _, preprocess = open_clip.create_model_and_transforms(
        "MobileCLIP-S2", pretrained="datacompdr"
    )
    model.eval()

    # Fold FastViT's train-time branches into inference form before export.
    try:
        from timm.utils.model import reparameterize_model

        model.visual = reparameterize_model(model.visual)
        print("reparameterized visual tower")
    except Exception as e:
        print(f"NOTE: reparameterize skipped: {e}")

    # Pull the normalization the model was trained with out of its preprocess
    # pipeline so the baked constants can never drift from the checkpoint.
    norm = next(t for t in preprocess.transforms if type(t).__name__ == "Normalize")
    mean, std = list(norm.mean), list(norm.std)
    print(f"baking normalization mean={mean} std={std}")

    image_inputs = (torch.rand(1, 3, IMAGE_SIZE, IMAGE_SIZE),)
    image_path = args.out_dir / "mobileclip_s2_image_xnnpack_fp32.pte"
    export_pte(ImageTower(model, mean, std), image_inputs, image_path)
    verify_pte(image_path, image_inputs, EMBED_DIM)

    ids = torch.zeros(1, CONTEXT_LEN, dtype=torch.long)
    ids[0, 0] = 49406  # BOT
    ids[0, 1] = 49407  # EOT
    mask = (ids != 0).long()
    text_inputs = (ids, mask)
    text_path = args.out_dir / "mobileclip_s2_text_xnnpack_fp32.pte"
    export_pte(TextTower(model), text_inputs, text_path)
    verify_pte(text_path, text_inputs, EMBED_DIM)

    write_tokenizer(args.out_dir / "mobileclip_s2_tokenizer.json")


if __name__ == "__main__":
    main()
