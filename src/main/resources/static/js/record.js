// Guided four-task recorder.
//
// States:  intro -> requesting -> starting -> ready -> countdown -> recording -> review
//          -> uploading -> ready (next task) ... -> complete
// Side paths: mic-error (denied / missing / busy), upload-error, fatal (server unreachable),
// and an interruption notice when a take is cancelled because the page was hidden or the mic vanished.

import { setupOrb } from './orb-page.js';
import { MicSession } from './recorder-audio.js';
import { describeMicError } from './voice-input.js';
import { getTasks, createSession, uploadRecording } from './api.js';
import { levels, envelope, NEAR_SILENT_PEAK } from './pcm.js';
import { TASK_COPY } from './tasks.js';

const COUNTDOWN_SECONDS = 3;

const root = document.documentElement;
root.classList.add('js');
const card = document.getElementById('recorderCard');
const stepsEl = document.getElementById('taskSteps');
const live = document.getElementById('recorderLive');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');

const orb = setupOrb();
const mic = new MicSession();

const app = {
  state: 'intro',
  tasks: [],
  index: 0,
  session: null,
  take: null,
  takeLevels: null,
  saved: {},
  notice: '',
  error: '',
  progress: 0,
  countdown: COUNTDOWN_SECONDS,
};
window.neurovox = { orb, mic, app };

// ---------- small DOM helper (text is always set with textContent) ----------

function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'text') el.textContent = value;
    else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

function button(label, onclick, { primary = false, id, disabled = false } = {}) {
  return h('button', { class: primary ? 'button button-primary' : 'button button-quiet', type: 'button', id, disabled, onclick }, label);
}

function announce(text) {
  live.textContent = text;
}

// ---------- state changes ----------

function go(state, patch = {}) {
  Object.assign(app, patch, { state });
  root.dataset.recorder = state;
  render();
}

function currentTask() {
  return app.tasks[app.index];
}

function render() {
  renderSteps();
  // Views return optional parts as null; replaceChildren would print those as the text "null".
  card.replaceChildren(...views[app.state]().filter(Boolean));
  const heading = card.querySelector('h1, h2');
  if (heading && !['countdown', 'recording'].includes(app.state)) {
    heading.setAttribute('tabindex', '-1');
    heading.focus({ preventScroll: true });
  }
  // The orb listens to the microphone while a task is being prepared and recorded.
  orb.setAudioSource(['countdown', 'recording'].includes(app.state) ? mic.reactor : null);
}

function renderSteps() {
  const inSession = !['intro', 'requesting', 'mic-error', 'starting', 'fatal'].includes(app.state);
  stepsEl.hidden = !inSession || app.tasks.length === 0;
  stepsEl.replaceChildren(...app.tasks.map((task, i) => {
    const done = Boolean(app.saved[task.code]);
    const current = i === app.index && app.state !== 'complete';
    return h('li', { class: `step${done ? ' is-done' : ''}${current ? ' is-current' : ''}`, 'aria-current': current ? 'step' : null },
      h('span', { class: 'step-dot', 'aria-hidden': 'true' }),
      h('span', { class: 'step-name', text: TASK_COPY[task.code]?.title || task.label }),
      h('span', { class: 'visually-hidden', text: done ? ' (saved)' : current ? ' (current)' : '' }));
  }));
}

// ---------- views ----------

