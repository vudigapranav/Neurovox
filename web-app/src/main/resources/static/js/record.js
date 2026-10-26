// Guided session: the website walks a new person through the whole process.
//
// Steps shown in the header:   About → Room → Microphone → Vowel → Syllables → Reading → Free speech → Analysis
// States:  loading → about → room → mic → ready → countdown → recording → review → uploading
//          → (rejected | upload-error | ready for the next task) … → analysing → results page
// Side paths: fatal (server unreachable), analysis-error, mic problems shown inside the mic step.
// An interrupted session can be resumed with record.html?session=<id>.

import { setupOrb } from './orb-page.js';
import { MicSession } from './recorder-audio.js';
import { getTasks, createSession, getSession, uploadRecording, analyseSession } from './api.js';
import { levels, envelope, NEAR_SILENT_PEAK } from './pcm.js';
import { TASK_COPY } from './tasks.js';
import { h, button, alert } from './ui.js';

const COUNTDOWN_SECONDS = 3;
const PHASES = [
  { id: 'about', name: 'About' },
  { id: 'room', name: 'Room' },
  { id: 'mic', name: 'Microphone' },
  { id: 'sustained_vowel', name: 'Vowel' },
  { id: 'pa_ta_ka', name: 'Syllables' },
  { id: 'reading_passage', name: 'Reading' },
  { id: 'free_speech', name: 'Free speech' },
  { id: 'analysis', name: 'Analysis' },
];

const root = document.documentElement;
root.classList.add('js');
const card = document.getElementById('recorderCard');
const stepsEl = document.getElementById('taskSteps');
const live = document.getElementById('recorderLive');
const params = new URLSearchParams(location.search);

const orb = setupOrb();
const mic = new MicSession();

const app = {
  state: 'loading',
  tasks: [],
  index: 0,
  session: null,
  take: null,
  takeLevels: null,
  saved: {},
  notice: '',
  error: '',
  rejection: null,
  micStatus: 'idle',   // idle | requesting | live | error
  micError: '',
  progress: 0,
  elapsed: 0,
  countdown: COUNTDOWN_SECONDS,
  understood: false,
};
window.neurovox = { orb, mic, app };

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

function currentPhase() {
  if (['about', 'room', 'mic'].includes(app.state)) return app.state;
  if (['analysing', 'analysis-error'].includes(app.state)) return 'analysis';
  if (app.state === 'loading' || app.state === 'fatal') return null;
  return currentTask()?.code;
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
  // The orb reacts to the microphone during the mic check and while a take is prepared and recorded.
  const listening = (app.state === 'mic' && app.micStatus === 'live') || ['countdown', 'recording'].includes(app.state);
  orb.setAudioSource(listening ? mic.reactor : null);
  if (app.state === 'mic' && app.micStatus === 'live') startMeter(); else stopMeter();
}

function renderSteps() {
  const phase = currentPhase();
  const index = PHASES.findIndex((p) => p.id === phase);
  stepsEl.hidden = phase === null;
  stepsEl.replaceChildren(...PHASES.map((p, i) => {
    const isTask = app.tasks.some((t) => t.code === p.id);
    const done = isTask ? Boolean(app.saved[p.id]) : i < index;
    const current = p.id === phase;
    return h('li', { class: `step${done ? ' is-done' : ''}${current ? ' is-current' : ''}`, 'aria-current': current ? 'step' : null },
      h('span', { class: 'step-dot', 'aria-hidden': 'true' }),
      h('span', { class: 'step-name', text: p.name }),
      h('span', { class: 'visually-hidden', text: done ? ' (done)' : current ? ' (current step)' : '' }));
  }));
  stepsEl.setAttribute('aria-label', index >= 0 ? `Step ${index + 1} of ${PHASES.length}: ${PHASES[index].name}` : 'Session progress');
}

function stepLabel() {
  const index = PHASES.findIndex((p) => p.id === currentPhase());
  return h('p', { class: 'eyebrow', text: `Step ${index + 1} of ${PHASES.length} · ${PHASES[index].name}` });
}

// ---------- views ----------

