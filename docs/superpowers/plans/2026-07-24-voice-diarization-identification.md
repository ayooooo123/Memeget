# Voice Diarization and Identification Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect ordered speakers in each video, let the user name and correct them, and use confirmed on-device voice profiles to suggest and search identities across videos.

**Architecture:** Reuse the existing 16 kHz PCM extractor and supported Whisper runner. Add the built-in FSMN VAD plus a gated, fixed-input CAM++ speaker-embedding export; a React-free diarization/matching core; normalized SQLite voice records; and focused viewer/teaching components. A single non-reentrant audio-work coordinator serializes transcription, VAD, and speaker inference. Suggested matches remain separate from user-confirmed training samples.

**Tech Stack:** React Native 0.85, Expo 56, TypeScript 5.9, expo-sqlite, expo-video, react-native-executorch 0.9.2, Python/PyTorch/ExecuTorch model export, Jest 30.

**Approved spec:** `docs/superpowers/specs/2026-07-24-voice-diarization-identification-design.md`

**Required execution skills:** `@test-driven-development`, `@react-native-best-practices`, `@verification-before-completion`, and `@subagent-driven-development`.

**Execution prerequisite:** Create a dedicated worktree before implementation. Do not implement on top of the current workspace's staged user changes.

---

## File structure

### New files

- `tools/model-export/export_speaker_encoder.py` — export and parity-check the fixed 3-second CAM++ waveform encoder.
- `tools/model-export/verify_speaker_encoder.py` — evaluate same/different-speaker separation on a deterministic LibriSpeech subset.
- `tools/model-export/publish_speaker_probe.sh` — publish a SHA-addressed prerelease probe and emit its build environment.
- `tools/model-export/speaker_gate.json` — durable approved digest, calibrated thresholds, and aggregate Android gate evidence.
- `src/voiceModels.ts` — speaker model source, ID, dimension, fixed input, and model-stamped thresholds.
- `src/voiceModels.test.ts` — environment override and stamp contracts.
- `src/audioWorkCore.ts` — pure, non-reentrant exclusive coordinator.
- `src/audioWorkCore.test.ts` — admission, cancellation, release, and bulk lease contracts.
- `src/audioWork.tsx` — React context binding the coordinator to keep-alive and interactive yielding.
- `src/voiceTypes.ts` — persisted and UI voice-domain types.
- `src/voiceDiarizationCore.ts` — window planning, clustering, turn merging, durations, and ordinals.
- `src/voiceDiarizationCore.test.ts` — deterministic synthetic diarization tests.
- `src/voiceProfileCore.ts` — centroid construction and conservative profile matching.
- `src/voiceProfileCore.test.ts` — confirmation, threshold, margin, rejection, and model-space tests.
- `src/voiceDb.ts` — voice schema and repository factory; receives the existing DB opener and cache invalidator.
- `src/voiceDb.test.ts` — portable `sql.js` repository, transaction, migration, and lifecycle tests.
- `src/voiceAnalysis.ts` — injected, React-free per-video orchestration and lifecycle.
- `src/voiceAnalysis.test.ts` — fake-runner/fake-repository lifecycle tests.
- `src/voiceTeaching.ts` — post-commit confirmation, rejection, and correction service.
- `src/voiceTeaching.test.ts` — event ordering and failed-mutation side-effect tests.
- `src/voice.tsx` — VAD/speaker hooks and production queue API.
- `src/components/VoicePanel.tsx` — timeline, speaker summary, and attributed transcript.
- `src/components/VoiceIdentifySheet.tsx` — profile selection, creation, confirmation, and rejection.
- `src/components/VoiceFixSheet.tsx` — merge, move, create, split, quality, exclusion, and identity corrections.
- `src/components/KnownVoicesSheet.tsx` — rename, aliases/type editing, sample count, and deletion.

### Existing files to modify

- `package.json` and `package-lock.json` — add portable `sql.js` test-only dependencies.
- `.github/workflows/export-models.yml` — export, verify, checksum, and publish the speaker model after the gate passes.
- `tools/model-export/README.md` — document the speaker model contract and verification commands.
- `App.tsx` — mount `AudioWorkProvider` above `AudioProvider` and `VoiceProvider`.
- `src/audio.tsx` — replace `busyRef`/direct keep-alive ownership with the shared coordinator and expose the current STT runner for optional turn transcription.
- `src/db.ts` — invoke voice schema initialization; connect repository wrappers; include voice cleanup in meme replace/delete/clear; join voice search terms.
- `src/searchCore.ts` and `src/searchCore.test.ts` — lower-weight suggested-identity matching without all-terms boost.
- `src/evalCore.ts` — provide the required empty suggested-voice field for evaluation-only search entries.
- `src/searchIndexCache.ts` and `src/searchIndexCache.test.ts` — cache suggested voice text separately.
- `src/components/MemeGrid.tsx` — load voice analysis for the viewer, add tag scope, route seek requests, and host the focused voice sheets.
- `src/screens/SettingsScreen.tsx` — opt-in, queue/retry/regenerate controls, counts, and known-voices management.
- `docs/audio-transcription.md` and `README.md` — document the shipped voice-analysis behavior after the device scenarios pass.

---

## Chunk 1: Runtime gate and pure foundations

### Task 1: Prove the on-device speaker model before product build-out

This task is a hard gate. Do not start schema or production UI tasks until every acceptance check passes. The prior unsupported generic Moonshine path produced speech for only 12/337 videos; a Python-successful export is not sufficient proof of Android runtime compatibility.

**Files:**
- Create: `tools/model-export/export_speaker_encoder.py`
- Create: `tools/model-export/verify_speaker_encoder.py`
- Create: `tools/model-export/publish_speaker_probe.sh`
- Create after the Android gate: `tools/model-export/speaker_gate.json`
- Create: `src/voiceModels.ts`
- Create initially as a development probe, expanded into production in Task 7: `src/voice.tsx`
- Modify: `tools/model-export/README.md`
- Modify temporarily under `__DEV__`, removed in Task 7: `src/screens/SettingsScreen.tsx`
- Modify: `App.tsx`

- [ ] **Step 1: Pin the candidate contract in a failing TypeScript test**

```ts
import { speakerModelIdentityFromEnv } from './voiceModels';

test('uses the shipped fixed-window CAM++ identity', () => {
  expect(speakerModelIdentityFromEnv({})).toMatchObject({
    id: 'wespeaker-campplus-voxceleb-3s-v1',
    dim: 512,
    sampleRate: 16_000,
    inputSamples: 48_000,
    sha256: null,
  });
});
test('uses a complete probe identity override', () => {
  expect(
    speakerModelIdentityFromEnv({
      EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SOURCE: 'custom.pte',
      EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_ID: 'custom-v1',
      EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_DIM: '512',
      EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SHA256: 'a'.repeat(64),
    })
  ).toMatchObject({ id: 'custom-v1', dim: 512, sha256: 'a'.repeat(64) });
});

test('ignores an override missing only its digest', () => {
  expect(
    speakerModelIdentityFromEnv({
      EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SOURCE: 'custom.pte',
      EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_ID: 'custom-v1',
      EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_DIM: '512',
    })
  ).toMatchObject({ id: 'wespeaker-campplus-voxceleb-3s-v1', sha256: null });
});
```

- [ ] **Step 2: Run the test and verify the module is absent**

Run: `npm test -- voiceModels --runInBand`  
Expected: FAIL because `./voiceModels` does not exist.

- [ ] **Step 3: Implement source and shape metadata without fake calibration**

Use the eventual release URL
`https://github.com/ayooooo123/Memeget/releases/download/models-v1/wespeaker_campplus_voxceleb_3s_xnnpack_fp32.pte`.
Expose `SPEAKER_MODEL_IDENTITY` and `speakerModelIdentityFromEnv`:

```ts
export interface SpeakerModelIdentity {
  id: string;
  label: string;
  source: string;
  dim: number;
  sha256: string | null;
  sampleRate: 16000;
  inputSamples: 48000;
}
```

An override is active only when source, model ID, dimension, and `EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SHA256` are all supplied; partial overrides fall back entirely to the provisional production identity with `sha256: null`. The Android probe requires a non-null override digest. Do not construct the final calibrated `SpeakerModelSpec` yet; Task 1 Step 10 replaces the provisional null with the Android-approved digest and adds measured thresholds.

