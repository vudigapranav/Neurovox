// Pure PCM helpers (no DOM, no Web Audio) so they can be unit tested in Node.

/** True when this machine stores Float32Array values little-endian (every mainstream platform does). */
export function hostIsLittleEndian() {
  return new Uint8Array(new Float32Array([1]).buffer)[3] === 0x3f;
}

/**
 * The exact bytes the server expects: 32-bit IEEE float, little-endian.
 * On a little-endian machine this is the Float32Array's own memory; otherwise each value is rewritten.
 */
export function toFloat32LittleEndian(samples, littleEndianHost = hostIsLittleEndian()) {
  if (littleEndianHost) {
    return new Uint8Array(samples.buffer, samples.byteOffset, samples.byteLength).slice();
  }
  const bytes = new Uint8Array(samples.length * 4);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < samples.length; i++) view.setFloat32(i * 4, samples[i], true);
  return bytes;
}

/** Joins captured chunks into one Float32Array of exactly `length` samples. */
export function concatChunks(chunks, length) {
  const out = new Float32Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    const take = Math.min(chunk.length, length - offset);
    out.set(take === chunk.length ? chunk : chunk.subarray(0, take), offset);
    offset += take;
    if (offset >= length) break;
  }
  return out;
}

/** Peak absolute value and RMS of a take, used only for the on-screen level hint. */
export function levels(samples) {
  let peak = 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) {
    const a = Math.abs(samples[i]);
    if (a > peak) peak = a;
    sum += samples[i] * samples[i];
  }
  return { peak, rms: samples.length ? Math.sqrt(sum / samples.length) : 0 };
}

/**
 * Min/max envelope for drawing a waveform with `buckets` columns.
 * Returns a Float32Array of [min0, max0, min1, max1, ...].
 */
export function envelope(samples, buckets) {
  const out = new Float32Array(buckets * 2);
  const size = samples.length / buckets;
  for (let b = 0; b < buckets; b++) {
    const start = Math.floor(b * size);
    const end = Math.max(start + 1, Math.floor((b + 1) * size));
    let min = 0;
    let max = 0;
    for (let i = start; i < end && i < samples.length; i++) {
      if (samples[i] < min) min = samples[i];
      if (samples[i] > max) max = samples[i];
    }
    out[b * 2] = min;
    out[b * 2 + 1] = max;
  }
  return out;
}

/** Below this peak a take almost certainly contains no voice (about -40 dBFS). A UI hint only. */
export const NEAR_SILENT_PEAK = 0.01;
