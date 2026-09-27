// Static Canvas 2D rendering of the orb, used when WebGL is unavailable or the
// context has been lost. It draws once per resize; there is no animation loop.

import { fibonacciSphere, createRandom } from './geometry.js';

export function drawStaticOrb(canvas, { width, height, dpr, x, y, radius }) {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);
  ctx.globalCompositeOperation = 'lighter';

  const rand = createRandom(5);
  for (let i = 0; i < 260; i++) {
    const depth = rand() * rand();
    ctx.fillStyle = `rgba(214, 190, 255, ${0.05 + depth * 0.3})`;
    ctx.fillRect(rand() * width, rand() * height, 0.8 + depth * 1.4, 0.8 + depth * 1.4);
  }

  const glow = ctx.createRadialGradient(x, y, radius * 0.6, x, y, radius * 1.6);
  glow.addColorStop(0, 'rgba(120, 60, 200, 0.10)');
  glow.addColorStop(1, 'rgba(120, 60, 200, 0)');
  ctx.fillStyle = glow;
  ctx.fillRect(0, 0, width, height);

  const count = radius > 200 ? 16000 : 7000;
  const dirs = fibonacciSphere(count);
  const tilt = 0.35;
  const ct = Math.cos(tilt);
  const st = Math.sin(tilt);
  for (let i = 0; i < count; i++) {
    const dx = dirs[i * 3];
    const dy = dirs[i * 3 + 1];
    const dz = dirs[i * 3 + 2];
    // Deterministic lumps: sinusoids along mixed, non-aligned directions so no
    // simple rotational symmetry (like a pentagon) shows in the silhouette.
    const lump = 0.045 * Math.sin(3.1 * dx + 1.7 * dy - 2.3 * dz + 0.4)
      + 0.035 * Math.sin(-2.2 * dx + 3.7 * dy + 1.3 * dz + 1.9)
      + 0.03 * Math.sin(1.4 * dx - 2.6 * dy + 4.1 * dz + 2.8)
      + 0.02 * Math.sin(5.3 * dx + 4.4 * dy + 0.7 * dz)
      + 0.015 * Math.sin(-6.1 * dx + 2.2 * dy - 5.5 * dz + 1.1);
    const r = radius * (1 + lump);
    const ry = dy * ct - dz * st;
    const rz = dy * st + dz * ct;
    const px = x + dx * r;
    const py = y - ry * r;
    const rim = Math.pow(1 - Math.abs(rz), 2.1);
    const back = rz < 0 ? 0.3 : 1;
    const warm = Math.max(0, -ry * 0.6 - dx * 0.3);
    const alpha = (0.04 + rim * 0.85 + Math.max(0, lump) * 2.2 + warm * 0.15) * back;
    if (alpha <= 0.01) continue;
    const red = Math.round(140 + warm * 110 + rim * 90);
    const green = Math.round(80 + rim * 150);
    ctx.fillStyle = `rgba(${Math.min(255, red)}, ${Math.min(255, green)}, 255, ${Math.min(1, alpha)})`;
    ctx.fillRect(px, py, 1.2, 1.2);
  }
  ctx.globalCompositeOperation = 'source-over';
}