- [ ] **Step 4: Implement the exporter with parity gates**

Export the non-LM VoxCeleb CAM++ checkpoint because the official WeSpeaker model notes reserve LM tuning for longer than 3-second audio. The wrapper contract is exactly raw float32 waveform `[1,48000]` to embedding `[1,512]`; preprocessing and L2 normalization must be inside the graph. The script must:

1. Download/load the official WeSpeaker checkpoint.
2. Resample only in the offline verification path; the graph receives 16 kHz.
3. Apply the checkpoint's exact fbank/normalization frontend inside the exported wrapper.
4. Export XNNPACK `.pte` using the repository's pinned ExecuTorch toolchain.
5. Load the `.pte` with the Python ExecuTorch runtime.
6. Fail unless output shape is `[1,512]`, all values are finite, norm is within `1 ± 1e-3`, and PyTorch/PTE cosine is at least `0.99` across six fixtures.
7. Print model bytes, median forward time, and peak resident memory where available.

If the frontend cannot lower to the bundled ExecuTorch runtime, stop the feature and amend the spec with the observed unsupported operators. Do not add ONNX Runtime or hand-written fbank as an unreviewed fallback.

**Gate result (2026-07-24): BLOCKED.** The official non-LM checkpoint and exact Kaldi fbank frontend load and capture with `torch.export`, but ExecuTorch 1.0.0 Edge validation rejects `aten._fft_r2c.default` (`float32` to `complex64`) and the following `aten.abs.default` (`complex64` to `float32`). No `.pte` exists. Per this stop condition, Steps 5–11 and Tasks 2–15 were not started. Resume only after an amended, reviewed model boundary passes the complete Task 1 gate.

- [ ] **Step 5: Add deterministic same/different-speaker evaluation**

`verify_speaker_encoder.py` downloads LibriSpeech `test-clean`, selects the first twelve sorted speaker IDs and two sorted utterances per speaker, creates 3-second crops, and evaluates all 12 same-speaker pairs plus all cross-speaker pairs. It also treats utterance A as each profile enrollment and utterance B as its query. `dist/speaker_eval.json` contains typed fields for model ID, same/different score distributions, equal-error threshold/rate, enrollment top-1 accuracy/margins, and recommended thresholds.

Threshold selection is deterministic:

1. `withinVideoThreshold` is the cosine operating point with the lowest balanced pair error; ties choose the higher threshold.
2. `profileThreshold` is the lowest threshold with zero false accepts on this gate set while retaining at least 75% same-speaker accepts.
3. `profileMargin` is the lower of the smallest correct top-1 enrollment margin and one quarter of the median correct margin; it must remain positive.
4. `minEmbeddingSpeechMs` is 1000 for the fixed 3-second non-LM model.

The script fails unless pair equal-error rate is at most 10%, enrollment top-1 accuracy is at least 90%, the profile threshold has zero false accepts and at least 75% same-speaker accepts, and `0 < withinVideoThreshold <= profileThreshold <= 1`. These gates, not an ungated recommendation, determine the production values. Record Apache-2.0 toolkit plus the checkpoint's VoxCeleb/CC-BY-4.0-derived terms in the model README.

- [ ] **Step 6: Run export and offline quality gates**

Run:

```bash
python tools/model-export/export_speaker_encoder.py --out-dir dist
python tools/model-export/verify_speaker_encoder.py \
  --model dist/wespeaker_campplus_voxceleb_3s_xnnpack_fp32.pte \
  --out dist/speaker_eval.json
```

Expected: both commands exit 0; parity cosine is `>= 0.99`; output is `[1,512]`; evaluation reports separated same/different ranges.

- [ ] **Step 7: Publish a uniquely addressed checksummed probe artifact**

Do not upload an unvalidated model to `models-v1`, and do not reuse a probe URL that the app may have cached. Implement `publish_speaker_probe.sh` to compute the model SHA-256, create a unique prerelease tag `models-probe-<first-12-sha>`, upload the file without `--clobber`, verify a downloaded copy, and write `dist/voice-probe.env`:

```bash
export PROBE_TAG=models-probe-<first-12-sha>
export EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SOURCE=https://github.com/ayooooo123/Memeget/releases/download/$PROBE_TAG/wespeaker_campplus_voxceleb_3s_xnnpack_fp32.pte
export EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_ID=wespeaker-campplus-voxceleb-3s-v1
export EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_DIM=512
export EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SHA256=<full-sha>
```

The angle-bracket values above describe script output, not literals committed to source. Run:

```bash
bash tools/model-export/publish_speaker_probe.sh \
  dist/wespeaker_campplus_voxceleb_3s_xnnpack_fp32.pte
source dist/voice-probe.env
curl -fL "$EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SOURCE" -o /tmp/speaker-probe.pte
test "$(shasum -a 256 /tmp/speaker-probe.pte | cut -d ' ' -f 1)" = "$EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SHA256"
```

Expected: unique prerelease creation, upload, download, and digest comparison all succeed. If the Android gate fails, run `source dist/voice-probe.env && gh release delete "$PROBE_TAG" -y`.

- [ ] **Step 8: Add the smallest possible Android probe**

In `src/voice.tsx`, use `useVAD({ model: models.vad.fsmn_vad() })` and `useExecutorchModule({ modelSource: SPEAKER_MODEL_IDENTITY.source })`. Adapt a 48,000-sample waveform to one `TensorPtr` with `ScalarType.FLOAT`; validate one `[1,512]` finite output and normalize defensively. Add a `__DEV__` Settings action, **Probe voice model**, that:

1. Reuses `materialize`, `extractAudio`, and `pcmBase64ToWaveform`.
2. Processes 20 video IDs without persisting embeddings.
3. Reads `EXPO_PUBLIC_MEMEGET_VOICE_PROBE_PAIRS` in the exact form `same=12:14,33:35;different=12:33,14:35`.
4. Logs IDs, VAD bounds, timings, vector norms, and only the requested pairwise cosine values.
5. Never logs waveform samples or embedding coordinates.
6. Produces one comparable embedding per configured ID by choosing that video's longest eligible VAD region, taking a centered 48,000-sample crop or symmetric zero-padding when shorter. A configured pair with no eligible region fails the probe instead of silently disappearing.
7. Adds every ID named in the pair config to the probe set, then fills the remainder to 20 with ascending indexed video IDs not already present.
8. Writes a shareable cache file `speaker_gate_device.json` containing `SPEAKER_MODEL_IDENTITY.sha256`, model ID, runtime version, processed/succeeded counts, aggregate minimum same score, aggregate maximum different score, and crash/error count; it contains no meme IDs, names, waveforms, or embeddings.
9. Deletes every materialized media/PCM cache file in `finally`, including no-speech, failed-forward, and cancelled paths.

- [ ] **Step 9: Run the Android compatibility and stability gate**

Build with all four identity override variables pointed at the probe artifact, plus known comparison pairs:

```bash
set -a
source dist/voice-probe.env
set +a
EXPO_PUBLIC_MEMEGET_VOICE_PROBE_PAIRS='same=12:14,33:35;different=12:33,14:35' \
  npm run android
```

Exercise: enable Audio analysis, wait for both models, and run the probe across its 20-video set, which must include silence, music, movie dialogue, and a podcast.

Expected evidence in logcat:

- FSMN VAD returns bounded, ordered start/end segments. The API exposes no confidence or overlap, and the probe must not invent them.
- CAM++ downloads from the checksummed release asset and loads with no program-version error.
- Twenty videos complete the probe without native crash; silence/no-eligible-speech is a successful no-forward result.
- At least twelve eligible CAM++ forwards succeed, including every configured pair video.
- Every accepted output is 512 finite values with norm near 1.
- All configured same-speaker scores meet `speaker_eval.json`'s recommended `profileThreshold`.
- All configured different-speaker scores are below the recommended `withinVideoThreshold`.
- `minimumSame - maximumDifferent` is at least the recommended `profileMargin`.

If any load/forward crash occurs or known pairs do not separate, delete the provisional asset and stop. Do not proceed by weakening tests or adding product tables/UI around an unproven model.


