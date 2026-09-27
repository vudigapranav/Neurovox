import { test } from 'node:test';
import assert from 'node:assert/strict';
import { logBandRanges, rms, levelFromRms, bandSpectrum, summariseBands, follow, SPECTRUM_BANDS } from '../../main/resources/static/js/audio-analysis.js';

test('log band ranges are ordered, non-empty and inside the spectrum', () => {
  for (const rate of [44100, 48000]) {
    const ranges = logBandRanges(rate, 2048);
    assert.equal(ranges.length, SPECTRUM_BANDS);
    let previousEnd = 0;
    for (const [start, end] of ranges) {
      assert.ok(start >= 1 && end > start && end <= 1024, `bad range ${start}-${end}`);
      assert.ok(start >= previousEnd - 1, 'ranges should move upward');
      previousEnd = end;
    }
    const topHz = (ranges.at(-1)[1] * rate) / 2048;
    assert.ok(topHz >= 7900 && topHz <= 8100, `top edge ${topHz} Hz`);
  }
});

test('rms of a full-scale sine is 1/sqrt(2); silence is 0', () => {
  const n = 4800;
  const sine = Float32Array.from({ length: n }, (_, i) => Math.sin((2 * Math.PI * 100 * i) / 48000));
  assert.ok(Math.abs(rms(sine) - Math.SQRT1_2) < 1e-3);
  assert.equal(rms(new Float32Array(256)), 0);
  assert.equal(rms([]), 0);
});

test('level mapping clamps to 0..1 and increases with loudness', () => {
  assert.equal(levelFromRms(0), 0);
  assert.equal(levelFromRms(NaN), 0);
  assert.equal(levelFromRms(1e-5), 0);
  assert.equal(levelFromRms(1), 1);
  const quiet = levelFromRms(0.005);
  const loud = levelFromRms(0.08);
  assert.ok(quiet > 0 && loud > quiet && loud < 1);
});

test('band spectrum normalises byte data per band', () => {
  const ranges = [[1, 3], [3, 5]];
  const bytes = new Uint8Array([0, 255, 255, 0, 51, 0]);
  const spec = bandSpectrum(bytes, ranges);
  assert.equal(spec[0], 1);
  assert.ok(Math.abs(spec[1] - 0.1) < 1e-6);
});

test('band summary splits spectrum into thirds', () => {
  const spec = Float32Array.from([1, 1, 1, 1, 0, 0, 0, 0, 0.5, 0.5, 0.5, 0.5]);
  const s = summariseBands(spec);
  assert.equal(s.low, 1);
  assert.equal(s.mid, 0);
  assert.equal(s.high, 0.5);
});

test('follow attacks faster than it releases', () => {
  const up = follow(0, 1, 0.4, 0.1);
  const down = follow(1, 0, 0.4, 0.1);
  assert.ok(Math.abs(up - 0.4) < 1e-9);
  assert.ok(Math.abs(down - 0.9) < 1e-9);
});
