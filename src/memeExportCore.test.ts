import {
  EXPORT_STAGES,
  type ExportState,
  type MemeExportResult,
  destinationLabel,
  exportDestinations,
  exportOutputSpec,
  exportSummary,
  initialExportState,
  isExportBusy,
  memeExportReducer,
  mergeExportProgress,
  reusableResult,
  skippedLayerWarning,
  stageAdvances,
  uniqueExportName,
} from './memeExportCore';

const result = (path = '/cache/out.png', warnings: string[] = []): MemeExportResult => ({
  path,
  name: 'out.png',
  mimeType: 'image/png',
  warnings,
});

/** Drive the machine through a list of events. */
function run(state: ExportState, ...events: Parameters<typeof memeExportReducer>[1][]): ExportState {
  return events.reduce(memeExportReducer, state);
}

function started(destination: 'library' | 'clipboard' | 'downloads' = 'library', revision = 'plan-a') {
  const state = run(initialExportState(), { type: 'start', destination, revision });
  if (state.phase.kind !== 'running') throw new Error('expected running');
  return { state, runId: state.phase.runId };
}

describe('progress reporting', () => {
  it('never moves backwards inside a stage', () => {
    const at = (progress: number) => ({ stage: 'encoding' as const, progress, detail: 'Encoding' });
    const merged = mergeExportProgress(at(0.6), at(0.4));
    expect(merged.progress).toBe(0.6);
  });

  it('resets the fraction when the stage genuinely changes', () => {
    const merged = mergeExportProgress(
      { stage: 'segmenting', progress: 0.9, detail: 'Isolating' },
      { stage: 'encoding', progress: 0.1, detail: 'Encoding' }
    );
    expect(merged).toEqual({ stage: 'encoding', progress: 0.1, detail: 'Encoding' });
  });

  it('ignores a report from an earlier stage', () => {
    // Media3 and the segmenter report independently; a straggler must not drag
    // the UI back to a stage that already finished.
    const current = { stage: 'saving' as const, progress: 0.2, detail: 'Saving' };
    expect(mergeExportProgress(current, { stage: 'encoding', progress: 0.99, detail: 'Encoding' })).toBe(current);
  });

  it('clamps nonsense fractions rather than rendering them', () => {
    const from = { stage: 'encoding' as const, progress: null, detail: 'Encoding' };
    expect(mergeExportProgress(from, { stage: 'encoding', progress: 1.7, detail: '' }).progress).toBe(1);
    expect(mergeExportProgress(from, { stage: 'encoding', progress: -3, detail: '' }).progress).toBe(0);
    expect(mergeExportProgress(from, { stage: 'encoding', progress: NaN, detail: '' }).progress).toBeNull();
  });

  it('keeps the previous detail when a report carries none', () => {
    const merged = mergeExportProgress(
      { stage: 'encoding', progress: 0.2, detail: 'Encoding video' },
      { stage: 'encoding', progress: 0.3, detail: '' }
    );
    expect(merged.detail).toBe('Encoding video');
  });

  it('orders every stage it declares', () => {
    for (let i = 1; i < EXPORT_STAGES.length; i += 1) {
      expect(stageAdvances(EXPORT_STAGES[i - 1], EXPORT_STAGES[i])).toBe(true);
      expect(stageAdvances(EXPORT_STAGES[i], EXPORT_STAGES[i - 1])).toBe(false);
    }
  });
});

describe('a single run resolves exactly once', () => {
  it('ignores progress from a superseded run', () => {
    const { state, runId } = started();
    const next = run(state, { type: 'progress', runId: runId + 99, progress: { stage: 'encoding', progress: 0.5, detail: 'x' } });
    expect(next).toBe(state);
  });

  it('does not lose the file when a superseded run succeeds late', () => {
    // The classic leak: a run the user abandoned still finishes and writes bytes.
    const { state, runId } = started();
    const next = run(state, { type: 'succeeded', runId: runId + 99, result: result('/cache/stale.png') });
    expect(next.phase.kind).toBe('running');
    expect(next.orphans).toContain('/cache/stale.png');
  });

  it('refuses a second start while one is running', () => {
    const { state } = started();
    expect(run(state, { type: 'start', destination: 'downloads', revision: 'plan-a' })).toBe(state);
  });

  it('cannot fail after it has succeeded', () => {
    const { state, runId } = started();
    const done = run(state, { type: 'succeeded', runId, result: result() });
    expect(run(done, { type: 'failed', runId, message: 'too late' })).toBe(done);
  });
});