- [ ] **Step 10: Freeze the measured model config and CI export**
After a passed gate, source `dist/voice-probe.env`, download that exact unique probe asset, verify `EXPO_PUBLIC_MEMEGET_SPEAKER_MODEL_SHA256`, upload the unchanged bytes to `models-v1`, verify the production download has the same digest, and only then delete the unique probe release. Record the approved digest in `speaker_gate.json` and the final non-null `SPEAKER_MODEL`; no development build keeps an unvalidated cache path.
Copy the gate-passed recommended thresholds from `speaker_eval.json` and approved digest into a complete non-null `SpeakerModelSpec` in `voiceModels.ts`, expose `SPEAKER_MODEL`, and remove the provisional null production identity. In `voiceModels.test.ts`, read `tools/model-export/speaker_gate.json` with Node `fs.readFileSync` plus `JSON.parse` (do not import JSON or change `tsconfig.json`) and assert exact equality for `sha256`, `withinVideoThreshold`, `profileThreshold`, `profileMargin`, and `minEmbeddingSpeechMs`, in addition to a 64-hex digest, `0 < withinVideoThreshold <= profileThreshold <= 1`, `0 < profileMargin < profileThreshold`, and positive duration. Merge the offline report, shared `speaker_gate_device.json`, and approved SHA-256 into `speaker_gate.json`; omit video IDs. Update the probe to consume `SPEAKER_MODEL`. Add the exporter/evaluator to `.github/workflows/export-models.yml`; CI must rerun every offline metric gate, run the exact config-equality test, and fail before upload unless the newly exported file's SHA-256 equals `speaker_gate.json.approvedSha256`. Publish only the already promoted, digest-matching artifact; never replace it with a merely parity-passing re-export. Update the model README with digest, metrics, exact input/output/license/verification commands, and promotion procedure. Remove transient evaluation/device reports from release assets.

- [ ] **Step 11: Re-run focused checks and commit the passed gate**

Run:

```bash
npm test -- voiceModels --runInBand
npm run typecheck
```

Expected: PASS. Commit only after offline parity, provisional-asset checksum, Android compatibility, stability, and known-pair evidence all pass.

```bash
git add tools/model-export/export_speaker_encoder.py tools/model-export/verify_speaker_encoder.py \
  tools/model-export/publish_speaker_probe.sh tools/model-export/speaker_gate.json \
  tools/model-export/README.md .github/workflows/export-models.yml \
  src/voiceModels.ts src/voiceModels.test.ts src/voice.tsx App.tsx \
  src/screens/SettingsScreen.tsx
git commit -m "feat(voice): prove on-device speaker embeddings"
```

### Task 2: Replace transcription's private mutex with one audio-work coordinator

**Files:**
- Create: `src/audioWorkCore.ts`
- Create: `src/audioWorkCore.test.ts`
- Create: `src/audioWork.tsx`
- Modify: `src/audio.tsx:60-77,150-282`
- Modify: `App.tsx:9-12,55-68`

- [ ] **Step 1: Write failing coordinator contract tests**

Cover: first admission succeeds; concurrent admission returns `busy`; `finally` releases after throw; a bulk lease can call a lease-aware worker repeatedly without reacquiring; cancellation is visible through `AudioWorkSignal`; and only the coordinator can construct a valid signal.

```ts
const coordinator = createAudioWorkCoordinator({
  onKindChange: jest.fn(),
  acquireKeepAlive: () => jest.fn(),
  yieldToInteractive: async () => {},
});
const held = deferred<void>();
const first = coordinator.runExclusive(
  'transcription',
  { label: 'Transcribing', shouldCancel: () => false },
  async () => held.promise
);
expect(
  await coordinator.runExclusive(
    'voice-analysis',
    { label: 'Voices', shouldCancel: () => false },
    async () => 1
  )
).toBe('busy');
held.resolve();
await first;
```

- [ ] **Step 2: Run the test and verify failure**

Run: `npm test -- audioWorkCore --runInBand`  
Expected: FAIL because the coordinator does not exist.

- [ ] **Step 3: Implement the pure non-reentrant coordinator**

Export `AudioWorkKind`, opaque `AudioWorkSignal`, `AudioWorkOptions = { label: string; shouldCancel: () => boolean }`, `RunExclusiveResult<T> = T | 'busy'`, and `createAudioWorkCoordinator`. The public signature is `runExclusive(kind, options, work)`. The signal exposes `cancelled()` and `yieldToInteractive()`; it carries a private token checked by lease-aware workers. `runExclusive` owns busy state, keep-alive acquisition/release, and state callbacks in `try/finally`.

- [ ] **Step 4: Bind it to React once**

`AudioWorkProvider` memoizes one coordinator using `acquireKeepAlive` and `yieldToSearch`. `useAudioWork()` returns `runningKind`, public `runExclusive`, and `assertSignal`. Mount it above both audio model providers in `App.tsx`.

- [ ] **Step 5: Migrate `AudioProvider` without changing behavior**

Split current logic into:

```ts
async function transcribeOneWithLease(
  signal: AudioWorkSignal,
  row: MemeNeedingAudioRow
): Promise<'done' | 'silent' | 'failed'>
async function runPendingWithLease(
  signal: AudioWorkSignal,
  opts: { onProgress?: (p: TranscribeProgress) => void }
): Promise<TranscribeResult>
```

`runTranscription` acquires one bulk lease and calls `runPendingWithLease` once; that internal worker owns the per-row loop. `regenerateMeme` acquires one single-video lease. Remove `busyRef` and direct `acquireKeepAlive` calls. Preserve `yieldToSearch`, `setTimeout(0)` between Whisper calls, pre-mark-failed crash protection, and all current return variants.

- [ ] **Step 6: Run regression tests and typecheck**

Run:

```bash
npm test -- audioWorkCore audioCore --runInBand
npm run typecheck
```

Expected: PASS; current transcription behavior remains covered.

- [ ] **Step 7: Smoke-test transcription admission and commit**

On device, start bulk transcription and invoke per-video retranscription. Expected: second action reports busy; bulk continues; keep-alive ends once; no second model generation starts.

```bash
git add src/audioWorkCore.ts src/audioWorkCore.test.ts src/audioWork.tsx src/audio.tsx App.tsx
git commit -m "refactor(audio): share one analysis coordinator"
```

### Task 3: Implement deterministic diarization and speaker ordering

**Files:**
- Create: `src/voiceTypes.ts`
- Create: `src/voiceDiarizationCore.ts`
- Create: `src/voiceDiarizationCore.test.ts`

- [ ] **Step 1: Write failing tests for observable diarization contracts**

Use normalized synthetic vectors. Cover three clusters arriving in interleaved turn order; ordinal by earliest owned interval; adjacent same-speaker merge; non-overlapping duration sums; deterministic tie-breaking; two individually sub-threshold VAD fragments separated by a tiny gap joining into one eligible region; a gap above the join threshold staying separate; two adjacent speaker regions above that gap never contributing samples to each other's contexts; an uninterrupted 120,000-sample region producing three deterministic owned windows whose durations sum to 120,000 and whose contexts are each 48,000 samples; duration-derived `short` windows and explicitly user-flagged `overlap`/`noisy` windows remaining visible but learning-ineligible; and one-speaker output. Assert initial automatic observations never invent overlap/noisy flags.

```ts
expect(
  diarize(observations, config).speakers.map((s) => ({
    ordinal: s.ordinal,
    voicedSamples: s.voicedSamples,
  }))
).toEqual([
  { ordinal: 1, voicedSamples: 48_000 },
  { ordinal: 2, voicedSamples: 24_000 },
  { ordinal: 3, voicedSamples: 32_000 },
]);
```

- [ ] **Step 2: Run the test and verify failure**

Run: `npm test -- voiceDiarizationCore --runInBand`  
Expected: FAIL because the module is absent.

- [ ] **Step 3: Define focused domain types**

In `voiceTypes.ts`, define `VoiceState`, `TranscriptState`, `TurnQuality`, `IdentityState`, `VoiceProfileKind`, `VoiceProfile`, `SpeechTurn`, `VideoSpeaker`, `VoiceAnalysis`, `SpeechObservation`, and `SpeakerModelStamp`. The native adapter immediately validates FSMN's seconds and converts them to clamped integer sample offsets with `round(seconds * 16_000)`. Core `SpeechObservation` uses `ownedStartSample`/`ownedEndSample`; fixed model contexts also use samples. Convert to integer persisted milliseconds exactly once at the repository boundary with `round(samples * 1000 / 16_000)`. UI and SQLite never receive native seconds.

