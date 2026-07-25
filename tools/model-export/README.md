# Model export (MobileCLIP-S2 + DINOv2)

ExecuTorch export scripts for Memeget's custom models. Run by
`.github/workflows/export-models.yml`, which publishes the resulting `.pte`
files to the `models-v1` GitHub release; the APK build points the app's
`EXPO_PUBLIC_MEMEGET_*` model sources at those assets.

The interface contract these exports satisfy (verified against the native code
in react-native-executorch 0.9.2) is documented at the top of each script and
in `docs/embedding-roadmap.md`:

- image models: `[1,3,H,W]` float32 RGB in `[0,1]`, resized by the runtime to
  the model's declared size, **no runtime mean/std** — normalization is baked
  into the exported graph
- text models: `(tokenIds int64 [1,77], attentionMask int64 [1,77])` in, final
  pooled embedding out; the tokenizer json pads/truncates to exactly 77
- outputs are raw embeddings; the app L2-normalizes in JS

Local run (needs network access to Hugging Face + PyPI):

```bash
pip install "executorch==1.0.0" "torch==2.9.*" open_clip_torch timm
python export_mobileclip_s2.py --out-dir dist
python export_dinov2.py --out-dir dist
```

If the app fails to *load* the models on-device (a program/version load error,
not a download error), the ExecuTorch pip version is newer than the runtime
bundled in react-native-executorch — lower `ET_VERSION` in the workflow and
re-run.

## Speaker encoder gate status

The fixed-window WeSpeaker CAM++ experiment is intentionally blocked before
publication. Reproduce it from the repository root with the official source
package and the runtime-matched ExecuTorch stack:

```bash
python3.12 -m venv /tmp/memeget-speaker-venv
/tmp/memeget-speaker-venv/bin/python -m pip install \
  "executorch==1.0.0" "torch==2.9.*" "torchaudio==2.9.*" \
  huggingface-hub pyyaml scipy psutil
/tmp/memeget-speaker-venv/bin/python -m pip install --no-deps \
  "git+https://github.com/wenet-e2e/wespeaker.git@dfa741957e5c11f477623b6e583d67d0af25ee88"
HF_HUB_DISABLE_XET=1 /tmp/memeget-speaker-venv/bin/python \
  tools/model-export/export_speaker_encoder.py --out-dir dist
```

`torch.export` captures the official checkpoint and exact Kaldi fbank
frontend. ExecuTorch 1.0.0 then raises `SpecViolationError` while converting to
Edge: `aten._fft_r2c.default` changes `float32` to `complex64`, and the
following `aten.abs.default` changes `complex64` to `float32`. Both stack traces
point to `features = kaldi.fbank(...)`. A preceding `torchao` compatibility
warning is not the failure. The command must not produce or publish a `.pte`
until a revised, parity-proven model boundary passes this gate.
