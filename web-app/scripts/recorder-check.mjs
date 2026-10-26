#!/usr/bin/env node
// End-to-end check of the whole guided journey against a running Neurovox server (browser → Java → SQLite → report).
//
//   java -jar target/neurovox.jar --port=8096 --data-dir=target/e2e-data
//   node --experimental-websocket scripts/recorder-check.mjs <outDir> http://localhost:8096/ [path/to/voice.wav]
//
// The fake microphone plays the given WAV in a loop (default: validation/synthetic/demo-voice.wav, a synthetic vowel
// made by validation/make_synthetic_vowels.py). No real voice is recorded. The script walks every step a new person
// sees — about, room, microphone check, four tasks (stop early, retake, replay, save), Java analysis, report,
// history — then the failure paths: a rejected take, a dropped connection while saving, an interrupted take, a
// blocked microphone, resuming an unfinished session, deleting a session and deleting all data, plus phone layouts.
// Screenshots and results.json go to <outDir>.

import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { launchChrome, Cdp, openPage, sleep } from './lib/browser.mjs';

const OUT = resolve(process.argv[2] || 'recorder-check-output');
const BASE = process.argv[3] || 'http://localhost:8080/';
const VOICE = resolve(process.argv[4] || 'validation/synthetic/demo-voice.wav');
mkdirSync(OUT, { recursive: true });
if (!existsSync(VOICE)) {
  console.error(`Missing ${VOICE}. Run: .venv/bin/python validation/make_synthetic_vowels.py`);
  process.exit(1);
}

/** Reads a 16-bit mono WAV into Float32Array (for seeding a second session through the API). */
function readWav(path) {
  const b = readFileSync(path);
  const rate = b.readUInt32LE(24);
  let pos = 12;
  while (pos < b.length) {
    const id = b.toString('ascii', pos, pos + 4);
    const size = b.readUInt32LE(pos + 4);
    if (id === 'data') {
      const n = size / 2;
      const out = new Float32Array(n);
      for (let i = 0; i < n; i++) out[i] = b.readInt16LE(pos + 8 + i * 2) / 32768;
      return { rate, samples: out };
    }
    pos += 8 + size + (size & 1);
  }
  throw new Error('no data chunk');
}