- [ ] **Step 4: Implement fixed-window planning**

Accept only validated ordered integer sample-offset VAD segments. Before the short-region gate, join consecutive segments whose gap is at most `DiarizationConfig.maxVadGapSamples`, initialized to 3,200 samples (200 ms at 16 kHz); do not join across a larger gap. For a joined region of length `L`, create `n = ceil(L / 48_000)` contiguous, non-overlapping owned subranges using boundaries `start + floor(i * L / n)`; their union is exactly the joined region, including the deterministic remainder. Give each owned subrange a centered 48,000-sample model context drawn only from within that same joined VAD region; contexts may overlap inside a long region, but samples before/after the joined region are always zeros rather than neighboring silence/music/another speaker. Owned ranges never overlap. Apply the `minEmbeddingSpeechMs * 16` short gate to the joined region before tiling, so a tiny final remainder of an eligible long region still receives its contextual embedding. A whole joined region below the gate remains one visible `short` observation without an embedding. Initial output is otherwise `clean`; only correction operations may later set `overlap` or `noisy`.

- [ ] **Step 5: Implement deterministic agglomerative clustering**

Start one cluster per clean embedded observation. Repeatedly merge the pair with the highest centroid cosine above `withinVideoThreshold`; tie-break by earliest owned start then original index. Normalize centroids after each merge. Never cluster incompatible model stamps or ineligible observations.

- [ ] **Step 6: Build turns and ordinals**

Merge adjacent owned intervals for the same cluster only when no other speaker intervenes and the gap is at most `DiarizationConfig.maxTurnGapSamples`, initialized to 4,000 samples (250 ms at 16 kHz). Each merged turn retains `voicedSamples` as the sum of constituent owned lengths, excluding the intervening gap; speaker totals sum turn `voicedSamples`, not `endSample - startSample` or padded contexts. Sort speakers by first turn and assign 1-based ordinals. Keep user-marked ambiguous turns with `videoSpeakerId = null`.

- [ ] **Step 7: Run tests and commit**

Run:

```bash
npm test -- voiceDiarizationCore --runInBand
npm run typecheck
```

Expected: PASS.

```bash
git add src/voiceTypes.ts src/voiceDiarizationCore.ts src/voiceDiarizationCore.test.ts
git commit -m "feat(voice): diarize ordered speaker turns"
```

### Task 4: Implement confirmation-only profile centroids and conservative matching

**Files:**
- Create: `src/voiceProfileCore.ts`
- Create: `src/voiceProfileCore.test.ts`

- [ ] **Step 1: Write failing centroid and matcher tests**

Cover: normalized mean of confirmed samples; suggested samples excluded; duration does not weight samples; incompatible model/dimension excluded; threshold and runner-up margin both required; exact observation/profile rejection veto; no centroid means no match; stable tie-breaking.

- [ ] **Step 2: Run the test and verify failure**

Run: `npm test -- voiceProfileCore --runInBand`  
Expected: FAIL because the module is absent.

- [ ] **Step 3: Implement allocation-conscious vector operations**

Use one reusable `Float64Array` accumulator per centroid build and one pass per comparison. Reject length mismatch before dot products. Return a new normalized `Float32Array` only for the final centroid.

- [ ] **Step 4: Implement `matchVoiceProfile`**

