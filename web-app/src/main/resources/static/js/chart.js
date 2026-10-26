// Plain <canvas> charts (no charting library). Values are drawn exactly as measured;
// no reference or "normal" bands are drawn, because Neurovox has no validated reference values.

import { display } from './features.js';

const INK = '#c6bad6';
const MUTED = 'rgba(220, 196, 255, 0.18)';
const POINT = '#ff8fd6';
const LINE = 'rgba(199, 139, 255, 0.8)';

function setup(canvas) {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const width = canvas.clientWidth || 640;
  const height = canvas.clientHeight || 260;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  ctx.font = '12px ui-monospace, Menlo, monospace';
  return { ctx, width, height };
}

/**
 * Axis label with enough decimals that the five ticks differ: the usual display precision, or more when the plotted
 * range is narrow (two sessions with jitter 0.55 % and 0.56 % would otherwise give identical labels).
 */
function tickLabel(v, unit, range) {
  const d = display(v, unit);
  const scale = unit === 'fraction' ? 100 : unit === 's' && Math.abs(v) < 0.01 ? 1e6 : 1;
  const step = (range * scale) / 4;
  const needed = step > 0 ? Math.max(0, Math.ceil(-Math.log10(step)) + 1) : 0;
  const shown = (d.value.split('.')[1] || '').length;
  const value = needed > shown ? (v * scale).toFixed(Math.min(needed, 8)) : d.value;
  return `${value} ${d.unit}`.trim();
}

/** Line chart of {time: Date, value: number} points, oldest first. */
export function drawTrend(canvas, points, unit) {
  const { ctx, width, height } = setup(canvas);
  if (!ctx) return;
  const pad = { left: 74, right: 18, top: 16, bottom: 34 };
  ctx.clearRect(0, 0, width, height);
  if (points.length === 0) return;
  let min = Math.min(...points.map((p) => p.value));
  let max = Math.max(...points.map((p) => p.value));
  if (min === max) {
    const spread = Math.abs(min) * 0.1 || 1;
    min -= spread;
    max += spread;
  }
  const margin = (max - min) * 0.12;
  min -= margin;
  max += margin;
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;
  const x = (i) => pad.left + (points.length === 1 ? plotW / 2 : (i / (points.length - 1)) * plotW);
  const y = (v) => pad.top + (1 - (v - min) / (max - min)) * plotH;

  ctx.strokeStyle = MUTED;
  ctx.fillStyle = INK;
  ctx.lineWidth = 1;
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let k = 0; k <= 4; k++) {
    const v = min + (k / 4) * (max - min);
    const yy = y(v);
    ctx.beginPath();
    ctx.moveTo(pad.left, yy);
    ctx.lineTo(width - pad.right, yy);
    ctx.stroke();
    ctx.fillText(tickLabel(v, unit, max - min), pad.left - 8, yy);
  }
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  const step = Math.max(1, Math.ceil(points.length / 6));
  points.forEach((p, i) => {
    if (i % step === 0 || i === points.length - 1) {
      ctx.fillText(p.time.toLocaleDateString(undefined, { day: 'numeric', month: 'short' }), x(i), height - pad.bottom + 10);
    }
  });

  ctx.strokeStyle = LINE;
  ctx.lineWidth = 1.6;
  ctx.beginPath();
  points.forEach((p, i) => (i === 0 ? ctx.moveTo(x(i), y(p.value)) : ctx.lineTo(x(i), y(p.value))));
  ctx.stroke();
  ctx.fillStyle = POINT;
  points.forEach((p, i) => {
    ctx.beginPath();
    ctx.arc(x(i), y(p.value), 4, 0, Math.PI * 2);
    ctx.fill();
  });
}

/** Horizontal bars around zero, for the 13 MFCC averages. */
export function drawBars(canvas, values, labels) {
  const { ctx, width, height } = setup(canvas);
  if (!ctx || values.length === 0) return;
  const pad = { left: 36, right: 12, top: 10, bottom: 24 };
  const maxAbs = Math.max(...values.map(Math.abs)) || 1;
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;
  const zero = pad.top + plotH / 2;
  const slot = plotW / values.length;
  ctx.strokeStyle = MUTED;
  ctx.beginPath();
  ctx.moveTo(pad.left, zero);
  ctx.lineTo(width - pad.right, zero);
  ctx.stroke();
  ctx.fillStyle = INK;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  values.forEach((v, i) => {
    const barH = (Math.abs(v) / maxAbs) * (plotH / 2 - 2);
    const cx = pad.left + slot * (i + 0.5);
    ctx.fillStyle = v >= 0 ? LINE : POINT;
    ctx.fillRect(cx - slot * 0.3, v >= 0 ? zero - barH : zero, slot * 0.6, Math.max(1, barH));
    ctx.fillStyle = INK;
    ctx.fillText(labels[i], cx, height - pad.bottom + 8);
  });
}
