// Minimal Chrome DevTools Protocol helpers shared by the headless check scripts.
// Needs Node 20+ run with --experimental-websocket (or Node 22+).

import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

/**
 * Starts headless Chrome with a fake microphone that is granted automatically. By default the fake microphone plays
 * Chrome's synthetic beep; set FAKE_AUDIO=<path.wav> (or pass {fakeAudio}) to play that file instead, in a loop.
 */
export async function launchChrome({ fakeAudio = process.env.FAKE_AUDIO } = {}) {
  const profile = mkdtempSync(join(tmpdir(), 'neurovox-chrome-'));
  const port = 9300 + Math.floor(Math.random() * 400);
  const proc = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--hide-scrollbars', '--mute-audio',
    '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
    '--autoplay-policy=no-user-gesture-required',
    // The sandboxed out-of-process audio service cannot read the file (it captures silence), so run audio in-process.
    ...(fakeAudio ? [`--use-file-for-fake-audio-capture=${fakeAudio}`, '--disable-features=AudioServiceOutOfProcess'] : []),
    '--enable-webgl', '--ignore-gpu-blocklist', '--enable-unsafe-swiftshader',
    'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  for (let i = 0; i < 80; i++) {
    try {
      const info = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
      return { proc, ws: info.webSocketDebuggerUrl };
    } catch { await sleep(150); }
  }
  proc.kill();
  throw new Error('Chrome did not start');
}

export class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.id = 0;
    this.pending = new Map();
    this.listeners = [];
    this.ws.onmessage = (event) => {
      const msg = JSON.parse(event.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
      } else if (msg.method) {
        if (process.env.CDP_DEBUG && !msg.method.startsWith('Network.')) console.error(`   <- ${msg.method}`);
        this.listeners.forEach((fn) => fn(msg));
      }
    };
    // A dead connection must fail loudly instead of leaving calls waiting forever.
    const failAll = (why) => {
      for (const { reject } of this.pending.values()) reject(new Error(`DevTools connection ${why}`));
      this.pending.clear();
      this.closed = why;
    };
    this.ws.onclose = () => failAll('closed');
    this.ws.onerror = (event) => failAll(`error: ${event.message || event.type}`);
  }
  open() { return new Promise((ok) => { this.ws.onopen = ok; }); }
  send(method, params = {}, sessionId) {
    if (this.closed) return Promise.reject(new Error(`DevTools connection ${this.closed}`));
    const id = ++this.id;
    if (process.env.CDP_DEBUG) console.error(`${new Date().toISOString().slice(11, 23)} -> ${method} ${method === 'Runtime.evaluate' ? String(params.expression).slice(0, 90) : ''}`);
    this.ws.send(JSON.stringify({ id, method, params, sessionId }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }
}

/** Opens a page target and returns helpers bound to it. */
export async function openPage(cdp) {
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
  const send = (m, p) => cdp.send(m, p, sessionId);
  await send('Page.enable');
  await send('Runtime.enable');
  // Uploads carry ~1 MB of audio; without a cap Chrome echoes the whole body in requestWillBeSent.
  await send('Network.enable', { maxPostDataSize: 1024 });
  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  };
  const mouse = (type, x, y) => send('Input.dispatchMouseEvent', {
    type, x, y, button: 'left', clickCount: type === 'mouseMoved' ? 0 : 1, buttons: type === 'mousePressed' ? 1 : 0,
  });
  let touchMode = false;
  const clickSelector = async (selector) => {
    // Instant scroll (pages may use smooth scrolling), then measure in a second round-trip.
    const found = await evaluate(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) return false;
      el.scrollIntoView({ block: 'center', behavior: 'instant' }); return true; })()`);
    const rect = found && await evaluate(`(() => { const r = document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; })()`);
    if (!rect) throw new Error(`No element for ${selector}`);
    if (touchMode) {
      // With touch emulation on, Chrome expects taps rather than mouse clicks.
      await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: rect.x, y: rect.y }] });
      await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      return;
    }
    await mouse('mouseMoved', rect.x, rect.y);
    await mouse('mousePressed', rect.x, rect.y);
    await mouse('mouseReleased', rect.x, rect.y);
  };
  const viewport = async ({ width, height, mobile = false, dpr = 1 }) => {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: dpr, mobile });
    await send('Emulation.setTouchEmulationEnabled', { enabled: mobile, maxTouchPoints: mobile ? 5 : 1 });
    touchMode = mobile;
  };
  const waitFor = async (expression, timeoutMs = 10_000, intervalMs = 100) => {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const value = await evaluate(expression).catch(() => false);
      if (value) return value;
      await sleep(intervalMs);
    }
    throw new Error(`Timed out waiting for: ${expression}`);
  };
  return { sessionId, send, evaluate, mouse, clickSelector, viewport, waitFor };
}
