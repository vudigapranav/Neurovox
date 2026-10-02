// Display formatting of Java measurements (units arrive in the dataset's own convention).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { display, displayText } from '../../main/resources/static/js/features.js';

test('fractions are shown as percent, small seconds as microseconds', () => {
  assert.deepEqual(display(0.0062, 'fraction'), { value: '0.62', unit: '%' });
  assert.deepEqual(display(0.000041, 's'), { value: '41.0', unit: 'µs' });
  assert.deepEqual(display(1.5, 's'), { value: '1.50', unit: 's' });
  assert.equal(displayText(121.456, 'Hz'), '121.5 Hz');
  assert.equal(displayText(21.033, 'dB'), '21.03 dB');
  assert.equal(displayText(0.02211, 'ratio'), '0.0221');
  assert.equal(displayText(12, 'count'), '12');
});

test('missing values are a dash, never NaN', () => {
  assert.deepEqual(display(null, 'Hz'), { value: '—', unit: '' });
  assert.deepEqual(display(Number.NaN, 'fraction'), { value: '—', unit: '' });
});