const views = {
  intro() {
    return [
      h('p', { class: 'eyebrow', text: 'Guided session' }),
      h('h1', { class: 'recorder-title', text: 'Four short voice tasks.' }),
      h('p', { class: 'recorder-lede', text: 'You will hold a vowel, repeat syllables, read a short passage and speak freely — about a minute of voice in total. Each take can be replayed and redone before it is saved.' }),
      h('ol', { class: 'intro-tasks' }, app.tasks.map((t) => h('li', {},
        h('span', { class: 'intro-name', text: TASK_COPY[t.code]?.title || t.label }),
        h('span', { class: 'intro-time', text: `${t.targetSeconds} s` })))),
      h('ul', { class: 'recorder-notes' },
        h('li', { text: 'Find a quiet room and sit about an arm’s length from the microphone.' }),
        h('li', { text: 'Recordings are saved on this computer only, by the local Neurovox server.' })),
      h('div', { class: 'recorder-actions' }, button('Allow microphone and begin', begin, { primary: true, id: 'beginButton', disabled: app.tasks.length === 0 })),
      app.error ? alert(app.error) : null,
    ];
  },

  requesting() {
    return [
      h('p', { class: 'eyebrow', text: 'Microphone' }),
      h('h1', { class: 'recorder-title', text: 'Waiting for permission…' }),
      h('p', { class: 'recorder-lede', text: 'Your browser is asking whether Neurovox may use the microphone. Choose “Allow” to continue.' }),
    ];
  },

  'mic-error'() {
    return [
      h('p', { class: 'eyebrow', text: 'Microphone' }),
      h('h1', { class: 'recorder-title', text: 'The microphone is not available.' }),
      alert(app.error),
      h('ul', { class: 'recorder-notes' },
        h('li', { text: 'If you blocked access, use the icon in the address bar to allow the microphone for this page, then try again.' }),
        h('li', { text: 'Check that a microphone is connected and not in use by another app.' })),
      h('div', { class: 'recorder-actions' }, button('Try again', begin, { primary: true }), h('a', { class: 'text-link', href: 'index.html', text: 'Back to home' })),
    ];
  },

  starting() {
    return [
      h('p', { class: 'eyebrow', text: 'Getting ready' }),
      h('h1', { class: 'recorder-title', text: 'Starting your session…' }),
    ];
  },

  fatal() {
    return [
      h('p', { class: 'eyebrow', text: 'Connection' }),
      h('h1', { class: 'recorder-title', text: 'Could not reach Neurovox.' }),
      alert(app.error),
      h('div', { class: 'recorder-actions' }, button('Try again', () => location.reload(), { primary: true })),
    ];
  },

  ready() {
    const task = currentTask();
    const copy = TASK_COPY[task.code];
    return [
      taskHeader(task),
      h('h2', { class: 'recorder-title', text: copy.title }),
      h('p', { class: 'recorder-lede', text: copy.instruction }),
      copy.passage ? h('blockquote', { class: 'passage', text: copy.passage }) : null,
      copy.prompts ? h('ul', { class: 'prompts' }, copy.prompts.map((p) => h('li', { text: p }))) : null,
      copy.cue ? h('p', { class: 'cue', 'aria-hidden': 'true', text: copy.cue }) : null,
      h('p', { class: 'tip', text: copy.tip }),
      app.notice ? alert(app.notice, 'notice') : null,
      h('div', { class: 'recorder-actions' },
        button(`Start recording · ${task.targetSeconds} s`, startCountdown, { primary: true, id: 'recordButton' })),
    ];
  },

  countdown() {
    const task = currentTask();
    return [
      taskHeader(task),
      h('h2', { class: 'recorder-title', text: TASK_COPY[task.code].title }),
      h('p', { class: 'countdown', 'aria-hidden': 'true', text: String(app.countdown) }),
      h('p', { class: 'recorder-lede', text: 'Get ready…' }),
      reminder(task),
      h('div', { class: 'recorder-actions' }, button('Cancel', cancelTake)),
    ];
  },

  recording() {
    const task = currentTask();
    const remaining = Math.max(0, Math.ceil(task.targetSeconds * (1 - app.progress)));
    return [
      taskHeader(task),
      h('h2', { class: 'recorder-title' }, h('span', { class: 'rec-dot', 'aria-hidden': 'true' }), 'Recording'),
      h('div', { class: 'timer' },
        h('span', { class: 'timer-value', id: 'timerValue', text: `${remaining}` }),
        h('span', { class: 'timer-unit', text: 's left' })),
      h('div', { class: 'progress', role: 'progressbar', 'aria-label': 'Recording progress', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(Math.round(app.progress * 100)) },
        h('span', { class: 'progress-fill', id: 'progressFill', style: `transform: scaleX(${app.progress})` })),
      reminder(task),
      h('div', { class: 'recorder-actions' }, button('Cancel take', cancelTake)),
    ];
  },

  review() {
    const task = currentTask();
    const seconds = app.take.length / mic.sampleRate;
    const quiet = app.takeLevels.peak < NEAR_SILENT_PEAK;
    const canvas = h('canvas', { class: 'waveform', id: 'waveform', role: 'img', 'aria-label': `Waveform of your ${TASK_COPY[task.code].title.toLowerCase()} take` });
    requestAnimationFrame(() => drawWaveform(canvas, app.take));
    return [
      taskHeader(task),
      h('h2', { class: 'recorder-title', text: 'Listen back' }),
      h('p', { class: 'recorder-lede', text: `${seconds.toFixed(1)} s recorded at ${(mic.sampleRate / 1000).toFixed(1)} kHz. Play it back, then save it or record again.` }),
      canvas,
      quiet ? alert('We could hardly hear anything in this take. Check the microphone and record again, a little closer.', 'notice') : null,
      h('div', { class: 'recorder-actions' },
        button('Save and continue', saveTake, { primary: true, id: 'saveButton' }),
        button('Play', togglePlayback, { id: 'playButton' }),
        button('Record again', retake, { id: 'retakeButton' })),
    ];
  },

  uploading() {
    const task = currentTask();
    return [
      taskHeader(task),
      h('h2', { class: 'recorder-title', text: 'Saving…' }),
      h('p', { class: 'recorder-lede', text: 'Sending the take to the local Neurovox server.' }),
    ];
  },

  'upload-error'() {
    const task = currentTask();
    return [
      taskHeader(task),
      h('h2', { class: 'recorder-title', text: 'This take was not saved.' }),
      alert(app.error),
      h('div', { class: 'recorder-actions' },
        button('Try saving again', saveTake, { primary: true }),
        button('Record again', retake)),
    ];
  },

  complete() {
    const recordings = app.session?.recordings || [];
    return [
      h('p', { class: 'eyebrow', text: 'Session saved' }),
      h('h1', { class: 'recorder-title', text: 'All four tasks recorded.' }),
      h('p', { class: 'recorder-lede', text: `Session ${app.session.id} is stored on this computer. Acoustic analysis and results arrive in a later version of Neurovox.` }),
      h('ul', { class: 'saved-list' }, recordings.map((r) => h('li', {},
        h('span', { class: 'saved-name', text: TASK_COPY[r.task]?.title || r.task }),
        h('span', { class: 'saved-meta', text: `${r.durationSeconds.toFixed(1)} s · ${(r.sampleRateHz / 1000).toFixed(1)} kHz` })))),
      h('p', { class: 'recorder-disclaimer', text: 'Neurovox is a research prototype. It does not diagnose any condition.' }),
      h('div', { class: 'recorder-actions' },
        h('a', { class: 'button button-primary', href: 'record.html', text: 'Record another session' }),
        h('a', { class: 'text-link', href: 'index.html', text: 'Back to home' })),
    ];
  },
};

