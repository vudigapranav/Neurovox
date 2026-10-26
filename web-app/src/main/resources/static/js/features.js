// How measurements are displayed. Values arrive from Java in the dataset's own units
// (fractions for jitter/shimmer, seconds, Hz, dB); this file only formats them.

/** Display value and unit, e.g. {value: '0.62', unit: '%'} for a fraction of 0.0062. */
export function display(value, unit) {
  if (value === null || value === undefined || !Number.isFinite(value)) return { value: '—', unit: '' };
  switch (unit) {
    case 'fraction': return { value: (value * 100).toFixed(2), unit: '%' };
    case 's':
      if (Math.abs(value) < 0.01) return { value: (value * 1e6).toFixed(1), unit: 'µs' };
      return { value: value.toFixed(2), unit: 's' };
    case 'Hz': return { value: value.toFixed(1), unit: 'Hz' };
    case 'dB': return { value: value.toFixed(2), unit: 'dB' };
    case 'ratio': return { value: value.toFixed(4), unit: '' };
    case 'semitones': return { value: value.toFixed(2), unit: 'st' };
    case 'count': return { value: String(Math.round(value)), unit: '' };
    case 'per s': return { value: value.toFixed(2), unit: '/s' };
    default: return { value: Math.abs(value) >= 100 ? value.toFixed(1) : value.toFixed(3), unit };
  }
}

export function displayText(value, unit) {
  const d = display(value, unit);
  return d.unit ? `${d.value} ${d.unit}` : d.value;
}

export const TASK_TITLES = {
  sustained_vowel: 'Sustained vowel',
  pa_ta_ka: 'Syllable repetition',
  reading_passage: 'Reading passage',
  free_speech: 'Free speech',
};

/** The four vowel measurements shown first in history and trends. */
export const KEY_FEATURES = ['MDVP:Fo(Hz)', 'MDVP:Jitter(%)', 'MDVP:Shimmer', 'HNR'];
