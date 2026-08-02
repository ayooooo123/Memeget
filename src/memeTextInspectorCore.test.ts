import { applyMemeTextPreset, createMemeTextLayer, normalizeMemeTextFontSize } from './memeTextLayoutCore';
import { composePendingTextLayer } from './memeTextInspectorCore';

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
