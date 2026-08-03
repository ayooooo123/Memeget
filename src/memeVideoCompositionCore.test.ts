import {
  MAX_COMPOSITION_SEGMENTS,
  MAX_TITLE_CARD_DURATION_US,
  MIN_TITLE_CARD_DURATION_US,
  TITLE_CARD_FRAME_RATE,
  VIDEO_COMPOSITION_PLAN_VERSION,
  buildVideoCompositionPlan,
  titleCardMimeType,
  videoCompositionPlanAssetRequirements,
  videoCompositionPlanIsBuildable,
  type VideoCompositionCardSegment,
  type VideoCompositionPlan,
  type VideoCompositionSourceSegment,
} from './memeVideoCompositionCore';
import {
  PROJECT_LIMITS,
  createDefaultImageProject,
  createDefaultVideoProject,
  outputDurationUs,
  validateMemeEditProject,
  type MemeEditProject,
} from './memeEditProjectCore';

const videoSource = {
  uri: 'file:///clip.mp4',
  name: 'clip.mp4',
  width: 1280,
  height: 720,
  durationUs: 10_000_000,
};

function project(
  mutate: (draft: MemeEditProject) => void = () => {}
): MemeEditProject {
  const draft = createDefaultVideoProject(videoSource);
  mutate(draft);
  return draft;
}

function plan(
  mutate: (draft: MemeEditProject) => void = () => {},
  planId = 'plan-1'
): VideoCompositionPlan {
  return buildVideoCompositionPlan(project(mutate), { planId });
}

function sourceSegments(built: VideoCompositionPlan): VideoCompositionSourceSegment[] {
  return built.segments.filter(
    (segment): segment is VideoCompositionSourceSegment => segment.kind === 'source'
  );
}

function cardSegments(built: VideoCompositionPlan): VideoCompositionCardSegment[] {
  return built.segments.filter(
    (segment): segment is VideoCompositionCardSegment => segment.kind === 'card'
  );
}

function codes(built: VideoCompositionPlan): string[] {
  return built.rejections.map((rejection) => rejection.code);
}