Return `null` unless the best score passes `profileThreshold`, exceeds the runner-up by `profileMargin`, has sufficient voiced duration, matches the model stamp/dimension, and is not vetoed. Return raw score plus qualitative confidence metadata; do not call cosine a probability.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
npm test -- voiceProfileCore --runInBand
npm run typecheck
```

Expected: PASS.

```bash
git add src/voiceProfileCore.ts src/voiceProfileCore.test.ts
git commit -m "feat(voice): match confirmed speaker profiles"
```

---

## Chunk 2: Persistence and production analysis

### Task 5: Add normalized voice storage and atomic lifecycle operations

**Files:**
- Create: `src/voiceDb.ts`
- Create: `src/voiceDb.test.ts`
- Modify: `src/db.ts:75-294,398-475,1384-1520,1711-1717`
- Modify: `package.json`
- Modify: `package-lock.json`

- [ ] **Step 1: Add a portable in-memory SQLite harness and failing lifecycle tests**

Run `npm install --save-dev sql.js @types/sql.js`, then exercise `createVoiceRepository` through a small in-memory `sql.js` adapter with the same async surface as Expo SQLite. Cover idempotent schema/migration, including `speech_turns.voiced_ms`; exact sample-offset-to-integer-millisecond mapping for start/end and per-turn voiced duration; successful replacement; forced failure midway through replacement rolling back speakers, turns, samples, rejections, centroids, and meme state; retry selection for failed first analysis and retained-result refresh failure; corrupt blob rejection; voice-aware meme deletion and URI replacement with forced failures after cleanup proving fingerprint/meme/voice/centroid rollback together; clear-all cleanup; and foreign-key enforcement. Cover insertion while `VOICE_ENABLED_KEY` is enabled: a normal video starts `pending`, an image stays `none`, and a degraded/undecodable video stays `none`.

- [ ] **Step 2: Run the repository test and verify failure**

Run: `npm test -- voiceDb --runInBand`  
Expected: FAIL because `voiceDb.ts` does not exist.

- [ ] **Step 3: Define the repository interface before SQL**

Export `VOICE_ENABLED_KEY` and `VoiceRepository` with exact operations used by analysis/UI: queue/count/stats, get analysis, `markAttemptStarted`, atomically replace a sample-domain analysis, record first/reanalysis failure, create/update/delete profile, confirm/reject identity, apply correction transaction, load search terms, remove one meme's voice data, and clear all voice data.

- [ ] **Step 4: Add idempotent schema creation and column migrations**

`initVoiceSchema(db)` creates the spec's `voice_profiles`, `video_speakers`, `speech_turns`, `voice_samples`, and `voice_rejections` tables and indexes. `speech_turns` includes `voiced_ms INTEGER NOT NULL`, separate from start/end span. Add that column and `voice_state`, `voice_model`, `voice_last_error`, and `voice_last_attempted_at` idempotently when absent; for legacy turns initialize `voiced_ms = max(0, end_ms - start_ms)`. Enable foreign keys. Existing non-degraded indexed videos become `pending` only when the voice opt-in migration runs; images and degraded/undecodable videos remain `none`.

- [ ] **Step 5: Implement blob and row mapping without duplicating vector math**

Inject `vecToBlob`/`blobToVec` into `createVoiceRepository`. Parse aliases defensively. Reject embeddings whose byte length is not `dimension * 4`; record a first/reanalysis failure rather than comparing malformed data. The replacement input uses integer 16 kHz sample offsets. Convert each turn's start, end, and `voicedSamples` exactly once with `round(samples * 1000 / 16_000)` and persist all three; reads expose `startMs`, `endMs`, and `voicedMs`. Speaker totals are recomputed from stored turn `voiced_ms`, never from boundary span.

- [ ] **Step 6: Implement atomic successful replacement**

Inside one SQLite transaction: remove old observation-local samples/rejections; track affected profiles; rebuild their centroids from remaining samples; load post-removal profiles; match new aggregates; insert speakers/turns; update model/state/error; and commit. Invalidate search only after commit. Verify the profile whose only sample was removed cannot suggest itself back.

- [ ] **Step 7: Implement attempt, failure, and cancellation-safe state operations**

`markAttemptStarted(id)` always updates `voice_last_attempted_at`; it leaves a first attempt `pending` and a reanalysis with valid rows `done`. First failure sets `failed` plus error. Reanalysis failure retains rows and `done`, records `voice_last_error`, and is returned by retry queries. Cancellation calls neither failure operation: the first attempt stays queued and reanalysis keeps its prior rows/state/error. Starting reanalysis never persists `pending` over a valid result.

- [ ] **Step 8: Wire insertion, deletion, and replacement invariants into `db.ts`**

Export `runVoiceAwareMemeMutation(db, memeId, mutateMeme)` from `voiceDb.ts`; it opens one transaction, runs transaction-scoped voice cleanup/centroid rebuild, then invokes `mutateMeme(tx)` for the fingerprint plus meme delete or URI-replacement statements. Refactor both current non-transactional `deleteMeme` and `INSERT OR REPLACE` paths in `db.ts` through this helper, so any later failure rolls back all domains together. For a new insert, explicitly derive `voice_state`: `pending` only when voice opt-in is enabled and the row is a non-degraded video; otherwise `none`. `clearIndex` deletes all voice profiles and observations. Invalidate search only after successful commit.

- [ ] **Step 9: Add repository wrappers to `db.ts` without growing it with SQL bodies**

Instantiate `createVoiceRepository({ getDb, vecToBlob, blobToVec, invalidateSearchIndex })` and re-export its typed operations. SQL and row mapping remain in `voiceDb.ts`; `db.ts` owns only connection, schema call, and cross-domain mutation hooks.

- [ ] **Step 10: Run repository tests, typecheck, and commit**

```bash
npm test -- voiceDb --runInBand
npm run typecheck
```

Expected: PASS, including the forced rollback and post-opt-in insertion cases.

```bash
git add package.json package-lock.json src/voiceDb.ts src/voiceDb.test.ts src/db.ts
git commit -m "feat(voice): persist speaker analyses and profiles"
```
### Task 6: Build an injected, testable per-video analysis service

**Files:**
- Create: `src/voiceAnalysis.ts`
- Create: `src/voiceAnalysis.test.ts`
- Modify: `src/audioCore.ts` only if a waveform-slice helper is needed; test it in `src/audioCore.test.ts`

- [ ] **Step 1: Write failing orchestration and VAD-boundary tests with fakes**

Test: native VAD seconds validate, clamp, and round to 16 kHz sample offsets; malformed/non-finite/reversed segments are rejected; no speech commits `done` with no speakers or turns; short-only speech commits `done` with one visible unassigned learning-ineligible turn and no speaker; a no-speech reanalysis failure retains the prior no-speech `done` result and records Refresh failed; VAD/embedding failure records the correct first-analysis failure; failed reanalysis retains old result; multiple eligible turns await one injected native-state-settling macrotask after every Whisper attempt before starting the next; per-turn Whisper failure stores only that turn as failed; unavailable Whisper stores `not_requested`; successful replacement receives sample-domain aggregates; successful replacement and committed failure-state writes each emit one library-change event only after the repository resolves; a replacement rollback followed by a committed failure-state write emits once after that second commit; a failure-state write that rejects/rolls back and cancellation emit none; first-attempt cancellation remains queued; reanalysis cancellation preserves rows/state/error; cancellation stops before the next expensive stage; and temp files always delete.

- [ ] **Step 2: Run the test and verify failure**

Run: `npm test -- voiceAnalysis --runInBand`  
Expected: FAIL because the service does not exist.

- [ ] **Step 3: Define injected runner and repository ports**

```ts
interface SampleSegment { startSample: number; endSample: number }
interface VoiceActivityRunner {
  detect(waveform: Float32Array): Promise<SampleSegment[]>
}
interface SpeakerEmbeddingRunner {
  embed(window: Float32Array): Promise<Float32Array>
}
interface TurnTranscriber {
  ready(): boolean
  transcribe(window: Float32Array): Promise<string>
}
```

Export pure `vadSecondsToSampleSegments(nativeSegments, waveformLength, 16_000)` for the native adapter. Inject materialize/extract/read/delete, clock, model spec, repository, library-change emitter, logger, and `settleAfterTurnTranscription`. The core imports no React Native modules.

- [ ] **Step 4: Implement one lease-aware analysis**

`analyzeVoiceMemeWithLease(signal, row, deps)` validates the coordinator signal, calls `markAttemptStarted` before expensive work, checkpoints cancellation between stages, extracts waveform once, accepts only bounded sample segments from the VAD port, plans fixed windows, embeds eligible windows sequentially, diarizes, then optionally transcribes eligible turns independently. After every attempted Whisper call—success or failure—await `settleAfterTurnTranscription()` before starting the next turn. Then call atomic replacement. On success, emit the library-change event after replacement resolves. On a non-cancellation error, record the appropriate first/reanalysis failure and emit only after that write resolves. On cancellation, return `cancelled` without calling replacement/failure or emitting.

- [ ] **Step 5: Preserve optional transcript semantics**

Clean each turn with existing `cleanTranscript`. Do not fail `voice_state` for unavailable/failed turn STT. Do not alter `memes.transcript`; whole-video transcription remains owned by `audio.tsx`.

- [ ] **Step 6: Run focused tests and commit**

```bash
npm test -- voiceAnalysis voiceDiarizationCore voiceProfileCore audioCore --runInBand
npm run typecheck
```

Expected: PASS, including post-commit event ordering and both cancellation lifecycle cases.

```bash
git add src/voiceAnalysis.ts src/voiceAnalysis.test.ts src/audioCore.ts src/audioCore.test.ts
git commit -m "feat(voice): orchestrate on-device speaker analysis"
```
### Task 7: Convert the model probe into the production voice provider

**Files:**
- Modify: `src/voice.tsx`
- Modify: `App.tsx`
- Modify: `src/audio.tsx`
- Modify: `src/screens/SettingsScreen.tsx`

- [ ] **Step 1: Define `VoiceApi`**

Expose opt-in/readiness/download/error/running state; `analyzePending`; `retryFirstFailures`; `retryRefreshFailures`; `regenerateAll`; `analyzeMeme`; and progress/result types. Dispatch results explicitly: coordinator refusal → `busy`; disabled/unloaded model → `not-ready`; absent/non-video row → `missing`; cancellation → `cancelled`; a committed result containing any speaker or unassigned/short turn → `done`; a committed result with neither speakers nor turns → `no-speech`; committed failure state → `failed`.

- [ ] **Step 2: Adapt supported native hooks at an explicit unit boundary**

Use built-in `models.vad.fsmn_vad()` through `useVAD`. Its `{ start, end }` values are seconds: the adapter must call tested `vadSecondsToSampleSegments(result, waveform.length, 16_000)` and expose only `{ startSample, endSample }` to `VoiceAnalysis`. Use the gate-proven custom `.pte` through `useExecutorchModule`. Build `TensorPtr` without copying when the window is already a 48,000-sample `Float32Array`; validate output size/type before normalization.

- [ ] **Step 3: Share the current Whisper runner without nesting providers**

Extend `AudioApi` with an internal `turnTranscriber` adapter whose `ready()` and `transcribe()` use the latest `useSpeechToText` ref. Inject `settleAfterTurnTranscription` as `() => new Promise<void>((resolve) => setTimeout(resolve, 0))`, preserving the current native hook's required macrotask yield between every per-turn attempt. `VoiceProvider` consumes these adapters but does not control `audio_state` or whole-video transcripts.

- [ ] **Step 4: Implement public single and bulk entry points**

`analyzeMeme` acquires one voice lease then calls the internal worker and returns `missing` for images or degraded/undecodable videos. `analyzePending` selects only `pending`; `retryFirstFailures` selects only `voice_state = 'failed'`; `retryRefreshFailures` selects only retained results with `voice_state = 'done' AND voice_last_error <> ''`; `regenerateAll` selects every non-degraded indexed video without clearing valid rows first. Each bulk action acquires one lease and loops the worker with the same opaque signal. Rows with an incompatible model remain untouched and surface in counts until the compatible model is available. Remove the ordinary probe control, but retain its no-persistence gate runner and `speaker_gate_device.json` writer behind both `__DEV__` and `EXPO_PUBLIC_MEMEGET_VOICE_GATE === '1'`; it is absent from release builds and hidden in normal development.

- [ ] **Step 5: Mount and typecheck**

Provider order:

```tsx
<AudioWorkProvider>
  <AudioProvider>
    <VoiceProvider>{children}</VoiceProvider>
  </AudioProvider>
