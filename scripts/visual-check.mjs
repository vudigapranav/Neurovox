#!/usr/bin/env node
// Headless-Chrome visual and interaction check for the Neurovox frontend.
//
//   node --experimental-websocket scripts/visual-check.mjs [outDir] [baseUrl]
//
// Without baseUrl it serves src/main/resources/static itself on 127.0.0.1.
// Uses the Chrome DevTools Protocol directly (no npm dependencies). Chrome's fake
// media device supplies a synthetic microphone; nothing is recorded or stored.
// Writes screenshots and results.json into outDir.

import { createServer } from 'node:http';
import { mkdirSync, readFileSync, writeFileSync, existsSync, statSync } from 'node:fs';
import { launchChrome, Cdp, sleep } from './lib/browser.mjs';
import { join, extname, resolve, normalize } from 'node:path';

const OUT = resolve(process.argv[2] || 'visual-check-output');
const STATIC = resolve(new URL('../src/main/resources/static/', import.meta.url).pathname);
mkdirSync(OUT, { recursive: true });

const MIME = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png' };

function serveStatic() {
  return new Promise((resolveServer) => {
    const server = createServer((req, res) => {
      const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      const file = normalize(join(STATIC, path === '/' ? 'index.html' : path));
      if (!file.startsWith(STATIC) || !existsSync(file) || statSync(file).isDirectory()) {
        res.writeHead(404); res.end(); return;
      }
      res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      res.end(readFileSync(file));
    });
    server.listen(0, '127.0.0.1', () => resolveServer(server));
  });
}

