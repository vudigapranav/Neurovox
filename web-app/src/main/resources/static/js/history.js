// History page: earlier sessions, a trend chart of one vowel measurement, and local data deletion.

import { listSessions, deleteSession, deleteAllData, analyseSession } from './api.js';
import { h, button, alert, formatDate, confirmDialog } from './ui.js';
import { displayText, KEY_FEATURES } from './features.js';
import { drawTrend } from './chart.js';

document.documentElement.classList.add('js');
const host = document.getElementById('history');
const params = new URLSearchParams(location.search);
let notice = params.get('deleted') ? `Session ${params.get('deleted')} was deleted.` : params.get('cleared') ? 'All local data was deleted.' : '';
let chosen = 'MDVP:Jitter(%)';
let last = null;

async function load() {
  let data;
  try {
    data = await listSessions();
  } catch (error) {
    host.replaceChildren(h('p', { class: 'eyebrow', text: 'History' }), h('h1', { class: 'report-title', text: 'Could not load your sessions.' }),
      alert(error.message), h('div', { class: 'recorder-actions' }, button('Try again', load, { primary: true })));
    return;
  }
  render(data);
}

function render(data) {
  last = data;
  const sessions = data.sessions;
  const catalog = data.features;
  host.replaceChildren(...[
    h('p', { class: 'eyebrow', text: 'History' }),
    h('h1', { class: 'report-title', text: sessions.length ? 'Your earlier sessions' : 'No sessions yet' }),
    notice ? alert(notice, 'notice') : null,
    sessions.length ? null : h('p', { class: 'report-lede', text: 'Sessions you record appear here, newest first, with a trend chart once at least one has been analysed.' }),
    sessions.length ? trend(sessions, catalog) : null,
    sessions.length ? list(sessions) : null,
    h('div', { class: 'report-actions' }, h('a', { class: 'button button-primary', href: 'record.html', text: 'Record a new session' })),
    dataSection(sessions),
  ].filter(Boolean));   // optional parts are null; replaceChildren would print them as "null"
  notice = '';
  drawChosen(sessions, catalog);
}

function analysedOldestFirst(sessions) {
  return sessions.filter((s) => s.analysed).slice().reverse();
}

function trend(sessions, catalog) {
  const select = h('select', { id: 'trendFeature', onchange: (e) => { chosen = e.target.value; drawChosen(sessions, catalog); } },
    catalog.map((f) => h('option', { value: f.key, selected: f.key === chosen, text: `${f.plainName} (${f.key})` })));
  return h('section', { class: 'report-section', 'aria-labelledby': 'trend-title' },
    h('h2', { id: 'trend-title', text: 'Trend of one measurement' }),
    h('label', { class: 'field', for: 'trendFeature' }, h('span', { text: 'Measurement' }), select),
    h('canvas', { class: 'trend-chart', id: 'trendChart', role: 'img', 'aria-describedby': 'trendCaption' }),
    h('p', { class: 'tip', id: 'trendCaption' }),
    h('div', { class: 'table-wrap' }, h('table', { class: 'feature-table compact', id: 'trendTable' })));
}

function drawChosen(sessions, catalog) {
  const canvas = document.getElementById('trendChart');
  if (!canvas) return;
  const feature = catalog.find((f) => f.key === chosen);
  const points = analysedOldestFirst(sessions).filter((s) => s.vowel[chosen] !== undefined)
    .map((s) => ({ id: s.id, time: new Date(s.startedAt), value: s.vowel[chosen] }));
  drawTrend(canvas, points, feature.unit);
  canvas.setAttribute('aria-label', `${feature.plainName} across ${points.length} analysed sessions`);
  document.getElementById('trendCaption').textContent = points.length === 0
    ? 'No analysed session has this measurement yet.'
    : `${feature.explanation} ${points.length === 1 ? 'Record another session to see a trend.' : ''} No normal range is drawn: Neurovox has no validated reference values.`;
  document.getElementById('trendTable').replaceChildren(
    h('thead', {}, h('tr', {}, h('th', { scope: 'col', text: 'Session' }), h('th', { scope: 'col', text: 'Date' }), h('th', { scope: 'col', class: 'num', text: feature.plainName }))),
    h('tbody', {}, points.map((p) => h('tr', {},
      h('td', { text: String(p.id) }), h('td', { text: formatDate(p.time.toISOString()) }), h('td', { class: 'num', text: displayText(p.value, feature.unit) })))));
}

