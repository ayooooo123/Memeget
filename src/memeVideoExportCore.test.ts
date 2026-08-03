import type { MemeExportProgress } from './memeExportCore';
import {
  ExportCancelledError,
  foldNativeExportProgress,
  renderUnlessCancelled,
} from './memeVideoExportCore';

const at = (
  stage: MemeExportProgress['stage'],
  progress: number | null,
  detail = 'working'
): MemeExportProgress => ({ stage, progress, detail });

describe('foldNativeExportProgress', () => {
  it('takes a recognized stage and its fraction', () => {
    expect(
      foldNativeExportProgress(at('preparing', null, 'Preparing'), {
        stage: 'encoding',
        progress: 0.42,
        detail: 'Encoding video',
      })
    ).toEqual({ stage: 'encoding', progress: 0.42, detail: 'Encoding video' });
  });

  it('leaves the bar alone for a stage this build does not know', () => {
    const current = at('encoding', 0.5, 'Encoding video');
    expect(
      foldNativeExportProgress(current, { stage: 'transmuxing', progress: 0.1, detail: 'Something new' })
    ).toBe(current);
    expect(foldNativeExportProgress(current, { stage: '', progress: 1, detail: '' })).toBe(current);
  });

  it('holds the fraction rather than letting a jittery encoder walk it backwards', () => {
    const held = foldNativeExportProgress(at('encoding', 0.6), {
      stage: 'encoding',
      progress: 0.4,
      detail: 'Encoding video',
    });
    expect(held.progress).toBe(0.6);
  });

  it('treats a non-finite fraction as "no fraction", not as zero', () => {
    // An indeterminate bar is honest; a bar pinned at 0% reads as a stall.
    expect(
      foldNativeExportProgress(at('preparing', null), {
        stage: 'encoding',
        progress: Number.NaN,
        detail: 'Encoding video',
      })
    ).toEqual({ stage: 'encoding', progress: null, detail: 'Encoding video' });
  });

  it('clamps a fraction the encoder overshot', () => {
    expect(
      foldNativeExportProgress(at('preparing', null), {
        stage: 'encoding',
        progress: 1.4,
        detail: 'Encoding video',
      }).progress
    ).toBe(1);
  });

  it('refuses to walk back to an earlier stage', () => {
    const current = at('encoding', 0.3, 'Encoding video');
    expect(
      foldNativeExportProgress(current, { stage: 'preparing', progress: 0, detail: 'Preparing' })
    ).toBe(current);
  });
});

describe('renderUnlessCancelled', () => {
  function harness(options: { cancelAfterRender?: boolean; cancelUpFront?: boolean } = {}) {
    let cancelled = options.cancelUpFront ?? false;
    const discarded: string[] = [];
    let renders = 0;
    return {
      discarded,
      renders: () => renders,
      effects: (render: () => Promise<string | null>) => ({
        render: async () => {
          renders += 1;
          const value = await render();
          if (options.cancelAfterRender) cancelled = true;
          return value;
        },
        cancelled: () => cancelled,
        discard: async (path: string) => {
          discarded.push(path);
        },
      }),
      cancel: () => {
        cancelled = true;
      },
    };
  }

  it('returns the render when nobody cancelled', async () => {
    const h = harness();
    await expect(renderUnlessCancelled(h.effects(async () => '/cache/out.mp4'))).resolves.toBe(
      '/cache/out.mp4'
    );
    expect(h.discarded).toEqual([]);
  });

  it('does not start a render that was cancelled before it began', async () => {
    const h = harness({ cancelUpFront: true });
    await expect(renderUnlessCancelled(h.effects(async () => '/cache/out.mp4'))).rejects.toBeInstanceOf(
      ExportCancelledError
    );
    expect(h.renders()).toBe(0);
  });

  it('throws away a render that finished after the cancel', async () => {
    // The expensive case: the encoder won the race, so the file exists and
    // nobody but this function knows it has to go.
    const h = harness({ cancelAfterRender: true });
    await expect(renderUnlessCancelled(h.effects(async () => '/cache/out.mp4'))).rejects.toBeInstanceOf(
      ExportCancelledError
    );
    expect(h.discarded).toEqual(['/cache/out.mp4']);
  });

  it('reports a cancelled run as a cancellation, whatever the native error said', async () => {
    // What actually happens on device: the cancel reaches native, and the
    // export promise comes back rejected with the exporter's own message.
    const h = harness();
    await expect(
      renderUnlessCancelled(
        h.effects(async () => {
          h.cancel();
          throw new Error('E_VIDEO_EXPORT_CANCELLED: the export was cancelled');
        })
      )
    ).rejects.toBeInstanceOf(ExportCancelledError);
    expect(h.discarded).toEqual([]);
  });

  it('lets a real failure through untouched', async () => {
    const h = harness();
    await expect(
      renderUnlessCancelled(
        h.effects(async () => {
          throw new Error('no encoder for 4K H.264');
        })
      )
    ).rejects.toThrow('no encoder for 4K H.264');
    expect(h.discarded).toEqual([]);
  });

  it('passes an unavailable renderer through as null, with nothing to discard', async () => {
    const h = harness();
    await expect(renderUnlessCancelled(h.effects(async () => null))).resolves.toBeNull();
    expect(h.discarded).toEqual([]);
  });
});
