// Results page: one analysed session, explained in plain language.
// Sections: how to read · recording quality · vowel measurements (16 + extras) · other tasks (descriptive)
// · spectral shape · research indicator status · actions (history, new session, WAV, delete).

import { getReport, analyseSession, listSessions, getModel, deleteSession, wavUrl } from './api.js';
import { h, button, alert, formatDate, confirmDialog } from './ui.js';
import { display, displayText, TASK_TITLES } from './features.js';
import { drawBars } from './chart.js';

document.documentElement.classList.add('js');
const host = document.getElementById('report');
const params = new URLSearchParams(location.search);
const sessionId = params.get('session');

function show(...children) {
  host.replaceChildren(...children.flat().filter(Boolean));
  const heading = host.querySelector('h1');
  heading?.setAttribute('tabindex', '-1');
  heading?.focus({ preventScroll: true });
}

function problem(title, message, ...actions) {
  show(h('p', { class: 'eyebrow', text: 'Session report' }), h('h1', { class: 'report-title', text: title }),
    alert(message), h('div', { class: 'recorder-actions' }, ...actions));
}

async function load() {
  if (!sessionId) {
    problem('Which session?', 'No session was chosen. Open one from your history.',
      h('a', { class: 'button button-primary', href: 'history.html', text: 'Open history' }));
    return;
  }
  let report;
  try {
    report = (await getReport(sessionId)).report;
  } catch (error) {
    if (error.code === 'not_analysed') {
      problem('Not analysed yet.', error.message,
        button('Run the analysis now', runAnalysis, { primary: true }),
        h('a', { class: 'button button-quiet', href: `record.html?session=${encodeURIComponent(sessionId)}`, text: 'Continue recording' }));
      return;
    }
    if (error.status === 404) {
      problem('Session not found.', 'This session does not exist on this computer. It may have been deleted.',
        h('a', { class: 'button button-primary', href: 'history.html', text: 'Open history' }));
      return;
    }
    problem('Could not load the report.', error.message, button('Try again', load, { primary: true }));
    return;
  }
  const [history, model] = await Promise.all([listSessions().catch(() => null), getModel().catch(() => null)]);
  render(report, history, model);
}

async function runAnalysis() {
  show(h('p', { class: 'eyebrow', text: 'Session report' }), h('h1', { class: 'report-title', text: 'Analysing…' }),
    h('p', { class: 'report-lede', text: 'Java is resampling, tracking pitch and measuring each recording on this computer.' }));
  try {
    await analyseSession(sessionId);
    load();
  } catch (error) {
    problem('The analysis did not finish.', error.message, button('Try again', runAnalysis, { primary: true }));
  }
}

/** The vowel values of the most recent earlier analysed session, for "since last session". */
function previousValues(history, report) {
  if (!history) return null;
  const started = report.session.startedAt;
  const earlier = history.sessions.filter((s) => s.analysed && s.id !== report.session.id && s.startedAt < started);
  return earlier.length ? { session: earlier[0], values: earlier[0].vowel } : null;
}

function render(report, history, model) {
  const tasks = Object.fromEntries(report.tasks.map((t) => [t.task, t]));
  const vowel = tasks.sustained_vowel;
  const previous = previousValues(history, report);
  show(
    h('p', { class: 'eyebrow', text: `Session ${report.session.id} · ${formatDate(report.session.startedAt)}` }),
    h('h1', { class: 'report-title', text: 'Your voice measurements' }),
    params.get('new') ? alert('Analysis complete. Your four recordings and these results are saved on this computer.', 'notice') : null,
    howToRead(report),
    qualitySection(report),
    vowelSection(vowel, previous),
    speechSection(report),
    spectralSection(vowel),
    indicatorSection(report, model),
    actions(report),
  );
  const canvas = document.getElementById('mfccChart');
  if (canvas) {
    const means = vowel.features.filter((f) => /^mfcc\.c\d+\.mean$/.test(f.name));
    if (means.every((f) => f.available)) drawBars(canvas, means.map((f) => f.value), means.map((_, i) => String(i + 1)));
  }
}

function section(id, title, ...children) {
  return h('section', { class: 'report-section', id, 'aria-labelledby': `${id}-title` },
    h('h2', { id: `${id}-title`, text: title }), ...children);
}

