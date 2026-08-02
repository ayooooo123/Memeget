import type { TextLayer } from './memeEditProjectCore';

export interface PendingTextEdit {
  layerId: string;
  text: string;
}

export function composePendingTextLayer(current: TextLayer, pending: PendingTextEdit | null): TextLayer {
  if (!pending || pending.layerId !== current.id || current.text === pending.text) return current;
  return { ...current, text: pending.text };
}
