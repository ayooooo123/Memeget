// Tiny render helper. Exists to avoid the "create an expensive value with
// useRef(expr)" trap: `useRef(new Animated.Value(0))` or
// `useRef(PanResponder.create(...))` evaluates the argument on EVERY render and
// throws away all but the first, which is wasted allocation + GC pressure in
// components that render a lot (every grid cell wraps a PressableScale).
import { useRef } from 'react';

// Compute `init()` once per component instance and return the same value on
// every later render. Unlike `useMemo`, it is guaranteed stable (useMemo may
// drop its cache); unlike `useRef(init())` it never re-runs `init`. The result
// is referentially stable, so it's safe to list in effect dependency arrays.
export function useConst<T>(init: () => T): T {
  const ref = useRef<{ value: T } | null>(null);
  if (ref.current === null) ref.current = { value: init() };
  return ref.current.value;
}
