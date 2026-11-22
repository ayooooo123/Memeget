# Format-tag score

`score_formats.py` asks: **given the pixels of a held-out image, where does the
correct format rank among the app's format prompts?** It runs the released
MobileCLIP-S2 image and text towers on the CPU. Use `--text-weights FILE.pte` to
replace only the text tower. It does not run the app's search or recognition
pipeline.

## Run

From the repository root, set `CORPUS` to the directory with both corpus
annotation files and the image directories. Keep the corpus and models outside
git. No image download, device, or GPU is required.

```sh
WORK=$(mktemp -d)
for tag in models-v1 models-memeft; do
  gh release download "$tag" -p 'mobileclip_s2_*' -p SHA256SUMS -D "$WORK/$tag" || exit 1
  (cd "$WORK/$tag" && grep '  mobileclip_s2_' SHA256SUMS | shasum -a 256 -c -) || exit 1
done
```

`models-v1` contains the stock image tower, stock text tower, and tokenizer.
`models-memeft` contains the earlier fine-tuned text tower. Both releases use
the same tokenizer. `--text-weights` accepts a compatible ExecuTorch text
program, not a training state dictionary.

The following setup is for Python 3.11 on Apple silicon. It installs the wheel
for CPU inference without the training, export, and test dependencies of the
full ExecuTorch package. On Linux, install the matching CPU wheel for PyTorch.

```sh
python3.11 -m venv "$WORK/venv"
PY="$WORK/venv/bin/python"
"$PY" -m pip install 'torch==2.9.1' 'numpy==2.4.6' 'pillow==13.0.0' \
  'tokenizers==0.23.3' 'ijson==3.6.0' flatbuffers packaging pyyaml ruamel.yaml tabulate
"$PY" -m pip install --only-binary=:all: --no-deps 'executorch==1.0.0'
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 VECLIB_MAXIMUM_THREADS=1 TOKENIZERS_PARALLELISM=false
score() {
  "$PY" tools/eval/score_formats.py --data-dir "${CORPUS:?set CORPUS to the local corpus directory}" \
    --models-dir "$WORK/models-v1" "$@"
}
score
```

The scorer uses one XNNPACK thread and one image per forward pass. It resizes RGB
pixels to 256 × 256 with Pillow's bilinear filter, converts them to planar
float32 in `[0,1]`, and applies no extra normalization to the pixels. It uses
the release tokenizer with zero padding to 77 IDs and an `ids != 0` mask, as in
the Android text path. The IDs matched all 15 cases in the native tokenizer
fixture. This is not a test of a phone's image decoder.

## What counts as correct

* **Images:** `golden.json` supplies only `memes[].id`, the filenames in the
  existing holdout. The scorer uses the full source tags from `dataset.jsonl`
  and `meme_dataset_kym.json` in the local corpus, joined by filename with
  `tools/finetune/dataset.py`. It looks in `images_only/` and then `images/`.
  It does not use saved image, query, or label vectors.
* **Candidates:** every entry with `category: "format"` in
  [`label-prompts.json`](../../native/app/src/main/assets/knowledge/label-prompts.json).
  The text tower encodes the **prompt**, not the target tag. The image tower sees
  only pixels. An exact, case-insensitive match between a source tag and a
  **label name** supplies the target. Associations such as “dog” for “This Is
  Fine” do not establish a format. The scorer does not turn the 595 different
  tag captions into 595 invented formats.
* **Ranks:** sort cosine similarities in descending order. Break ties by label
  name. Count one trial per distinct image–format pair. Top-*k* is the fraction
  of those pairs whose label ranks in the first *k*. MRR is the mean of
  `1 / rank` over the same pairs. An image can contribute more than one pair.
* **Coverage:** print the ID of each missing image, and of each labelled image
  that cannot be decoded. Exclude those images from the denominator, **not**
  their labels from the candidate list. An image with no exact format tag is
  unscored, not a negative. If there are no usable pairs, exit with an error
  rather than report a zero.

## Recorded CPU runs

Apple silicon, macOS 26.5.2, Python 3.11.9, package versions above. For the
golden membership and format prompts, the git blob IDs at the time of the run
were `cbb04631715c77de21579fd1bab77cc906c7c97e` and
`c94970c12f870111f1aeceda447f6d4a17155c99`, respectively.

Release checksums (SHA-256 of the files, not the tag names):

```text
models-v1       image       dd6a357fea3199b00eff17cd0898023213cf176e7566f7cbdf8dab9f0a1fe7f5
models-v1       text        e7576174e756d5579179df4c76ea136123db0a66ee222974a3cf8e2a590ff488
models-memeft   text        fce2095910293f579947171d16ffeb15d7290769a8c7d1499eeaf323607ed229
both releases   tokenizer   8e019f9349caf5f9bb92514b0e4fc00c92841aef6ae8df9d833d975c623b7c72
```

**Stock prediction:** all 595 files present; 16 scorable images, 17 pairs,
21 candidate formats. Top-1 and MRR should exceed uniform chance.