function howToRead(report) {
  return h('details', { class: 'report-guide', open: true },
    h('summary', { text: 'How to read this report' }),
    h('ul', {},
      h('li', { text: 'Each number was computed by the Java code on this computer from your own recordings. Technical names are shown next to the plain ones, so the values can be checked against Praat.' }),
      h('li', { text: 'There are no “normal ranges” here. Neurovox has no validated reference values, so it does not label your numbers as good or bad.' }),
      h('li', { text: 'The most useful comparison is with your own earlier sessions recorded the same way (same microphone, same room). History shows the trends.' }),
      h('li', { text: 'Only the sustained vowel feeds the 16 classic voice measurements. The other three tasks give descriptive numbers only.' }),
      h('li', { text: report.researchIndicator.shown
        ? 'An experimental research indicator is shown below. It is not a diagnosis and not a probability of disease.'
        : 'The experimental research model is not applied to live recordings; the section at the bottom explains why.' })));
}

function qualitySection(report) {
  return section('quality', 'Recording quality',
    h('ul', { class: 'quality-list' }, report.tasks.map((t) => {
      const problems = t.quality.checks.filter((c) => c.status !== 'pass');
      return h('li', {},
        h('span', { class: 'quality-task', text: TASK_TITLES[t.task] }),
        h('span', { class: `badge badge-${t.quality.status}`, text: { pass: 'Good', warn: 'Usable, with a note', fail: 'Problem' }[t.quality.status] }),
        h('span', { class: 'quality-note', text: problems.length ? problems.map((c) => `${c.message} ${c.recovery}`).join(' ') : `${t.recording.durationSeconds.toFixed(1)} s at ${(t.recording.sampleRateHz / 1000).toFixed(1)} kHz.` }));
    })));
}

function vowelSection(vowel, previous) {
  const uci = vowel.features.filter((f) => f.group === 'uci');
  const extra = vowel.features.filter((f) => f.group === 'vowel_extra');
  const region = vowel.details.regionStart !== undefined
    ? `Measured on the steady part from ${Number(vowel.details.regionStart).toFixed(2)} s to ${Number(vowel.details.regionEnd).toFixed(2)} s (${Number(vowel.details.pulses)} vocal cycles found).`
    : 'No steady part long enough was found, so the measurements could not be taken.';
  return section('vowel', 'Sustained vowel — 16 voice measurements',
    h('p', { class: 'report-lede', text: `${region} Order and names follow the Oxford/UCI dataset columns.` }),
    previous ? h('p', { class: 'tip', text: `“Change” compares with your session ${previous.session.id} on ${formatDate(previous.session.startedAt)}.` }) : null,
    h('div', { class: 'table-wrap' }, h('table', { class: 'feature-table' },
      h('thead', {}, h('tr', {},
        h('th', { scope: 'col', text: 'Measurement' }),
        h('th', { scope: 'col', class: 'num', text: 'Your value' }),
        previous ? h('th', { scope: 'col', class: 'num', text: 'Change' }) : null,
        h('th', { scope: 'col', text: 'What it means' }))),
      h('tbody', {}, [...uci, ...extra].map((f) => featureRow(f, previous))))));
}

function featureRow(f, previous) {
  const d = display(f.value, f.unit);
  let change = null;
  if (previous) {
    const before = previous.values[f.name];
    change = f.available && before !== undefined ? signedChange(f.value - before, f.unit) : '—';
  }
  return h('tr', { class: f.available ? '' : 'is-unavailable' },
    h('th', { scope: 'row' }, h('span', { class: 'feature-name', text: f.plainName || f.name }),
      f.group === 'uci' ? h('code', { class: 'feature-key', text: f.name }) : null),
    h('td', { class: 'num' }, f.available ? [h('span', { class: 'value', text: d.value }), d.unit ? h('span', { class: 'unit', text: ` ${d.unit}` }) : null]
      : h('span', { class: 'unavailable', text: 'Not measured' })),
    previous ? h('td', { class: 'num change', text: change }) : null,
    h('td', { class: 'explain', text: f.available ? f.explanation : f.unavailableReason }));
}