const views = {
  loading() {
    return [h('p', { class: 'eyebrow', text: 'Guided session' }), h('h1', { class: 'recorder-title', text: 'Loading…' })];
  },

  fatal() {
    return [
      h('p', { class: 'eyebrow', text: 'Connection' }),
      h('h1', { class: 'recorder-title', text: 'Could not reach Neurovox.' }),
      alert(app.error),
      h('ul', { class: 'recorder-notes' },
        h('li', { text: 'Check that the terminal running “java -jar target/neurovox.jar” is still open and shows “Neurovox is running”.' }),
        h('li', { text: 'If you stopped it, start it again, then press Try again.' })),
      h('div', { class: 'recorder-actions' }, button('Try again', () => location.reload(), { primary: true }),
        h('a', { class: 'text-link', href: 'index.html', text: 'Back to home' })),
    ];
  },

  about() {
    const check = h('input', { type: 'checkbox', id: 'understood', checked: app.understood,
      onchange: (e) => { app.understood = e.target.checked; document.getElementById('aboutContinue').disabled = !app.understood; } });
    return [
      stepLabel(),
      h('h1', { class: 'recorder-title', text: 'What Neurovox measures.' }),
      h('p', { class: 'recorder-lede', text: 'You will record four short voice tasks — about a minute of voice. Java code on this computer then measures pitch, how evenly your vocal folds repeat each cycle (jitter, shimmer) and how much noise is in the tone (HNR).' }),
      h('ul', { class: 'recorder-notes' },
        h('li', { text: 'Neurovox is a student research prototype. It does not diagnose Parkinson’s disease or any other condition, does not estimate a stage and gives no treatment advice.' }),
        h('li', { text: 'Your recordings and results stay on this computer. Nothing is uploaded anywhere.' }),
        h('li', { text: 'You can replay, redo or delete every recording.' })),
      h('label', { class: 'check-row', for: 'understood' }, check, h('span', { text: 'I understand this is a measurement tool, not a medical test.' })),
      app.notice ? alert(app.notice, 'notice') : null,
      h('div', { class: 'recorder-actions' },
        button('Continue to room setup', () => go('room', { notice: '' }), { primary: true, id: 'aboutContinue', disabled: !app.understood }),
        h('a', { class: 'text-link', href: 'index.html#guide', text: 'Read the full guide' })),
    ];
  },

  room() {
    return [
      stepLabel(),
      h('h1', { class: 'recorder-title', text: 'Prepare a quiet room.' }),
      h('ol', { class: 'prep-list' },
        h('li', {}, h('strong', { text: 'Quiet. ' }), 'Close windows and doors; switch off fans, music and the TV.'),
        h('li', {}, h('strong', { text: 'Sit upright. ' }), 'Keep the microphone about 20–30 cm (a hand-span or two) from your mouth, and keep that distance for every task.'),
        h('li', {}, h('strong', { text: 'One microphone. ' }), 'Use the same microphone each time you record, so sessions can be compared. A headset microphone is best; a laptop microphone works.'),
        h('li', {}, h('strong', { text: 'No headphones needed. ' }), 'Playback is quiet; you can use speakers or headphones.'),
        h('li', {}, h('strong', { text: 'Water. ' }), 'Have a sip of water nearby; take a comfortable breath before each task.')),
      h('div', { class: 'recorder-actions' },
        button('Continue to microphone check', () => go('mic', { notice: '' }), { primary: true, id: 'roomContinue' }),
        button('Back', () => go('about'))),
    ];
  },

  mic() {
    const status = {
      idle: null,
      requesting: alert('Your browser is asking whether this page may use the microphone. Choose “Allow”.', 'notice'),
      live: null,
      error: alert(app.micError),
    }[app.micStatus];
    const isLive = app.micStatus === 'live';
    return [
      stepLabel(),
      h('h1', { class: 'recorder-title', text: isLive ? 'Say “aah” to test.' : 'Allow the microphone.' }),
      h('p', { class: 'recorder-lede', text: isLive
        ? `Microphone connected (${(mic.sampleRate / 1000).toFixed(1)} kHz). Say “aah” at your normal loudness: the bar should reach the green zone and the orb should move. Nothing is saved yet.`
        : 'Neurovox needs your permission to hear the microphone. The browser will ask once; the recording only starts when you press Start on each task.' }),
      isLive ? h('div', { class: 'mic-meter', role: 'img', 'aria-label': 'Microphone level' },
        h('span', { class: 'mic-meter-fill', id: 'micMeterFill' }), h('span', { class: 'mic-meter-zone', 'aria-hidden': 'true' })) : null,
      isLive ? h('p', { class: 'tip', id: 'micMeterText', 'aria-live': 'polite', text: 'Waiting for sound…' }) : null,
      status,
      app.micStatus === 'error' ? micHelp() : null,
      app.notice ? alert(app.notice, 'notice') : null,
      h('div', { class: 'recorder-actions' },
        isLive
          ? button(app.session && Object.keys(app.saved).length ? 'Continue the session' : 'Continue to the voice tasks', startTasks, { primary: true, id: 'micContinue' })
          : button(app.micStatus === 'error' ? 'Try again' : 'Allow microphone', openMic, { primary: true, id: 'micAllow', disabled: app.micStatus === 'requesting' }),
        button('Back', () => { mic.close(); go('room', { micStatus: 'idle', notice: '' }); })),
    ];
  },

  ready() {
    const task = currentTask();
    const copy = TASK_COPY[task.code];
    const saved = app.saved[task.code];
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title', text: copy.title }),
      h('p', { class: 'recorder-lede', text: copy.instruction }),
      copy.passage ? h('blockquote', { class: 'passage', text: copy.passage }) : null,
      copy.prompts ? h('ul', { class: 'prompts' }, copy.prompts.map((p) => h('li', { text: p }))) : null,
      copy.cue ? h('p', { class: 'cue', 'aria-hidden': 'true', text: copy.cue }) : null,
      h('p', { class: 'tip', text: `${copy.tip} About ${task.targetSeconds} s; you may stop after ${task.minSeconds} s.` }),
      h('p', { class: 'task-use', text: copy.use }),
      saved ? alert(`Saved already (${saved.durationSeconds.toFixed(1)} s). Record again to replace it, or continue.`, 'notice') : null,
      app.notice ? alert(app.notice, 'notice') : null,
      h('div', { class: 'recorder-actions' },
        button(saved ? `Record again · ${task.targetSeconds} s` : `Start recording · ${task.targetSeconds} s`, startCountdown, { primary: !saved, id: 'recordButton' }),
        saved ? button(nextLabel(), advance, { primary: true, id: 'skipButton' }) : null,
        button('Back', back, { id: 'backButton' })),
    ];
  },

  countdown() {
    const task = currentTask();
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title', text: TASK_COPY[task.code].title }),
      h('p', { class: 'countdown', 'aria-hidden': 'true', text: String(app.countdown) }),
      h('p', { class: 'recorder-lede', text: 'Get ready — take a breath.' }),
      reminder(task),
      h('div', { class: 'recorder-actions' }, button('Cancel', () => cancelTake(''))),
    ];
  },

  recording() {
    const task = currentTask();
    const remaining = Math.max(0, Math.ceil(task.targetSeconds * (1 - app.progress)));
    const canStop = app.elapsed >= task.minSeconds;
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title' }, h('span', { class: 'rec-dot', 'aria-hidden': 'true' }), 'Recording'),
      h('div', { class: 'timer' },
        h('span', { class: 'timer-value', id: 'timerValue', text: `${remaining}` }),
        h('span', { class: 'timer-unit', text: 's left' })),
      h('div', { class: 'progress', role: 'progressbar', 'aria-label': 'Recording progress', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(Math.round(app.progress * 100)) },
        h('span', { class: 'progress-fill', id: 'progressFill', style: `transform: scaleX(${app.progress})` })),
      reminder(task),
      h('div', { class: 'recorder-actions' },
        button(canStop ? 'Stop recording' : `Stop (after ${task.minSeconds} s)`, stopEarly, { id: 'stopButton', disabled: !canStop }),
        button('Cancel take', () => cancelTake(''), { id: 'cancelButton' })),
    ];
  },

  review() {
    const task = currentTask();
    const seconds = app.take.length / mic.sampleRate;
    const quiet = app.takeLevels.peak < NEAR_SILENT_PEAK;
    const loud = app.takeLevels.peak > 0.99;
    const canvas = h('canvas', { class: 'waveform', id: 'waveform', role: 'img', 'aria-label': `Waveform of your ${TASK_COPY[task.code].title.toLowerCase()} recording` });
    requestAnimationFrame(() => drawWaveform(canvas, app.take));
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title', text: 'Review this recording' }),
      h('p', { class: 'recorder-lede', text: `${seconds.toFixed(1)} s recorded at ${(mic.sampleRate / 1000).toFixed(1)} kHz. Play it back. If it sounds right, save it; Java will check its quality. Otherwise record again.` }),
      canvas,
      quiet ? alert('We could hardly hear anything in this recording. Move closer to the microphone, check the right microphone is selected, and record again.', 'notice') : null,
      loud ? alert('Some peaks hit the microphone’s limit. If saving fails, move a little further away and record again.', 'notice') : null,
      h('div', { class: 'recorder-actions' },
        button('Save this take', saveTake, { primary: true, id: 'saveButton' }),
        button('Play', togglePlayback, { id: 'playButton' }),
        button('Record again', retake, { id: 'retakeButton' })),
    ];
  },

  uploading() {
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title', text: 'Saving…' }),
      h('p', { class: 'recorder-lede', text: 'The local Java server is checking loudness, clipping and voicing before it stores the take.' }),
    ];
  },

  rejected() {
    const quality = app.rejection?.quality;
    const failures = (quality?.checks || []).filter((c) => c.status === 'fail');
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title', text: 'This take can’t be used.' }),
      h('p', { class: 'recorder-lede', text: 'The quality check found a problem, so the take was not saved. Here is what to change:' }),
      ...failures.map((c) => h('div', { class: 'quality-issue' },
        h('p', { class: 'quality-issue-what', text: c.message }),
        h('p', { class: 'quality-issue-fix', text: c.recovery }))),
      failures.length ? null : alert(app.error),
      h('div', { class: 'recorder-actions' },
        button('Record again', retake, { primary: true, id: 'retakeButton' }),
        button('Play the take', togglePlayback, { id: 'playButton' })),
    ];
  },

  'upload-error'() {
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title', text: 'This take was not saved.' }),
      alert(app.error),
      h('p', { class: 'tip', text: 'Your take is still here. If the server stopped, start it again, then press Try saving again.' }),
      h('div', { class: 'recorder-actions' },
        button('Try saving again', saveTake, { primary: true }),
        button('Record again', retake)),
    ];
  },

  analysing() {
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title', text: 'Java is analysing your voice…' }),
      h('p', { class: 'recorder-lede', text: 'This usually takes a few seconds. Everything runs on this computer:' }),
      h('ol', { class: 'pipeline-list' },
        h('li', { text: 'Each take is resampled to 16 kHz with an anti-aliasing filter and its DC offset is removed.' }),
        h('li', { text: 'Pitch is tracked every 10 ms with autocorrelation.' }),
        h('li', { text: 'In the steady part of the vowel, every vocal-fold cycle is located.' }),
        h('li', { text: 'From those cycles: 16 measurements (pitch, jitter, shimmer, noise). From all tasks: spectral shape (MFCC) and speech timing.' }),
        h('li', { text: 'Results are saved in the local SQLite database.' })),
      h('p', { class: 'loading-dots', 'aria-hidden': 'true', text: '• • •' }),
    ];
  },

  'analysis-error'() {
    return [
      stepLabel(),
      h('h2', { class: 'recorder-title', text: 'The analysis did not finish.' }),
      alert(app.error),
      h('p', { class: 'tip', text: 'Your four recordings are saved. You can try the analysis again now, or later from History.' }),
      h('div', { class: 'recorder-actions' },
        button('Try the analysis again', runAnalysis, { primary: true }),
        h('a', { class: 'text-link', href: 'history.html', text: 'Open history' })),
    ];
  },
};

