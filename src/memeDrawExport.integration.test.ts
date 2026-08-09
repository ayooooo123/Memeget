// End-to-end smoke for the draw feature's data path: a drawing added through
// the real reducer must survive into the still-image render plan, the video
// overlay render plan, and the video composition's overlay handoff — the exact
// JSON the native renderers consume. Unit tests cover each stage; this proves
// the stages agree once wired together.
import {
  createDefaultImageProject,
  createDefaultVideoProject,
  reduceMemeEditProject,
  type DrawLayer,
} from './memeEditProjectCore';
import { buildImageRenderPlan, buildVideoOverlayRenderPlan } from './memeImageRenderCore';
import { buildVideoCompositionPlan } from './memeVideoCompositionCore';
import { createDrawLayer, withAppendedElement, buildDrawElement, DEFAULT_DRAW_SETTINGS } from './memeDrawToolCore';

function drawing(id: string): DrawLayer {
  const stroke = buildDrawElement({ ...DEFAULT_DRAW_SETTINGS, shape: 'free', color: '#ff0000' }, [
    { x: 0.2, y: 0.5 },
    { x: 0.8, y: 0.5 },
  ]);
  const box = buildDrawElement({ ...DEFAULT_DRAW_SETTINGS, shape: 'rectangle', color: '#00ff00', filled: false }, [
    { x: 0.1, y: 0.1 },
    { x: 0.5, y: 0.4 },
  ]);
  if (!stroke || !box) throw new Error('fixture elements should build');
  return withAppendedElement(withAppendedElement(createDrawLayer(id), stroke), box);
}

describe('draw export data path', () => {
  test('a drawing added to an image survives into the still render plan', () => {
    const project = reduceMemeEditProject(createDefaultImageProject({ uri: 'file:///m.png', name: 'm.png', width: 1000, height: 500 }), {
      type: 'add-layer',
      layer: drawing('d'),
    });
    const plan = buildImageRenderPlan(project, { planId: 'still' });
    const draw = plan.layers.find((layer) => layer.kind === 'draw');
    expect(draw).toBeDefined();
    if (!draw || draw.kind !== 'draw') throw new Error('draw plan missing');
    expect(draw.elements).toHaveLength(2);
    // Short edge is 500px → the cross-out endpoints land in output pixels.
    expect(draw.elements[0].points[0]).toEqual({ x: 200, y: 250 });
    expect(draw.elements[1].shape).toBe('rectangle');
  });

  test('a drawing on a video reaches the overlay plan and the composition overlay handoff', () => {
    const project = reduceMemeEditProject(
      createDefaultVideoProject({ uri: 'file:///c.mp4', name: 'c.mp4', width: 1280, height: 720, durationUs: 6_000_000 }),
      { type: 'add-layer', layer: drawing('d') }
    );
    const overlayPlan = buildVideoOverlayRenderPlan(project, { planId: 'overlay' });
    expect(overlayPlan.background.mode).toBe('transparent');
    expect(overlayPlan.layers.some((layer) => layer.kind === 'draw')).toBe(true);

    // The studio renders overlayPlan to a PNG, then feeds its path in here.
    const composition = buildVideoCompositionPlan(project, { planId: 'clip', overlayUri: 'file:///cache/overlay.png' });
    expect(composition.rejections).toEqual([]);
    expect(composition.overlay).toEqual({
      uri: 'file:///cache/overlay.png',
      widthPx: composition.output.widthPx,
      heightPx: composition.output.heightPx,
    });
    // Overlay bitmap and composition frame share a size, so the burn-in aligns.
    expect(overlayPlan.output.widthPx).toBe(composition.output.widthPx);
    expect(overlayPlan.output.heightPx).toBe(composition.output.heightPx);
  });
});