async function main() {
  const { proc, ws } = await launchChrome({ fakeAudio: VOICE });
  const cdp = new Cdp(ws);
  await cdp.open();
  const page = await openPage(cdp);
  const { send, evaluate, clickSelector, viewport, waitFor } = page;
  const results = { base: BASE, voice: VOICE, steps: {} };
  const requests = [];
  const errors = [];
  cdp.listeners.push((msg) => {
    if (msg.method === 'Page.javascriptDialogOpening') cdp.send('Page.handleJavaScriptDialog', { accept: true }, page.sessionId);
    if (msg.method === 'Network.requestWillBeSent') requests.push(`${msg.params.request.method} ${msg.params.request.url}`);
    if (msg.method === 'Runtime.exceptionThrown') errors.push(msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text);
    if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') errors.push(msg.params.args.map((a) => a.value ?? a.description).join(' '));
  });
  const shot = async (name, full = false) => {
    const params = { format: 'png' };
    if (full) {
      const { cssContentSize } = await send('Page.getLayoutMetrics');
      params.captureBeyondViewport = true;
      params.clip = { x: 0, y: 0, width: cssContentSize.width, height: Math.min(cssContentSize.height, 6000), scale: 1 };
    }
    const { data } = await send('Page.captureScreenshot', params);
    writeFileSync(join(OUT, `${name}.png`), Buffer.from(data, 'base64'));
    return `${name}.png`;
  };
  const state = () => evaluate('document.documentElement.dataset.recorder');
  const waitState = async (s, timeout = 15_000) => {
    try {
      return await waitFor(`document.documentElement.dataset.recorder === ${JSON.stringify(s)}`, timeout);
    } catch {
      const now = await evaluate('({ state: document.documentElement.dataset.recorder, alert: document.querySelector(".alert")?.textContent || null })').catch(() => ({}));
      throw new Error(`Expected recorder state "${s}", found ${JSON.stringify(now)}`);
    }
  };
  const text = (sel) => evaluate(`document.querySelector(${JSON.stringify(sel)})?.textContent || ''`);
  const record = (name, data) => { results.steps[name] = data; console.log(name, JSON.stringify(data)); };
  const load = async (path) => {
    await send('Page.navigate', { url: new URL(path, BASE).href });
    await waitFor('document.readyState === "complete"', 10_000);
    await sleep(800);
  };
  const recordTake = async ({ stopEarly }) => {
    await clickSelector('#recordButton');
    await waitState('recording', 6000);
    const task = await evaluate('window.neurovox.app.tasks[window.neurovox.app.index]');
    if (stopEarly) {
      await waitFor('!document.getElementById("stopButton").disabled', (task.minSeconds + 6) * 1000);
      await clickSelector('#stopButton');
    }
    await waitState('review', (task.targetSeconds + 8) * 1000);
    return task;
  };

  try {
    await viewport({ width: 1440, height: 900 });

    // ---------- 1–3: about, room, microphone ----------
    await load('record.html');
    await waitState('about');
    const continueDisabled = await evaluate('document.getElementById("aboutContinue").disabled');
    const steps = await evaluate('[...document.querySelectorAll("#taskSteps .step-name")].map(e => e.textContent)');
    record('about', { continueDisabledUntilTicked: continueDisabled, steps, shot: await shot('e01-about') });
    await clickSelector('#understood');
    await clickSelector('#aboutContinue');
    await waitState('room');
    record('room', { heading: await text('h1'), shot: await shot('e02-room') });
    await clickSelector('#roomContinue');
    await waitState('mic');
    await shot('e03-mic-before');
    await clickSelector('#micAllow');
    await waitFor('window.neurovox.app.micStatus === "live"', 10_000);
    await sleep(1500);
    record('mic-live', {
      sampleRate: await evaluate('window.neurovox.mic.sampleRate'),
      meterText: await text('#micMeterText'),
      orbLevel: await evaluate('window.neurovox.orb.stats().level'),
      shot: await shot('e04-mic-live'),
    });
    await clickSelector('#micContinue');
    await waitState('ready');
    const sessionId = await evaluate('window.neurovox.app.session.id');
    record('first-task', { sessionId, url: await evaluate('location.search'), shot: await shot('e05-ready-vowel') });

    // ---------- 4–5: four tasks with stop-early, retake, replay and save ----------
    const orbLevels = {};
    for (let i = 0; i < 4; i++) {
      const isVowel = i === 0;
      if (isVowel) {
        // First vowel take: stop early, review, then record again in full.
        await recordTake({ stopEarly: true });
        record('stopped-early', { seconds: await evaluate('window.neurovox.app.take.length / window.neurovox.mic.sampleRate'), heading: await text('h2'), shot: await shot('e06-review-stopped-early') });
        await clickSelector('#retakeButton');
        await waitState('ready');
        await clickSelector('#recordButton');
        await sleep(900);
        await shot('e07-countdown');
        await waitState('recording', 6000);
        await sleep(2000);
        orbLevels.vowel = await evaluate('window.neurovox.orb.stats().level');
        await shot('e08-recording-vowel');
        await waitState('review', 14_000);
        await clickSelector('#playButton');
        await sleep(600);
        record('replay', { button: await text('#playButton') });
        await clickSelector('#playButton');
        await shot('e09-review-vowel');
      } else {
        const task = await recordTake({ stopEarly: true });
        if (task.code === 'reading_passage') await shot('e10-review-reading');
      }
      await clickSelector('#saveButton');
      await waitFor(`['ready', 'analysing'].includes(document.documentElement.dataset.recorder) || location.pathname.endsWith('results.html')`, 20_000);
      if (i === 0) record('saved-vowel', { state: await state(), notice: await text('.alert') });
    }

    // ---------- 6: Java analysis, then 7: the report ----------
    await waitFor('location.pathname.endsWith("results.html") && !!document.querySelector(".feature-table")', 60_000);
    await sleep(800);
    const report = await evaluate(`({
      heading: document.querySelector('h1').textContent,
      rows: [...document.querySelectorAll('#vowel tbody tr')].map(r => ({ name: r.querySelector('.feature-name').textContent, key: r.querySelector('.feature-key')?.textContent || null, value: r.querySelector('td.num').textContent })),
      quality: [...document.querySelectorAll('.quality-list li')].map(li => li.textContent),
      research: document.querySelector('#research-title').textContent,
      disclaimer: document.querySelector('.report-disclaimer').textContent,
    })`);
    record('report', { url: await evaluate('location.search'), ...report, orbLevels, shotTop: await shot('e11-report-top'), shotFull: await shot('e12-report-full', true) });

    // ---------- 8: history (seed a second session through the API so the trend has two points) ----------
    const voice = readWav(VOICE);
    const api = (path, init) => fetch(new URL(path, BASE), init).then(async (r) => ({ status: r.status, json: await r.json() }));
    const created = await api('/api/sessions', { method: 'POST' });
    const second = created.json.session.id;
    const tasks = (await api('/api/tasks')).json.tasks;
    for (const t of tasks) {
      const seconds = t.code === 'sustained_vowel' ? 5 : t.minSeconds + 1;
      const n = Math.round(seconds * voice.rate);
      const take = new Float32Array(n);
      for (let k = 0; k < n; k++) take[k] = voice.samples[(k + 4800) % voice.samples.length] * 0.9;
      const up = await api(`/api/sessions/${second}/recordings/${t.code}?sampleRate=${voice.rate}&format=pcm_f32le&channels=1`,
        { method: 'PUT', headers: { 'Content-Type': 'application/octet-stream' }, body: Buffer.from(take.buffer) });
      if (up.status >= 300) throw new Error(`seed upload ${t.code}: ${JSON.stringify(up.json)}`);
    }
    await api(`/api/sessions/${second}/analysis`, { method: 'POST' });
    await load('history.html');
    await waitFor('!!document.getElementById("trendChart")', 10_000);
    await sleep(500);
    record('history', {
      rows: await evaluate('document.querySelectorAll(".sessions-table tbody tr").length'),
      trendPoints: await evaluate('document.querySelectorAll("#trendTable tbody tr").length'),
      shot: await shot('e13-history', true),
    });

    // ---------- failure: a take the quality gate rejects ----------
    await load('record.html');
    await waitState('about');
    await clickSelector('#understood');
    await clickSelector('#aboutContinue');
    await clickSelector('#roomContinue');
    await clickSelector('#micAllow');
    await waitFor('window.neurovox.app.micStatus === "live"', 10_000);
    await clickSelector('#micContinue');
    await waitState('ready');
    const third = await evaluate('window.neurovox.app.session.id');
    await recordTake({ stopEarly: true });
    await evaluate('window.neurovox.app.take = new Float32Array(window.neurovox.mic.sampleRate * 5)');   // silence
    await clickSelector('#saveButton');
    await waitState('rejected');
    record('rejected-take', { what: await text('.quality-issue-what'), fix: await text('.quality-issue-fix'), shot: await shot('e14-rejected') });
    await clickSelector('#retakeButton');
    await waitState('ready');

    // ---------- failure: the connection drops while saving ----------
    await recordTake({ stopEarly: true });
    await send('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await clickSelector('#saveButton');
    await waitState('upload-error');
    record('upload-error', { message: await text('.alert'), shot: await shot('e15-upload-error') });
    await send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await clickSelector('.recorder-actions .button-primary');
    await waitState('ready');
    record('upload-retry', { saved: await evaluate('Object.keys(window.neurovox.app.saved)') });

    // ---------- failure: the page is hidden during a take ----------
    await clickSelector('#recordButton');
    await waitState('recording', 6000);
    await evaluate(`Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange'))`);
    await waitState('ready');
    record('interrupted', { notice: await text('.alert-notice'), shot: await shot('e16-interrupted') });

    // ---------- resume the unfinished session ----------
    await load(`record.html?session=${third}`);
    await waitState('mic');
    record('resume', { notice: await text('.alert-notice'), shot: await shot('e17-resume') });

    // ---------- failure: microphone blocked ----------
    await load('record.html');
    await waitState('about');
    await evaluate(`navigator.mediaDevices.getUserMedia = () => Promise.reject(Object.assign(new Error('denied'), { name: 'NotAllowedError' }))`);
    await clickSelector('#understood');
    await clickSelector('#aboutContinue');
    await clickSelector('#roomContinue');
    await clickSelector('#micAllow');
    await waitFor('window.neurovox.app.micStatus === "error"', 5000);
    record('mic-blocked', { message: await text('.alert-error'), help: await evaluate('document.querySelectorAll(".recorder-notes li").length'), shot: await shot('e18-mic-blocked') });

    // ---------- phone layouts ----------
    await viewport({ width: 390, height: 844, mobile: true, dpr: 3 });
    const overflow = {};
    for (const [name, path] of [['record', 'record.html'], ['results', `results.html?session=${sessionId}`], ['history', 'history.html'], ['home', 'index.html#guide']]) {
      await load(path);
      await sleep(600);
      overflow[name] = await evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth');
      await shot(`e19-mobile-${name}`);
    }
    record('mobile', { overflow });
    await viewport({ width: 1440, height: 900 });

    // ---------- delete one session from its report, then all data ----------
    await load(`results.html?session=${sessionId}`);
    await waitFor('!!document.getElementById("deleteSession")', 10_000);
    await clickSelector('#deleteSession');
    await waitFor('!!document.querySelector("dialog[open]")', 3000);
    await shot('e20-delete-dialog');
    await clickSelector('dialog[open] .button-danger');
    await waitFor('location.pathname.endsWith("history.html") && !!document.querySelector(".alert-notice")', 10_000);
    record('delete-session', { notice: await text('.alert-notice'), remaining: await evaluate('document.querySelectorAll(".sessions-table tbody tr").length') });
    await clickSelector('#deleteAll');
    await waitFor('!!document.querySelector("dialog[open] .confirm-input")', 3000);
    const disabledBeforeTyping = await evaluate('document.querySelector("dialog[open] .button-danger").disabled');
    await evaluate(`(() => { const i = document.querySelector('dialog[open] .confirm-input'); i.value = 'delete all'; i.dispatchEvent(new Event('input')); })()`);
    await clickSelector('dialog[open] .button-danger');
    await waitFor('location.search.includes("cleared")', 10_000);
    await sleep(500);
    record('delete-all', { disabledBeforeTyping, heading: await text('h1'), sessionsLeft: (await api('/api/sessions')).json.sessions.length, shot: await shot('e21-all-deleted') });

    const foreign = requests.filter((r) => !r.includes(new URL(BASE).host) && !r.includes('about:blank') && !r.startsWith('GET data:'));
    results.requests = { total: requests.length, notToThisServer: foreign };
    results.errors = errors;
  } finally {
    writeFileSync(join(OUT, 'results.json'), JSON.stringify(results, null, 2));
    proc.kill();
  }
}

main().catch((error) => { console.error(error); process.exit(1); });
