"""Export the official non-LM VoxCeleb CAM++ speaker encoder to ExecuTorch.

Contract:
  input:  float32 waveform [1, 48000] at 16 kHz, normalized to [-1, 1]
  output: float32 L2-normalized speaker embedding [1, 512]

The graph contains the exact WeSpeaker training frontend: PCM scaling by 32768,
Kaldi fbank (80 bins, 25 ms, 10 ms, Hamming, no dither/energy), mean-only
CMVN, CAM++, and L2 normalization. Resampling is deliberately not exported.
"""

from __future__ import annotations

import argparse
import importlib.util
import pathlib
import resource
import statistics
import time
import sys
import types
from typing import Iterable

import torch
import torch.nn.functional as F
import torchaudio.compliance.kaldi as kaldi
import yaml
from huggingface_hub import hf_hub_download

MODEL_REPO = "Wespeaker/wespeaker-voxceleb-campplus"
MODEL_REVISION = "acf623ad8ca746e50baa432255cf8fc57c669c45"
MODEL_ID = "wespeaker-campplus-voxceleb-3s-v1"
OUTPUT_NAME = "wespeaker_campplus_voxceleb_3s_xnnpack_fp32.pte"
SAMPLE_RATE = 16_000
INPUT_SAMPLES = 48_000
EMBED_DIM = 512
PARITY_MIN_COSINE = 0.99


class SpeakerEncoder(torch.nn.Module):
    def __init__(self, model: torch.nn.Module) -> None:
        super().__init__()
        self.model = model

    def forward(self, waveform: torch.Tensor) -> torch.Tensor:
        pcm = waveform * float(1 << 15)
        features = kaldi.fbank(
            pcm,
            num_mel_bins=80,
            frame_length=25,
            frame_shift=10,
            dither=0.0,
            sample_frequency=SAMPLE_RATE,
            window_type="hamming",
            use_energy=False,
        )
        features = features - torch.mean(features, dim=0, keepdim=True)
        embedding = self.model(features.unsqueeze(0))
        return F.normalize(embedding, p=2.0, dim=1, eps=1e-12)


def _load_campplus_class() -> type[torch.nn.Module]:
    """Load only WeSpeaker's official model files, avoiding its optional CLI imports."""
    package_spec = importlib.util.find_spec("wespeaker")
    assert package_spec and package_spec.origin, "official WeSpeaker source is not installed"
    package_dir = pathlib.Path(package_spec.origin).parent
    models_dir = package_dir / "models"

    wespeaker_package = types.ModuleType("wespeaker")
    wespeaker_package.__path__ = [str(package_dir)]
    models_package = types.ModuleType("wespeaker.models")
    models_package.__path__ = [str(models_dir)]
    sys.modules["wespeaker"] = wespeaker_package
    sys.modules["wespeaker.models"] = models_package

    for name in ("pooling_layers", "campplus"):
        module_name = f"wespeaker.models.{name}"
        spec = importlib.util.spec_from_file_location(module_name, models_dir / f"{name}.py")
        assert spec and spec.loader, f"cannot load official {module_name}"
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)

    return sys.modules["wespeaker.models.campplus"].CAMPPlus


def load_official_model() -> SpeakerEncoder:
    CAMPPlus = _load_campplus_class()

    config_path = hf_hub_download(
        repo_id=MODEL_REPO, filename="config.yaml", revision=MODEL_REVISION
    )
    checkpoint_path = hf_hub_download(
        repo_id=MODEL_REPO, filename="avg_model.pt", revision=MODEL_REVISION
    )
    with open(config_path, encoding="utf-8") as handle:
        config = yaml.safe_load(handle)

    model_args = config["model_args"]
    assert config["model"] == "CAMPPlus", config["model"]
    assert model_args == {
        "feat_dim": 80,
        "embed_dim": EMBED_DIM,
        "pooling_func": "TSTP",
    }, model_args
    assert config["projection_args"]["do_lm"] is False, "LM checkpoint is forbidden for 3-second input"

    model = CAMPPlus(**model_args)
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    if isinstance(checkpoint, dict) and "state_dict" in checkpoint:
        checkpoint = checkpoint["state_dict"]
    missing, unexpected = model.load_state_dict(checkpoint, strict=False)
    missing = [key for key in missing if "projection" not in key]
    unexpected = [key for key in unexpected if "projection" not in key]
    assert not missing, f"checkpoint missing model tensors: {missing}"
    assert not unexpected, f"checkpoint has unexpected model tensors: {unexpected}"
    model.eval()
    wrapper = SpeakerEncoder(model)
    wrapper.eval()
    return wrapper


