#!/usr/bin/env node
// End-to-end check of the guided recorder against a running Neurovox server.
//
//   java -jar target/neurovox.jar --port=8096 --data-dir=/tmp/nv-e2e
//   node --experimental-websocket scripts/recorder-check.mjs <outDir> http://localhost:8096/
//
// Uses headless Chrome's fake microphone (a synthetic tone), so no real voice is recorded.
// Records all four tasks at their real durations (about 90 s in total), including a retake and a
// playback, then exercises an upload failure, an interrupted take, a denied microphone and the
// mobile layout. Writes screenshots and results.json to outDir.

import { mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { launchChrome, Cdp, openPage, sleep } from './lib/browser.mjs';

const OUT = resolve(process.argv[2] || 'recorder-check-output');
const BASE = process.argv[3] || 'http://localhost:8080/';
mkdirSync(OUT, { recursive: true });

async function main() {
  const { proc, ws } = await launchChrome();
  const cdp = new Cdp(ws);
  await cdp.open();
  const page = await openPage(cdp);
  const { send, evaluate, clickSelector, viewport, waitFor } = page;
  const results = { base: BASE, steps: {} };
  const requests = [];
  const errors = [];
  const dialogs = [];
  cdp.listeners.push((msg) => {
    // The recorder warns before leaving mid-session (beforeunload); accept so navigation continues.
    if (msg.method === 'Page.javascriptDialogOpening') {
      dialogs.push(msg.params.type);
      cdp.send('Page.handleJavaScriptDialog', { accept: true }, page.sessionId);
    }
    if (msg.method === 'Network.requestWillBeSent') requests.push(`${msg.params.request.method} ${msg.params.request.url}`);
    if (msg.method === 'Runtime.exceptionThrown') errors.push(msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text);
    if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') errors.push(msg.params.args.map((a) => a.value ?? a.description).join(' '));
  });
  const shot = async (name) => {
    const { data } = await send('Page.captureScreenshot', { format: 'png' });
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
  const record = (name, data) => { results.steps[name] = data; console.log(name, JSON.stringify(data)); };
  const load = async (path) => {
    await send('Page.navigate', { url: new URL(path, BASE).href });
    await waitFor('document.readyState === "complete" && !!window.neurovox', 10_000);
    await sleep(1200);
  };

  try {
    // ---------- desktop: full session (SKIP_FULL=1 skips it while iterating on later steps) ----------
    await viewport({ width: 1440, height: 900 });
    if (!process.env.SKIP_FULL) {
    await load('record.html');
    await waitState('intro');
    record('intro', { shot: await shot('r01-intro'), orb: await evaluate('window.neurovox.orb.stats().mode') });

    await clickSelector('#beginButton');
    await waitState('ready');
    const sampleRate = await evaluate('window.neurovox.mic.sampleRate');
    const sessionId = await evaluate('window.neurovox.app.session.id');
    record('ready-first-task', { sampleRate, sessionId, shot: await shot('r02-ready-vowel') });

    const tasks = await evaluate('window.neurovox.app.tasks.map(t => ({ code: t.code, seconds: t.targetSeconds }))');
    const orbLevels = {};
    for (let i = 0; i < tasks.length; i++) {
      const task = tasks[i];
      const takes = task.code === 'sustained_vowel' ? 2 : 1; // retake the vowel once
      for (let take = 1; take <= takes; take++) {
        await clickSelector('#recordButton');
        if (i === 0 && take === 1) {
          await sleep(900);
          record('countdown', { state: await state(), shot: await shot('r03-countdown') });
        }
        await waitState('recording', 6000);
        await sleep(Math.min(2500, task.seconds * 400));
        const level = await evaluate('window.neurovox.orb.stats().level');
        orbLevels[task.code] = Math.max(orbLevels[task.code] || 0, level);
        if (take === 1 && (i === 0 || task.code === 'reading_passage')) await shot(`r04-recording-${task.code}`);
        await waitState('review', (task.seconds + 8) * 1000);
        if (take === 1 && (i === 0 || task.code === 'reading_passage')) await shot(`r05-review-${task.code}`);
        if (take < takes) {
          await clickSelector('#retakeButton');
          await waitState('ready');
        }
      }
      if (i === 0) {
        await clickSelector('#playButton');
        await sleep(700);
        record('playback', { button: await evaluate('document.getElementById("playButton").textContent'), orbLevel: await evaluate('window.neurovox.orb.stats().level') });
        await clickSelector('#playButton');
      }
      await clickSelector('#saveButton');
      await waitFor(`['ready', 'complete'].includes(document.documentElement.dataset.recorder)`, 15_000);
    }
    await waitState('complete');
    const session = await evaluate('window.neurovox.app.session');
    record('complete', { session, orbLevelsDuringRecording: orbLevels, shot: await shot('r06-complete') });
    }

    // ---------- upload failure: the network drops while saving (as if the server stopped) ----------
    // (Offline emulation rather than request interception: intercepted uploads echo ~1 MB of audio
    // over DevTools, which the experimental WebSocket client cannot handle.)
    await load('record.html');
    await waitState('intro');
    await clickSelector('#beginButton');
    await waitState('ready');
    await clickSelector('#recordButton');
    await waitState('review', 16_000);
    await send('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await clickSelector('#saveButton');
    await waitState('upload-error');
    record('upload-error', { message: await evaluate('document.querySelector(".alert").textContent'), shot: await shot('r07-upload-error') });
    await send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await clickSelector('.recorder-actions .button-primary'); // "Try saving again"
    await waitState('ready');
    record('upload-retry', { saved: await evaluate('Object.keys(window.neurovox.app.saved)') });

    // ---------- interrupted take (page hidden while recording) ----------
    await clickSelector('#recordButton');
    await waitState('recording', 6000);
    await evaluate(`Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange'))`);
    await waitState('ready');
    record('interrupted', { notice: await evaluate('document.querySelector(".alert")?.textContent || ""'), shot: await shot('r08-interrupted') });

    // ---------- microphone denied ----------
    await load('record.html');
    await waitState('intro');
    await evaluate(`navigator.mediaDevices.getUserMedia = () => Promise.reject(Object.assign(new Error('denied'), { name: 'NotAllowedError' }))`);
    await clickSelector('#beginButton');
    await waitState('mic-error');
    record('mic-denied', { message: await evaluate('document.querySelector(".alert").textContent'), shot: await shot('r09-mic-denied') });

    // ---------- mobile ----------
    await viewport({ width: 390, height: 844, mobile: true, dpr: 3 });
    await load('record.html');
    await waitState('intro');
    await shot('r10-mobile-intro');
    await clickSelector('#beginButton');
    await waitState('ready');
    await shot('r11-mobile-ready');
    await clickSelector('#recordButton');
    await waitState('recording', 6000);
    await sleep(2000);
    await shot('r12-mobile-recording');
    await waitState('review', 14_000);
    await shot('r13-mobile-review');
    record('mobile', { overflow: await evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth') });
    await viewport({ width: 390, height: 844, mobile: true, dpr: 3 });
    for (let i = 0; i < 3; i++) {
      await clickSelector('#saveButton').catch(() => {});
      await waitFor(`['ready', 'complete'].includes(document.documentElement.dataset.recorder)`, 15_000);
      if ((await state()) === 'complete') break;
      await clickSelector('#recordButton');
      await waitState('review', 40_000);
      if (i === 1) await shot('r14-mobile-reading-review');
    }

    results.requests = requests;
    results.errors = errors;
    results.dialogs = dialogs;
  } finally {
    writeFileSync(join(OUT, 'results.json'), JSON.stringify(results, null, 2));
    proc.kill();
  }
}

main().catch((error) => { console.error(error); process.exit(1); });