</AudioWorkProvider>
```

Run: `npm run typecheck`  
Expected: PASS.

- [ ] **Step 6: Smoke-test one video and commit**

Analyze one known three-speaker clip. Expected: DB/UI reads integer-millisecond ordered speakers/turns matching audible boundaries, flat transcript stays unchanged, one post-commit library refresh occurs, and no concurrent Whisper/voice forward occurs.

```bash
git add src/voice.tsx src/audio.tsx App.tsx src/screens/SettingsScreen.tsx
git commit -m "feat(voice): run production speaker analysis"
```
### Task 8: Add queue, retry, regenerate, and counts to Audio settings

**Files:**
- Modify: `src/screens/SettingsScreen.tsx:1-131,547-617,875-961`
- Modify: `src/voice.tsx`

- [ ] **Step 1: Add voice stats to the existing refresh batch**

Track pending, analyzed/no-speech, failed-first-analysis, refresh-failed, incompatible-model, known profile count, and running progress. Fetch in `refresh()` with the same defensive `.catch()` pattern as transcription stats.

- [ ] **Step 2: Add an independent Voice identification opt-in**

Place it under Audio analysis. Explain that it downloads local VAD/speaker models and never uploads audio. Enabling queues existing non-degraded indexed videos; images and degraded/undecodable videos remain `none`. Task 5 already makes subsequently indexed eligible videos enter `pending`. Disabling prevents model load and new analysis but preserves results/profiles.

- [ ] **Step 3: Add production controls**

Implement Analyze pending, Stop, Retry first failures, Retry refresh failures, Regenerate all, and model progress. First-failure retry is non-destructive because no last-good result exists. Before refresh retry or Regenerate, show the same warning that a successful replacement removes this video's prior confirmed sample/identity and may require reconfirmation; cancellation starts nothing. Neither action deletes last-good rows before each replacement succeeds.

- [ ] **Step 4: Verify shared admission and durable cancellation**

Start transcription, then voice analysis; expected voice action reports busy. Reverse the order; expected transcription reports busy. Enable voice analysis and verify existing eligible videos queue while degraded rows do not. Exercise non-destructive first-failure retry. For retained-result refresh retry, cancel the warning and verify no work starts, then confirm and verify last-good rows remain until replacement commits. Do the same cancel/confirm check for Regenerate all. Cancel an admitted run at a checkpoint: remaining rows stay queued, a cancelled first attempt remains pending without a failure error, and cancelled reanalysis keeps `done`, prior rows, and its prior error value.

- [ ] **Step 5: Typecheck and commit**

Run: `npm run typecheck`  
Expected: PASS.

```bash
git add src/screens/SettingsScreen.tsx src/voice.tsx
git commit -m "feat(voice): manage speaker analysis in settings"
```
## Chunk 3: Viewer, identification, and corrections

### Task 9: Show ordered speakers, timeline, turns, and seeking

**Files:**
- Create: `src/components/VoicePanel.tsx`
- Modify: `src/components/MemeGrid.tsx:1332-1688,1714-1735`

- Modify: `src/voiceDiarizationCore.ts`
- Modify: `src/voiceDiarizationCore.test.ts`

- [ ] **Step 1: Add pure timeline layout tests**

In `voiceDiarizationCore.test.ts`, test `layoutTurns(turns, durationMs)` returns clamped left/width fractions against the full player-reported duration and deterministic speaker colors for assigned clean turns. User-marked overlap/ambiguous turns use a neutral overlap style; unassigned `quality = 'short'` turns use a separate muted-short style that does not imply overlap. A non-finite or non-positive/not-yet-loaded duration returns no timeline segments rather than dividing by zero or compressing to the last turn.

- [ ] **Step 2: Load analysis only for the open video**

`ViewerSheet` tracks `{ status: 'loading' | 'ready' | 'error'; analysis }`, resets it and all playback metadata when the item changes, fetches `getVoiceAnalysis(item.id)` for videos only, and ignores late results. Render a non-stale loading/error state and disable voice-dependent Tag actions until the read settles. Do not add nested voice arrays to every `MemeRecord` or grid query.

- [ ] **Step 3: Implement `VoicePanel`**

Render stable rows with `#N`, Unknown/Likely/Confirmed name, first-spoke timestamp, turn count, and summed duration. Render the color timeline and chronological attributed turns. Show user-marked ambiguous overlap neutrally. One speaker still reads naturally without multi-speaker instructions. Keep the existing whole-video `memes.transcript` selectable and copyable alongside the structured view; attributed turns never replace it.

- [ ] **Step 4: Add seek plumbing without owning a second player**

`VideoPreview` keeps the one `useVideoPlayer` instance and sets `player.timeUpdateEventInterval = 0.25` while mounted (Expo Video otherwise emits no periodic time updates). It reports player duration and current position to `ViewerSheet` from that player's status/time-update events, with both reset to zero on item change; no polling player or hidden media instance is created. Lift `seekToMs` back to the same preview and set `player.currentTime = ms / 1000`; timeline/turn/sample taps call it. Pass the real duration to `VoicePanel`; retain current-position state for Task 11 to pass into `VoiceFixSheet` when that component exists.

- [ ] **Step 5: Handle empty/error states**

`loading`, read error, no structured result, short-only result, and valid no-speech are distinct. No result keeps the current flat transcript. `done` with neither speakers nor turns shows no everyday panel and a maintenance note `No speech detected`. A short-only result renders its neutral timed unassigned turn with `Short speech—voice not identifiable`; it is not labeled no speech. Every structured result still shows the selectable flat transcript below attributed turns. Retained result plus `voice_last_error` shows `Refresh failed` without hiding turns.

- [ ] **Step 6: Add the single-video analysis consumer**

Add `Analyze voices` when the open video has no result and `Reanalyze voices` in its maintenance actions otherwise. Before reanalysis, warn that successful replacement removes this video's prior confirmed voice sample/identity and may require reconfirmation; cancel starts nothing. After confirmation, await `VoiceApi.analyzeMeme(item.id)` and dispatch every result: `done`, `no-speech`, and `failed` reload only the open analysis (so first failure or retained-result refresh error is visible); `busy`, `not-ready`, `missing`, and `cancelled` keep the current view and show the matching message. Disable the action while it runs and never clear last-good turns optimistically.

- [ ] **Step 7: Typecheck, device-smoke, and commit**

Run: `npm run typecheck`. On device verify the timeline spans the full duration including trailing silence, seeks the three-speaker clip, ordinals/durations match stored turns, live position resets on item change, short-only speech renders neutrally, and the flat transcript remains selectable. Exercise Analyze success/no-speech and Reanalyze warning cancel/confirm, retained-result failure, busy, and cancellation; only committed outcomes reload the open analysis.

```bash
git add src/components/VoicePanel.tsx src/components/MemeGrid.tsx src/voiceDiarizationCore.ts src/voiceDiarizationCore.test.ts
git commit -m "feat(voice): show speaker timelines in videos"
```

### Task 10: Identify speakers and create confirmed profiles

**Files:**
- Create: `src/components/VoiceIdentifySheet.tsx`
- Modify: `src/components/VoicePanel.tsx`
- Modify: `src/components/MemeGrid.tsx`
- Modify: `src/voiceDb.ts`
- Modify: `src/voiceDb.test.ts`
- Create: `src/voiceTeaching.ts`
- Create: `src/voiceTeaching.test.ts`

- [ ] **Step 1: Write failing real-repository transition tests**

Add `sql.js` cases to `voiceDb.test.ts`: confirm unknown to new profile; confirm suggested to existing compatible profile; reject suggestion creates only the exact veto and removes its suggested search term; incompatible/no-eligible observations cannot confirm normally; explicit migration of an inactive profile atomically deletes its old-space samples, clears every other old-space suggested association/search term for that profile, updates model/dimension, inserts only the current compatible confirmed sample, and rebuilds the centroid without changing the profile identity or confirmed historical labels; automatic suggestion never creates `voice_samples`; confirmation rebuilds the centroid once; and a forced mid-transaction error rolls back identity, samples, suggestions, rejections, centroid, stamp, and search invalidation. In `voiceTeaching.test.ts`, verify confirm, migrate, and reject each emit one library-change event only after the repository promise resolves; a rejected repository promise emits none.

- [ ] **Step 2: Implement profile transactions and the teaching service**