async function main() {
  const server = process.argv[3] ? null : await serveStatic();
  const base = process.argv[3] || `http://127.0.0.1:${server.address().port}/`;
  const { proc, ws } = await launchChrome();
  const cdp = new Cdp(ws);
  await cdp.open();
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
  const send = (m, p) => cdp.send(m, p, sessionId);
  const results = { base, scenarios: {} };
  const requests = [];
  const consoleErrors = [];
  cdp.listeners.push((msg) => {
    if (msg.method === 'Network.requestWillBeSent') requests.push(msg.params.request.url);
    if (msg.method === 'Runtime.exceptionThrown') consoleErrors.push(msg.params.exceptionDetails.text);
    if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
      consoleErrors.push(msg.params.args.map((a) => a.value ?? a.description).join(' '));
    }
  });
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable', { maxPostDataSize: 1024 });

  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  };
  const shot = async (name) => {
    const { data } = await send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(join(OUT, `${name}.png`), Buffer.from(data, 'base64'));
    return `${name}.png`;
  };
  const viewport = async ({ width, height, mobile = false, dpr = 1 }) => {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: dpr, mobile });
    await send('Emulation.setTouchEmulationEnabled', { enabled: mobile, maxTouchPoints: mobile ? 5 : 1 });
  };
  const load = async (query = '') => {
    await send('Page.navigate', { url: base + query });
    for (let i = 0; i < 60; i++) {
      await sleep(100);
      const ready = await evaluate('document.readyState === "complete" && !!window.neurovox').catch(() => false);
      if (ready) break;
    }
    await sleep(1600);
  };
  const stats = () => evaluate('window.neurovox.orb.stats()');
  const mouse = (type, x, y) => send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: type === 'mouseMoved' ? 0 : 1, buttons: type === 'mousePressed' ? 1 : 0 });
  // Real pointer click (a user gesture), unlike element.click().
  const clickEl = async (selector) => {
    const rect = await evaluate(`(() => { const r = document.querySelector('${selector}').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; })()`);
    await mouse('mouseMoved', rect.x, rect.y);
    await mouse('mousePressed', rect.x, rect.y);
    await mouse('mouseReleased', rect.x, rect.y);
  };
  const orbFrame = () => evaluate(`(() => { const s = getComputedStyle(document.documentElement); return { x: parseFloat(s.getPropertyValue('--orb-x')), y: parseFloat(s.getPropertyValue('--orb-y')), r: parseFloat(s.getPropertyValue('--orb-r')) }; })()`);
  const record = (name, data) => { results.scenarios[name] = data; console.log(name, JSON.stringify(data)); };

  try {
    // ---- Desktop idle ----
    await viewport({ width: 1440, height: 900, dpr: 1 });
    await load();
    const renderer = await evaluate(`(() => { const g = document.createElement('canvas').getContext('webgl'); const e = g && g.getExtension('WEBGL_debug_renderer_info'); return g ? (e ? g.getParameter(e.UNMASKED_RENDERER_WEBGL) : g.getParameter(g.RENDERER)) : null; })()`);
    record('desktop-idle', { renderer, stats: await stats(), shot: await shot('01-desktop-idle') });
    await sleep(2500);
    record('desktop-idle-later', { shot: await shot('02-desktop-idle-later') });

    // ---- Pointer tilt/attraction and tap wave ----
    const orbPos = await evaluate(`(() => { const s = getComputedStyle(document.documentElement); return { x: parseFloat(s.getPropertyValue('--orb-x')), y: parseFloat(s.getPropertyValue('--orb-y')), r: parseFloat(s.getPropertyValue('--orb-r')) }; })()`);
    for (let i = 0; i <= 12; i++) {
      await mouse('mouseMoved', orbPos.x + orbPos.r * 0.9, orbPos.y - orbPos.r * (0.9 - i * 0.02));
      await sleep(60);
    }
    await sleep(500);
    record('desktop-pointer', { orb: orbPos, shot: await shot('03-desktop-pointer') });
    await mouse('mousePressed', orbPos.x + orbPos.r * 0.6, orbPos.y - orbPos.r * 0.4);
    await mouse('mouseReleased', orbPos.x + orbPos.r * 0.6, orbPos.y - orbPos.r * 0.4);
    await sleep(450);
    record('desktop-tap-wave', { shot: await shot('04-desktop-tap-wave') });

    // ---- Keyboard ----
    await evaluate(`document.getElementById('orbHit').focus({ focusVisible: true })`);
    for (const key of ['ArrowLeft', 'ArrowLeft', 'ArrowUp']) {
      await send('Input.dispatchKeyEvent', { type: 'keyDown', key, code: key, windowsVirtualKeyCode: { ArrowLeft: 37, ArrowUp: 38 }[key] });
      await send('Input.dispatchKeyEvent', { type: 'keyUp', key, code: key });
    }
    await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter' });
    await sleep(500);
    record('desktop-keyboard', { focused: await evaluate('document.activeElement.id'), shot: await shot('05-desktop-keyboard') });
    await evaluate('document.activeElement.blur()');
    await mouse('mouseMoved', 200, 880);
    await sleep(3200);

    // ---- Voice: synthetic voice (Web Audio graph -> analyser -> orb) ----
    // Pointer is parked away from the orb so only audio changes the shape.
    await load('?simulate-voice');
    await mouse('mouseMoved', 20, 880);
    await sleep(3000);
    const quietShots = [];
    for (let i = 0; i < 3; i++) { quietShots.push(await shot(`06-voice-quiet-${i}`)); await sleep(350); }
    requests.length = 0;
    await clickEl('#micToggle');
    await mouse('mouseMoved', 20, 880);
    await sleep(1200);
    const loudShots = [];
    const levels = [];
    for (let i = 0; i < 40 && loudShots.length < 3; i++) {
      const s = await stats();
      levels.push(+s.level.toFixed(3));
      if (s.level > 0.5) { loudShots.push(await shot(`07-voice-loud-${loudShots.length}`)); await sleep(250); }
      else await sleep(60);
    }
    record('voice-synthetic', {
      status: await evaluate(`document.getElementById('listenStatus').textContent`),
      pressed: await evaluate(`document.getElementById('micToggle').getAttribute('aria-pressed')`),
      meterBarsOn: await evaluate(`document.querySelectorAll('#levelMeter i.on').length`),
      levels, orb: await orbFrame(), quietShots, loudShots,
      networkRequestsWhileListening: requests.filter((u) => !u.startsWith('data:')),
    });
    await clickEl('#micToggle');
    await sleep(400);
    record('voice-stopped', {
      status: await evaluate(`document.getElementById('listenStatus').textContent`),
      reactorActive: await evaluate('window.neurovox.preview.active'),
      contextClosed: await evaluate('window.neurovox.preview.context === null'),
    });

    // ---- Voice: real getUserMedia path with Chrome's fake microphone device ----
    // On macOS, headless Chrome may never resolve getUserMedia when it cannot obtain
    // system microphone authorisation. That is recorded as "not verifiable", not as a pass.
    await load();
    requests.length = 0;
    await clickEl('#micToggle');
    let active = false;
    for (let i = 0; i < 60 && !active; i++) { active = await evaluate('window.neurovox.preview.active'); await sleep(100); }
    if (active) {
      let fakePeak = 0;
      for (let i = 0; i < 60; i++) { fakePeak = Math.max(fakePeak, (await stats()).level); await sleep(50); }
      record('voice-fake-mic', {
        verified: true, peakLevel: +fakePeak.toFixed(3),
        tracks: await evaluate(`window.neurovox.preview.stream.getAudioTracks().map(t => t.label)`),
        networkRequestsWhileListening: requests.filter((u) => !u.startsWith('data:')),
        shot: await shot('08-voice-fake-mic'),
      });
      await clickEl('#micToggle');
    } else {
      record('voice-fake-mic', {
        verified: false,
        reason: 'getUserMedia did not resolve within 6 s in headless Chrome (system microphone authorisation unavailable)',
        uiState: await evaluate(`document.getElementById('micLabel').textContent`),
      });
    }

    // ---- Microphone denied ----
    await send('Browser.setPermission', { permission: { name: 'audioCapture' }, setting: 'denied', origin: new URL(base).origin }).catch(() => {});
    await load();
    await evaluate(`navigator.mediaDevices.getUserMedia = () => Promise.reject(Object.assign(new Error('denied'), { name: 'NotAllowedError' }))`);
    await clickEl('#micToggle');
    await sleep(500);
    record('mic-denied', {
      status: await evaluate(`document.getElementById('listenStatus').textContent`),
      button: await evaluate(`document.getElementById('micLabel').textContent`),
      orbMode: (await stats()).mode,
      shot: await shot('09-mic-denied'),
    });
    await send('Browser.resetPermissions').catch(() => {});

    // ---- Desktop sections ----
    await load();
    for (const [id, name] of [['session', '10-desktop-session'], ['method', '11-desktop-method'], ['boundaries', '12-desktop-boundaries']]) {
      await evaluate(`document.documentElement.style.scrollBehavior = 'auto'; document.getElementById('${id}').scrollIntoView(); document.querySelectorAll('[data-reveal]').forEach(e => e.classList.add('is-visible'))`);
      await sleep(900);
      await shot(name);
    }
    await evaluate(`window.scrollTo(0, document.body.scrollHeight)`);
    await sleep(700);
    await shot('13-desktop-footer');

    // ---- Context loss and restore ----
    await evaluate(`window.scrollTo(0, 0)`);
    await load();
    await evaluate(`window.__lose = document.getElementById('orbCanvas').getContext('webgl').getExtension('WEBGL_lose_context'); window.__lose.loseContext()`);
    await sleep(900);
    const lost = { mode: (await stats()).mode, status: await evaluate(`document.getElementById('listenStatus').textContent`), shot: await shot('14-context-lost') };
    await evaluate(`window.__lose.restoreContext()`);
    await sleep(1500);
    record('context-loss', { lost, restoredMode: (await stats()).mode, shot: await shot('15-context-restored') });

    // ---- WebGL unavailable ----
    await load('?nowebgl');
    record('webgl-fallback', { stats: await stats(), status: await evaluate(`document.getElementById('listenStatus').textContent`), shot: await shot('16-webgl-fallback') });

    // ---- Reduced motion ----
    await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await load();
    const f0 = (await stats()).flow;
    await sleep(1000);
    const f1 = (await stats()).flow;
    record('reduced-motion', { flowPerSecond: +(f1 - f0).toFixed(3), shot: await shot('17-reduced-motion') });
    await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] });
    await load();
    const g0 = (await stats()).flow;
    await sleep(1000);
    record('normal-motion', { flowPerSecond: +((await stats()).flow - g0).toFixed(3) });

    // ---- Mobile ----
    await viewport({ width: 390, height: 844, mobile: true, dpr: 3 });
    await load();
    record('mobile-idle', { stats: await stats(), shot: await shot('18-mobile-idle') });
    const m = await orbFrame();
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: m.x + m.r * 0.5, y: m.y - m.r * 0.3 }] });
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await sleep(450);
    record('mobile-tap', { shot: await shot('19-mobile-tap') });
    const overflow = await evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth');
    await evaluate(`document.documentElement.style.scrollBehavior = 'auto'; window.scrollTo(0, 600); document.querySelectorAll('[data-reveal]').forEach(e => e.classList.add('is-visible'))`);
    await sleep(800);
    await shot('20-mobile-hero-lower');
    for (const [id, name] of [['session', '21-mobile-session'], ['method', '22-mobile-method'], ['boundaries', '23-mobile-boundaries']]) {
      await evaluate(`document.getElementById('${id}').scrollIntoView()`);
      await sleep(700);
      await shot(name);
    }
    record('mobile-layout', { horizontalOverflowPx: overflow, innerWidth: await evaluate('innerWidth'), orb: m,
      ctaBottom: await evaluate(`Math.round(document.querySelector('.hero .button-primary').getBoundingClientRect().bottom + scrollY)`) });

    // Small phone
    await viewport({ width: 320, height: 640, mobile: true, dpr: 2 });
    await load();
    record('small-phone', { overflow: await evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth'), innerWidth: await evaluate('innerWidth'), shot: await shot('24-small-phone') });

    // Tablet
    await viewport({ width: 820, height: 1180, mobile: true, dpr: 2 });
    await load();
    record('tablet', { shot: await shot('25-tablet') });

    // Retina desktop: heaviest particle load at device pixel ratio 2.
    await viewport({ width: 1440, height: 900, dpr: 2 });
    await load();
    await sleep(4000);
    record('desktop-retina-performance', { stats: await stats() });

    results.consoleErrors = consoleErrors.filter((e) => !/^denied$/.test(e));
    results.requestedUrls = [...new Set(requests)];
  } finally {
    writeFileSync(join(OUT, 'results.json'), JSON.stringify(results, null, 2));
    proc.kill();
    server?.close();
  }
}

main().catch((error) => { console.error(error); process.exit(1); });
