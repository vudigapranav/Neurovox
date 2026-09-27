// GLSL sources for the particle orb and the ambient particle field.
// Written for WebGL 1 (GLSL ES 1.00) so the widest range of devices can run it.

// 3D simplex noise by Ian McEwan and Stefan Gustavson (Ashima Arts), MIT licence.
// https://github.com/ashima/webgl-noise
const SIMPLEX_NOISE = `
vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float snoise(vec3 v) {
  const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
  const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
  vec3 i = floor(v + dot(v, C.yyy));
  vec3 x0 = v - i + dot(i, C.xxx);
  vec3 g = step(x0.yzx, x0.xyz);
  vec3 l = 1.0 - g;
  vec3 i1 = min(g.xyz, l.zxy);
  vec3 i2 = max(g.xyz, l.zxy);
  vec3 x1 = x0 - i1 + C.xxx;
  vec3 x2 = x0 - i2 + C.yyy;
  vec3 x3 = x0 - D.yyy;
  i = mod289(i);
  vec4 p = permute(permute(permute(
            i.z + vec4(0.0, i1.z, i2.z, 1.0))
          + i.y + vec4(0.0, i1.y, i2.y, 1.0))
          + i.x + vec4(0.0, i1.x, i2.x, 1.0));
  float n_ = 0.142857142857;
  vec3 ns = n_ * D.wyz - D.xzx;
  vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
  vec4 x_ = floor(j * ns.z);
  vec4 y_ = floor(j - 7.0 * x_);
  vec4 x = x_ * ns.x + ns.yyyy;
  vec4 y = y_ * ns.x + ns.yyyy;
  vec4 h = 1.0 - abs(x) - abs(y);
  vec4 b0 = vec4(x.xy, y.xy);
  vec4 b1 = vec4(x.zw, y.zw);
  vec4 s0 = floor(b0) * 2.0 + 1.0;
  vec4 s1 = floor(b1) * 2.0 + 1.0;
  vec4 sh = -step(h, vec4(0.0));
  vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
  vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
  vec3 p0 = vec3(a0.xy, h.x);
  vec3 p1 = vec3(a0.zw, h.y);
  vec3 p2 = vec3(a1.xy, h.z);
  vec3 p3 = vec3(a1.zw, h.w);
  vec4 norm = taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
  p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
  vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
  m = m * m;
  return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}
`;

