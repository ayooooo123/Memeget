# GIF export gate: OMIT

**Decision: do not ship animated GIF export.** MP4 already solves the problem GIF
was proposed to solve, and no available encoder clears the bar.

Task 7.3 of the image/video editing plan required this to be a *gate*: research a
maintained encoder, measure it, and ship GIF only if it passes. It does not pass.
Recording why, so the question does not get re-opened from memory.

## Why GIF was on the table at all

The original request was: *"for webm videos, make the copy feature copy it in a
supported file format since webm videos can't be pasted in mobile browser apps
like twitter apparently. A small conversion to a gif would prob be ideal."*

GIF was the proposed *remedy*, not the requirement. The requirement is **a copied
video that pastes into mobile share targets**. That is already shipped: the copy
path transcodes WebM to real MP4 via `transcodeVideoToMp4`
(`compatibleCopyTarget` in `src/memeActionsCore.ts`), and MP4 is the format X
itself recommends. So GIF has to beat a working solution, not an empty slot.

It does not.

## The candidates, and what disqualifies each

| Library | License | Maintained | Memory model | Disqualifier |
|---|---|---|---|---|
| `waynejo/android-ndk-gif` | MIT | No — last commit 2022-01-22 | Streams; single frame buffer in native memory | Ships `.so` built with `LOCAL_CPPFLAGS := -std=c++11` and no `-Wl,-z,max-page-size=16384`. Fails Android 15+ 16 KB page alignment. Unmaintained, so no upstream fix. |
| `square/gifencoder` | Apache-2.0 | No — last real release 2019-09-30 | **Buffers every frame** as `Color[][]` on the JVM heap | OOM by construction for clip-length animations. This app has already had one OOM incident decoding large media. |
| `shaksternano/gif.kt` | MIT | Yes — last commit 2026-04-24 | Streams frames; NeuQuant palette | Kotlin Multiplatform; the honest objection is integration cost, not quality. It is the only technically viable option. |
| Glide `gifencoder-integration` | Apache-2.0 | With Glide | n/a | Not standalone; requires full Glide. |
| Media3 / platform | — | — | — | No GIF **encoder** exists. Android decodes GIF; it does not encode it. |

So there is exactly one candidate that could work — `gif.kt`. The reason to omit
is not that it is bad. It is that the output is bad.

## The output is the real disqualifier

For a 5-second 720p clip at 12.5 fps (63 frames):

| Format | Size |
|---|---|
| GIF, conservative 50% LZW | ~27.7 MB |
| GIF, meme-optimised 70% LZW (flat colour) | ~16.6 MB |
| MP4, H.264 at 2.5 Mbps | ~1.5 MB |

**GIF is 11-18x larger.** And X's mobile GIF upload limit is 5 MB, so a 720p
meme clip cannot be uploaded as a GIF *at all*, at any quality setting. The
format that was supposed to fix a sharing problem cannot clear the sharing
platform's own limit.

There is also a quality cost specific to this content. Memes are flat colour with
hard-edged text — the worst case for 256-colour quantisation. Median-cut (Square)
visibly bands text edges. NeuQuant (gif.kt) is much better but still quantising.
MP4 has neither constraint.

> **Honesty note on these numbers.** The sizes and the 5 MB limit are
> *calculated and cited*, not measured on-device by us — they are marked
> `[INFERENCE]` in the source research. They are not load-bearing: the decision
> survives being generous. Even if the GIF encoder were twice as good as
> projected, it would still be ~8 MB against MP4's 1.5 MB and still over X's
> limit. Nothing here rests on the third significant figure.

## What we ship instead

Video variations export as H.264/AAC MP4, and the copy path transcodes WebM to
real MP4 bytes. That is the complete answer to the original request.

Explicitly refused: renaming an MP4 to `.gif`, or any MIME/extension trick. A
file whose bytes do not match its extension is a bug that surfaces at the worst
possible moment — in someone else's upload pipeline, after the user believed the
share worked.

## What would reopen this

- A meme clip short and small enough that GIF lands under 5 MB — realistically
  sub-2-second, heavily downscaled. If a "make a reaction GIF" feature is ever
  wanted, that is a *different, bounded* feature with its own size budget, and
  `gif.kt` is the encoder to evaluate.
- A share target that accepts GIF and rejects MP4. None known today; X accepts
  MP4 and prefers it.
