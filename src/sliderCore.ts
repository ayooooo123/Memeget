export interface SliderGestureState {
  active: boolean;
  latestValue: number;
}

export type SliderGestureEvent =
  | { type: 'grant' | 'move'; value: number }
  | { type: 'release'; value: number }
  | { type: 'terminate' };

export interface SliderGestureTransition {
  state: SliderGestureState;
  displayValue: number | null;
  completeValue: number | null;
}

function clampSliderValue(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(1, value));
}

export function initialSliderGestureState(value: number): SliderGestureState {
  return { active: false, latestValue: clampSliderValue(value) };
}

export function reduceSliderGesture(
  state: SliderGestureState,
  event: SliderGestureEvent
): SliderGestureTransition {
  if (event.type === 'grant') {
    const value = clampSliderValue(event.value);
    return { state: { active: true, latestValue: value }, displayValue: value, completeValue: null };
  }
  if (!state.active) return { state, displayValue: null, completeValue: null };
  if (event.type === 'move') {
    const value = clampSliderValue(event.value);
    return { state: { active: true, latestValue: value }, displayValue: value, completeValue: null };
  }
  const value = event.type === 'release' ? clampSliderValue(event.value) : state.latestValue;
  return {
    state: { active: false, latestValue: value },
    displayValue: value,
    completeValue: value,
  };
}
