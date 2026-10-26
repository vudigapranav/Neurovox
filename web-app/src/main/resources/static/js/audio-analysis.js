// Pure audio feature helpers used to drive the orb animation.
// These are visual controls only; the acoustic measurements Neurovox reports are
// computed in Java from the recorded PCM, never from these values.

export const SPECTRUM_BANDS = 12;

/**
 * Log-spaced FFT bin ranges between fMin and fMax.
 * Returns an array of [startBin, endBin) pairs, each at least one bin wide.
 */
export function logBandRanges(sampleRate, fftSize, count = SPECTRUM_BANDS, fMin = 90, fMax = 8000) {
  const binHz = sampleRate / fftSize;
  const nyquistBin = fftSize / 2;
  const top = Math.min(fMax, sampleRate / 2);
  const ranges = [];
  for (let i = 0; i < count; i++) {
    const lo = fMin * Math.pow(top / fMin, i / count);
    const hi = fMin * Math.pow(top / fMin, (i + 1) / count);
    let start = Math.max(1, Math.floor(lo / binHz));
    let end = Math.min(nyquistBin, Math.ceil(hi / binHz));
    if (end <= start) end = Math.min(nyquistBin, start + 1);
    ranges.push([start, end]);
  }
  return ranges;
}

/** Root-mean-square of a time-domain buffer of floats in [-1, 1]. */
export function rms(samples) {
  if (!samples || samples.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
  return Math.sqrt(sum / samples.length);
}

/**
 * Maps RMS to a 0..1 visual level on a dB scale.
 * floorDb is treated as silence, ceilDb as a loud voice close to the microphone.
 */
export function levelFromRms(value, floorDb = -58, ceilDb = -14) {
  if (!(value > 0)) return 0;
  const db = 20 * Math.log10(value);
  return clamp01((db - floorDb) / (ceilDb - floorDb));
}

/**
 * Averages byte frequency data (0..255, as produced by AnalyserNode) into bands,
 * normalised to 0..1.
 */
export function bandSpectrum(bytes, ranges) {
  const out = new Float32Array(ranges.length);
  for (let b = 0; b < ranges.length; b++) {
    const [start, end] = ranges[b];
    let sum = 0;
    for (let i = start; i < end; i++) sum += bytes[i] || 0;
    out[b] = clamp01(sum / Math.max(1, end - start) / 255);
  }
  return out;
}

/** Collapses the 12-band spectrum into low / mid / high energy for the orb shader. */
export function summariseBands(spectrum) {
  const n = spectrum.length;
  const third = Math.floor(n / 3);
  const avg = (from, to) => {
    let s = 0;
    for (let i = from; i < to; i++) s += spectrum[i];
    return to > from ? s / (to - from) : 0;
  };
  return { low: avg(0, third), mid: avg(third, 2 * third), high: avg(2 * third, n) };
}

/** Asymmetric smoothing: fast attack, slower release, so speech feels responsive but not jittery. */
export function follow(previous, target, attack = 0.35, release = 0.08) {
  const k = target > previous ? attack : release;
  return previous + (target - previous) * k;
}

export function clamp01(x) {
  return x < 0 ? 0 : x > 1 ? 1 : x;
}