function signedChange(delta, unit) {
  const d = display(Math.abs(delta), unit);
  const sign = delta > 0 ? '+' : delta < 0 ? '−' : '±';
  return `${sign}${d.value}${d.unit ? ' ' + d.unit : ''}`;
}

function speechSection(report) {
  const others = report.tasks.filter((t) => t.task !== 'sustained_vowel');
  return section('speech', 'Other tasks — descriptive only',
    h('p', { class: 'report-lede', text: 'These numbers describe how you spoke. They are never used by any classifier, and have no reference ranges.' }),
    h('div', { class: 'task-cards' }, others.map((t) => h('article', { class: 'task-card' },
      h('h3', { text: TASK_TITLES[t.task] }),
      h('dl', {}, t.features.filter((f) => f.group === 'speech').map((f) => h('div', { class: 'dl-row', title: f.explanation },
        h('dt', { text: f.plainName }),
        h('dd', { text: f.available ? displayText(f.value, f.unit) : 'Not measured' }))))))));
}

function spectralSection(vowel) {
  return section('spectral', 'Spectral shape of the vowel (MFCC)',
    h('p', { class: 'report-lede', text: 'Thirteen mel-frequency cepstral coefficients summarise the overall shape of the voice spectrum, which depends mostly on the vowel and your vocal tract. They are shown for description; they are not compared with anything.' }),
    h('canvas', { class: 'bar-chart', id: 'mfccChart', role: 'img', 'aria-label': 'Bar chart of the 13 average MFCC values of the vowel' }));
}

function indicatorSection(report, model) {
  const ind = report.researchIndicator;
  const gate = report.gate;
  const ev = model?.evaluation || {};
  const pct = (v) => (Number.isFinite(v) ? `${(v * 100).toFixed(1)} %` : 'n/a');
  if (ind.shown) {
    return section('research', 'Experimental research indicator',
      h('div', { class: 'indicator-card' },
        h('p', { class: 'indicator-value', text: ind.aboveThreshold ? 'Above the model’s threshold' : 'Below the model’s threshold' }),
        h('p', { class: 'tip', text: `Model output ${ind.modelOutput.toFixed(3)} on a 0–1 scale (model ${ind.modelVersion}, input: sustained vowel only).` })),
      alert(ind.label, 'notice'));
  }
  return section('research', 'Research model — not applied to this recording',
    h('p', { class: 'report-lede', text: ind.reason }),
    h('ul', { class: 'recorder-notes' }, (gate.reasons || []).map((r) => h('li', { text: r }))),
    h('p', { class: 'tip', text: `Offline result on the public dataset (people kept separate between training and testing): accuracy ${pct(ev.grouped_5fold_recording?.accuracy)}, balanced accuracy ${pct(ev.grouped_5fold_recording?.balanced_accuracy)}, ROC-AUC ${Number.isFinite(ev.grouped_5fold_recording?.roc_auc) ? ev.grouped_5fold_recording.roc_auc.toFixed(2) : 'n/a'}. Always answering “Parkinson’s” would already score ${pct(ev.majority_class_accuracy)}. Not computed by Neurovox: ${report.notComputed.join(', ')}. Details: docs/VALIDATION.md in the project folder.` }));
}

function actions(report) {
  const id = report.session.id;
  return h('div', { class: 'report-actions' },
    h('a', { class: 'button button-primary', href: 'history.html', text: 'View history and trends' }),
    h('a', { class: 'button button-quiet', href: 'record.html', text: 'Record another session' }),
    h('a', { class: 'button button-quiet', href: wavUrl(id, 'sustained_vowel'), download: `neurovox-session-${id}-vowel.wav`, text: 'Download vowel WAV' }),
    button('Delete this session', async () => {
      const ok = await confirmDialog({ title: `Delete session ${id}?`, body: 'Its four recordings and all measurements are removed from this computer. This cannot be undone.', confirmLabel: 'Delete session' });
      if (!ok) return;
      try {
        await deleteSession(id);
        location.href = `history.html?deleted=${id}`;
      } catch (error) {
        host.append(alert(error.message));
      }
    }, { danger: true, id: 'deleteSession' }));
}

load();