describe('cancellation', () => {
  it('freezes the progress bar once cancel is requested', () => {
    const { state, runId } = started();
    const cancelling = run(state, { type: 'cancel' });
    const after = run(cancelling, {
      type: 'progress',
      runId,
      progress: { stage: 'encoding', progress: 0.8, detail: 'Encoding' },
    });
    expect(after).toBe(cancelling);
  });

  it('discards and sweeps a result that arrives after cancel', () => {
    // Losing this race silently creates a meme the user explicitly cancelled.
    const { state, runId } = started();
    const after = run(state, { type: 'cancel' }, { type: 'succeeded', runId, result: result('/cache/raced.png') });
    expect(after.phase.kind).toBe('cancelled');
    expect(after.orphans).toContain('/cache/raced.png');
  });

  it('treats a failure after cancel as a cancel, not an error to show', () => {
    const { state, runId } = started();
    const after = run(state, { type: 'cancel' }, { type: 'failed', runId, message: 'codec released' });
    expect(after.phase.kind).toBe('cancelled');
  });

  it('sweeps a partial file the worker reports on acknowledgement', () => {
    const { state, runId } = started();
    const after = run(state, { type: 'cancel' }, { type: 'cancelAcknowledged', runId, partialPath: '/cache/partial.mp4' });
    expect(after.phase.kind).toBe('cancelled');
    expect(after.orphans).toEqual(['/cache/partial.mp4']);
  });

  it('is idempotent, because the button can be tapped twice', () => {
    const { state } = started();
    const once = run(state, { type: 'cancel' });
    expect(run(once, { type: 'cancel' })).toBe(once);
  });
});

describe('the cached render', () => {
  it('is reused when a second destination is chosen', () => {
    // The point of the cache: choosing Copy after Save must not re-encode.
    const { state, runId } = started('library', 'plan-a');
    const ready = run(state, { type: 'succeeded', runId, result: result() });
    expect(reusableResult(ready, 'plan-a')).not.toBeNull();
    expect(run(ready, { type: 'start', destination: 'clipboard', revision: 'plan-a' })).toBe(ready);
  });

  it('is never reused for a project that has since changed', () => {
    // The dangerous one: silently saving the PREVIOUS edit.
    const { state, runId } = started('library', 'plan-a');
    const ready = run(state, { type: 'succeeded', runId, result: result() });
    expect(reusableResult(ready, 'plan-b')).toBeNull();
  });

  it('is swept when the project changes underneath it', () => {
    const { state, runId } = started('library', 'plan-a');
    const ready = run(state, { type: 'succeeded', runId, result: result('/cache/old.png') });
    const changed = run(ready, { type: 'projectChanged', revision: 'plan-b' });
    expect(changed.phase.kind).toBe('idle');
    expect(changed.orphans).toContain('/cache/old.png');
  });

  it('survives a projectChanged that reports the same revision', () => {
    const { state, runId } = started('library', 'plan-a');
    const ready = run(state, { type: 'succeeded', runId, result: result() });
    expect(run(ready, { type: 'projectChanged', revision: 'plan-a' })).toBe(ready);
  });

  it('is swept when a fresh render supersedes it', () => {
    const { state, runId } = started('library', 'plan-a');
    const ready = run(state, { type: 'succeeded', runId, result: result('/cache/v1.png') });
    const again = run(ready, { type: 'start', destination: 'library', revision: 'plan-b' });
    expect(again.phase.kind).toBe('running');
    expect(again.orphans).toContain('/cache/v1.png');
  });

  it('is swept once consumed', () => {
    const { state, runId } = started();
    const ready = run(state, { type: 'succeeded', runId, result: result('/cache/done.png') });
    const consumed = run(ready, { type: 'consumed' });
    expect(consumed.phase.kind).toBe('idle');
    expect(consumed.orphans).toContain('/cache/done.png');
  });

  it('drains orphans exactly once', () => {
    const { state, runId } = started();
    const ready = run(state, { type: 'succeeded', runId, result: result('/cache/a.png') });
    const drained = run(ready, { type: 'consumed' }, { type: 'orphansDrained' });
    expect(drained.orphans).toEqual([]);
    expect(run(drained, { type: 'orphansDrained' })).toBe(drained);
  });
});

describe('failure', () => {
  it('keeps the destination so retry means what was asked', () => {
    const { state, runId } = started('downloads', 'plan-a');
    const failed = run(state, { type: 'failed', runId, message: 'no encoder' });
    expect(failed.phase).toMatchObject({ kind: 'failed', destination: 'downloads', message: 'no encoder' });
  });

  it('can be retried without the editor losing the project', () => {
    const { state, runId } = started('downloads', 'plan-a');
    const retried = run(state, { type: 'failed', runId, message: 'no encoder' }, { type: 'start', destination: 'downloads', revision: 'plan-a' });
    expect(retried.phase.kind).toBe('running');
    expect(isExportBusy(retried)).toBe(true);
  });

  it('clears itself when the user edits instead of retrying', () => {
    const { state, runId } = started('downloads', 'plan-a');
    const failed = run(state, { type: 'failed', runId, message: 'no encoder' });
    expect(run(failed, { type: 'projectChanged', revision: 'plan-b' }).phase.kind).toBe('idle');
  });

  it('is dismissible', () => {
    const { state, runId } = started();
    const failed = run(state, { type: 'failed', runId, message: 'boom' });
    expect(run(failed, { type: 'dismiss' }).phase.kind).toBe('idle');
  });
});

