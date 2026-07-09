# Model export (MobileCLIP-S2 + DINOv2)

ExecuTorch export scripts for Memeget's custom embedding models. Run by
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
pip install "executorch==1.0.0" open_clip_torch timm transformers tokenizers
python export_mobileclip_s2.py --out-dir dist
python export_dinov2.py --out-dir dist
```

If the app fails to *load* the models on-device (a program/version load error,
not a download error), the ExecuTorch pip version is newer than the runtime
bundled in react-native-executorch — lower `ET_VERSION` in the workflow and
re-run.

Follow-up once fp32 is proven on-device: 8da4w quantization to roughly quarter
the download/RAM.
