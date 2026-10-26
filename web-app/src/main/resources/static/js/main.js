// Landing page wiring: orb lifecycle, pointer/touch/keyboard input, microphone
// preview, scroll behaviour and the periodicity illustrations.

import { setupOrb } from './orb-page.js';
import { MicrophonePreview, describeMicError } from './voice-input.js';
import { createRandom } from './geometry.js';

const root = document.documentElement;
root.classList.add('js');

const params = new URLSearchParams(location.search);
const simulateVoice = params.has('simulate-voice');
const reducedQuery = matchMedia('(prefers-reduced-motion: reduce)');

const els = {
  mic: document.getElementById('micToggle'),
  micLabel: document.getElementById('micLabel'),
  status: document.getElementById('listenStatus'),
  meter: document.getElementById('levelMeter'),
  header: document.querySelector('.site-header'),
  hero: document.getElementById('top'),
};

// ---------- orb ----------

const orb = setupOrb({
  onStatus(mode) {
    if (mode === 'lost') setStatus('The graphics context was interrupted. Showing a still image until it recovers.');
    if (mode === 'fallback') setStatus('Showing a still orb because WebGL is unavailable. Everything else works normally.');
    if (mode === 'webgl' && root.dataset.orbWasLost) setStatus(defaultStatus());
    if (mode === 'lost') root.dataset.orbWasLost = '1';
  },
});

// ---------- microphone preview ----------

const preview = new MicrophonePreview();
let meterFrame = 0;

function defaultStatus() {
  return 'Move, tap, or focus the orb and use the arrow keys.';
}

function setStatus(text) {
  els.status.textContent = text;
}

function setMicUi(state) {
  root.dataset.listening = state;
  const live = state === 'live';
  els.mic.setAttribute('aria-pressed', String(live));
  els.mic.disabled = state === 'requesting';
  els.micLabel.textContent = {
    idle: 'Let the orb listen',
    requesting: 'Waiting for permission…',
    live: 'Stop listening',
    error: 'Try the microphone again',
  }[state];
}

function runMeter() {
  const bars = els.meter.children;
  const tick = () => {
    const reactor = preview.reactor;
    if (!reactor) return;
    // When the WebGL loop is not running (fallback), read the analyser here instead.
    const state = root.dataset.orb === 'webgl' ? reactor.state : reactor.read();
    for (let i = 0; i < bars.length; i++) {
      const threshold = (i + 0.5) / bars.length;
      bars[i].classList.toggle('on', state.level > threshold * 0.9);
    }
    meterFrame = requestAnimationFrame(tick);
  };
  meterFrame = requestAnimationFrame(tick);
}

async function startPreview() {
  setMicUi('requesting');
  setStatus(simulateVoice ? 'Starting a synthetic test voice. No microphone is used.' : 'Your browser will ask for microphone access.');
  try {
    const reactor = await preview.start({ simulate: simulateVoice });
    orb.setAudioSource(reactor);
    setMicUi('live');
    setStatus(simulateVoice
      ? 'Synthetic test voice driving the orb. No microphone in use.'
      : 'Listening. Speak or hum, then try louder. Nothing is recorded or saved.');
    runMeter();
  } catch (error) {
    orb.setAudioSource(null);
    setMicUi('error');
    setStatus(describeMicError(error));
  }
}

async function stopPreview(message = 'Stopped listening. No audio was kept.') {
  cancelAnimationFrame(meterFrame);
  orb.setAudioSource(null);
  await preview.stop();
  [...els.meter.children].forEach((bar) => bar.classList.remove('on'));
  setMicUi('idle');
  setStatus(message);
}

preview.onEnded = () => stopPreview('The microphone disconnected. No audio was kept.');
els.mic.addEventListener('click', () => (preview.active ? stopPreview() : startPreview()));
addEventListener('pagehide', () => preview.stop());
document.addEventListener('visibilitychange', () => {
  if (document.hidden && preview.active) stopPreview('Paused listening while the page was hidden. No audio was kept.');
});
setMicUi('idle');
if (root.dataset.orb === 'webgl') setStatus(defaultStatus());

// ---------- scroll ----------

let scrollQueued = false;
function onScroll() {
  if (scrollQueued) return;
  scrollQueued = true;
  requestAnimationFrame(() => {
    scrollQueued = false;
    const heroHeight = els.hero.offsetHeight || innerHeight;
    orb.setScroll(scrollY, scrollY / (heroHeight * 0.85));
    els.header.classList.toggle('is-scrolled', scrollY > 24);
  });
}
addEventListener('scroll', onScroll, { passive: true });
onScroll();

const revealTargets = document.querySelectorAll('[data-reveal]');
if ('IntersectionObserver' in window && !reducedQuery.matches) {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    });
  }, { rootMargin: '0px 0px -12% 0px' });
  revealTargets.forEach((el) => observer.observe(el));
} else {
  revealTargets.forEach((el) => el.classList.add('is-visible'));
}

// ---------- periodicity illustrations ----------
// Each bar is one illustrative vocal cycle: spacing = period, height = amplitude.
// These are drawings for explanation, not measurements.

function drawStrip(svg, { jitter = 0, shimmer = 0, seed = 1 }) {
  const rand = createRandom(seed);
  const width = 600;
  const height = 64;
  const base = 15;
  let x = 6;
  let bars = '';
  while (x < width - 6) {
    const amp = 0.78 * (1 + (rand() - 0.5) * 2 * shimmer);
    const h = Math.max(6, amp * (height - 8));
    bars += `<rect x="${x.toFixed(1)}" y="${((height - h) / 2).toFixed(1)}" width="1.6" height="${h.toFixed(1)}" rx="0.8"/>`;
    x += base * (1 + (rand() - 0.5) * 2 * jitter);
  }
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('preserveAspectRatio', 'none');
  svg.innerHTML = bars;
}

document.querySelectorAll('[data-strip]').forEach((svg, index) => {
  const kind = svg.dataset.strip;
  drawStrip(svg, {
    jitter: kind === 'jitter' ? 0.32 : 0,
    shimmer: kind === 'shimmer' ? 0.38 : 0,
    seed: 17 + index,
  });
});

// Exposed for the recorder in a later sprint and for automated checks.
window.neurovox = { orb, preview };