function taskHeader(task) {
  return h('p', { class: 'eyebrow', text: `Task ${task.order} of ${app.tasks.length}` });
}

function reminder(task) {
  const copy = TASK_COPY[task.code];
  if (copy.passage) return h('blockquote', { class: 'passage passage-live', text: copy.passage });
  if (copy.prompts) return h('ul', { class: 'prompts' }, copy.prompts.map((p) => h('li', { text: p })));
  return h('p', { class: 'cue cue-live', text: copy.cue });
}

function alert(text, kind = 'error') {
  return h('p', { class: `alert alert-${kind}`, role: kind === 'error' ? 'alert' : 'status', text });
}

// ---------- actions ----------

async function begin() {
  go('requesting', { error: '' });
  await mic.close();
  try {
    await mic.open();
  } catch (error) {
    await mic.close();
    go('mic-error', { error: describeMicError(error) });
    return;
  }
  mic.onInterrupted = () => {
    stopEverything();
    go('mic-error', { error: 'The microphone was disconnected. Reconnect it and try again; saved tasks are kept.' });
  };
  if (app.session) {
    // Reconnected after an interruption: continue the same session at the first unsaved task.
    go('ready', { index: firstUnsavedIndex(), notice: 'Microphone reconnected. Your saved tasks are kept.' });
    return;
  }
  go('starting');
  try {
    const response = await createSession();
    go('ready', { session: response.session, tasks: response.tasks, index: 0, notice: '' });
    announce(`Session started. Task 1 of ${response.tasks.length}.`);
  } catch (error) {
    await mic.close();
    go('fatal', { error: error.message });
  }
}

let countdownTimer = 0;

function startCountdown() {
  if (reducedMotion.matches) {
    // Still count down, but announce it rather than relying on a big animated number.
    announce('Recording starts in 3 seconds.');
  }
  go('countdown', { countdown: COUNTDOWN_SECONDS, notice: '' });
  announce(`${COUNTDOWN_SECONDS}`);
  countdownTimer = setInterval(() => {
    app.countdown -= 1;
    if (app.countdown <= 0) {
      clearInterval(countdownTimer);
      startRecording();
    } else {
      const el = card.querySelector('.countdown');
      if (el) el.textContent = String(app.countdown);
      announce(String(app.countdown));
    }
  }, 1000);
}

