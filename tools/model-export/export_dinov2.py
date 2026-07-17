"""Export DINOv2-base to ExecuTorch .pte as Memeget's visual-similarity model.

Produces, in --out-dir:
  dinov2_base_xnnpack_fp32.pte   input [1,3,224,224] f32 in [0,1], output [1,768]

Same runtime contract as the MobileCLIP export (see export_mobileclip_s2.py):
the runtime feeds [0,1] RGB resized to the model's declared input with no
mean/std — so ImageNet normalization is baked into the graph here. Output is
the CLS token embedding; the app L2-normalizes in JS.
"""

import argparse
import pathlib

import torch

IMAGE_SIZE = 224
EMBED_DIM = 768
IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]


class DinoWrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
        self.register_buffer("mean", torch.tensor(IMAGENET_MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(IMAGENET_STD).view(1, 3, 1, 1))

    def forward(self, pixels: torch.Tensor) -> torch.Tensor:
        x = (pixels - self.mean) / self.std
        out = self.model(pixel_values=x)
        return out.last_hidden_state[:, 0]  # CLS token, [1,768]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", type=pathlib.Path, default=pathlib.Path("dist"))
    args = ap.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    from transformers import Dinov2Model

    from export_mobileclip_s2 import (
        calibration_images,
        export_int8_if_good,
        export_pte,
        verify_pte,
    )

    model = Dinov2Model.from_pretrained("facebook/dinov2-base")
    model.eval()

    wrapper = DinoWrapper(model)
    inputs = (torch.rand(1, 3, IMAGE_SIZE, IMAGE_SIZE),)
    out_path = args.out_dir / "dinov2_base_xnnpack_fp32.pte"
    export_pte(wrapper, inputs, out_path)
    verify_pte(out_path, inputs, EMBED_DIM, reference=wrapper)

    # int8 DINOv2 — the highest-value quant target: it's the biggest file
    # (346 MB fp32 → ~90 MB) and a plain ViT, which quantizes cleanly. Try
    # dynamic first (weights-only, no calibration); if it clears the cos ≥ 0.98
    # gate it ships, else fall back to static/calibrated, else keep fp32. Written
    # only on a pass, so "More like this" quality can never regress.
    int8_path = args.out_dir / "dinov2_base_xnnpack_int8.pte"
    cos = export_int8_if_good(wrapper, inputs, int8_path, mode="dynamic")
    if cos is None and not int8_path.exists():
        export_int8_if_good(
            wrapper, inputs, int8_path, mode="static",
            calib_inputs=calibration_images(IMAGE_SIZE),
        )
    if int8_path.exists():
        verify_pte(int8_path, inputs, EMBED_DIM, reference=wrapper)


if __name__ == "__main__":
    main()
