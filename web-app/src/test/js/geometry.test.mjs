import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fibonacciSphere, buildOrbParticles, buildTraceArcs, buildField, particleBudget, orbLayout, createRandom } from '../../main/resources/static/js/geometry.js';

test('fibonacci sphere points are unit length and evenly balanced', () => {
  const pts = fibonacciSphere(5000);
  let cx = 0, cy = 0, cz = 0;
  for (let i = 0; i < 5000; i++) {
    const [x, y, z] = [pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]];
    assert.ok(Math.abs(Math.hypot(x, y, z) - 1) < 1e-5);
    cx += x; cy += y; cz += z;
  }
  assert.ok(Math.hypot(cx, cy, cz) / 5000 < 1e-3, 'centroid should be near the origin');
});

test('orb particles include a surface shell and an inner volume', () => {
  const p = buildOrbParticles({ surface: 1000, inner: 200 });
  assert.equal(p.count, 1200);
  for (let i = 0; i < 1000; i++) assert.equal(p.meta[i * 3 + 1], 1);
  for (let i = 1000; i < 1200; i++) {
    const r = p.meta[i * 3 + 1];
    assert.ok(r >= 0.55 && r <= 0.95, `inner radius ${r}`);
  }
  assert.ok(p.dir.every(Number.isFinite) && p.meta.every(Number.isFinite));
});

test('trace arcs are line segments on the unit sphere', () => {
  const t = buildTraceArcs({ arcs: 10, segments: 8 });
  assert.equal(t.count, 10 * 8 * 2);
  for (let v = 0; v < t.count; v++) {
    const len = Math.hypot(t.dir[v * 3], t.dir[v * 3 + 1], t.dir[v * 3 + 2]);
    assert.ok(Math.abs(len - 1) < 1e-5);
    const along = t.meta[v * 3 + 2];
    assert.ok(along >= 0 && along <= 1);
  }
});

test('ambient field stays within its clip-space box', () => {
  const f = buildField({ count: 500 });
  for (let i = 0; i < 500; i++) {
    assert.ok(Math.abs(f.pos[i * 3]) <= 1.1 && Math.abs(f.pos[i * 3 + 1]) <= 1.1);
    assert.ok(f.pos[i * 3 + 2] >= 0 && f.pos[i * 3 + 2] <= 1);
  }
});

test('mobile budget is capped below desktop, including pixel ratio', () => {
  const mobile = particleBudget({ width: 390, height: 844, coarsePointer: true });
  const desktop = particleBudget({ width: 1440, height: 900, coarsePointer: false });
  assert.ok(mobile.surface + mobile.inner < (desktop.surface + desktop.inner) / 3);
  assert.ok(mobile.maxDpr <= 1.5 && desktop.maxDpr <= 2);
  assert.deepEqual(particleBudget({ width: 1440, height: 900, coarsePointer: true }), mobile);
});

test('orb layout keeps the orb clear of the text column', () => {
  const d = orbLayout(1440, 900);
  assert.ok(d.x - d.radius * 1.2 > 620, 'desktop orb should sit right of the ~560px text column');
  const m = orbLayout(390, 844);
  assert.equal(m.x, 195);
  assert.ok(m.y + m.radius * 1.2 < 844 * 0.6, 'mobile orb should leave the lower screen for text');
  for (const [w, h] of [[320, 568], [768, 1024], [1080, 700], [2560, 1440]]) {
    const l = orbLayout(w, h);
    assert.ok(l.radius > 0 && l.x - l.radius >= 0 && l.x + l.radius <= w, `orb fits ${w}x${h}`);
  }
});

test('random generator is deterministic', () => {
  const a = createRandom(3); const b = createRandom(3);
  for (let i = 0; i < 10; i++) assert.equal(a(), b());
});