async function startRecording() {
  const task = currentTask();
  go('recording', { progress: 0 });
  announce(`Recording ${TASK_COPY[task.code].title}. ${task.targetSeconds} seconds.`);
  try {
    const take = await mic.record(task.targetSeconds, { onProgress: updateProgress });
    go('review', { take, takeLevels: levels(take) });
    announce('Recording finished. Review your take.');
  } catch (error) {
    if (error.name !== 'AbortError') {
      go('ready', { notice: 'Recording stopped unexpectedly. Please record this task again.' });
    }
  }
}

function updateProgress(fraction) {
  app.progress = fraction;
  const task = currentTask();
  const fill = document.getElementById('progressFill');
  const value = document.getElementById('timerValue');
  if (fill) {
    fill.style.transform = `scaleX(${fraction})`;
    fill.parentElement.setAttribute('aria-valuenow', String(Math.round(fraction * 100)));
  }
  if (value) value.textContent = String(Math.max(0, Math.ceil(task.targetSeconds * (1 - fraction))));
}

function cancelTake(notice = '') {
  clearInterval(countdownTimer);
  mic.cancel();
  go('ready', { take: null, notice: typeof notice === 'string' ? notice : '' });
}

function retake() {
  mic.stopPlayback();
  go('ready', { take: null, notice: '' });
}

function togglePlayback() {
  const btn = document.getElementById('playButton');
  if (mic.playback) {
    mic.stopPlayback();
    return;
  }
  const reactor = mic.play(app.take, () => {
    orb.setAudioSource(null);
    if (btn) btn.textContent = 'Play';
  });
  orb.setAudioSource(reactor);
  if (btn) btn.textContent = 'Stop';
}

async function saveTake() {
  mic.stopPlayback();
  const task = currentTask();
  go('uploading');
  try {
    const response = await uploadRecording(app.session.id, task.code, mic.sampleRate, app.take);
    app.saved[task.code] = response.recording;
    app.session = response.session;
    announce(`${TASK_COPY[task.code].title} saved.`);
    const next = firstUnsavedIndex();
    if (next === -1) {
      await mic.close();
      go('complete', { take: null });
      announce('All four tasks saved. Session complete.');
    } else {
      go('ready', { index: next, take: null, notice: '' });
    }
  } catch (error) {
    go('upload-error', { error: error.message });
  }
}

function firstUnsavedIndex() {
  return app.tasks.findIndex((t) => !app.saved[t.code]);
}

function stopEverything() {
  clearInterval(countdownTimer);
  mic.cancel();
  mic.stopPlayback();
}

// ---------- interruptions ----------

document.addEventListener('visibilitychange', () => {
  if (document.hidden && ['countdown', 'recording'].includes(app.state)) {
    cancelTake('Recording stopped because the page was hidden. Please record this task again.');
  }
});

addEventListener('beforeunload', (event) => {
  const unsaved = app.take && ['review', 'upload-error'].includes(app.state);
  const midSession = app.session && app.state !== 'complete' && Object.keys(app.saved).length > 0;
  if (unsaved || midSession) {
    event.preventDefault();
    event.returnValue = '';
  }
});
addEventListener('pagehide', () => mic.close());

// ---------- waveform ----------

function drawWaveform(canvas, samples) {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const width = canvas.clientWidth || 480;
  const height = canvas.clientHeight || 96;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.scale(dpr, dpr);
  const columns = Math.max(1, Math.floor(width / 3));
  const env = envelope(samples, columns);
  const peak = Math.max(0.05, levels(samples).peak);
  const mid = height / 2;
  const gradient = ctx.createLinearGradient(0, 0, width, 0);
  gradient.addColorStop(0, '#a77bff');
  gradient.addColorStop(1, '#ff8fd6');
  ctx.fillStyle = gradient;
  for (let i = 0; i < columns; i++) {
    const top = (env[i * 2 + 1] / peak) * (mid - 2);
    const bottom = (-env[i * 2] / peak) * (mid - 2);
    ctx.fillRect(i * 3, mid - top, 1.6, Math.max(1, top + bottom));
  }
}

// ---------- start ----------

(async function init() {
  try {
    const { tasks } = await getTasks();
    app.tasks = tasks;
    go('intro');
  } catch (error) {
    go('fatal', { error: error.message });
  }
})();