// Orb vertex shader. Every particle starts as a unit direction on a sphere.
// Its radius is then pushed in and out by:
//   * three octaves of noise (slow body, medium ripples, fine grain),
//   * a 12-band voice spectrum mapped from the equator (low) to the poles (high),
//   * a pointer "attraction" bump and up to four travelling tap waves.
// Rim brightness uses the view-space normal (a fresnel term), which is what
// makes the shell read as a 3D volume instead of a flat disc.
export const ORB_VERTEX = `
precision highp float;
attribute vec3 aDir;
attribute vec3 aMeta;       // x: random 0..1, y: shell radius, z: position along trace (-1 for points)

uniform float uTime;        // wall-clock seconds (waves, breathing)
uniform float uFlow;        // accumulated flow time; runs faster when the voice is loud
uniform mat3 uRot;          // tilt + slow spin
uniform vec3 uFrame;        // xy: orb centre in clip space, z: orb radius in clip-space y units
uniform float uAspect;      // canvas width / height
uniform float uDpr;
uniform float uPointSize;
uniform vec4 uAudio;        // level, low, mid, high (all 0..1)
uniform float uSpec[12];    // log-spaced voice spectrum (0..1)
uniform vec4 uPointer;      // xyz: pointer direction in orb space, w: strength 0..1
uniform vec4 uWave[4];      // xyz: impact direction, w: start time
uniform float uWaveGain;
uniform float uIntensity;   // global fade (scroll position, fallback transitions)
uniform mediump float uTrace; // 0 when drawing points, 1 when drawing traces (precision must match the fragment shader)

varying vec4 vColor;

${SIMPLEX_NOISE}

float spectrumAt(float u) {
  float f = clamp(u, 0.0, 1.0) * 11.0;
  int i0 = int(floor(f));
  int i1 = int(min(floor(f) + 1.0, 11.0));
  return mix(uSpec[i0], uSpec[i1], fract(f));
}

void main() {
  vec3 d = normalize(aDir);
  float t = uFlow;
  float level = uAudio.x;

  float n1 = snoise(d * 1.2 + vec3(0.0, t * 0.11, t * 0.07));
  float n2 = snoise(d * 2.8 + vec3(t * 0.19, -t * 0.13, 0.0) + n1 * 0.35);
  float n3 = snoise(d * 6.4 + vec3(0.0, t * 0.47, -t * 0.39));

  // Spectrum ribbons: equator carries low frequencies, poles carry high ones.
  float band = abs(acos(clamp(d.y, -1.0, 1.0)) / 3.14159265 - 0.5) * 2.0;
  band = clamp(band + n1 * 0.14, 0.0, 1.0);
  float spec = spectrumAt(band);

  float breath = sin(uTime * 0.55) * 0.02 + sin(uTime * 0.21 + 1.3) * 0.012;

  float disp = breath
    + n1 * (0.095 + 0.20 * uAudio.y)
    + n2 * (0.045 + 0.13 * uAudio.z)
    + n3 * (0.005 + 0.085 * uAudio.w)
    + spec * level * 0.28 * (0.5 + 0.5 * (n2 * 0.5 + 0.5))
    + level * 0.04;

  // Pointer attraction: the surface nearest the pointer swells towards it.
  float pd = dot(d, uPointer.xyz);
  disp += smoothstep(0.5, 1.0, pd) * 0.13 * uPointer.w;

  // Tap waves travel across the surface as a damped ring.
  float waveGlow = 0.0;
  for (int i = 0; i < 4; i++) {
    vec4 w = uWave[i];
    float age = uTime - w.w;
    if (age > 0.0 && age < 3.2) {
      float ang = acos(clamp(dot(d, w.xyz), -1.0, 1.0));
      float x = ang - age * 1.85;
      float env = exp(-x * x * 16.0) * exp(-age * 1.2);
      disp += env * 0.19 * cos(x * 8.0) * uWaveGain;
      waveGlow += env * uWaveGain;
    }
  }

  float shell = aMeta.y;
  float inner = step(shell, 0.985);           // inner particles move less
  float r = shell * (1.0 + disp * mix(1.0, 0.55, inner));
  vec3 p = uRot * (d * r);
  vec3 n = uRot * d;

  float camera = 3.4;
  float persp = camera / (camera - p.z);
  vec2 clip = p.xy * persp * uFrame.z;
  clip.x /= uAspect;
  gl_Position = vec4(clip + uFrame.xy, 0.0, 1.0);

  float facing = n.z;
  float rim = pow(1.0 - abs(facing), 2.1);
  float key = clamp(dot(n, normalize(vec3(-0.35, -0.7, 0.62))), 0.0, 1.0);
  float peak = clamp(disp * 3.2, 0.0, 1.0);
  float back = facing < 0.0 ? 0.32 : 1.0;

  vec3 violet = vec3(0.50, 0.26, 1.0);
  vec3 pink = vec3(1.0, 0.42, 0.84);
  vec3 hot = vec3(1.0, 0.88, 1.0);
  vec3 color = mix(violet, pink, clamp(key * 0.85 + peak * 0.35 + n2 * 0.15, 0.0, 1.0));
  color = mix(color, hot, clamp(rim * rim * 0.55 + peak * level * 0.35 + waveGlow * 0.4, 0.0, 1.0));

  float alpha = (0.045 + rim * 0.78 + key * key * 0.3 + peak * 0.3 + waveGlow * 0.5) * back;
  alpha *= mix(1.0, 0.32, inner);

  if (uTrace > 0.5) {
    // Faint filaments with a pulse travelling along each one.
    float along = aMeta.z;
    float ends = sin(3.14159265 * along);
    float pulse = pow(fract(along * 1.3 - uTime * 0.16 + aMeta.x * 7.0), 14.0);
    alpha *= ends * (0.16 + pulse * 0.9 + level * 0.25);
  } else {
    gl_PointSize = uPointSize * uDpr * persp * (0.75 + aMeta.x * 0.6) * (1.0 + peak * 0.7 + level * 0.25);
  }

  vColor = vec4(color, clamp(alpha, 0.0, 1.0) * uIntensity);
}
`;