describe('buildVideoCompositionPlan', () => {
  it('emits one source segment per retained range with a contiguous output timeline', () => {
    const built = plan((draft) => {
      draft.video!.retainedRanges = [
        { startUs: 500_000, endUs: 2_000_000 },
        { startUs: 4_000_000, endUs: 7_500_000 },
      ];
    });

    expect(built.version).toBe(VIDEO_COMPOSITION_PLAN_VERSION);
    expect(built.id).toBe('plan-1');
    expect(built.rejections).toEqual([]);
    expect(videoCompositionPlanIsBuildable(built)).toBe(true);
    expect(built.segments).toEqual([
      {
        kind: 'source',
        index: 0,
        sourceStartUs: 500_000,
        sourceEndUs: 2_000_000,
        timelineDurationUs: 1_500_000,
        outputStartUs: 0,
        outputEndUs: 1_500_000,
      },
      {
        kind: 'source',
        index: 1,
        sourceStartUs: 4_000_000,
        sourceEndUs: 7_500_000,
        timelineDurationUs: 3_500_000,
        outputStartUs: 1_500_000,
        outputEndUs: 5_000_000,
      },
    ]);
    expect(built.output.durationUs).toBe(5_000_000);
    expect(built.output.retainedDurationUs).toBe(5_000_000);
    expect(built.output.cardDurationUs).toBe(0);
  });

  it('leaves no gap between adjacent output segments at any speed', () => {
    const built = plan((draft) => {
      draft.video!.speed = 1.25;
      draft.video!.retainedRanges = [
        { startUs: 0, endUs: 1_000_001 },
        { startUs: 3_000_000, endUs: 4_000_000 },
        { startUs: 6_000_000, endUs: 6_333_333 },
      ];
    });

    const spans = built.segments;
    expect(spans[0].outputStartUs).toBe(0);
    for (let index = 1; index < spans.length; index += 1) {
      expect(spans[index].outputStartUs).toBe(spans[index - 1].outputEndUs);
    }
    expect(spans[spans.length - 1].outputEndUs).toBe(built.output.durationUs);
    expect(built.output.durationUs).toBe(
      outputDurationUs(built.segments.map((segment) => ({
        startUs: 0,
        endUs: segment.timelineDurationUs,
      })), 1.25)
    );
  });

  it('splits a retained range where a title card lands mid-range', () => {
    const built = plan((draft) => {
      draft.video!.retainedRanges = [{ startUs: 1_000_000, endUs: 5_000_000 }];
      draft.video!.insertedCards = [
        { uri: 'file:///title.png', atUs: 1_500_000, durationUs: 2_000_000 },
      ];
    });

    expect(codes(built)).toEqual([]);
    expect(built.segments).toEqual([
      {
        kind: 'source',
        index: 0,
        sourceStartUs: 1_000_000,
        sourceEndUs: 2_500_000,
        timelineDurationUs: 1_500_000,
        outputStartUs: 0,
        outputEndUs: 1_500_000,
      },
      {
        kind: 'card',
        index: 1,
        uri: 'file:///title.png',
        mimeType: 'image/png',
        timelineDurationUs: 2_000_000,
        frameRate: TITLE_CARD_FRAME_RATE,
        outputStartUs: 1_500_000,
        outputEndUs: 3_500_000,
      },
      {
        kind: 'source',
        index: 2,
        sourceStartUs: 2_500_000,
        sourceEndUs: 5_000_000,
        timelineDurationUs: 2_500_000,
        outputStartUs: 3_500_000,
        outputEndUs: 6_000_000,
      },
    ]);
    expect(built.output.durationUs).toBe(6_000_000);
    expect(built.output.retainedDurationUs).toBe(4_000_000);
    expect(built.output.cardDurationUs).toBe(2_000_000);
  });

  it('places a card at a seam without splitting either neighbouring range', () => {
    const built = plan((draft) => {
      draft.video!.retainedRanges = [
        { startUs: 0, endUs: 2_000_000 },
        { startUs: 5_000_000, endUs: 6_000_000 },
      ];
      draft.video!.insertedCards = [
        { uri: 'file:///seam.jpg', atUs: 2_000_000, durationUs: 1_000_000 },
      ];
    });

    expect(built.segments.map((segment) => segment.kind)).toEqual(['source', 'card', 'source']);
    expect(sourceSegments(built)).toEqual([
      expect.objectContaining({ sourceStartUs: 0, sourceEndUs: 2_000_000 }),
      expect.objectContaining({ sourceStartUs: 5_000_000, sourceEndUs: 6_000_000 }),
    ]);
    expect(cardSegments(built)[0]).toEqual(
      expect.objectContaining({ mimeType: 'image/jpeg', outputStartUs: 2_000_000 })
    );
  });

  it('keeps a leading card first and a trailing card last', () => {
    const built = plan((draft) => {
      draft.video!.retainedRanges = [{ startUs: 0, endUs: 3_000_000 }];
      draft.video!.insertedCards = [
        { uri: 'file:///tail.png', atUs: 3_000_000, durationUs: 500_000 },
        { uri: 'file:///head.png', atUs: 0, durationUs: 400_000 },
      ];
    });

    expect(built.segments.map((segment) => segment.kind)).toEqual(['card', 'source', 'card']);
    expect(cardSegments(built)[0].uri).toBe('file:///head.png');
    expect(cardSegments(built)[1].uri).toBe('file:///tail.png');
    expect(built.segments[0].outputStartUs).toBe(0);
    expect(built.output.durationUs).toBe(3_900_000);
  });

  it('orders same-position cards by requested time then project order', () => {
    const built = plan((draft) => {
      draft.video!.retainedRanges = [{ startUs: 0, endUs: 4_000_000 }];
      draft.video!.insertedCards = [
        { uri: 'file:///b.png', atUs: 2_000_000, durationUs: 500_000 },
        { uri: 'file:///a.png', atUs: 2_000_000, durationUs: 500_000 },
      ];
    });

    expect(cardSegments(built).map((segment) => segment.uri)).toEqual([
      'file:///b.png',
      'file:///a.png',
    ]);
    expect(sourceSegments(built)).toHaveLength(2);
  });

  it('scales card durations with the composition speed, exactly like the retained ranges', () => {
    const built = plan((draft) => {
      draft.video!.speed = 2;
      draft.video!.retainedRanges = [{ startUs: 0, endUs: 4_000_000 }];
      draft.video!.insertedCards = [
        { uri: 'file:///title.png', atUs: 1_000_000, durationUs: 2_000_000 },
      ];
    });

    // atUs is a position on the retained, card-free output timeline, so 1 s of
    // output at 2x is 2 s of source.
    expect(sourceSegments(built)[0]).toEqual(
      expect.objectContaining({ sourceStartUs: 0, sourceEndUs: 2_000_000, outputEndUs: 1_000_000 })
    );
    expect(cardSegments(built)[0]).toEqual(
      expect.objectContaining({
        timelineDurationUs: 2_000_000,
        outputStartUs: 1_000_000,
        outputEndUs: 2_000_000,
      })
    );
    expect(built.output.durationUs).toBe(3_000_000);
  });

  it('carries the base transform through to the output pixel size', () => {
    const built = plan((draft) => {
      draft.base.rotation = 90;
      draft.base.crop = { x: 0, y: 0.25, width: 1, height: 0.5 };
    });

    // 1280x720 rotated a quarter turn is 720x1280; half the height is 720x640.
    expect(built.output.widthPx).toBe(720);
    expect(built.output.heightPx).toBe(640);
    expect(built.source.rotation).toBe(90);
    expect(built.source.crop).toEqual({ x: 0, y: 0.25, width: 1, height: 0.5 });
  });

  it('prefers the materialized source uri when one exists', () => {
    const built = plan((draft) => {
      draft.transient.materializedSourceUri = 'file:///cache/clip.mp4';
    });

    expect(built.source.uri).toBe('file:///cache/clip.mp4');
  });

  it('reports muted audio without dropping the volume it would restore', () => {
    const built = plan((draft) => {
      draft.video!.audio = { muted: true, volume: 0.4 };
    });

    expect(built.audio).toEqual({ muted: true, volume: 0.4 });
  });

  it('is deterministic: the same project yields a byte-identical plan', () => {
    const mutate = (draft: MemeEditProject) => {
      draft.video!.speed = 1.5;
      draft.video!.retainedRanges = [
        { startUs: 100_000, endUs: 900_000 },
        { startUs: 2_000_000, endUs: 3_100_000 },
      ];
      draft.video!.insertedCards = [
        { uri: 'file:///card.webp', atUs: 400_000, durationUs: 600_000 },
      ];
    };

    expect(JSON.stringify(plan(mutate))).toBe(JSON.stringify(plan(mutate)));
  });

  it('never reads the clock or the random generator', () => {
    const now = jest.spyOn(Date, 'now');
    const random = jest.spyOn(Math, 'random');
    try {
      plan((draft) => {
        draft.video!.insertedCards = [
          { uri: 'file:///card.png', atUs: 0, durationUs: 1_000_000 },
        ];
      });
      expect(now).not.toHaveBeenCalled();
      expect(random).not.toHaveBeenCalled();
    } finally {
      now.mockRestore();
      random.mockRestore();
    }
  });
});

