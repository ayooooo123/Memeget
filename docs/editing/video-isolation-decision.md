# Video subject isolation gate: REJECTED

**Decision: do not ship on-device video subject isolation.** Measured on a
physical Pixel 9 Pro, every configuration in the 3×3 matrix misses the speed
budget — the fastest one by 6%, the realistic ones by 3-4x.

This is what the gate existed to decide, and it decided it. Tasks 6.1-6.3
(bounded video mask tracks, foreground/background compositing, selectable
tracked object replacement) are dropped rather than built.

## What was measured

Device: Pixel 9 Pro (`caiman`), Android 17. Observed `2026-08-03T19:27:32Z`.
Model: MediaPipe SelfieSegmenter float16 (256×256 input), tasks-vision 0.10.29.
Fixtures: `one_person`, `two_people_crossing_occluding`, `fast_motion`.
Budget: **≤3× realtime** on a 10 s 720p clip, **≤128 MiB** peak PSS delta.

| Working size | Mask fps | Worst runtime | × realtime | Peak PSS Δ | Under 3× |
|---|---|---|---|---|---|
| 256 | 8 | 31.8 s | **3.18×** | 78.9 MiB | no |
| 256 | 12 | 49.0 s | 4.90× | 67.4 MiB | no |
| 256 | 15 | 58.2 s | 5.82× | 64.6 MiB | no |
| 384 | 8 | 45.9 s | 4.59× | 81.3 MiB | no |
| 384 | 12 | 66.1 s | 6.61× | 66.2 MiB | no |
| 384 | 15 | 98.0 s | 9.80× | 73.3 MiB | no |
| 512 | 8 | 84.4 s | 8.44× | 71.9 MiB | no |
| 512 | 12 | 99.7 s | 9.97× | 79.5 MiB | no |
| 512 | 15 | 124.2 s | **12.42×** | 75.4 MiB | no |

All nine configurations completed all three fixtures (`matrixComplete: PASS`),
so this is a complete result, not a partial one that happened to look bad.

## Why this is decisive rather than tunable

**Memory was never the problem.** Every configuration sat at 64-81 MiB against a
128 MiB budget, so there is no headroom trade to make — you cannot buy speed by
spending the memory you were not using.

**The cheapest possible setting still fails.** 256×256 at 8 fps is the lowest
quality this pipeline can produce and it is *still* 3.18× realtime. There is no
configuration below it to fall back to. And quality is already unacceptable
across the whole matrix (`qualityStatus: FAIL` on all nine), so the settings
that get closest on speed are the ones a user would reject on sight.

Turning a 10-second clip into a 32-second wait, for a mask that does not hold up
on crossing subjects or fast motion, is not a feature.

## What passed

- `matrixComplete` — all 256/384/512 × 8/12/15 observations completed.
- `nativePageAlignment` — the shipped `.so` files meet the Android 15+ 16 KB
  page-size requirement.
- `cancellationCleanup` — cancelling releases the decoder and leaves nothing.
- `playbackReview` — the six playback archives are internally consistent.

So the pipeline is correctly *built*. It is just too slow to be worth shipping,
which is a different thing and worth stating separately.

## One honest caveat on `provenance: FAIL`

The provenance criterion also reports FAIL, but not because anything mismatched:
`failedBoundaries` is empty. Three digests could not be *observed at all* from an
on-device run — `fixtureSource.sha256`, `generator.archiveSha256`, and
`generator.binarySha256` — because those are host-side assets that do not exist
on the phone. The check reports unverified as FAIL rather than skipping it, which
is the right behaviour, but it is a measurement limitation and not evidence of
tampering. Every digest that *could* be checked matched: the model, the
tasks-vision POM, the model card, and all three Apache-2.0 license assets.

It does not change the outcome. `videoIsolation` fails on measured numbers.

## Evidence

`docs/editing/video-segmentation-device-gate.json` plus 27 mask PNGs and 6
playback archives in `video-segmentation-mask-evidence/`.

The previous committed JSON referenced 33 artifacts of which only 12 existed in
git — it described a run whose evidence had never been checked in. This set was
verified before committing: **33 referenced, 33 present, none missing, none
unreferenced.** The MediaStore de-duplication trap was checked for explicitly
(a collision renames files to `name (1).ext`, which would let a stale pull pass
as fresh); no artifact of ours was renamed.

## What would reopen this

A materially faster segmenter — roughly 4× on the cheapest setting just to reach
the budget, and more than that to reach it at a quality anyone would accept.
Hardware acceleration through NNAPI/GPU delegate is the obvious lever and was not
used here; that is the thing to measure before revisiting.

Still-image cutouts are unaffected and shipped. ML Kit Subject Segmentation runs
once per photo instead of once per frame, which is why it clears a bar this
cannot.
