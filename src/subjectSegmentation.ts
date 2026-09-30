// Still-image subject cutouts without Google Play services.
//
// This used to be one native call into ML Kit Subject Segmentation, which only
// exists as an unbundled Play Services module — on a phone without Google
// services the model can never be installed. Now the segmentation runs here, on
// react-native-executorch's FastSAM (the runtime the app already ships), and the
// native module does the two pixel-heavy steps around it (see
// MemeStillSubjectSegmenter.kt):
//
//   prepare (native) → FastSAM forward (JS) → pick subjects (memeCutoutCore)
//   → write cutout PNGs (native)
//
// The exported surface matches what the studio used before, so the request
// lifecycle, failure codes and progress events are unchanged for it.
import { FASTSAM_S, InstanceSegmentationModule, ResourceFetcherUtils } from 'react-native-executorch';
import { ExpoResourceFetcher } from 'react-native-executorch-expo-resource-fetcher';

import {
  cancelNativeSubjectSegmentation,
  prepareSubjectSegmentation,
  releaseSubjectCutouts,
  subjectSegmentationAvailable,
  sweepSubjectCutouts,
  writeSubjectCutouts,
  type NativeSubjectCutoutResult,
} from '../modules/memeget-bg';
import { CUTOUT_FAILURE_CODES, packMaskBits, selectSubjectMasks } from './memeCutoutCore';
import { onDemandModel } from './onDemandModel';
import { base64Encode } from './zipWriter';

export { releaseSubjectCutouts, subjectSegmentationAvailable, sweepSubjectCutouts };

export interface SubjectSegmentationProgressEvent {
  requestId: string;
  phase: 'downloading' | 'segmenting';
  bytesDownloaded?: number;
  totalBytes?: number;
}

// FastSAM returns every region it finds; the cap bounds the masks held in JS
// at once (each is one byte per pixel of its box) while leaving plenty to
// choose MAX_CUTOUT_SUBJECTS from.
const MAX_MODEL_INSTANCES = 24;
// The downloader reports a fraction; the studio's progress state counts units.
const PROGRESS_UNITS = 1000;

const model = onDemandModel<InstanceSegmentationModule<'fastsam-s'>>({
  name: 'cutout',
  load: (onProgress) => InstanceSegmentationModule.fromModelName(FASTSAM_S, onProgress),
  idleMs: 30_000,
  // A cutout is a tap: after a failed download the user retries when they're
  // back online, and that retry must actually try.
  retryMs: 0,
});

class CutoutError extends Error {
  constructor(
    readonly code: string,
    message: string
  ) {
    super(message);
  }
}

const listeners = new Set<(payload: SubjectSegmentationProgressEvent) => void>();

export function addSubjectSegmentationProgressListener(
  listener: (payload: SubjectSegmentationProgressEvent) => void
): { remove(): void } {
  listeners.add(listener);
  return { remove: () => listeners.delete(listener) };
}

function emit(payload: SubjectSegmentationProgressEvent): void {
  for (const listener of listeners) listener(payload);
}

const cancelled = new Set<string>();

function throwIfCancelled(requestId: string): void {
  if (cancelled.has(requestId)) throw new CutoutError(CUTOUT_FAILURE_CODES.cancelled, 'Cutout cancelled');
}

// Ask an in-flight request to stop. The run rejects with E_CUTOUT_CANCELLED at
// its next checkpoint (a model forward can't be interrupted, so that may be
// when it returns).
export function cancelSubjectSegmentation(requestId: string): void {
  cancelled.add(requestId);
  cancelNativeSubjectSegmentation(requestId);
}

// Whether the FastSAM weights are already on the device. False means the first
// cutout downloads them, a wait the studio announces up front.
export async function subjectSegmentationModuleInstalled(): Promise<boolean> {
  const fileName = ResourceFetcherUtils.getFilenameFromUri(FASTSAM_S.modelSource);
  const files = await ExpoResourceFetcher.listDownloadedFiles().catch(() => [] as string[]);
  return files.some((file) => file.endsWith(fileName));
}

// Segment the subjects of a local still image and materialize one cutout PNG
// per subject plus a combined one, under a per-request cache directory the
// caller releases with `releaseSubjectCutouts`.
//
// Resolves null ONLY when the native half is absent. A real failure REJECTS
// with an E_CUTOUT_* `code` (see classifyCutoutFailure in memeCutoutCore) —
// and an image with no subject RESOLVES with `combined: null`, because
// "nothing to cut out" is an answer, not an error.
export async function segmentImageSubjects(
  source: string,
  requestId: string
): Promise<NativeSubjectCutoutResult | null> {
  if (!subjectSegmentationAvailable) return null;
  cancelled.delete(requestId);
  try {
    const prepared = await prepareSubjectSegmentation(source, requestId);
    if (!prepared) return null;
    throwIfCancelled(requestId);
    const instances = await model.run(
      (fastSam) => {
        emit({ requestId, phase: 'segmenting' });
        return fastSam.forward(prepared.workingUri, { maxInstances: MAX_MODEL_INSTANCES });
      },
      (fraction) =>
        emit({
          requestId,
          phase: 'downloading',
          bytesDownloaded: Math.round(fraction * PROGRESS_UNITS),
          totalBytes: PROGRESS_UNITS,
        })
    );
    if (instances === null) {
      throw new CutoutError(
        CUTOUT_FAILURE_CODES.offline,
        'The cutout model could not be downloaded. Check the connection and try again.'
      );
    }
    throwIfCancelled(requestId);
    const { subjects, dropped } = selectSubjectMasks(instances, {
      width: prepared.workingWidth,
      height: prepared.workingHeight,
    });
    return await writeSubjectCutouts(
      requestId,
      subjects.flatMap((s) => [s.x, s.y, s.width, s.height]),
      subjects.map((s) => base64Encode(packMaskBits(s.mask))),
      dropped
    );
  } catch (error) {
    await releaseSubjectCutouts(requestId).catch(() => false);
    if (error instanceof CutoutError) throw error;
    // Native rejections already carry an E_CUTOUT_* code; a model error does not.
    if (error && typeof error === 'object' && 'code' in error && String(error.code).startsWith('E_CUTOUT_')) {
      throw error;
    }
    throw new CutoutError(CUTOUT_FAILURE_CODES.failed, error instanceof Error ? error.message : String(error));
  } finally {
    cancelled.delete(requestId);
  }
}
