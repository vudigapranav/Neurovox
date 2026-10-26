// Pure geometry helpers for the orb. No DOM or WebGL here, so they can be unit tested in Node.

/** Small deterministic PRNG (mulberry32) so the orb looks the same on every load. */
export function createRandom(seed = 1) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Evenly spaced unit vectors on a sphere using the Fibonacci (golden-angle) spiral.
 * Returns a Float32Array of xyz triples.
 */
export function fibonacciSphere(count) {
  const out = new Float32Array(count * 3);
  const golden = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < count; i++) {
    const y = 1 - (2 * (i + 0.5)) / count;
    const radius = Math.sqrt(1 - y * y);
    const theta = golden * i;
    out[i * 3] = Math.cos(theta) * radius;
    out[i * 3 + 1] = y;
    out[i * 3 + 2] = Math.sin(theta) * radius;
  }
  return out;
}

/** Random unit vector, uniformly distributed on the sphere. */
function randomDirection(rand) {
  const z = rand() * 2 - 1;
  const phi = rand() * Math.PI * 2;
  const r = Math.sqrt(1 - z * z);
  return [r * Math.cos(phi), r * Math.sin(phi), z];
}

function cross(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}

function normalize(v) {
  const len = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0] / len, v[1] / len, v[2] / len];
}

/**
 * Orb particle attributes: surface shell plus a sparser inner volume for depth.
 * dir: unit direction (xyz). meta: [random, shellRadius, -1].
 */
export function buildOrbParticles({ surface, inner, seed = 7 }) {
  const rand = createRandom(seed);
  const total = surface + inner;
  const dir = new Float32Array(total * 3);
  const meta = new Float32Array(total * 3);
  const shell = fibonacciSphere(surface);
  dir.set(shell);
  for (let i = 0; i < surface; i++) {
    // Tiny jitter keeps the spiral pattern from reading as a grid.
    dir[i * 3] += (rand() - 0.5) * 0.004;
    dir[i * 3 + 1] += (rand() - 0.5) * 0.004;
    dir[i * 3 + 2] += (rand() - 0.5) * 0.004;
    meta[i * 3] = rand();
    meta[i * 3 + 1] = 1;
    meta[i * 3 + 2] = -1;
  }
  for (let j = 0; j < inner; j++) {
    const i = surface + j;
    const d = randomDirection(rand);
    dir.set(d, i * 3);
    meta[i * 3] = rand();
    meta[i * 3 + 1] = 0.55 + Math.pow(rand(), 0.6) * 0.4;
    meta[i * 3 + 2] = -1;
  }
  return { dir, meta, count: total };
}

/**
 * Fine traces: short great-circle arcs drawn as GL_LINES segments.
 * Each vertex stores its direction and meta [arcRandom, 1.0, alongArc 0..1].
 */
export function buildTraceArcs({ arcs, segments = 48, seed = 11 }) {
  const rand = createRandom(seed);
  const vertexCount = arcs * segments * 2;
  const dir = new Float32Array(vertexCount * 3);
  const meta = new Float32Array(vertexCount * 3);
  let v = 0;
  for (let a = 0; a < arcs; a++) {
    const start = randomDirection(rand);
    const axis = normalize(cross(start, randomDirection(rand)));
    const tangent = cross(axis, start);
    const length = 0.5 + rand() * 1.8;
    const offset = rand();
    const point = (s) => {
      const angle = s * length;
      const c = Math.cos(angle);
      const si = Math.sin(angle);
      return [start[0] * c + tangent[0] * si, start[1] * c + tangent[1] * si, start[2] * c + tangent[2] * si];
    };
    for (let s = 0; s < segments; s++) {
      for (const k of [s, s + 1]) {
        const along = k / segments;
        dir.set(point(along), v * 3);
        meta[v * 3] = offset;
        meta[v * 3 + 1] = 1;
        meta[v * 3 + 2] = along;
        v++;
      }
    }
  }
  return { dir, meta, count: vertexCount };
}

/** Ambient particles in clip space with a depth value. */
export function buildField({ count, seed = 23 }) {
  const rand = createRandom(seed);
  const pos = new Float32Array(count * 3);
  const rnd = new Float32Array(count);
  for (let i = 0; i < count; i++) {
    pos[i * 3] = rand() * 2.2 - 1.1;
    pos[i * 3 + 1] = rand() * 2.2 - 1.1;
    pos[i * 3 + 2] = Math.pow(rand(), 2.2);
    rnd[i] = rand();
  }
  return { pos, rnd, count };
}

/** Particle budget by device class. Mobile gets roughly a third of the desktop load. */
export function particleBudget({ width, height, coarsePointer }) {
  const small = Math.min(width, height) < 700 || coarsePointer;
  return small
    ? { surface: 16000, inner: 1600, arcs: 80, field: 420, maxDpr: 1.5 }
    : { surface: 64000, inner: 4000, arcs: 200, field: 1100, maxDpr: 2 };
}

/**
 * Where the orb sits on screen, in pixels. Desktop: right-hand column.
 * Mobile: centred in the upper part of the hero so text stays readable below it.
 */
export function orbLayout(width, height) {
  if (width >= 1080) {
    const radius = Math.min(height * 0.33, width * 0.24);
    return { x: width * 0.69, y: height * 0.5, radius };
  }
  const radius = Math.min(width * 0.3, height * 0.17);
  return { x: width * 0.5, y: Math.max(radius + 84, height * 0.25), radius };
}
