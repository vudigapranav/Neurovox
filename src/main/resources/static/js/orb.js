// Procedural particle orb (raw WebGL 1, no dependencies).
//
// Layers, drawn back to front with additive blending:
//   1. ambient field  – depth-sorted dust that drifts and parts around the pointer
//   2. traces         – faint great-circle filaments with travelling pulses
//   3. orb particles  – a dense shell plus a sparser inner volume
//
// Inputs: pointer (tilt + attraction), taps (surface waves), keyboard (nudge/pulse)
// and an optional AudioReactor whose level and spectrum reshape the shell.

import { ORB_VERTEX, ORB_FRAGMENT, FIELD_VERTEX, FIELD_FRAGMENT } from './shaders.js';
import { buildOrbParticles, buildTraceArcs, buildField, particleBudget, orbLayout } from './geometry.js';
import { drawStaticOrb } from './fallback.js';

const WAVES = 4;

export function createOrb({ canvas, fallbackCanvas, reducedMotion = false, onStatus = () => {} }) {
  const coarsePointer = matchMedia('(pointer: coarse)').matches;
  const s = {
    mode: 'starting',       // webgl | fallback | lost
    reduced: reducedMotion,
    width: 1, height: 1, dpr: 1, maxDpr: 2,
    time: 0, flow: 0, last: 0, raf: 0, frameMs: 16, warmup: 0,
    tilt: { x: 0, y: 0, tx: 0, ty: 0 },
    pointer: { x: 0, y: 0, strength: 0, target: 0, lastMove: -10 },
    waves: Array.from({ length: WAVES }, () => [0, 0, 1, -100]),
    waveIndex: 0,
    click: [0, 0, -100],
    audio: { level: 0, low: 0, mid: 0, high: 0 },
    spectrum: new Float32Array(12),
    reactor: null,
    fade: 0,
    scrollY: 0,
    budget: null,
  };
  let gl = null;
  let res = null;
  const rotation = new Float32Array(9);
  const waveData = new Float32Array(WAVES * 4);

  // ---------- setup ----------

  function compile(type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS) && !gl.isContextLost()) {
      throw new Error(gl.getShaderInfoLog(shader) || 'Shader compile failed');
    }
    return shader;
  }

  function program(vs, fs, attributes, uniforms) {
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS) && !gl.isContextLost()) {
      throw new Error(gl.getProgramInfoLog(p) || 'Program link failed');
    }
    const a = {};
    const u = {};
    attributes.forEach((name) => { a[name] = gl.getAttribLocation(p, name); });
    uniforms.forEach((name) => { u[name] = gl.getUniformLocation(p, name); });
    return { p, a, u };
  }

  function interleave(dir, meta, count) {
    const data = new Float32Array(count * 6);
    for (let i = 0; i < count; i++) {
      data.set(dir.subarray(i * 3, i * 3 + 3), i * 6);
      data.set(meta.subarray(i * 3, i * 3 + 3), i * 6 + 3);
    }
    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
    return buffer;
  }

  function initGL() {
    gl = canvas.getContext('webgl', { alpha: true, antialias: false, premultipliedAlpha: true, powerPreference: 'high-performance' })
      || canvas.getContext('experimental-webgl');
    if (!gl) return false;
    const budget = particleBudget({ width: innerWidth, height: innerHeight, coarsePointer });
    s.budget = budget;
    s.maxDpr = budget.maxDpr;
    s.dpr = Math.min(window.devicePixelRatio || 1, s.maxDpr);

    const orbUniforms = ['uTime', 'uFlow', 'uRot', 'uFrame', 'uAspect', 'uDpr', 'uPointSize', 'uAudio', 'uSpec',
      'uPointer', 'uWave', 'uWaveGain', 'uIntensity', 'uTrace'];
    const orbProgram = program(ORB_VERTEX, ORB_FRAGMENT, ['aDir', 'aMeta'], orbUniforms);
    const fieldProgram = program(FIELD_VERTEX, FIELD_FRAGMENT, ['aPos', 'aRand'],
      ['uTime', 'uDrift', 'uAspect', 'uDpr', 'uTilt', 'uPointer', 'uClick', 'uAudio', 'uIntensity']);

    const particles = buildOrbParticles({ surface: budget.surface, inner: budget.inner });
    const traces = buildTraceArcs({ arcs: budget.arcs });
    const field = buildField({ count: budget.field });
    const fieldData = new Float32Array(field.count * 4);
    for (let i = 0; i < field.count; i++) {
      fieldData.set(field.pos.subarray(i * 3, i * 3 + 3), i * 4);
      fieldData[i * 4 + 3] = field.rnd[i];
    }
    const fieldBuffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, fieldBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, fieldData, gl.STATIC_DRAW);

    res = {
      orb: orbProgram,
      field: fieldProgram,
      points: { buffer: interleave(particles.dir, particles.meta, particles.count), count: particles.count },
      traces: { buffer: interleave(traces.dir, traces.meta, traces.count), count: traces.count },
      fieldBuf: { buffer: fieldBuffer, count: field.count },
    };
    gl.disable(gl.DEPTH_TEST);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE);
    return true;
  }

  // ---------- layout ----------

  function resize() {
    // clientWidth excludes any layout-viewport growth on mobile, so the orb can't feed back into overflow.
    s.width = Math.max(1, document.documentElement.clientWidth || innerWidth);
    s.height = Math.max(1, innerHeight);
    const w = Math.round(s.width * s.dpr);
    const h = Math.round(s.height * s.dpr);
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    // CSS vars describe the orb in page coordinates (no scroll) so hero padding never shifts.
    const base = orbLayout(s.width, s.height);
    const style = document.documentElement.style;
    style.setProperty('--orb-x', `${base.x}px`);
    style.setProperty('--orb-y', `${base.y}px`);
    style.setProperty('--orb-r', `${base.radius}px`);
    if (s.mode === 'fallback' || s.mode === 'lost') drawFallback();
    applyScroll();
  }

  /** True for tablet/phone layouts, where the orb sits above the text instead of beside it. */
  function stacked() {
    return s.width < 1080;
  }

  /**
   * Orb centre and radius in viewport pixels for the current scroll position.
   * Stacked layouts: the orb scrolls away with the hero so it never sits behind text.
   * Side-by-side layout: the orb stays fixed and recedes as the hero scrolls out.
   */
  function frame() {
    const base = orbLayout(s.width, s.height);
    if (stacked()) return { x: base.x, y: base.y - s.scrollY, radius: base.radius };
    const f = s.fade;
    return { x: base.x, y: base.y - f * s.height * 0.08, radius: base.radius * (1 - f * 0.14) };
  }

  function drawFallback() {
    if (!fallbackCanvas) return;
    drawStaticOrb(fallbackCanvas, { width: s.width, height: s.height, dpr: Math.min(window.devicePixelRatio || 1, 2), ...orbLayout(s.width, s.height) });
  }

  /** Scroll effects that don't need a redraw: fallback transform and the CSS glow position. */
  function applyScroll() {
    const l = frame();
    document.documentElement.style.setProperty('--orb-view-y', `${l.y}px`);
    if (!fallbackCanvas) return;
    if (stacked()) {
      fallbackCanvas.style.transform = `translate3d(0, ${-s.scrollY}px, 0)`;
      fallbackCanvas.style.opacity = '';
    } else {
      fallbackCanvas.style.transform = '';
      fallbackCanvas.style.opacity = s.mode === 'webgl' ? '' : String(1 - s.fade * 0.55);
    }
  }

  // ---------- input ----------

  function toViewDirection(clientX, clientY) {
    const l = frame();
    const vx = (clientX - l.x) / l.radius;
    const vy = -(clientY - l.y) / l.radius;
    const len = Math.hypot(vx, vy);
    let view;
    if (len < 1) view = [vx, vy, Math.sqrt(1 - len * len)];
    else view = [vx / len, vy / len, 0];
    // Rotation is orthonormal, so its transpose maps view space back to orb space.
    const r = rotation;
    const obj = [
      r[0] * view[0] + r[1] * view[1] + r[2] * view[2],
      r[3] * view[0] + r[4] * view[1] + r[5] * view[2],
      r[6] * view[0] + r[7] * view[1] + r[8] * view[2],
    ];
    return { dir: obj, distance: len };
  }

  function pointerMove(clientX, clientY) {
    const p = s.pointer;
    p.x = clientX;
    p.y = clientY;
    p.lastMove = s.time;
    const range = s.reduced ? 0.25 : 1;
    s.tilt.ty = ((clientX / s.width) - 0.5) * 0.7 * range;
    s.tilt.tx = ((clientY / s.height) - 0.5) * 0.5 * range;
    const { distance } = toViewDirection(clientX, clientY);
    p.target = smoothstep(2.4, 0.7, distance);
  }

  function pulseAt(clientX, clientY) {
    const { dir } = toViewDirection(clientX, clientY);
    const w = s.waves[s.waveIndex];
    w[0] = dir[0]; w[1] = dir[1]; w[2] = dir[2]; w[3] = s.time;
    s.waveIndex = (s.waveIndex + 1) % WAVES;
    s.click[0] = (clientX / s.width) * 2 - 1;
    s.click[1] = 1 - (clientY / s.height) * 2;
    s.click[2] = s.time;
  }

  function pulseCenter() {
    const l = frame();
    pulseAt(l.x, l.y);
  }

  function nudge(dx, dy) {
    const range = s.reduced ? 0.25 : 1;
    s.tilt.ty = clamp(s.tilt.ty + dx * 0.18 * range, -0.9, 0.9);
    s.tilt.tx = clamp(s.tilt.tx + dy * 0.18 * range, -0.7, 0.7);
  }

  // ---------- frame loop ----------

  function updateRotation(yaw, pitch) {
    const cy = Math.cos(yaw), sy = Math.sin(yaw), cx = Math.cos(pitch), sx = Math.sin(pitch);
    // R = Rx(pitch) * Ry(yaw), stored column-major for uniformMatrix3fv.
    rotation[0] = cy;       rotation[1] = sx * sy;  rotation[2] = -cx * sy;
    rotation[3] = 0;        rotation[4] = cx;       rotation[5] = sx;
    rotation[6] = sy;       rotation[7] = -sx * cy; rotation[8] = cx * cy;
  }

  function step(now) {
    s.raf = requestAnimationFrame(step);
    const dtMs = s.last ? Math.min(now - s.last, 100) : 16;
    s.last = now;
    const dt = dtMs / 1000;
    const speed = s.reduced ? 0.18 : 1;
    s.time += dt * speed;

    // Audio: read the reactor, or let the previous values relax back to idle.
    const a = s.audio;
    if (s.reactor) {
      const r = s.reactor.read();
      a.level = r.level; a.low = r.low; a.mid = r.mid; a.high = r.high;
      s.spectrum.set(r.spectrum);
    } else {
      a.level *= 0.92; a.low *= 0.92; a.mid *= 0.92; a.high *= 0.92;
      for (let i = 0; i < 12; i++) s.spectrum[i] *= 0.92;
    }
    const gain = s.reduced ? 0.55 : 1;
    s.flow += dt * speed * (1 + a.level * 2.4);

    // Pointer strength fades out when the pointer rests or leaves.
    const p = s.pointer;
    const idle = s.time - p.lastMove > 2.5 / speed;
    p.strength += ((idle ? 0 : p.target) - p.strength) * 0.06;
    const t = s.tilt;
    const ease = s.reduced ? 0.02 : 0.05;
    t.x += (t.tx - t.x) * ease;
    t.y += (t.ty - t.y) * ease;
    updateRotation(s.time * 0.07 + t.y, t.x + 0.12);

    // Adaptive quality: drop resolution if frames are consistently slow.
    s.frameMs += (dtMs - s.frameMs) * 0.05;
    s.warmup += dt;
    if (s.warmup > 2.5 && s.frameMs > 26 && s.dpr > 1) {
      s.dpr = Math.max(1, s.dpr - 0.25);
      s.warmup = 0;
      resize();
    }

    render(gain);
  }

  function render(gain) {
    if (!gl || gl.isContextLost()) return;
    const l = frame();
    const aspect = s.width / s.height;
    const intensity = 1 - s.fade * 0.55;
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);

    // Ambient field
    const f = res.field;
    gl.useProgram(f.p);
    gl.bindBuffer(gl.ARRAY_BUFFER, res.fieldBuf.buffer);
    gl.enableVertexAttribArray(f.a.aPos);
    gl.vertexAttribPointer(f.a.aPos, 3, gl.FLOAT, false, 16, 0);
    gl.enableVertexAttribArray(f.a.aRand);
    gl.vertexAttribPointer(f.a.aRand, 1, gl.FLOAT, false, 16, 12);
    gl.uniform1f(f.u.uTime, s.time);
    gl.uniform1f(f.u.uDrift, s.reduced ? 0 : 1);
    gl.uniform1f(f.u.uAspect, aspect);
    gl.uniform1f(f.u.uDpr, s.dpr);
    gl.uniform2f(f.u.uTilt, s.tilt.y, -s.tilt.x);
    gl.uniform3f(f.u.uPointer, (s.pointer.x / s.width) * 2 - 1, 1 - (s.pointer.y / s.height) * 2,
      s.reduced ? 0 : Math.max(s.pointer.strength, s.time - s.pointer.lastMove < 1.5 ? 0.8 : 0));
    gl.uniform3f(f.u.uClick, s.click[0], s.click[1], s.reduced ? -100 : s.click[2]);
    gl.uniform2f(f.u.uAudio, s.audio.level, s.audio.high);
    gl.uniform1f(f.u.uIntensity, 1 - s.fade * 0.3);
    gl.drawArrays(gl.POINTS, 0, res.fieldBuf.count);
    gl.disableVertexAttribArray(f.a.aRand);

    // Orb: shared uniforms, then traces and particles.
    const o = res.orb;
    gl.useProgram(o.p);
    gl.uniform1f(o.u.uTime, s.time);
    gl.uniform1f(o.u.uFlow, s.flow);
    gl.uniformMatrix3fv(o.u.uRot, false, rotation);
    gl.uniform3f(o.u.uFrame, (l.x / s.width) * 2 - 1, 1 - (l.y / s.height) * 2, l.radius / (s.height / 2));
    gl.uniform1f(o.u.uAspect, aspect);
    gl.uniform1f(o.u.uDpr, s.dpr);
    gl.uniform1f(o.u.uPointSize, s.budget.surface > 20000 ? 1.35 : 1.6);
    gl.uniform4f(o.u.uAudio, s.audio.level * gain, s.audio.low * gain, s.audio.mid * gain, s.audio.high * gain);
    gl.uniform1fv(o.u.uSpec, s.spectrum);
    const pd = toViewDirection(s.pointer.x, s.pointer.y).dir;
    gl.uniform4f(o.u.uPointer, pd[0], pd[1], pd[2], s.reduced ? s.pointer.strength * 0.3 : s.pointer.strength);
    s.waves.forEach((w, i) => waveData.set(w, i * 4));
    gl.uniform4fv(o.u.uWave, waveData);
    gl.uniform1f(o.u.uWaveGain, s.reduced ? 0.35 : 1);
    gl.uniform1f(o.u.uIntensity, intensity);

    const drawLayer = (layer, mode, trace) => {
      gl.bindBuffer(gl.ARRAY_BUFFER, layer.buffer);
      gl.enableVertexAttribArray(o.a.aDir);
      gl.vertexAttribPointer(o.a.aDir, 3, gl.FLOAT, false, 24, 0);
      gl.enableVertexAttribArray(o.a.aMeta);
      gl.vertexAttribPointer(o.a.aMeta, 3, gl.FLOAT, false, 24, 12);
      gl.uniform1f(o.u.uTrace, trace);
      gl.drawArrays(mode, 0, layer.count);
    };
    drawLayer(res.traces, gl.LINES, 1);
    drawLayer(res.points, gl.POINTS, 0);
    gl.disableVertexAttribArray(o.a.aMeta);
  }

  // ---------- lifecycle ----------

  function setMode(mode) {
    s.mode = mode;
    document.documentElement.dataset.orb = mode;
    onStatus(mode);
  }

  function useFallback(mode = 'fallback') {
    cancelAnimationFrame(s.raf);
    s.raf = 0;
    setMode(mode);
    drawFallback();
  }

  function start() {
    let ok = false;
    try {
      ok = !new URLSearchParams(location.search).has('nowebgl') && initGL();
    } catch (error) {
      console.warn('[neurovox] WebGL orb unavailable:', error);
      ok = false;
    }
    resize();
    if (!ok) { useFallback(); return; }
    setMode('webgl');
    s.last = 0;
    s.raf = requestAnimationFrame(step);
  }

  canvas.addEventListener('webglcontextlost', (event) => {
    event.preventDefault();
    useFallback('lost');
  });
  canvas.addEventListener('webglcontextrestored', () => {
    try {
      if (!initGL()) throw new Error('context unavailable');
      resize();
      setMode('webgl');
      s.last = 0;
      s.raf = requestAnimationFrame(step);
    } catch {
      useFallback();
    }
  });

  document.addEventListener('visibilitychange', () => {
    if (s.mode !== 'webgl') return;
    if (document.hidden) { cancelAnimationFrame(s.raf); s.raf = 0; }
    else if (!s.raf) { s.last = 0; s.raf = requestAnimationFrame(step); }
  });
  addEventListener('resize', resize, { passive: true });

  return {
    start,
    pointerMove,
    pointerLeave() { s.pointer.target = 0; },
    pulseAt,
    pulseCenter,
    nudge,
    /** Attach an AudioReactor (or null) to drive the shape from live audio. */
    setAudioSource(reactor) { s.reactor = reactor; },
    setReducedMotion(value) { s.reduced = value; },
    /** Page scroll in px, and fade: 0 at the top of the page, 1 once the hero has scrolled away. */
    setScroll(scrollY, fade) {
      s.scrollY = Math.max(0, scrollY);
      s.fade = clamp(fade, 0, 1);
      applyScroll();
    },
    stats() {
      return { mode: s.mode, dpr: s.dpr, frameMs: Math.round(s.frameMs * 10) / 10, particles: res ? res.points.count : 0,
        traces: res ? res.traces.count / 2 : 0, field: res ? res.fieldBuf.count : 0, level: s.audio.level, flow: s.flow };
    },
  };
}

function clamp(x, lo, hi) { return x < lo ? lo : x > hi ? hi : x; }
function smoothstep(e0, e1, x) {
  const t = clamp((x - e0) / (e1 - e0), 0, 1);
  return t * t * (3 - 2 * t);
}
