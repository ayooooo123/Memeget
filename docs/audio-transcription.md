# Audio transcription (speech-to-text)

Memeget transcribes the speech in video memes fully **on-device**, so "what was
that clip where the guy says X" becomes a text search. Like the vision pass this
is an *enrichment*: videos are already indexed and searchable by their keyframe;
this adds what's *said* in them.

The STT model is **pluggable** — we expect to swap it as better on-device models
appear, so this doc is written model-agnostically. The current default model is
recorded under [Current model](#current-model); everything above it is stable
regardless of which model is loaded.

## Pipeline

Per video, driven by `runTranscription` (bulk) or `regenerateMeme` (one clip) in
`src/audio.tsx`:

1. **Materialize** the SAF file to a plain path (`src/saf.ts` `materialize`) —
   `MediaExtractor` is happier with a file path than a `content://` uri.
2. **Native audio decode** (`modules/memeget-bg` `AudioExtractor.kt`): decode the
   first audio track with `MediaExtractor` + `MediaCodec`, downmix to mono,
   resample to **16 kHz float32 PCM** normalized to `[-1, 1]`, write a raw
   little-endian `.f32` to the cache dir. Nothing in the JS/Expo layer can decode
   AAC/Opus, so this must be native. Capped at `AUDIO_MAX_SECONDS`.
3. **Transcribe** the waveform with the loaded STT model (see the model contract
   below), producing raw text.
4. **Clean** the text (`cleanTranscript`): strip bracketed/parenthesized non-speech
   event tokens (`[Music]`, `(laughs)`, `♪…♪`). A transcript that was *only* such
   tokens collapses to `''` — treated as "no speech".
5. **Persist** on the meme row (`setMemeTranscript`) and fold into the lexical side
   of search (`assembleSearchText` — ocr + name + caption + transcript + tags +
   extra terms). An empty transcript is a valid result (no audio / no speech).

`audioCore.ts` holds the React-free, unit-tested core (model identity,
byte-wrangling, cleanup, diagnostics); `audio.tsx` holds the supported native STT
hook and queue/lifecycle plumbing.

## STT model contract

A model is driven behind a small runner shape so the queue / keep-alive / DB /
search paths never change when the model does. Whatever the model, the app needs:

- **Input:** a 16 kHz mono `Float32Array` waveform in `[-1, 1]` (what the native
  extractor produces).
- **Output:** plain transcript text, later cleaned + stored.

How that runner is implemented is the model's business. The current implementation
uses `react-native-executorch`'s supported `useSpeechToText` hook; alternate
engines must preserve the same `{ transcribe(waveform) → { text } }` seam.

### Model selection

`AUDIO_MODEL_INFO` in `src/audioCore.ts` identifies the active model and owns its
model-specific requeue key. `src/audio.tsx` maps that identity to a built-in
`react-native-executorch` speech-to-text preset:

```ts
useSpeechToText({
  model: models.speech_to_text.whisper_tiny_en(),
  preventLoad: !(hydrated && enabled),
});
```

Changing models requires changing both the preset and `AUDIO_MODEL_INFO`, then
regenerating the library. The model-specific migration key automatically requeues
every indexed video once after a shipped cutover.

## Regenerating transcriptions

Transcription is opt-in (Settings → Audio analysis) and, unlike the vision/poster
passes, is **manual** — there is no background auto-runner. Two ways to redo it,
both sharing the AudioApi mutex so they can't race:

- **Per meme** — open a video → ⋯ menu → **Re-run the transcription**. Retranscribes
  that one clip inline via `AudioApi.regenerateMeme(id)` and refreshes the open
  viewer's transcript in place. The stale transcript is cleared up front, so even
  a failed/empty retry leaves search consistent.
- **Whole library** — Settings → Audio analysis → **Regenerate all transcriptions**.
  Confirms, clears every video transcript (`resetAllAudio`), re-queues all indexed
  videos, then runs a full pass. Use this after switching models.

Also in Settings: **Transcribe N videos** (drain the pending queue) and **Retry N
failed** (`resetAudioFailures`, re-queue only the errored clips).

DB helpers (`src/db.ts`): `requeueMemeAudio` / `resetAllAudio` clear the transcript
and flip `audio_state` back to `pending` (and invalidate the search index);
`getMemeForAudio` / `getMemeTranscript` back the per-meme path. Lifecycle of
`audio_state` is in `src/types.ts` (`none | pending | done | failed`).

## Evaluating a model (diagnostics)

When judging whether a model is good enough, an empty transcript is ambiguous —
it can mean genuine silence, over-aggressive cleanup, or the model failing on a
clip that clearly has speech. `transcribeOne` logs enough to tell them apart, so
`adb logcat | grep '\[stt\]'` classifies every result:

- `[stt] transcribe id=… samples=… rms=… peak=… <name>` — one line per clip before
  the model runs. `rms`/`peak` are the audio level.
- `[stt] empty id=… rms=… rawLen=… raw=… <name>` — emitted only when the stored
  transcript came out empty:
  - `rms ≈ 0` → **genuinely silent** clip (no audio track / no speech). Expected.
  - healthy `rms`, `rawLen=0` → the **model produced nothing** over real audio.
  - healthy `rms`, `rawLen>0` → the model emitted only text that cleanup removed
    (for example music/applause event labels).
- `[stt] text id=… \"…\"` — cleaned transcript that was persisted.
- `[stt] error id=… <err>` — extraction/transcription threw; row marked `failed`.

## Swapping the transcription model

1. Select a supported preset in `src/audio.tsx` (or implement a new engine behind
   the `{ transcribe(waveform) → { text } }` seam).
2. Update `AUDIO_MODEL_INFO` (id, label, and a new requeue key).
3. Rebuild, install, enable audio analysis, and let the model download/load.
4. The model-specific migration requeues existing videos automatically; Settings
   → **Regenerate all transcriptions** can repeat the pass manually.
5. Judge the model with the `[stt]` logs above, the with-speech rate, and known
   search examples. False-empty rate is the primary rejection metric.

## Current model

**Whisper tiny English** through `react-native-executorch@0.9.2`'s supported
native speech-to-text runner.

- Preset: `models.speech_to_text.whisper_tiny_en()`.
- Input: normalized mono 16 kHz waveform from `AudioExtractor.kt`.
- The native runner performs Whisper's log-mel preprocessing, walks long input in
  strict 30 s windows, manages decoder cache positions, initial prompt tokens,
  timestamp tokens, temperature fallback, and segment assembly.
- English-only preset: call `transcribe(waveform)` without a language option;
  passing one is rejected for `.en` models.

### Why Moonshine was removed

Moonshine-tiny was tested through the generic ExecuTorch tensor module because
`react-native-executorch@0.9.2` explicitly supports only Whisper in its native STT
runner. That bypass required a hand-written cache-less decoder and assumptions
about the exported encoder/decoder tensor contract.

On the real library it produced text for only **12 of 337 videos** (~3.6%), and a
known speaking clip still returned empty when regenerated individually. The
native audio extractor was shared with the previously working Whisper path and
produced normalized 16 kHz PCM, so the failure boundary was the unsupported
Moonshine runner, not media extraction or the bulk counter. The custom runner was
removed rather than patched again.
