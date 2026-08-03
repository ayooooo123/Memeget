import { applyMemeTextPreset, createMemeTextLayer, normalizeMemeTextFontSize } from './memeTextLayoutCore';
import { createDefaultImageProject, createProjectHistory, undoProjectHistory } from './memeEditProjectCore';
import {
  applyTextSessionContent,
  applyTextSessionLayerUpdate,
  composePendingTextLayer,
  textInputSyncDecision,
} from './memeTextInspectorCore';

describe('meme text inspector pending content composition', () => {
  test('style updates compose pending typed text before applying the updater', () => {
    const selected = createMemeTextLayer('caption', 'plain', { text: 'old caption' });
    const pending = { layerId: 'caption', text: 'typed but not debounced yet' };

    const updated = applyMemeTextPreset(composePendingTextLayer(selected, pending), 'impact');

    expect(updated.text).toBe('typed but not debounced yet');
    expect(updated.style.preset).toBe('impact');
  });

  test('slider updates keep pending typed text in the same layer update', () => {
    const selected = createMemeTextLayer('caption', 'subtitle', { text: 'old caption' });
    const pending = { layerId: 'caption', text: 'new words' };

    const updated = {
      ...composePendingTextLayer(selected, pending),
      fontSize: normalizeMemeTextFontSize(0.12),
    };

    expect(updated.text).toBe('new words');
    expect(updated.fontSize).toBe(0.12);
  });
});

describe('uncontrolled text input synchronization', () => {
  test('does not echo a same-origin focused debounce back into the native input', () => {
    expect(textInputSyncDecision({
      incomingText: 'café',
      incomingRevision: 7,
      currentText: 'café',
      focused: true,
      localDraftActive: false,
      lastLocallyEmitted: { text: 'café', revision: 7 },
    })).toEqual({ kind: 'none', reason: 'same-origin' });
  });

  test('normal keypress then true external revision wins while the input remains focused', () => {
    const external = {
      incomingText: 'before local edit',
      incomingRevision: 9,
      currentText: 'local edit',
      focused: true,
      lastLocallyEmitted: { text: 'local edit', revision: 8 },
    };

    expect(textInputSyncDecision({ ...external, localDraftActive: true })).toEqual({
      kind: 'none',
      reason: 'local-draft-active',
    });
    expect(textInputSyncDecision({ ...external, localDraftActive: false })).toEqual({
      kind: 'set-native',
      text: 'before local edit',
      preserveSelection: true,
    });
    expect(textInputSyncDecision({
      ...external,
      currentText: 'before local edit!',
      localDraftActive: true,
    })).toEqual({ kind: 'none', reason: 'local-draft-active' });
  });

  test('does not touch native selection when a history revision leaves text unchanged', () => {
    expect(textInputSyncDecision({
      incomingText: 'same text',
      incomingRevision: 10,
      currentText: 'same text',
      focused: true,
      localDraftActive: false,
      lastLocallyEmitted: null,
    })).toEqual({ kind: 'none', reason: 'already-current' });
  });
});

describe('meme text inspector transaction session', () => {
  test('multiple debounce pauses stay inside one open focus transaction until blur commit', () => {
    const project = createDefaultImageProject({ uri: 'file:///source.jpg', name: 'source.jpg', width: 100, height: 100 });
    const layer = createMemeTextLayer('caption', 'plain', { text: 'before focus' });
    project.layers = [layer];
    let history = createProjectHistory(project);

    history = applyTextSessionContent(history, 'caption', 'first pause', false);
    history = applyTextSessionContent(history, 'caption', 'second pause', false);
    history = applyTextSessionContent(history, 'caption', 'final blur', true);

    expect(history.transaction).toBeNull();
    expect(history.past).toHaveLength(1);
    expect(history.present.layers[0]).toMatchObject({ text: 'final blur' });
    expect(undoProjectHistory(history).present.layers[0]).toMatchObject({ text: 'before focus' });
  });

  test('typing then preset update commits the focus transaction with the style action and leaves no transaction open', () => {
    const project = createDefaultImageProject({ uri: 'file:///source.jpg', name: 'source.jpg', width: 100, height: 100 });
    const layer = createMemeTextLayer('caption', 'plain', { text: 'before focus' });
    project.layers = [layer];
    let history = createProjectHistory(project);

    history = applyTextSessionContent(history, 'caption', 'typed pending', false);
    history = applyTextSessionLayerUpdate(history, 'caption', 'typed pending', (current) => applyMemeTextPreset(current, 'impact'));

    expect(history.transaction).toBeNull();
    expect(history.past).toHaveLength(1);
    expect(history.present.layers[0]).toMatchObject({ text: 'typed pending', style: expect.objectContaining({ preset: 'impact' }) });
    expect(undoProjectHistory(history).present.layers[0]).toMatchObject({ text: 'before focus', style: expect.objectContaining({ preset: 'plain' }) });
  });
});