```text
$ score | tee "$WORK/stock.out"
golden=595 missing=0 no_format_tag=579
image=mobileclip_s2_image_xnnpack_fp32.pte text=mobileclip_s2_text_xnnpack_fp32.pte device=cpu
images=16 pairs=17 labels=21 unreadable=0
rank: top-1=41.18% top-3=76.47% top-5=82.35% MRR=0.5927
```

**Shuffle prediction:** top-1 near `1/21` (4.76%), top-3 near `3/21`
(14.29%), top-5 near `5/21` (23.81%), MRR near `sum(1/r, r=1..21)/21`
(0.1736). A shuffle assigns the prompt vectors to different label names using
one permutation for the entire cohort. The images and their targets stay fixed.
The first draw can vary substantially, so the scorer also reports the mean of
256 uniform permutations from a fixed seed.

```text
$ score --shuffle-labels
golden=595 missing=0 no_format_tag=579
image=mobileclip_s2_image_xnnpack_fp32.pte text=mobileclip_s2_text_xnnpack_fp32.pte device=cpu
images=16 pairs=17 labels=21 unreadable=0
shuffle first (seed=0): top-1=0.00% top-3=11.76% top-5=17.65% MRR=0.1501
shuffle mean (256 draws, seed=0): top-1=4.57% top-3=14.59% top-5=25.16% MRR=0.1753
```

**Repeat prediction:** the same stock command in a new process should print the
same numbers from fresh embeddings. These transcripts show stdout.

```text
$ score | tee "$WORK/repeat.out"
golden=595 missing=0 no_format_tag=579
image=mobileclip_s2_image_xnnpack_fp32.pte text=mobileclip_s2_text_xnnpack_fp32.pte device=cpu
images=16 pairs=17 labels=21 unreadable=0
rank: top-1=41.18% top-3=76.47% top-5=82.35% MRR=0.5927
$ cmp "$WORK/stock.out" "$WORK/repeat.out" && echo 'stock stdout: identical'
stock stdout: identical
```

**Memeft prediction:** changing only the text program should change some ranks,
but an improvement on format tags is not guaranteed. The cohort must stay
fixed. The B0 decision rule for this run was to consider a warm start only if
both top-1 and MRR increased.

```text
$ score --text-weights "$WORK/models-memeft/mobileclip_s2_text_memeft_xnnpack_fp32.pte"
golden=595 missing=0 no_format_tag=579
image=mobileclip_s2_image_xnnpack_fp32.pte text=mobileclip_s2_text_memeft_xnnpack_fp32.pte device=cpu
images=16 pairs=17 labels=21 unreadable=0
rank: top-1=29.41% top-3=58.82% top-5=70.59% MRR=0.4773
```

**B0 verdict: drop memeft as the replacement for stock on format tagging.**
It got 5 rather than 7 pairs right at top-1, 10 rather than 13 at top-3, and
12 rather than 14 at top-5. MRR also fell. This comparison gives no reason to
use it as a warm start for this task. It does not measure caption retrieval,
generic queries, or recognition of unknown formats. The downloaded models and
temporary environment can be removed with `rm -r -- "$WORK"` once the runs end.

Only five candidate formats have a known positive here. Nine of the 17 pairs
are “Reaction Image”; always guessing that label would get 52.94% top-1. The
shuffle checks that label alignment matters, not that the stock model is a good
classifier. One pair changes top-1 by 5.88 percentage points.

## Historical comparison

The retired evaluator ranked saved image vectors against saved depot-name
vectors. Its original [golden file at `0c75d71`](https://github.com/ayooooo123/Memeget/blob/0c75d71/tools/eval/golden.json)
has 180 images and 24 depot names. Replaying those dot products gives 59/180
top-1 (32.78%), 75/180 top-3 (41.67%), 85/180 top-5 (47.22%), and MRR 0.4120.
These are the **historical 33% / 42% / 47% / 0.41**, not a baseline for today's
task. A depot could name a character, a collection, or a topic. The current
holdout comes from different sources; the app's format labels, prompts, and
positive pairs also differ. A change in these scores is not a gain or loss on
the old benchmark.

The native [`GoldenSearchRecallTest`](../../native/app/src/test/java/com/memeget/nativeapp/data/GoldenSearchRecallTest.kt)
still exercises search with saved `imageVec` and `queryVec` values. Changing a
text checkpoint alone cannot change those inputs. Do not use that test to
compare the two releases.

## Before spending GPU time

Freeze a larger test set before comparing more checkpoints. Sample distinct
image families across **each** format, with a separate slice for memes that
match no known format. Have two people label the images without seeing model
predictions. Resolve disagreements and allow multiple correct formats. Keep
duplicates, crops, and reposts on the same side of the train/test split.
Keep the images private. Report the number of examples and results per format,
as well as the overall score and uncertainty on the paired change. Evaluate
abstention separately on the no-format slice.