function nextLabel() {
  const next = app.tasks.findIndex((t, i) => i > app.index && !app.saved[t.code]);
  if (next !== -1) return `Continue to ${TASK_COPY[app.tasks[next].code].title.toLowerCase()}`;
  return firstUnsavedIndex() === -1 ? 'Continue to analysis' : 'Continue';
}

function reminder(task) {
  const copy = TASK_COPY[task.code];
  if (copy.passage) return h('blockquote', { class: 'passage passage-live', text: copy.passage });
  if (copy.prompts) return h('ul', { class: 'prompts' }, copy.prompts.map((p) => h('li', { text: p })));
  return h('p', { class: 'cue cue-live', text: copy.cue });
}

function micHelp() {
  return h('ul', { class: 'recorder-notes' },
    h('li', { text: 'Chrome / Edge: click the icon at the left of the address bar → Site settings → Microphone → Allow, then press Try again.' }),
    h('li', { text: 'Safari: Safari menu → Settings → Websites → Microphone → localhost → Allow.' }),
    h('li', { text: 'Firefox: click the microphone icon in the address bar and remove the block.' }),
    h('li', { text: 'macOS: System Settings → Privacy & Security → Microphone → turn on your browser. Windows: Settings → Privacy → Microphone → allow desktop apps.' }));
}

