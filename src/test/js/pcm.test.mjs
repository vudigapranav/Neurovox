import { test } from 'node:test';
import assert from 'node:assert/strict';
import { toFloat32LittleEndian, concatChunks, levels, envelope, hostIsLittleEndian } from '../../main/resources/static/js/pcm.js';

test('bytes are float32 little-endian regardless of host order', () => {
  const samples = new Float32Array([1, -0.5]);
  const expected = [0x00, 0x00, 0x80, 0x3f, 0x00, 0x00, 0x00, 0xbf];
  assert.deepEqual([...toFloat32LittleEndian(samples, true)], expected);
  assert.deepEqual([...toFloat32LittleEndian(samples, false)], expected, 'the DataView path must give the same bytes');
  assert.equal(typeof hostIsLittleEndian(), 'boolean');
});

test('byte conversion copies only the view, not the whole underlying buffer', () => {
  const backing = new Float32Array([9, 1, 2, 9]);
  const view = backing.subarray(1, 3);
  const bytes = toFloat32LittleEndian(view, true);
  assert.equal(bytes.length, 8);
  const back = new Float32Array(bytes.buffer);
  assert.deepEqual([...back], [1, 2]);
});

test('chunks join into exactly the requested length', () => {
  const chunks = [new Float32Array([1, 2, 3]), new Float32Array([4, 5]), new Float32Array([6, 7, 8])];
  assert.deepEqual([...concatChunks(chunks, 8)], [1, 2, 3, 4, 5, 6, 7, 8]);
  assert.deepEqual([...concatChunks(chunks, 4)], [1, 2, 3, 4]);
  assert.deepEqual([...concatChunks([], 0)], []);
});

test('levels report peak and rms', () => {
  const l = levels(new Float32Array([0.5, -1, 0.5, 0]));
  assert.equal(l.peak, 1);
  assert.ok(Math.abs(l.rms - Math.sqrt(1.5 / 4)) < 1e-6);
  assert.deepEqual(levels(new Float32Array(0)), { peak: 0, rms: 0 });
});

test('envelope gives min/max per column', () => {
  const env = envelope(new Float32Array([0.1, -0.2, 0.3, -0.4, 0.5, -0.6]), 3);
  assert.deepEqual([...env].map((v) => Math.round(v * 10) / 10), [-0.2, 0.1, -0.4, 0.3, -0.6, 0.5]);
});