describe('buildVideoCompositionPlan rejections', () => {
  it('refuses an image project outright', () => {
    expect(() =>
      buildVideoCompositionPlan(
        createDefaultImageProject({ uri: 'file:///m.png', name: 'm.png', width: 10, height: 10 }),
        { planId: 'plan-image' }
      )
    ).toThrow(TypeError);
  });

  it('rejects a project whose retained ranges are all empty', () => {
    const built = plan((draft) => {
      draft.video!.retainedRanges = [];
    });

    expect(codes(built)).toEqual(['no-retained-ranges']);
    expect(videoCompositionPlanIsBuildable(built)).toBe(false);
    expect(built.segments).toEqual([]);
    expect(built.output.durationUs).toBe(0);
  });

  it('rejects a card whose asset is not a still image media3 can decode', () => {
    const built = plan((draft) => {
      draft.video!.insertedCards = [
        { uri: 'file:///clip.mp4', atUs: 0, durationUs: 1_000_000 },
        { uri: 'file:///loop.gif', atUs: 0, durationUs: 1_000_000 },
        { uri: 'file:///unknown', atUs: 0, durationUs: 1_000_000 },
      ];
    });

    expect(codes(built)).toEqual([
      'card-unsupported-asset',
      'card-unsupported-asset',
      'card-unsupported-asset',
    ]);
    expect(built.rejections[0].path).toBe('video.insertedCards[0]');
    expect(built.rejections[0].message).toContain('mp4');
    expect(built.rejections[1].message).toContain('gif');
    expect(cardSegments(built)).toEqual([]);
    expect(videoCompositionPlanIsBuildable(built)).toBe(false);
  });

  it('rejects card durations outside the bounded title-card window', () => {
    const built = plan((draft) => {
      draft.video!.insertedCards = [
        { uri: 'file:///a.png', atUs: 0, durationUs: MIN_TITLE_CARD_DURATION_US - 1 },
        { uri: 'file:///b.png', atUs: 0, durationUs: MAX_TITLE_CARD_DURATION_US + 1 },
      ];
    });

    expect(codes(built)).toEqual([
      'card-duration-out-of-range',
      'card-duration-out-of-range',
    ]);
    expect(built.rejections[0].message).toContain('0.2s');
    expect(cardSegments(built)).toEqual([]);
  });

  it('rounds a card duration to whole milliseconds, because media3 takes image durations in ms', () => {
    const built = plan((draft) => {
      draft.video!.insertedCards = [
        { uri: 'file:///a.png', atUs: 0, durationUs: 1_000_499 },
        { uri: 'file:///b.png', atUs: 0, durationUs: 1_000_500 },
      ];
    });

    expect(codes(built)).toEqual([]);
    expect(cardSegments(built).map((segment) => segment.timelineDurationUs)).toEqual([
      1_000_000,
      1_001_000,
    ]);
  });

  it('rejects a card positioned outside the retained output timeline', () => {
    const built = plan((draft) => {
      draft.video!.retainedRanges = [{ startUs: 0, endUs: 2_000_000 }];
      draft.video!.insertedCards = [
        { uri: 'file:///late.png', atUs: 2_000_001, durationUs: 1_000_000 },
        { uri: 'file:///early.png', atUs: -1, durationUs: 1_000_000 },
      ];
    });

    expect(codes(built)).toEqual(['card-position-out-of-range', 'card-position-out-of-range']);
    expect(cardSegments(built)).toEqual([]);
  });

  it('rejects a speed the composition cannot express', () => {
    const built = plan((draft) => {
      draft.video!.speed = 0;
    });

    expect(codes(built)).toEqual(['unsupported-speed']);
    expect(built.segments).toEqual([]);
  });

  it('bounds the total segment count so split and card operations stay finite', () => {
    const ranges = Array.from({ length: PROJECT_LIMITS.maxRetainedRanges }, (_, index) => ({
      startUs: index * 200_000,
      endUs: index * 200_000 + 100_000,
    }));
    const built = plan((draft) => {
      draft.source.durationUs = 20_000_000;
      draft.video!.retainedRanges = ranges;
      draft.video!.insertedCards = Array.from(
        { length: PROJECT_LIMITS.maxInsertedCards },
        (_, index) => ({
          uri: `file:///card-${index}.png`,
          // Land inside successive retained ranges so every card forces a split.
          atUs: index * 100_000 + 50_000,
          durationUs: MIN_TITLE_CARD_DURATION_US,
        })
      );
    });

    expect(built.segments.length).toBeLessThanOrEqual(MAX_COMPOSITION_SEGMENTS);
    expect(codes(built)).toEqual([]);
  });

  it('refuses to plan past the segment ceiling instead of silently truncating', () => {
    const ranges = Array.from({ length: PROJECT_LIMITS.maxRetainedRanges }, (_, index) => ({
      startUs: index * 200_000,
      endUs: index * 200_000 + 190_000,
    }));
    const built = buildVideoCompositionPlan(
      project((draft) => {
        draft.source.durationUs = 20_000_000;
        draft.video!.retainedRanges = ranges;
        draft.video!.insertedCards = Array.from({ length: 24 }, (_, index) => ({
          uri: `file:///card-${index}.png`,
          atUs: index * 190_000 + 95_000,
          durationUs: MIN_TITLE_CARD_DURATION_US,
        }));
      }),
      { planId: 'plan-ceiling', maxSegments: 8 }
    );

    expect(codes(built)).toEqual(['segment-limit-exceeded']);
    expect(built.segments).toEqual([]);
    expect(videoCompositionPlanIsBuildable(built)).toBe(false);
  });
});