// ---------- microphone ----------

function micErrorMessage(error) {
  switch (error?.name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return 'Microphone access is blocked for this page. Allow it using the steps below, then press Try again.';
    case 'NotFoundError':
    case 'OverconstrainedError':
      return 'No microphone was found. Plug one in or switch on the built-in microphone, then press Try again.';
    case 'NotReadableError':
      return 'The microphone is busy in another app (for example a video call). Close that app, then press Try again.';
    case 'InsecureContext':
      return 'The browser only allows the microphone on http://localhost. Open Neurovox at http://localhost:8080 (not as a file).';
    case 'Unsupported':
      return 'This browser cannot record audio here. Use a current version of Chrome, Edge, Firefox or Safari.';
    default:
      return 'The microphone could not start. Reconnect it and press Try again.';
  }
}

async function openMic() {
  go('mic', { micStatus: 'requesting', micError: '' });
  await mic.close();
  try {
    await mic.open();
  } catch (error) {
    await mic.close();
    go('mic', { micStatus: 'error', micError: micErrorMessage(error) });
    return;
  }
  mic.onInterrupted = () => {
    stopEverything();
    go('mic', { micStatus: 'error', micError: 'The microphone was disconnected. Reconnect it and press Try again. Saved tasks are kept.' });
  };
  go('mic', { micStatus: 'live' });
  announce('Microphone connected. Say aah to test the level.');
}

