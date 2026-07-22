import React, { createContext, useContext, useMemo, useState } from 'react';
import { useImageEmbeddings, useTextEmbeddings } from 'react-native-executorch';

import { normalize } from './db';
import {
  PRIMARY_EMBEDDING_MODEL,
  VISUAL_EMBEDDING_MODEL,
  type EmbeddingModelSpec,
} from './embeddingModels';

// The primary image + text encoders (MobileCLIP-S2) and the optional visual
// encoder (DINOv2) are all react-native-executorch custom models resolved from
// embeddingModels.ts. Image and text MUST come from the same primary export so
// their vectors share one space; switching that export moves the space, so a
// one-time re-index (Settings → Clear index, then Index) is needed to keep old
// and new vectors consistent.
// react-native-executorch's public model type only enumerates its built-in
// model names, but the native runtime also accepts a { modelName: 'custom',
// modelSource, tokenizerSource? } descriptor to load an arbitrary .pte export
// (how MobileCLIP-S2 and DINOv2 load). That shape is unexpressible in the
// shipped types, so cast through unknown at this single boundary.
type ImageModelProp = Parameters<typeof useImageEmbeddings>[0]['model'];
type TextModelProp = Parameters<typeof useTextEmbeddings>[0]['model'];

function customImageModel(spec: EmbeddingModelSpec): ImageModelProp {
  return { modelName: 'custom', modelSource: spec.imageModelSource } as unknown as ImageModelProp;
}

function customTextModel(spec: EmbeddingModelSpec): TextModelProp {
  return {
    modelName: 'custom',
    modelSource: spec.textModelSource,
    tokenizerSource: spec.tokenizerSource,
  } as unknown as TextModelProp;
}

const PRIMARY_IMAGE_MODEL = customImageModel(PRIMARY_EMBEDDING_MODEL);
const PRIMARY_TEXT_MODEL = customTextModel(PRIMARY_EMBEDDING_MODEL);
// DINOv2 is optional. When it isn't configured its hook is never loaded
// (preventLoad below), but useImageEmbeddings still needs a descriptor — reuse
// the primary image model as an inert placeholder instead of a second export.
const VISUAL_IMAGE_MODEL = VISUAL_EMBEDDING_MODEL.imageModelSource
  ? customImageModel(VISUAL_EMBEDDING_MODEL)
  : PRIMARY_IMAGE_MODEL;

export interface EmbeddingsApi {
  ready: boolean;
  progress: number; // 0..1 model download/load progress
  error: string | null;
  primaryModel: EmbeddingModelSpec;
  primaryLabel: string;
  visualModel: EmbeddingModelSpec;
  visualReady: boolean;
  visualProgress: number; // 0..1 model download/load progress
  visualError: string | null;
  // Whether the visual tower is currently summoned. False = deliberately
  // unloaded (drained queue, deferring to a heavy pass/poster backfill) — the
  // Settings row shows "On demand" then, not a bogus perpetual "Loading 0%".
  visualWanted: boolean;
  // Demand control for the visual (DINO) tower: it exists only for the
  // idle-time backfill, so it loads when that loop has work and unloads when
  // drained — no cold start or resident RAM on ordinary app opens.
  setVisualWanted: (on: boolean) => void;
  embedImage: (uri: string) => Promise<number[]>; // normalized
  embedText: (text: string) => Promise<number[]>; // normalized
  embedVisualImage?: (uri: string) => Promise<{ model: string; embedding: number[] } | null>; // normalized
}

const Ctx = createContext<EmbeddingsApi | null>(null);

export function EmbeddingsProvider({ children }: { children: React.ReactNode }) {
  const image = useImageEmbeddings({ model: PRIMARY_IMAGE_MODEL });
  const text = useTextEmbeddings({ model: PRIMARY_TEXT_MODEL });
  const [visualWanted, setVisualWanted] = useState(false);
  const visual = useImageEmbeddings({
    model: VISUAL_IMAGE_MODEL,
    preventLoad: !VISUAL_EMBEDDING_MODEL.available || !visualWanted,
  } as any);

  const api = useMemo<EmbeddingsApi>(() => {
    const imageReady = (image as any).isReady ?? false;
    const textReady = (text as any).isReady ?? false;
    const visualReady = VISUAL_EMBEDDING_MODEL.available && ((visual as any).isReady ?? false);
    const dp = (image as any).downloadProgress;
    const tp = (text as any).downloadProgress;
    const vp = (visual as any).downloadProgress;
    const progress =
      typeof dp === 'number' && typeof tp === 'number' ? (dp + tp) / 2 : dp ?? tp ?? 0;
    const err = (image as any).error ?? (text as any).error ?? null;
    const visualErr = VISUAL_EMBEDDING_MODEL.available ? ((visual as any).error ?? null) : null;

    return {
      ready: imageReady && textReady,
      progress,
      error: err ? String(err) : null,
      primaryModel: PRIMARY_EMBEDDING_MODEL,
      primaryLabel: PRIMARY_EMBEDDING_MODEL.label,
      visualModel: VISUAL_EMBEDDING_MODEL,
      visualReady,
      visualProgress: typeof vp === 'number' ? vp : 0,
      visualError: visualErr ? String(visualErr) : null,
      visualWanted,
      setVisualWanted,
      embedImage: async (uri: string) => normalize(Array.from(await image.forward(uri))),
      embedText: async (t: string) => normalize(Array.from(await text.forward(t))),
      embedVisualImage: VISUAL_EMBEDDING_MODEL.available
        ? async (uri: string) => {
            if (!((visual as any).isReady ?? false)) return null;
            return {
              model: VISUAL_EMBEDDING_MODEL.id,
              embedding: normalize(Array.from(await visual.forward(uri))),
            };
          }
        : undefined,
    };
  }, [image, text, visual, visualWanted]);

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useEmbeddings(): EmbeddingsApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useEmbeddings must be used inside <EmbeddingsProvider>');
  return ctx;
}

// The learner and zero-shot classifier live in learnCore.ts (React-free, so
// they run headlessly and are unit-testable); re-exported here so existing
// imports keep working.
export {
  EXEMPLAR_PROB_THRESHOLD,
  classifyExemplars,
  classifyImage,
  classifyPrompts,
  createYielder,
  fitLogistic,
  headProb,
  mergeClassified,
  scoreExemplar,
  trainLabelModel,
} from './learnCore';
export type { ExemplarScore, LabelHead, LabelVec, TrainLabelInputs } from './learnCore';