def parity_fixtures() -> list[torch.Tensor]:
    generator = torch.Generator().manual_seed(20260725)
    time_axis = torch.arange(INPUT_SAMPLES, dtype=torch.float32) / SAMPLE_RATE
    fixtures = [
        0.25 * torch.sin(2 * torch.pi * 113.0 * time_axis),
        0.35 * torch.sin(2 * torch.pi * 211.0 * time_axis)
        + 0.08 * torch.sin(2 * torch.pi * 421.0 * time_axis),
        0.1 * torch.randn(INPUT_SAMPLES, generator=generator),
        torch.linspace(-0.4, 0.4, INPUT_SAMPLES),
        torch.where((torch.arange(INPUT_SAMPLES) // 800) % 2 == 0, 0.2, -0.2),
        0.15 * torch.randn(INPUT_SAMPLES, generator=generator)
        + 0.2 * torch.sin(2 * torch.pi * 173.0 * time_axis),
    ]
    return [fixture.clamp(-1, 1).unsqueeze(0) for fixture in fixtures]


def _decompose_export_ops(program: torch.export.ExportedProgram) -> torch.export.ExportedProgram:
    from torch._decomp import get_decompositions

    ops = []
    for name in (
        "_native_batch_norm_legit_no_training",
        "_native_batch_norm_legit",
        "native_batch_norm",
    ):
        namespace = getattr(torch.ops.aten, name, None)
        if namespace is not None:
            ops.append(namespace.default)
    decompositions = get_decompositions(ops)

    def hamming_window(
        window_length: int,
        periodic: bool,
        alpha: float,
        beta: float,
        *,
        dtype: torch.dtype | None = None,
        layout: torch.layout | None = None,
        device: torch.device | None = None,
        pin_memory: bool | None = None,
    ) -> torch.Tensor:
        denominator = window_length if periodic else window_length - 1
        positions = torch.arange(
            window_length,
            dtype=dtype,
            layout=layout,
            device=device,
            pin_memory=pin_memory,
        )
        return alpha - beta * torch.cos((2.0 * torch.pi / denominator) * positions)

    decompositions[torch.ops.aten.hamming_window.periodic_alpha_beta] = hamming_window
    return program.run_decompositions(decompositions)


def export_pte(wrapper: SpeakerEncoder, out_path: pathlib.Path) -> None:
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower

    example = parity_fixtures()[0]
    with torch.no_grad():
        exported = torch.export.export(wrapper, (example,))
    exported = _decompose_export_ops(exported)
    program = to_edge_transform_and_lower(
        exported, partitioner=[XnnpackPartitioner()]
    ).to_executorch()
    out_path.write_bytes(program.buffer)


def _runtime_output(method: object, waveform: torch.Tensor) -> torch.Tensor:
    output = method.execute([waveform])[0]
    return output if isinstance(output, torch.Tensor) else torch.as_tensor(output)


def verify_pte(
    wrapper: SpeakerEncoder, out_path: pathlib.Path, fixtures: Iterable[torch.Tensor]
) -> None:
    from executorch.runtime import Runtime

    program = Runtime.get().load_program(str(out_path))
    method = program.load_method("forward")
    timings_ms: list[float] = []
    cosines: list[float] = []

    for waveform in fixtures:
        with torch.no_grad():
            reference = wrapper(waveform)
        started = time.perf_counter()
        candidate = _runtime_output(method, waveform)
        timings_ms.append((time.perf_counter() - started) * 1000)

        assert tuple(candidate.shape) == (1, EMBED_DIM), tuple(candidate.shape)
        assert bool(torch.isfinite(candidate).all()), "PTE produced non-finite values"
        norm = float(torch.linalg.vector_norm(candidate))
        assert abs(norm - 1.0) <= 1e-3, f"PTE output norm {norm:.8f}"
        cosine = float(F.cosine_similarity(reference.flatten(), candidate.flatten(), dim=0))
        assert cosine >= PARITY_MIN_COSINE, f"PyTorch/PTE cosine {cosine:.8f}"
        cosines.append(cosine)

    peak_rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    print(f"model={MODEL_ID}")
    print(f"output_shape=[1,{EMBED_DIM}]")
    print(f"model_bytes={out_path.stat().st_size}")
    print(f"parity_cosine_min={min(cosines):.8f}")
    print(f"median_forward_ms={statistics.median(timings_ms):.3f}")
    print(f"peak_resident_bytes={peak_rss}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=pathlib.Path, default=pathlib.Path("dist"))
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    wrapper = load_official_model()
    out_path = args.out_dir / OUTPUT_NAME
    export_pte(wrapper, out_path)
    verify_pte(wrapper, out_path, parity_fixtures())


if __name__ == "__main__":
    main()
