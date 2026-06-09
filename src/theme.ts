// Memeget design system — "ink & volt".
// One deep ink background, soft layered surfaces, and a single loud volt-green
// signature color used sparingly for the moments that matter (search, teach,
// success). Everything else stays quiet so the memes are the loudest thing on
// screen.

export const colors = {
  // Canvas
  bg: '#0a0b0e',
  surface: '#13151b',
  surface2: '#1b1e27',
  surface3: '#252935',
  border: '#262a36',
  borderLight: '#343a4a',

  // Type
  text: '#f2f4f8',
  textDim: '#c0c6d4',
  muted: '#7e8597',
  faint: '#565d6e',

  // Signature
  volt: '#d8ff4a', // primary accent — actions, focus, brand
  voltDim: '#2a3214', // volt at low alpha-ish for fills
  onVolt: '#11130a',

  // Support
  accent: '#8ab4ff', // informational (links, counts)
  good: '#5ee0a0',
  goodDim: '#15301f',
  danger: '#ff7a76',
  dangerDim: '#3a1d1c',

  // Components
  chip: '#21252f',
  chipTaught: '#1e2f1a',
  overlay: 'rgba(4,5,8,0.88)',
  scrim: 'rgba(4,5,8,0.6)',
};

export const radius = {
  sm: 8,
  md: 12,
  lg: 16,
  xl: 22,
  pill: 999,
};

export const space = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
};

export const type = {
  display: { fontSize: 28, fontWeight: '800' as const, letterSpacing: -0.5 },
  title: { fontSize: 17, fontWeight: '700' as const, letterSpacing: -0.2 },
  body: { fontSize: 15, fontWeight: '400' as const },
  label: { fontSize: 13, fontWeight: '600' as const },
  caption: { fontSize: 12, fontWeight: '500' as const },
  micro: { fontSize: 11, fontWeight: '600' as const, letterSpacing: 0.6, textTransform: 'uppercase' as const },
};

// Room scrollable content must leave under the floating tab bar.
export const TABBAR_CLEARANCE = 92;

export const shadow = {
  // Floating elements (tab bar, sheets). Android uses elevation.
  float: {
    shadowColor: '#000',
    shadowOpacity: 0.45,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 14,
  },
  card: {
    shadowColor: '#000',
    shadowOpacity: 0.25,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 3 },
    elevation: 4,
  },
};