Create profile with trimmed non-empty name, kind, normalized unique aliases, model/dimension, and first sample. Selecting an active profile verifies model compatibility and learning eligibility. Selecting an inactive profile requires explicit confirmation-driven migration: in one transaction delete all old-space `voice_samples`, clear all other `suggested` video-speaker associations/search terms for that profile to unknown, update the profile model/dimension, add only the current eligible sample, rebuild its centroid, and preserve the profile ID/name plus historical `confirmed` labels on videos. Never mix vector spaces. Rejection clears only that suggestion and records `(video_speaker_id, profile_id)`. `VoiceTeachingService` wraps these repository commits and emits library change only after resolution; the repository invalidates search only after its transaction commits.

- [ ] **Step 3: Build the identification sheet**

Choose the clearest sample deterministically as the longest `clean`, learning-eligible turn; tie-break by earliest start. Show that sample, all turns, total clean voiced duration, current suggestion, compatible active profiles, create-new form, Reject, and Leave unidentified. List inactive profiles separately; when the current observation is eligible in the new space, offer `Migrate this profile` with a warning that its old learning samples and old-space suggestions will be replaced while its name/confirmed history remain. Duplicate names show kind and confirmed sample count. Disable confirmation/migration when no eligible embedding exists and explain why.

- [ ] **Step 4: Refresh viewer and search after mutation**

The sheet calls `VoiceTeachingService`; on success it reloads only the open analysis and announces the label to accessibility services. Search invalidation and the library event already occur after the committed service mutation. Keep the sheet open and show the error if the mutation rejects.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
npm test -- voiceDb voiceTeaching voiceProfileCore --runInBand
npm run typecheck
```

Expected: PASS.

```bash
git add src/components/VoiceIdentifySheet.tsx src/components/VoicePanel.tsx \
  src/components/MemeGrid.tsx src/voiceDb.ts src/voiceDb.test.ts \
  src/voiceTeaching.ts src/voiceTeaching.test.ts
git commit -m "feat(voice): identify and confirm video speakers"
```

### Task 11: Add correction operations that protect training data

**Files:**
- Create: `src/components/VoiceFixSheet.tsx`
- Modify: `src/voiceDb.ts`
- Modify: `src/voiceAnalysis.ts`
- Modify: `src/voiceAnalysis.test.ts`
- Modify: `src/voiceDb.test.ts`
- Modify: `src/voice.tsx`
- Modify: `src/voiceTeaching.ts`
- Modify: `src/voiceTeaching.test.ts`
- Modify: `src/components/VoicePanel.tsx`
- Modify: `src/components/MemeGrid.tsx`

- [ ] **Step 1: Write failing correction invariants**

Cover pure split planning/cancellation/result mapping in `voiceAnalysis.test.ts`, including two eligible halves that await the injected native-state-settling macrotask after each successful or failed Whisper attempt and delete extracted media/PCM in every outcome; post-commit event ordering in `voiceTeaching.test.ts`; and real `sql.js` transactions in `voiceDb.test.ts`: merge unknown speakers; merge same confirmed profile; confirmed-plus-unknown requires Keep profile or Clear identity; different confirmed profiles require an explicit result; move turn; create speaker from turn; mark overlap/ambiguous; mark noisy; restore clean; recompute each corrected speaker from persisted per-turn `voiced_ms`; split at each duration boundary; ordinals recalculate; old samples are removed; at most one new confirmed sample is created; affected centroids rebuild; and a forced error rolls the whole correction back without invalidation/event.

- [ ] **Step 2: Implement non-inference corrections atomically**

Merge/move/create/quality changes use stored per-turn embeddings and persisted `voiced_ms`. `overlap` detaches the ambiguous turn with `videoSpeakerId = null`; `noisy` may stay assigned, but both force `learningEligible = false`. Restoring `clean` requires a valid assigned speaker and eligible embedding. Recompute aggregate embeddings, exact voiced durations, suggestions, samples, and ordinals in one transaction. Any merge involving exactly one confirmed profile requires an explicit Keep profile or Clear identity choice; different confirmed profiles require an explicit resulting profile or Clear both. Reject invalid moves that leave a learning sample with no eligible turns. `VoiceTeachingService.applyCorrection` emits only after the repository commit; rollback emits/invalidate neither.

- [ ] **Step 3: Implement split through the coordinator**

Expose `splitTurn` on `VoiceApi` with result union `'done' | 'busy' | 'cancelled' | 'not-ready' | 'missing' | 'invalid' | 'failed'`. Its provider wrapper maps every coordinator/worker outcome explicitly and delegates admitted work to `splitTurnWithLease(signal, request, deps)` without reentrant acquisition. The worker requires a timestamp strictly inside the selected turn, re-extracts waveform, and commits neither half on extraction/embedding/cancellation failure. Evaluate halves independently: below either embedding or transcription-duration gate remains visible with `quality = 'short'`, `learningEligible = false`, empty text, and `transcriptState = 'not_requested'`; eligible with unavailable Whisper keeps `transcriptState = 'not_requested'`; successful Whisper sets `transcriptState = 'done'`; per-half STT error sets `transcriptState = 'failed'` with empty text. After every attempted half transcription, await the injected `settleAfterTurnTranscription()` in `finally` before the other half. Delete all materialized media/PCM files in outer `finally` for success, failure, busy-after-admission, and cancellation. Never copy old attributed text or alter the whole-video transcript.

- [ ] **Step 4: Build a bounded correction editor**

`VoiceFixSheet` lists turns by time and supports Merge speakers, Move turn, New speaker from turn, Split here, Mark overlap/ambiguous, Mark noisy, Restore clean, and Change/clear identity. `Split here` uses the current position reported by the sole `VideoPreview`, is disabled unless strictly inside the selected turn, and dispatches every split result: only `done` closes/reloads; `busy`, `cancelled`, `not-ready`, `missing`, `invalid`, and `failed` keep the sheet open with the appropriate message. Confirmed-plus-unknown and conflicting-profile merges require the explicit choices from Step 2.

- [ ] **Step 5: Run tests, device-smoke, and commit**

Run:

```bash
npm test -- voiceAnalysis voiceDb voiceTeaching voiceDiarizationCore voiceProfileCore --runInBand
npm run typecheck
```

On device perform one false split merge and one false merge split from the reported playback position, including a split while audio work is busy. Mark one turn overlap and one noisy, then restore one clean. Verify result messages, ordinals, durations, neutral rendering, transcript states, learning eligibility, profile sample counts, the unchanged flat transcript, and no second player.

```bash
git add src/components/VoiceFixSheet.tsx src/components/VoicePanel.tsx \
  src/components/MemeGrid.tsx src/voiceDb.ts src/voiceDb.test.ts \
  src/voice.tsx src/voiceAnalysis.ts src/voiceAnalysis.test.ts \
  src/voiceTeaching.ts src/voiceTeaching.test.ts
git commit -m "feat(voice): correct detected speaker turns"
```

### Task 12: Add explicit tag scope without changing bulk tagging

**Files:**
- Modify: `src/components/MemeGrid.tsx:791-975,1119-1252,1338-1688`
- Reuse: `src/components/VoiceIdentifySheet.tsx`

- [ ] **Step 1: Extract the existing visual teach open action**

Keep the current positive/negative exemplar path unchanged behind a named `openVisualTeach` callback. Existing images and long-press tag correction still call it directly.

- [ ] **Step 2: Add the three-way scope sheet for viewer Tag**

For analyzed videos show Visual subject, Voice, and This video only. Voice opens speaker selection with ordinal/timestamp/duration/play, then `VoiceIdentifySheet`. This video only writes a manual tag without an exemplar. While analysis is loading, disable Tag rather than falling through to visual teaching. For images, preserve direct visual teaching; bulk tagging remains unchanged.

- [ ] **Step 3: Render confirmed voice identity distinctly**

Use a local/transient display source type (`Tag['source'] | 'voice'`) in the viewer adapter; do not extend the persisted `Tag.source` union. The normalized `profile_id` association remains authoritative, and voice vectors/profile ownership never enter the meme's tags JSON.

- [ ] **Step 4: Verify the exact third-speaker workflow**

Exercise the target movie clip: Tag → Voice → `#3` → create/select identity; only #3 gains a sample. Also verify Visual subject still creates only visual exemplars, This video only writes only a manual tag, an image opens visual teaching directly, and bulk tagging is unchanged.

- [ ] **Step 5: Typecheck and commit**