describe('output naming', () => {
  const at = Date.UTC(2026, 7, 3, 18, 4, 5);

  it('renders video as real MP4 even from a WebM source', () => {
    // The user's original complaint: WebM will not paste. An MP4 extension over
    // WebM bytes would be the trick this project explicitly refuses, but the
    // NAME must still be mp4 because the encoder really does produce mp4.
    expect(exportOutputSpec('reaction.webm', 'video', at)).toEqual({
      name: 'reaction-variation-20260803-180405.mp4',
      mimeType: 'video/mp4',
    });
  });

  it('renders stills as png', () => {
    expect(exportOutputSpec('wojak.jpg', 'image', at)).toEqual({
      name: 'wojak-variation-20260803-180405.png',
      mimeType: 'image/png',
    });
  });

  it('separates two exports of the same meme', () => {
    const a = exportOutputSpec('meme.png', 'image', at);
    const b = exportOutputSpec('meme.png', 'image', at + 1000);
    expect(a.name).not.toBe(b.name);
  });

  it('breaks a same-second collision rather than letting MediaStore rename it', () => {
    const name = exportOutputSpec('meme.png', 'image', at).name;
    expect(uniqueExportName(name, [])).toBe(name);
    expect(uniqueExportName(name, [name])).toBe('meme-variation-20260803-180405-2.png');
    expect(uniqueExportName(name, [name, 'meme-variation-20260803-180405-2.png'])).toBe(
      'meme-variation-20260803-180405-3.png'
    );
  });

  it('keeps the extension when de-colliding', () => {
    expect(uniqueExportName('clip.mp4', ['clip.mp4'])).toBe('clip-2.mp4');
  });
});

describe('destinations offered', () => {
  it('offers only the destinations the build can actually perform', () => {
    // A button that silently no-ops is worse than an absent one.
    expect(exportDestinations({ canCopy: false, canDownload: false }).map((d) => d.id)).toEqual(['library']);
    expect(exportDestinations({ canCopy: true, canDownload: true }).map((d) => d.id)).toEqual([
      'library',
      'clipboard',
      'downloads',
    ]);
    expect(exportDestinations({ canCopy: false, canDownload: true }).map((d) => d.id)).toEqual([
      'library',
      'downloads',
    ]);
  });

  it('always offers the library, since that needs no extra native capability', () => {
    for (const canCopy of [true, false]) {
      for (const canDownload of [true, false]) {
        expect(exportDestinations({ canCopy, canDownload })[0].id).toBe('library');
      }
    }
  });

  it('gives every destination a distinct label and a hint', () => {
    const specs = exportDestinations({ canCopy: true, canDownload: true });
    expect(new Set(specs.map((s) => s.label)).size).toBe(specs.length);
    for (const spec of specs) expect(spec.hint.length).toBeGreaterThan(0);
  });

  it('names the library destination by the folder it actually wrote to', () => {
    expect(destinationLabel('library', 'Memes')).toBe('Memes');
    expect(destinationLabel('downloads', 'Memes')).toBe('Downloads');
  });
});

describe('what the user is told', () => {
  it('reports skipped layers rather than omitting them silently', () => {
    expect(skippedLayerWarning(0)).toEqual([]);
    expect(skippedLayerWarning(1)).toEqual(['1 layer could not be rendered']);
    expect(skippedLayerWarning(3)).toEqual(['3 layers could not be rendered']);
  });

  it('surfaces every warning in the summary', () => {
    expect(
      exportSummary(result('/p', ['2 layers could not be rendered', 'audio re-encoded']), 'downloads', 'Memes')
    ).toBe('Saved to Downloads — 2 layers could not be rendered; audio re-encoded');
  });

  it('stays quiet when there is nothing to warn about', () => {
    expect(exportSummary(result(), 'library', 'Memes')).toBe('Saved to Memes');
  });

  it('does not claim a clipboard copy was "saved" somewhere', () => {
    // A toast saying "Saved to the clipboard" sends people looking in Downloads.
    expect(exportSummary(result(), 'clipboard', 'Memes')).toBe('Copied — paste it anywhere');
  });
});
