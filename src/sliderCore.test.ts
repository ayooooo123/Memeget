import { initialSliderGestureState, reduceSliderGesture } from './sliderCore';

describe('slider responder lifecycle', () => {
  test('termination commits the latest displayed value exactly once', () => {
    let state = initialSliderGestureState(0.1);
    let displayedValue = 0.1;
    let projectValue = 0.1;
    let historyCommits = 0;

    const grant = reduceSliderGesture(state, { type: 'grant', value: 0.25 });
    state = grant.state;
    expect(grant).toMatchObject({ displayValue: 0.25, completeValue: null });
    displayedValue = grant.displayValue ?? displayedValue;

    const move = reduceSliderGesture(state, { type: 'move', value: 0.7 });
    state = move.state;
    expect(move).toMatchObject({ displayValue: 0.7, completeValue: null });
    displayedValue = move.displayValue ?? displayedValue;

    const terminate = reduceSliderGesture(state, { type: 'terminate' });
    state = terminate.state;
    expect(terminate).toMatchObject({ displayValue: 0.7, completeValue: 0.7 });
    displayedValue = terminate.displayValue ?? displayedValue;
    if (terminate.completeValue !== null) {
      projectValue = terminate.completeValue;
      historyCommits += 1;
    }

    const lateRelease = reduceSliderGesture(state, { type: 'release', value: 0.9 });
    expect(lateRelease).toMatchObject({ displayValue: null, completeValue: null });
    if (lateRelease.completeValue !== null) {
      projectValue = lateRelease.completeValue;
      historyCommits += 1;
    }
    expect(displayedValue).toBe(0.7);
    expect(projectValue).toBe(displayedValue);
    expect(historyCommits).toBe(1);
  });

  test('normal release commits its final coordinate and cannot complete twice', () => {
    let state = initialSliderGestureState(0.2);
    state = reduceSliderGesture(state, { type: 'grant', value: 0.3 }).state;

    const release = reduceSliderGesture(state, { type: 'release', value: 0.8 });
    expect(release).toMatchObject({ displayValue: 0.8, completeValue: 0.8 });
    expect(reduceSliderGesture(release.state, { type: 'terminate' })).toMatchObject({
      displayValue: null,
      completeValue: null,
    });
  });
});