describe('titleCardMimeType', () => {
  it('maps the still-image extensions media3 decodes and nothing else', () => {
    expect(titleCardMimeType('file:///a.PNG')).toBe('image/png');
    expect(titleCardMimeType('file:///a.jpeg')).toBe('image/jpeg');
    expect(titleCardMimeType('file:///a.jpg')).toBe('image/jpeg');
    expect(titleCardMimeType('file:///a.webp')).toBe('image/webp');
    expect(titleCardMimeType('file:///a.heic')).toBe('image/heic');
    expect(titleCardMimeType('content://media/1/a.bmp')).toBe('image/bmp');
    expect(titleCardMimeType('file:///a.gif')).toBeNull();
    expect(titleCardMimeType('file:///a.mp4')).toBeNull();
    expect(titleCardMimeType('file:///a.svg')).toBeNull();
    expect(titleCardMimeType('file:///noextension')).toBeNull();
    expect(titleCardMimeType('file:///a.png?v=2#frag')).toBe('image/png');
  });
});

describe('videoCompositionPlanAssetRequirements', () => {
  it('lists every card in composition order, with the type the guard must verify', () => {
    const built = plan((draft) => {
      draft.video!.retainedRanges = [{ startUs: 0, endUs: 4_000_000 }];
      draft.video!.insertedCards = [
        { uri: 'file:///second.jpg', atUs: 2_000_000, durationUs: 500_000 },
        { uri: 'file:///first.png', atUs: 0, durationUs: 500_000 },
      ];
    });

    expect(videoCompositionPlanAssetRequirements(built)).toEqual([
      { uri: 'file:///first.png', role: 'TITLE_CARD', mimeType: 'image/png' },
      { uri: 'file:///second.jpg', role: 'TITLE_CARD', mimeType: 'image/jpeg' },
    ]);
  });

  it('asks for nothing when the composition references no external asset', () => {
    expect(videoCompositionPlanAssetRequirements(plan())).toEqual([]);
  });
});

describe('plans built from real projects', () => {
  it('accepts every project the project validator accepts', () => {
    const draft = project((next) => {
      next.video!.speed = 1.5;
      next.video!.retainedRanges = [
        { startUs: 0, endUs: 1_000_000 },
        { startUs: 2_000_000, endUs: 4_000_000 },
      ];
      next.video!.insertedCards = [
        { uri: 'file:///card.png', atUs: 500_000, durationUs: 1_000_000 },
      ];
    });
    expect(validateMemeEditProject(draft)).toEqual({ ok: true, value: draft });

    const built = buildVideoCompositionPlan(draft, { planId: 'plan-valid' });
    expect(built.rejections).toEqual([]);
    expect(videoCompositionPlanIsBuildable(built)).toBe(true);
  });
});