let meterFrame = 0;
const meterBuffer = new Float32Array(2048);

function startMeter() {
  cancelAnimationFrame(meterFrame);
  const tick = () => {
    const fill = document.getElementById('micMeterFill');
    const text = document.getElementById('micMeterText');
    if (!fill || !mic.reactor) return;
    mic.reactor.analyser.getFloatTimeDomainData(meterBuffer);
    let sum = 0;
    let peak = 0;
    for (const v of meterBuffer) { sum += v * v; peak = Math.max(peak, Math.abs(v)); }
    const db = 20 * Math.log10(Math.sqrt(sum / meterBuffer.length) || 1e-6);
    const position = Math.max(0, Math.min(1, (db + 60) / 60));   // −60 dBFS … 0 dBFS
    fill.style.transform = `scaleX(${position})`;
    const message = peak > 0.98 ? 'Too loud — move a little further away.'
      : db > -45 ? 'Good level.'
        : db > -55 ? 'A little quiet — move closer or speak up.' : 'Waiting for sound…';
    if (text.textContent !== message) text.textContent = message;
    meterFrame = requestAnimationFrame(tick);
  };
  meterFrame = requestAnimationFrame(tick);
}

function stopMeter() {
  cancelAnimationFrame(meterFrame);
}

// ---------- session and task flow ----------

async function startTasks() {
  if (!app.session) {
    try {
      const response = await createSession();
      app.session = response.session;
      history.replaceState(null, '', `record.html?session=${response.session.id}`);
    } catch (error) {
      go('mic', { micStatus: 'live', notice: error.message });
      return;
    }
  }
  const next = firstUnsavedIndex();
  if (next === -1) {
    runAnalysis();
    return;
  }
  go('ready', { index: next, notice: '' });
  announce(`Task ${next + 1} of ${app.tasks.length}: ${TASK_COPY[app.tasks[next].code].title}.`);
}

function back() {
  if (app.index === 0) {
    go('mic', { notice: '' });
  } else {
    go('ready', { index: app.index - 1, notice: '' });
  }
}

function advance() {
  const next = app.tasks.findIndex((t, i) => i > app.index && !app.saved[t.code]);
  const target = next !== -1 ? next : firstUnsavedIndex();
  if (target === -1) {
    runAnalysis();
  } else {
    go('ready', { index: target, notice: '' });
  }
}

let countdownTimer = 0;

function startCountdown() {
  go('countdown', { countdown: COUNTDOWN_SECONDS, notice: '' });
  announce(`Recording starts in ${COUNTDOWN_SECONDS} seconds.`);
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
  go('recording', { progress: 0, elapsed: 0 });
  announce(`Recording ${TASK_COPY[task.code].title}. ${task.targetSeconds} seconds.`);
  try {
    const take = await mic.record(task.targetSeconds, { onProgress: updateProgress });
    go('review', { take, takeLevels: levels(take) });
    announce('Recording finished. Review this recording.');
  } catch (error) {
    if (error.name !== 'AbortError') {
      go('ready', { notice: 'Recording stopped unexpectedly. Please record this task again.' });
    }
  }
}

