// Shared page wiring for the particle orb: creation, pointer/touch taps, keyboard
// control on the orb's focus target, and reduced-motion changes.
// Used by the landing page and the recorder.

import { createOrb } from './orb.js';

const INTERACTIVE = 'a, button:not(#orbHit), input, select, textarea, label, summary';

export function setupOrb({ onStatus = () => {} } = {}) {
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const orb = createOrb({
    canvas: document.getElementById('orbCanvas'),
    fallbackCanvas: document.getElementById('orbFallback'),
    reducedMotion: reduced.matches,
    onStatus,
  });
  orb.start();
  reduced.addEventListener?.('change', (event) => orb.setReducedMotion(event.matches));

  addEventListener('pointermove', (event) => orb.pointerMove(event.clientX, event.clientY), { passive: true });
  addEventListener('pointerdown', (event) => {
    orb.pointerMove(event.clientX, event.clientY);
    if (event.target.closest(INTERACTIVE)) return;
    orb.pulseAt(event.clientX, event.clientY);
  }, { passive: true });
  document.addEventListener('pointerleave', () => orb.pointerLeave());

  const hit = document.getElementById('orbHit');
  hit?.addEventListener('keydown', (event) => {
    const moves = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    if (moves[event.key]) {
      event.preventDefault();
      orb.nudge(...moves[event.key]);
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      orb.pulseCenter();
    }
  });

  return orb;
}
