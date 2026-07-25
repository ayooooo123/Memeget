# Voice diarization and identification design

**Date:** 2026-07-24  
**Status:** Approved for implementation planning

## Summary

Memeget will detect distinct speakers in a video, order them by when each voice is first heard, show their turns and total speaking time, and let the user name a detected voice. A confirmed name creates a device-local voice profile that can suggest the same identity in later videos.

The first version uses one global speaker profile per user-defined identity. A profile may represent a real person, fictional character, or another user-defined identity. The system does not attempt to distinguish performer from character automatically.

All audio and voice embeddings remain on-device. The system separates three operations:

- **Diarization:** determine which speech turns sound like the same speaker within one video.
- **Identification:** let the user name a detected speaker.
- **Recognition:** compare a detected speaker with confirmed profiles from other videos.

Automatic suggestions never become training data without user confirmation.

## Existing system

Memeget already has the foundations needed for this feature:

- `modules/memeget-bg` extracts video audio as normalized 16 kHz mono float32 PCM.
- `src/audio.tsx` runs an opt-in, manual audio-analysis queue.
- `src/audioCore.ts` drives Moonshine transcription and returns a flat transcript.
- `src/db.ts` stores `transcript` and `audio_state` on each meme.
- `src/components/MemeGrid.tsx` shows the transcript in the video viewer and hosts visual teach-by-example flows.
- `src/searchText.ts` and `src/searchCore.ts` fold transcript and tags into hybrid search.

The existing Moonshine runner does not emit timestamps or speaker attribution. Structured turns therefore require a separate diarization stage and per-turn transcription for turns long enough to transcribe reliably. The existing whole-video transcript remains the stable searchable and copyable fallback.

## Goals

1. Detect multiple distinct voices in a video.
2. Number speakers by first speaking time: `#1`, `#2`, `#3`, and so on.
3. Show each speaker's first turn, number of turns, and total spoken duration.
4. Show a color-coded timeline and speaker-attributed transcript turns when available.
5. Let the user identify a particular speaker, including the third speaker in a multi-person clip.
6. Reuse confirmed voice identities to suggest matches in later videos.
7. Integrate voice identity into tagging and search without mixing voice and visual embedding spaces.
8. Let the user correct diarization and identity errors before those errors affect learning.
9. Preserve Memeget's no-upload, offline-first privacy model.

## Non-goals

- Face detection, lip synchronization, or automatic face-to-speaker alignment.
- Cloud diarization or a cloud fallback.
- Inferring legal, biological, or real-world identity from a voice.
- Automatically separating an actor from a fictional character profile.
- Training model weights on-device. Self-learning means adding confirmed embedding examples and updating profile centroids.
- Exporting voice profiles in existing visual teaching packs in the first version.
- Reliably identifying simultaneous speakers. Overlap is represented as uncertain and excluded from learning by default.

## Chosen approach

Use a modular on-device pipeline rather than a single end-to-end diarization model:

1. Reuse the existing native PCM extractor.
2. Detect speech regions with a small voice-activity model.
3. Divide valid speech into embedding windows.
4. Generate a normalized speaker embedding for each window.
5. Cluster embeddings within the video.
6. Merge adjacent windows assigned to the same speaker into turns.
7. Order speakers by the start time of their first valid turn.
8. Transcribe each sufficiently long turn with the existing Moonshine runner.
9. Aggregate each detected speaker's valid embeddings.
10. Compare the aggregate with confirmed global voice profiles.