Run: `npm run typecheck`  
Expected: PASS.

```bash
git add src/components/MemeGrid.tsx src/components/VoiceIdentifySheet.tsx
git commit -m "feat(voice): teach identities through tag scope"
```

---

## Chunk 4: Search, profile management, and release verification

### Task 13: Integrate confirmed and suggested identities into search

**Files:**
- Modify: `src/searchCore.ts:35-68`
- Modify: `src/searchCore.test.ts`
- Modify: `src/searchIndexCache.ts:24-35`
- Modify: `src/searchIndexCache.test.ts`
- Modify: `src/db.ts:1394-1520`
- Modify: `src/voiceDb.ts`
- Modify: `src/voiceDb.test.ts`
- Modify: `src/evalCore.ts`

- [ ] **Step 1: Write failing scorer and SQL term-loading tests**

Add scorer entries with identical semantic inputs and confirmed voice text in `searchText`, suggested text only in `suggestedVoiceText`, or no voice match. Assert ranking order `confirmed > suggested > no voice`; confirmed exact identity receives normal lexical plus all-terms boost, suggestion receives `SUGGESTED_VOICE_WEIGHT = 0.10` and no all-terms boost, and rejection/unknown receives none. Add a separate case where stronger semantic similarity still outranks a weak suggestion. In `voiceDb.test.ts`, verify the joined term query returns normalized confirmed names/aliases, only current non-rejected suggestions, no unknown/rejected terms, and one aggregate row per meme.

- [ ] **Step 2: Extend the cache entry and scorer**

Add required `suggestedVoiceText: string`. Match query terms against it independently and add `0.10 * matched/terms.length`; never apply `ALL_TERMS_BOOST` from suggested text. Keep confirmed names/aliases in the existing `searchText`. Migrate every scorer-entry constructor: cache rows supply joined suggested text, search tests update their helper, and both `evalCore.ts` call sites supply `suggestedVoiceText: ''` because evaluation records have no voice data.

- [ ] **Step 3: Load voice terms in one query per cache rebuild**

`voiceDb.loadVoiceSearchTerms(db)` returns `Map<memeId, { confirmed: string; suggested: string }>` using normalized profile joins. `loadSearchIndex` fetches the map once, not once per meme, then builds each entry. Profile mutations invalidate the cache after commit.

- [ ] **Step 4: Run search tests and commit**

Run:

```bash
npm test -- voiceDb searchCore searchIndexCache searchText --runInBand
npm run typecheck
```

Expected: PASS.

```bash
git add src/searchCore.ts src/searchCore.test.ts src/searchIndexCache.ts \
  src/searchIndexCache.test.ts src/db.ts src/voiceDb.ts src/voiceDb.test.ts \
  src/evalCore.ts
git commit -m "feat(search): rank confirmed and likely voices"
```

### Task 14: Add known-voices management and complete deletion lifecycle

**Files:**
- Create: `src/components/KnownVoicesSheet.tsx`
- Modify: `src/screens/SettingsScreen.tsx`
- Modify: `src/voiceDb.ts`
- Modify: `src/db.ts:398-475,1711-1717`
- Modify: `src/voiceDb.test.ts`

- [ ] **Step 1: Add real repository lifecycle tests**

In `voiceDb.test.ts`, test rename updates joined search terms; aliases normalize/dedupe; profile deletion preserves anonymous speakers/turns but removes samples/suggestions/rejections; meme deletion and URI replacement rebuild affected centroids; clear index removes profiles/rejections/turns; model-mismatch profiles stay named but inactive; and forced transaction failure rolls back each destructive operation.

- [ ] **Step 2: Implement profile edit/delete transactions**

Rename/kind/aliases update `updated_at` and invalidate search. Delete profile nulls speaker associations to Unknown, deletes samples/rejections for that profile, and preserves turns. Remove meme voice data before deleting/replacing the meme and rebuild affected centroids.

- [ ] **Step 3: Build Known voices sheet**

Settings row shows profile count. Sheet lists name, kind, aliases, confirmed sample count, and active/inactive model stamp. Support edit and destructive delete confirmation. Do not add voice profiles to visual teaching-pack export.

- [ ] **Step 4: Verify edits and deletion on device**

Edit a profile's name, kind, and aliases; reopen Known voices to verify persistence, then search the new name and alias and verify the old term no longer matches. Delete one identified video and verify profile sample count/centroid updates. Delete the profile and verify the video timeline remains while identity becomes Unknown. Clear index and verify all profiles and observations disappear after confirmation.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
npm test -- voiceDb voiceProfileCore searchCore searchIndexCache --runInBand
npm run typecheck
```

Expected: PASS.

```bash
git add src/components/KnownVoicesSheet.tsx src/screens/SettingsScreen.tsx \
  src/voiceDb.ts src/voiceDb.test.ts src/db.ts
git commit -m "feat(voice): manage known voice profiles"
```

### Task 15: Run full device acceptance, then update documentation

**Files:**
- Modify only after all scenarios pass: `README.md`
- Modify only after all scenarios pass: `docs/audio-transcription.md`
- Modify if device evidence changes calibrated values: `src/voiceModels.ts`, `src/voiceModels.test.ts`
- Modify if calibration changes: `tools/model-export/speaker_gate.json`

- [ ] **Step 1: Run all focused automated verification**

Run:

```bash
npm test -- voiceModels audioWorkCore voiceDiarizationCore voiceProfileCore voiceAnalysis \
  voiceDb voiceTeaching searchCore searchIndexCache searchText audioCore --runInBand
npm run typecheck
```

Expected: all suites PASS; typecheck exits 0.

- [ ] **Step 2: Run the approved three-speaker acceptance scenario**

Analyze a movie clip with three sequential speakers. Verify first-heard `#1/#2/#3`, total durations, timeline seek, attributed turns where available, and naming only #3.

- [ ] **Step 3: Verify cross-video recognition safeguards**

Confirm #3 as a profile, analyze a second same-voice clip, and verify Likely appears. Confirm it and verify sample count increments. On a third suggestion, reject it and verify search removal, exact veto persistence, and unchanged centroid.

- [ ] **Step 4: Verify correction and failure scenarios**

Merge a falsely split speaker; split a falsely merged turn into one eligible and one sub-threshold half; trigger a reanalysis failure. Verify ordinals/samples rebuild, short half stays `not_requested`, prior successful result remains `done`, Refresh failed appears, and retry selects it.

- [ ] **Step 5: Verify concurrency, privacy, and resource cleanup**

Exercise transcription-versus-voice busy admission both directions. Stop a bulk run. Repeat in airplane mode after models are cached. Confirm no network request, no leaked PCM cache files, one keep-alive lease, and no native crash across 20 videos.

- [ ] **Step 6: Tune only from recorded evidence**

If representative clips show unacceptable false matches/misses, rerun the deterministic offline evaluator. For a threshold-only change, build a development client with `EXPO_PUBLIC_MEMEGET_VOICE_GATE=1` and the explicit pair config from Task 1, run the retained hidden gate action on Android, share `speaker_gate_device.json`, and merge its aggregate evidence into `speaker_gate.json`; remove/disable the action again by rebuilding without the flag. A changed model binary returns to Task 1's unique-probe promotion gate. Change thresholds only when offline and Android gates pass; bump the stamped config/model ID and make the exact config-equality test fail then pass. Reanalyze the library before repeating Steps 2–5. Never compare or mix incompatible samples; failed reanalysis retains old rows/samples inactive, and removes them only within a successful atomic replacement or explicit clear.

- [ ] **Step 7: Update user-facing documentation after behavior is proven**

Document: on-device FSMN VAD + CAM++ embeddings, first-heard ordinals, identification/corrections, confirmation-only learning, suggested versus confirmed search, model downloads, settings operations, limitations, and no-upload guarantee. Keep the existing Whisper transcription section accurate.

- [ ] **Step 8: Run final verification and commit docs/calibration**

Run:

```bash
npm test --runInBand
npm run typecheck
```

Expected: full Jest suite PASS; typecheck exits 0.

```bash
git add README.md docs/audio-transcription.md tools/model-export/speaker_gate.json \
  src/voiceModels.ts src/voiceModels.test.ts
git commit -m "docs: describe on-device voice identification"
```
