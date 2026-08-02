import {
  applyProjectAction,
  beginProjectTransaction,
  commitProjectTransaction,
  type ProjectHistory,
  type TextLayer,
} from './memeEditProjectCore';
export interface PendingTextEdit {
  layerId: string;
  text: string;
}

export interface LocallyEmittedText {
  text: string;
  revision: number;
}

export interface TextInputSyncInput {
  incomingText: string;
  incomingRevision: number;
  currentText: string;
  focused: boolean;
  localDraftActive: boolean;
  lastLocallyEmitted: LocallyEmittedText | null;
}

export type TextInputSyncDecision =
  | { kind: 'none'; reason: 'same-origin' | 'local-draft-active' | 'already-current' }
  | { kind: 'set-native'; text: string; preserveSelection: boolean };

export function textInputSyncDecision(input: TextInputSyncInput): TextInputSyncDecision {
  const sameOrigin = input.lastLocallyEmitted !== null &&
    input.lastLocallyEmitted.revision === input.incomingRevision &&
    input.lastLocallyEmitted.text === input.incomingText;
  if (sameOrigin) return { kind: 'none', reason: 'same-origin' };
  if (input.localDraftActive) return { kind: 'none', reason: 'local-draft-active' };
  if (input.incomingText === input.currentText) return { kind: 'none', reason: 'already-current' };
  return { kind: 'set-native', text: input.incomingText, preserveSelection: input.focused };
}

export function composePendingTextLayer(current: TextLayer, pending: PendingTextEdit | null): TextLayer {
  if (!pending || pending.layerId !== current.id || current.text === pending.text) return current;
  return { ...current, text: pending.text };
}

function findTextLayer(history: ProjectHistory, layerId: string): TextLayer | null {
  const layer = history.present.layers.find((candidate) => candidate.id === layerId);
  return layer?.kind === 'text' ? layer : null;
}

export function applyTextSessionContent(
  history: ProjectHistory,
  layerId: string,
  text: string,
  commit: boolean
): ProjectHistory {
  const current = findTextLayer(history, layerId);
  if (!current) return history;
  const opened = beginProjectTransaction(history);
  const updated = applyProjectAction(opened, { type: 'update-layer', layer: { ...current, text } });
  return commit ? commitProjectTransaction(updated) : updated;
}

export function applyTextSessionLayerUpdate(
  history: ProjectHistory,
  layerId: string,
  pendingText: string | null,
  updater: (current: TextLayer) => TextLayer
): ProjectHistory {
  const current = findTextLayer(history, layerId);
  if (!current) return history;
  const opened = beginProjectTransaction(history);
  const withPending = pendingText === null ? current : composePendingTextLayer(current, { layerId, text: pendingText });
  const updated = applyProjectAction(opened, { type: 'update-layer', layer: updater(withPending) });
  return commitProjectTransaction(updated);
}