This mirrors the standard segmentation, embedding, and clustering boundaries used by diarization toolkits such as [pyannote.audio](https://github.com/pyannote/pyannote-audio), while allowing Memeget to choose mobile-sized components independently. Candidate model families include [Silero VAD](https://github.com/snakers4/silero-vad) for voice activity and [WeSpeaker](https://github.com/wenet-e2e/wespeaker) speaker encoders. These are candidates, not preselected dependencies. The implementation plan must begin with an Android benchmark and license/export check, then choose either compatible ExecuTorch exports or a narrowly scoped mobile runtime integration.

A hosted diarization API was rejected because audio upload, network dependency, accounts, and retention policy would contradict Memeget's product contract. A full pyannote-style on-device pipeline was rejected for the first version because overlap-aware segmentation and model porting add substantial size and runtime risk beyond the core workflow.

## Domain model

### Speaker ordinal

A speaker's ordinal is local to one video. It is computed by sorting detected speakers by the start time of their earliest valid turn. Naming a speaker does not replace the ordinal: `#3 · Joe Rogan` remains the third distinct voice first heard in that video.

Ordinals are recalculated after a user merges speakers, moves a turn, or creates a new speaker from a turn.

### Profile identity

A voice profile has:

- A user-entered display name.
- A type: `person`, `character`, or `other`.
- Optional aliases used for search.
- A model stamp and embedding dimension.
- A centroid derived only from user-confirmed samples.

Profiles are deliberately user-defined. Two profiles may represent the same physical voice in different contexts if the user wants separate character identities.

### Match states

Every detected video speaker has one of three user-visible identity states:

- **Unknown:** no profile passed recognition policy.
- **Suggested:** a profile passed the automated match policy but was not confirmed for this observation.
- **Confirmed:** the user explicitly selected or created the profile for this detected speaker.

The UI uses language that preserves this distinction: `Unknown speaker`, `Likely Joe Rogan`, and `Joe Rogan`.

## Component boundaries

### `VoiceActivityRunner`

**Input:** normalized 16 kHz mono waveform.  
**Output:** time-ordered speech regions with start/end sample offsets and confidence.

It detects speech, not speaker identity. Post-processing joins tiny gaps and rejects regions too short or low-confidence for embedding.

### `SpeakerEmbeddingRunner`

**Input:** one valid speech window.  
**Output:** a normalized fixed-dimensional embedding plus model stamp.

It has no database or clustering responsibility. All comparisons require matching model stamp and dimension.

### `DiarizationCore`

**Input:** speech windows, their embeddings, timing, and quality metadata.  
**Output:** local speakers, ordered turns, overlap/quality flags, and aggregate speaker embeddings.

This is a React-free pure module. It owns clustering, adjacent-window merging, duration totals, and ordinal assignment. Clustering thresholds are part of the selected model's stamped configuration and are calibrated against the app's evaluation clips.

### `VoiceProfileMatcher`

**Input:** one aggregate video-speaker embedding, confirmed profile centroids, and rejection records.  
**Output:** no match or one suggested profile with calibrated confidence metadata.

A suggestion requires:

- Sufficient clean voiced duration.
- A best-profile score above the selected model's acceptance threshold.
- A minimum margin over the second-best profile.
- No rejection for that observation/profile pair.
- Compatible model stamp and dimension.

It returns unknown when evidence is weak or ambiguous. Raw cosine similarity is not presented as a probability. The UI may display a percentage only after a calibration maps scores to an empirically measured probability; otherwise it uses qualitative confidence language.

### `AudioAnalysisCoordinator`

The audio provider owns one shared coordinator for transcription and voice work. Both bulk and per-video operations enter through:

```ts
runExclusive<T>(
  kind: 'transcription' | 'voice-analysis',
  work: (signal: AudioWorkSignal) => Promise<T>
): Promise<T | 'busy'>
```

`AudioWorkSignal` exposes cooperative cancellation and the existing opportunity to yield to interactive search. `runExclusive` is the only owner of the busy state and keep-alive lease; it acquires both before model or decoder work and releases both in `finally`. A per-video request returns `busy` when another audio job owns the coordinator. A bulk request holds the same lease across its queue but checks cancellation and yields between videos. The existing transcription entry points migrate to this coordinator rather than keeping a private `busyRef`, so voice analysis cannot create a second independent lock.

### `VoiceAnalysisService`

**Input:** a video meme identifier.  
**Output:** a complete voice-analysis result persisted atomically.

It coordinates materialization, PCM extraction, VAD, speaker embeddings, diarization, optional per-turn transcription, profile matching, persistence, and search-index invalidation. It performs all decoder and model work inside `AudioAnalysisCoordinator.runExclusive`; it does not own an independent mutex or keep-alive lease.

### `VoiceTeachingService`

It creates and renames profiles, confirms or rejects suggestions, rebuilds centroids, and applies corrections. It never accepts an automatically suggested observation as a positive sample without an explicit user action.

## Data model

Voice data is normalized rather than stored inside the existing `tags` JSON. The schema uses integer millisecond boundaries for stable display and seeking.

### `memes` additions

- `voice_state TEXT NOT NULL DEFAULT 'none'`
  - `none`: image or voice analysis not enabled.
  - `pending`: video awaiting analysis.
  - `done`: analysis completed, including a valid no-speech result.
  - `failed`: analysis failed.
- `voice_model TEXT NOT NULL DEFAULT ''`
- `voice_last_error TEXT NOT NULL DEFAULT ''`
- `voice_last_attempted_at INTEGER NOT NULL DEFAULT 0`

Voice lifecycle remains separate from `audio_state`; transcription can succeed while voice analysis fails or is disabled. `voice_last_error` records the latest failed attempt independently of the last successful result, allowing a video to remain `done` with usable rows while still appearing in retry and maintenance UI.

### `voice_profiles`

- `id INTEGER PRIMARY KEY`
- `name TEXT NOT NULL`
- `kind TEXT NOT NULL` constrained to `person | character | other`
- `aliases TEXT NOT NULL DEFAULT '[]'`
- `model TEXT NOT NULL`
- `dimension INTEGER NOT NULL`
- `centroid BLOB`
- `confirmed_sample_count INTEGER NOT NULL DEFAULT 0`
- `created_at INTEGER NOT NULL`
- `updated_at INTEGER NOT NULL`

Profile names need not be globally unique. Selection UI disambiguates duplicate names by kind and sample count.

### `video_speakers`

- `id INTEGER PRIMARY KEY`
- `meme_id INTEGER NOT NULL`
- `ordinal INTEGER NOT NULL`
- `embedding BLOB NOT NULL`
- `model TEXT NOT NULL`
- `dimension INTEGER NOT NULL`
- `voiced_ms INTEGER NOT NULL`
- `profile_id INTEGER`
- `identity_state TEXT NOT NULL` constrained to `unknown | suggested | confirmed`
- `match_score REAL`
- `created_at INTEGER NOT NULL`
- Unique `(meme_id, ordinal)` after each atomic result or correction rewrite.

### `speech_turns`

- `id INTEGER PRIMARY KEY`
- `meme_id INTEGER NOT NULL`
- `video_speaker_id INTEGER`
- `start_ms INTEGER NOT NULL`
- `end_ms INTEGER NOT NULL`
- `transcript TEXT NOT NULL DEFAULT ''`
- `transcript_state TEXT NOT NULL DEFAULT 'not_requested'` constrained to `not_requested | done | failed`
- `embedding BLOB`
- `quality TEXT NOT NULL` constrained to `clean | short | overlap | noisy`
- `learning_eligible INTEGER NOT NULL DEFAULT 1`

A nullable speaker allows an overlap/ambiguous turn to remain on the timeline without contaminating a cluster. Per-turn embeddings allow merge, move, reassignment, and aggregate rebuilding without decoding the video again. Splitting inside an existing turn is the exception: it requires waveform access and fresh embeddings for the two new time ranges.

### `voice_samples`

- `id INTEGER PRIMARY KEY`
- `profile_id INTEGER NOT NULL`
- `video_speaker_id INTEGER NOT NULL`
- `embedding BLOB NOT NULL`
- `model TEXT NOT NULL`
- `dimension INTEGER NOT NULL`
- `voiced_ms INTEGER NOT NULL`
- `created_at INTEGER NOT NULL`
- Unique `(profile_id, video_speaker_id)`.

Each confirmed video speaker contributes one normalized sample, so a long podcast does not dominate many short clips. The centroid is the normalized mean of compatible confirmed samples. Duration may gate sample eligibility but does not scale influence without a separately evaluated quality weighting.

### `voice_rejections`

- `video_speaker_id INTEGER NOT NULL`
- `profile_id INTEGER NOT NULL`
- `created_at INTEGER NOT NULL`
- Primary key `(video_speaker_id, profile_id)`.

A rejection vetoes that exact observation/profile suggestion. It is not a global negative sample against every future recording of the voice.

## Analysis data flow

1. A video with no prior successful result enters persisted `voice_state = pending` when queued. Reanalysis of a `done` video keeps the durable state and rows at `done`; running/refreshing is in-memory coordinator state until the replacement commits. Both paths update `voice_last_attempted_at`.
2. `VoiceAnalysisService` materializes the video and extracts the waveform through the existing native decoder.
3. VAD returns speech regions. No valid regions is a successful `done` result with no speakers.
4. Valid regions are windowed for the speaker encoder. Windows that are too short, overlap, or fail quality checks remain representable but are not learning eligible.
5. `DiarizationCore` clusters clean embeddings, merges adjacent same-speaker windows, computes aggregate embeddings, and assigns ordinals.
6. If Moonshine is enabled and ready, it transcribes each sufficiently long merged turn independently. A short turn or unavailable transcription model keeps `transcript = ''` and `transcript_state = not_requested`. A failed turn call keeps the diarization result, stores `transcript_state = failed` for that turn, and continues with later turns. The existing whole-video transcription continues to populate `memes.transcript`; its success or failure does not determine `voice_state`. A later transcription pass may fill `speech_turns` whose state is `not_requested` or `failed` without rerunning diarization.
7. `VoiceProfileMatcher` evaluates each aggregate against compatible confirmed profiles.
8. The service prepares a complete replacement, then commits all durable changes in one transaction: new `video_speakers` and `speech_turns`, dependent samples and rejections, rebuilt centroids, `memes.voice_model`, `memes.voice_state = done`, `voice_last_error = ''`, and any denormalized search fields introduced by implementation.
   - Before deleting old speakers, remove their `voice_samples` and `voice_rejections` and record every affected profile.
   - Rebuild affected profile centroids from their remaining confirmed samples.
   - Do not transfer an old confirmed identity or rejection to a newly clustered speaker automatically. The user's old confirmation named the old observation; carrying it onto changed audio would create training data the user did not confirm.
   - Match the new speakers only against profiles that still have compatible confirmed samples. Profiles with no remaining samples keep their names and aliases but cannot produce suggestions until the user confirms a new sample.
   - The reanalysis confirmation warns that identities taught from this video may need reconfirmation.
9. After the transaction commits, the service invalidates the derived in-memory search cache and emits the existing library-change event. Confirmed and suggested voice search text is rebuilt from the committed normalized associations; cache invalidation and event emission are not part of the durable transaction.
10. Temporary PCM files are removed through the existing cleanup path.

Extraction, VAD, speaker-embedding, clustering, or persistence failure sets `voice_state = failed` and `voice_last_error` for a first analysis. A failed reanalysis leaves `voice_state = done` and every prior successful speaker, turn, sample, rejection, and centroid unchanged, but stores `voice_last_error` in a separate transaction so the viewer can show `Refresh failed` and the retry action can include it. `Retry failed voice analyses` selects both `voice_state = failed` rows and `done` rows with a non-empty `voice_last_error`. Moonshine being disabled, unavailable, or failing for one or more turns does not fail voice analysis; the per-turn `transcript_state` records that optional enrichment outcome.

## Viewer UX

The current flat `Speech in video` section becomes `Voices in this video` when structured results exist.

### Speaker summary

Each row shows:

- Local ordinal and identity state.
- First speaking timestamp.
- Number of turns.
- Total spoken duration.
- A play/seek action for the clearest sample.

Example:

```text
#1  Unknown speaker
    First spoke 0:01 · 3 turns · 7.4s total

#2  Unknown speaker
    First spoke 0:05 · 1 turn · 2.1s total

#3  Joe Rogan
    Third voice heard · 2 turns · 4.8s total
```

A color-coded timeline uses the same speaker colors as the rows. Selecting a segment seeks the video to that turn. Ambiguous overlap uses a neutral pattern rather than either speaker's color.

### Structured transcript

Speaker-attributed turns appear chronologically:

```text
0:01  #1              “We need to leave.”
0:05  #2              “Why?”
0:08  #1              “Because they know.”
0:11  #3 · Joe Rogan “It’s already too late.”
```

Turns too short for reliable transcription still show timing and speaker. The existing flat transcript remains copyable and searchable.

### Identification sheet

Tapping an unknown or suggested speaker opens a sheet that:

1. Plays the clearest eligible sample.
2. Shows all of that speaker's turns and total clean duration.
3. Offers existing compatible profiles, with a suggested profile first when present.
4. Allows creation of a profile with name, kind, and optional aliases.
5. Allows confirmation, rejection, identity replacement, or leaving the speaker unknown.

Confirming creates or updates a `voice_sample`, rebuilds the profile centroid, updates the video's identity state, and invalidates search.

### Fix speakers

A `Fix speakers` action supports the minimum corrections needed to protect training quality:

- Merge two detected speakers.
- Move a turn to another speaker.
- Create a new speaker from a turn.
- Split one turn at a user-selected playback timestamp, then assign either half to an existing or new speaker.
- Exclude a turn from learning.
- Change or remove an identity association.

A correction transaction rebuilds affected aggregate embeddings, durations, ordinals, profile samples, and centroids. It clears suggestions that depended on the changed aggregates and reruns matching for affected unconfirmed speakers.

Splitting a turn runs through `AudioAnalysisCoordinator.runExclusive`, re-materializes the video, extracts the waveform, and generates fresh embeddings for both time ranges. Neither half is committed if extraction or embedding fails. A half that is too short remains visible but is marked ineligible for learning. The old attributed transcript is never copied or divided because Moonshine supplies no word timestamps. If Moonshine is ready, each half is transcribed independently before commit; otherwise each receives `transcript = ''` and `transcript_state = not_requested`. An individual half's transcription failure stores an empty transcript with `transcript_state = failed` without aborting the split. The existing whole-video `memes.transcript` is unchanged.

Merging speakers is an explicit identity decision. If neither speaker is confirmed, suggestions are cleared and the merged aggregate is matched again. If one speaker is confirmed, the merge sheet asks the user to keep that profile or clear identity. If both are confirmed to the same profile, the merged observation remains confirmed. If they are confirmed to different profiles, the merge is blocked until the user chooses one profile or clears both. The transaction removes both old samples, creates at most one eligible sample for the explicitly chosen resulting profile, and rebuilds every affected centroid; it never silently chooses an identity.

## Tagging UX

The existing viewer `Tag` action gains a scope choice:

```text
What does this tag identify?

[ Visual subject ]  [ Voice ]  [ This video only ]
```

- **Visual subject** keeps the existing image-exemplar teaching behavior.
- **Voice** creates or updates a voice profile.
- **This video only** creates a manual meme tag without learning.

When `Voice` is selected for a multi-speaker video, the user must choose one detected speaker. Each choice shows ordinal, first timestamp, duration, and a play action. The saved event is explicit: `In this video, Speaker #3 is Joe Rogan.` The entire mixed video waveform is never stored as a voice sample.

Bulk tagging keeps its current manual/visual behavior. Bulk voice teaching is excluded because selected videos can contain different numbers and orders of speakers.

## Search integration

Confirmed profile names and aliases are strong lexical identity terms. Suggested profile names may be searchable but must not receive the same decisive all-terms boost as confirmed identity.

To preserve that distinction, the search-index entry gains separate fields:

- `searchText`: existing OCR, filename, caption, transcript, tags, extra terms, and confirmed voice terms.
- `suggestedVoiceText`: names and aliases from suggested profile matches only.

`scoreEntry` evaluates suggested voice text with a smaller additive lexical weight and no all-terms boost. Exact weights are selected alongside the model threshold evaluation and covered by ranking tests. Unknown and rejected identities add no voice terms.

Renaming or deleting a profile invalidates every associated search entry. Deleting a profile removes its samples and associations but preserves anonymous turns and transcripts. Confirmed profile labels may be rendered alongside meme tags with a distinct `voice` source, but the normalized profile association remains the source of truth.

## Privacy and lifecycle

Voice embeddings are biometric-like identifiers and receive the same local-only guarantee as media analysis:

- No waveform, turn, embedding, identity, or profile is uploaded.
- Model downloads follow the existing opt-in model-download behavior.
- Voice profiles are not included in visual teaching-pack export.
- Clearing the index also clears video-speaker observations, turns, samples, rejections, and profiles after explicit confirmation.
- Deleting a meme removes its observations and rebuilds any affected profile centroid.
- Removing the voice model may preserve named profiles but makes their old embeddings inactive until compatible reanalysis; incompatible embedding spaces are never compared.

## Error and edge-case behavior

- **No speech:** successful analysis with no speaker rows; viewer may state `No speech detected` in maintenance details.
- **One speaker:** show `#1`; do not add multi-speaker ceremony.
- **Short utterance:** show timing, mark it short, and exclude it from recognition/training when below model eligibility.
- **Overlap:** show an ambiguous turn and exclude it from learning by default.
- **Music, singing, effects, impressions, or phone audio:** lower quality or leave unknown rather than force a profile.
- **Ambiguous profile match:** remain unknown when acceptance or margin checks fail.
- **Model mismatch:** do not compare; queue reanalysis when the compatible model is available.
- **Failed first analysis:** mark failed and expose retry through the existing audio-analysis controls.
- **Failed reanalysis:** retain the prior successful structured result and report that refresh failed.
- **Profile deletion:** preserve anonymous diarization and transcript data.
- **Meme deletion:** remove dependent samples and rebuild affected profile centroids.

## Settings and operations

Voice analysis is opt-in under Audio analysis. Controls mirror the existing transcription lifecycle:

- Analyze pending videos.
- Retry failed voice analyses.
- Reanalyze this video.
- Regenerate all voice analyses after a model change.
- View and manage known voices: rename, edit aliases/type, inspect confirmed sample count, or delete.

Voice and transcription may share one combined per-video pass after the pipeline is stable, but their persisted states and retry semantics remain independent.

## Verification

### Pure-core tests

1. Speech windows from three synthetic embedding clusters produce three speakers.
2. Ordinals follow earliest valid turn, not cluster identifier or total duration.
3. Total spoken duration equals the sum of a speaker's turns.
4. Adjacent same-speaker windows merge without changing total duration.
5. Ambiguous and overlap windows are excluded from aggregate embeddings.
6. Profile matching enforces acceptance threshold, runner-up margin, rejection veto, model stamp, and dimension.
7. Profile centroids use only confirmed compatible samples.
8. Suggestions do not change centroids.
9. Corrections recalculate speakers, durations, ordinals, samples, and centroids.

### Database and search tests

1. A complete analysis result, dependent records, model stamp, successful state, cleared error, and any persisted search fields commit atomically.
2. Failed reanalysis preserves prior successful rows, remains `done`, records `voice_last_error`, and is included by retry queries.
3. Naming `#3` associates only that `video_speaker`, never the mixed video waveform.
4. Rejection removes a suggestion and its search term.
5. Confirmed identity terms receive normal lexical matching.
6. Suggested identity terms receive only the reduced suggestion weight.
7. Renaming and deletion invalidate affected search entries.
8. Deleting a meme removes dependent samples and rebuilds profile centroids.
9. Incompatible model spaces never compare.
10. The existing concatenated transcript remains searchable.
11. Splitting a turn never copies the old attributed text; each half records `done`, `not_requested`, or `failed` from its own transcription outcome.

### End-to-end device scenarios

1. Analyze a movie clip with three sequential speakers; verify timeline order, duration totals, seeking, and `#3` identification.
2. Confirm `#3` as a new profile; analyze a second clip containing the same voice and verify a suggestion appears.
3. Reject the suggestion; verify it disappears, is not searchable as confirmed, and cannot train the profile.
4. Merge a falsely split speaker and split a falsely merged turn; verify ordinals and samples rebuild and split transcript states reflect independent outcomes.
5. Trigger a reanalysis failure; verify the prior successful timeline remains visible, state remains `done`, `Refresh failed` appears, and retry selects the video.
6. Perform the workflow in airplane mode and verify no network request occurs after models are cached.

## Rollout sequence

1. Benchmark and select VAD/speaker-embedding models on the target Android device; establish model stamp, input contract, size, latency, and thresholds.
2. Add pure diarization, matching, and centroid modules with synthetic contract tests.
3. Add normalized storage, migrations, atomic replacement, and deletion lifecycle.
4. Integrate the on-device analysis service with the existing PCM and Moonshine paths, replacing the private transcription mutex with the shared `AudioAnalysisCoordinator`.
5. Add viewer summaries, timeline, attributed turns, and seek/play behavior.
6. Add identify, confirm, reject, and correction workflows.
7. Add tag scope and known-voices management.
8. Add confirmed and suggested search integration.
9. Run the device scenarios and tune thresholds against representative meme, movie, and podcast clips.

## Decision summary

- Unified user-defined voice profiles: approved.
- Modular on-device diarization: approved.
- First-heard speaker ordinals: approved.
- Timeline, durations, and attributed turns: approved.
- Voice-aware tag scope: approved.
- Suggestions require confirmation before learning: approved.
- Correction tools protect training quality: approved.
- Face-to-speaker alignment, cloud processing, and voice-pack export: excluded from the first version.