function list(sessions) {
  return h('section', { class: 'report-section', 'aria-labelledby': 'list-title' },
    h('h2', { id: 'list-title', text: 'Sessions' }),
    h('div', { class: 'table-wrap' }, h('table', { class: 'feature-table sessions-table' },
      h('thead', {}, h('tr', {},
        h('th', { scope: 'col', text: 'Session' }),
        h('th', { scope: 'col', text: 'Status' }),
        ...KEY_FEATURES.map((k) => h('th', { scope: 'col', class: 'num', text: { 'MDVP:Fo(Hz)': 'Pitch', 'MDVP:Jitter(%)': 'Jitter', 'MDVP:Shimmer': 'Shimmer', HNR: 'HNR' }[k] })),
        h('th', { scope: 'col', text: 'Actions' }))),
      h('tbody', {}, sessions.map(row)))));
}

function row(s) {
  const status = s.analysed ? 'Analysed' : s.status === 'complete' ? 'Recorded, not analysed' : `In progress (${s.recordings} of 4)`;
  const units = { 'MDVP:Fo(Hz)': 'Hz', 'MDVP:Jitter(%)': 'fraction', 'MDVP:Shimmer': 'fraction', HNR: 'dB' };
  return h('tr', { 'data-session': String(s.id) },
    h('th', { scope: 'row' }, h('span', { class: 'feature-name', text: `#${s.id}` }), h('span', { class: 'feature-key', text: formatDate(s.startedAt) })),
    h('td', { text: status }),
    ...KEY_FEATURES.map((k) => h('td', { class: 'num', text: s.vowel[k] !== undefined ? displayText(s.vowel[k], units[k]) : '—' })),
    h('td', { class: 'row-actions' },
      s.analysed ? h('a', { class: 'text-link', href: `results.html?session=${s.id}`, text: 'Open report' })
        : s.status === 'complete' ? button('Analyse now', async (e) => {
          e.target.disabled = true;
          e.target.textContent = 'Analysing…';
          try {
            await analyseSession(s.id);
            location.href = `results.html?session=${s.id}&new=1`;
          } catch (error) {
            notice = error.message;
            load();
          }
        })
          : h('a', { class: 'text-link', href: `record.html?session=${s.id}`, text: 'Continue recording' }),
      button('Delete', async () => {
        const ok = await confirmDialog({ title: `Delete session ${s.id}?`, body: 'Its recordings and measurements are removed from this computer. This cannot be undone.', confirmLabel: 'Delete session' });
        if (!ok) return;
        try {
          await deleteSession(s.id);
          notice = `Session ${s.id} was deleted.`;
        } catch (error) {
          notice = error.message;
        }
        load();
      }, { danger: true })));
}

function dataSection(sessions) {
  return h('section', { class: 'report-section danger-zone', 'aria-labelledby': 'data-title' },
    h('h2', { id: 'data-title', text: 'Your local data' }),
    h('p', { class: 'report-lede', text: 'Everything is stored in the data folder next to where you started Neurovox (by default ./data): the SQLite database neurovox.db and the recordings folder. Nothing is sent anywhere.' }),
    h('p', { class: 'tip', text: 'Deleting a single session removes its recordings and measurements. “Delete all local data” removes every session and recording; the empty database stays so Neurovox keeps working.' }),
    button('Delete all local data', async () => {
      const ok = await confirmDialog({ title: 'Delete all local data?', body: `This removes ${sessions.length} session(s), every recording and every measurement from this computer. It cannot be undone.`, confirmLabel: 'Delete everything', typeToConfirm: 'delete all' });
      if (!ok) return;
      try {
        await deleteAllData();
        location.href = 'history.html?cleared=1';
      } catch (error) {
        notice = error.message;
        load();
      }
    }, { danger: true, id: 'deleteAll' }));
}

let resizeTimer = 0;
addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => last && drawChosen(last.sessions, last.features), 150);
});

load();