export const ORB_FRAGMENT = `
precision mediump float;
uniform float uTrace;
varying vec4 vColor;
void main() {
  float a = vColor.a;
  if (uTrace < 0.5) {
    vec2 c = gl_PointCoord - 0.5;
    float r = length(c);
    a *= smoothstep(0.5, 0.28, r);
  }
  gl_FragColor = vec4(vColor.rgb * a, a);
}
`;

// Ambient particles live in screen space with a depth value used for parallax,
// size and brightness. The pointer pushes nearby particles aside and a tap sends
// an expanding ring through the field.
export const FIELD_VERTEX = `
precision highp float;
attribute vec3 aPos;        // xy: -1..1, z: depth 0..1 (1 = near)
attribute float aRand;
uniform float uTime;
uniform float uDrift;       // 0 in reduced-motion mode
uniform float uAspect;
uniform float uDpr;
uniform vec2 uTilt;
uniform vec3 uPointer;      // xy: pointer in clip space, z: strength
uniform vec3 uClick;        // xy: tap position in clip space, z: start time
uniform vec2 uAudio;        // level, high
uniform float uIntensity;
varying float vAlpha;
varying float vHue;

void main() {
  float depth = aPos.z;
  vec2 p = aPos.xy;
  p.y = mod(p.y + 1.1 + uTime * uDrift * 0.012 * (0.25 + depth), 2.2) - 1.1;
  p.x += sin(uTime * 0.07 * uDrift + aRand * 6.2831) * 0.025 * depth;
  p += uTilt * 0.035 * depth;

  vec2 scale = vec2(uAspect, 1.0);
  vec2 dp = (p - uPointer.xy) * scale;
  float dist = length(dp);
  p += (dp / max(dist, 1e-4)) / scale * smoothstep(0.32, 0.0, dist) * 0.075 * uPointer.z * (0.4 + depth);

  float age = uTime - uClick.z;
  float ring = 0.0;
  if (age > 0.0 && age < 2.4) {
    vec2 dc = (p - uClick.xy) * scale;
    float dcl = length(dc);
    float x = dcl - age * 0.9;
    ring = exp(-x * x * 60.0) * exp(-age * 1.6);
    p += (dc / max(dcl, 1e-4)) / scale * ring * 0.035;
  }

  gl_Position = vec4(p, 0.0, 1.0);
  gl_PointSize = (0.9 + depth * 1.9) * uDpr * (1.0 + ring * 1.4);
  float twinkle = 0.55 + 0.45 * sin(uTime * (0.35 + aRand * 0.8) + aRand * 40.0);
  vAlpha = (0.05 + depth * 0.32) * twinkle * (1.0 + ring * 3.0 + uAudio.x * 0.6) * uIntensity;
  vHue = aRand;
}
`;

export const FIELD_FRAGMENT = `
precision mediump float;
varying float vAlpha;
varying float vHue;
void main() {
  float r = length(gl_PointCoord - 0.5);
  float a = vAlpha * smoothstep(0.5, 0.2, r);
  vec3 color = mix(vec3(0.78, 0.66, 1.0), vec3(1.0, 0.72, 0.92), vHue);
  gl_FragColor = vec4(color * a, a);
}
`;