function updateProgress(fraction) {
  app.progress = fraction;
  const task = currentTask();
  const wasStoppable = app.elapsed >= task.minSeconds;
  app.elapsed = fraction * task.targetSeconds;
  const fill = document.getElementById('progressFill');
  const value = document.getElementById('timerValue');
  if (fill) {
    fill.style.transform = `scaleX(${fraction})`;
    fill.parentElement.setAttribute('aria-valuenow', String(Math.round(fraction * 100)));
  }
  if (value) value.textContent = String(Math.max(0, Math.ceil(task.targetSeconds * (1 - fraction))));
  const stop = document.getElementById('stopButton');
  if (stop && !wasStoppable && app.elapsed >= task.minSeconds) {
    stop.disabled = false;
    stop.textContent = 'Stop recording';
  }
}

function stopEarly() {
  mic.stopEarly();
}

function cancelTake(notice = '') {
  clearInterval(countdownTimer);
  mic.cancel();
  go('ready', { take: null, notice: typeof notice === 'string' ? notice : '' });
}

function retake() {
  mic.stopPlayback();
  go('ready', { take: null, notice: '', rejection: null });
}

function togglePlayback() {
  const btn = document.getElementById('playButton');
  if (mic.playback) {
    mic.stopPlayback();
    return;
  }
  const reactor = mic.play(app.take, () => {
    orb.setAudioSource(null);
    if (btn) btn.textContent = btn.id === 'playButton' && app.state === 'rejected' ? 'Play the take' : 'Play';
  });
  orb.setAudioSource(reactor);
  if (btn) btn.textContent = 'Stop playback';
}

async function saveTake() {
  mic.stopPlayback();
  const task = currentTask();
  go('uploading');
  try {
    const response = await uploadRecording(app.session.id, task.code, mic.sampleRate, app.take);
    app.saved[task.code] = response.recording;
    app.session = response.session;
    const warnings = (response.quality?.checks || []).filter((c) => c.status === 'warn');
    const note = warnings.length ? `Saved, with a note: ${warnings.map((c) => c.message).join(' ')}` : '';
    announce(`${TASK_COPY[task.code].title} saved.`);
    const next = app.tasks.findIndex((t, i) => i > app.index && !app.saved[t.code]);
    const target = next !== -1 ? next : firstUnsavedIndex();
    if (target === -1) {
      runAnalysis();
    } else {
      go('ready', { index: target, take: null, notice: note });
    }
  } catch (error) {
    if (error.code === 'audio_quality') {
      go('rejected', { rejection: error.details, error: error.message });
      announce('This take could not be used. ' + error.message);
    } else {
      go('upload-error', { error: error.message });
    }
  }
}

async function runAnalysis() {
  await mic.close();
  go('analysing', { take: null });
  announce('All four tasks saved. Java is analysing the recordings.');
  try {
    await analyseSession(app.session.id);
    location.href = `results.html?session=${app.session.id}&new=1`;
  } catch (error) {
    go('analysis-error', { error: error.message });
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
  if (app.state === 'analysing') return;
  const unsaved = app.take && ['review', 'upload-error', 'rejected'].includes(app.state);
  const midSession = app.session && Object.keys(app.saved).length > 0 && firstUnsavedIndex() !== -1;
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
    const resumeId = params.get('session');
    if (resumeId) {
      const { session } = await getSession(resumeId);
      app.session = session;
      for (const r of session.recordings) app.saved[r.task] = r;
      app.understood = true;
      if (session.status === 'complete') {
        go('analysing');
        runAnalysis();
        return;
      }
      go('mic', { notice: `Continuing session ${session.id}: ${session.recordings.length} of ${tasks.length} tasks saved. Allow the microphone to carry on.` });
      return;
    }
    go('about');
  } catch (error) {
    if (error.status === 404) {
      history.replaceState(null, '', 'record.html');
      go('about', { notice: 'That session no longer exists (it may have been deleted). You can start a new one.' });
      return;
    }
    go('fatal', { error: error.message });
  }
})();
