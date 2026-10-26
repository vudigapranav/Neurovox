// Static checks on the shipped frontend: no video, no CDN, and the microphone
// preview has no code path that records, stores or transmits audio.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const STATIC = new URL('../../main/resources/static/', import.meta.url).pathname;
const files = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p); else files.push(p);
  }
})(STATIC);
const text = (p) => readFileSync(p, 'utf8');
const stripComments = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
const sources = files.filter((p) => ['.html', '.css', '.js'].includes(extname(p)));

test('no video files or video references ship with the frontend', () => {
  assert.deepEqual(files.filter((p) => /\.(mp4|webm|mov|m4v)$/i.test(p)), []);
  for (const p of sources) assert.doesNotMatch(text(p), /<video|\.mp4|\.webm|orb-reference/i, p);
});

test('no external network resources (works offline, no CDN)', () => {
  for (const p of sources) {
    const src = text(p);
    // Allow URLs inside comments that credit sources.
    const code = stripComments(src);
    assert.doesNotMatch(code, /(src|href)\s*=\s*["']https?:/i, p);
    assert.doesNotMatch(code, /@import\s+url\(\s*["']?https?:/i, p);
    assert.doesNotMatch(code, /import\s[^;]*from\s+["']https?:/i, p);
  }
});

test('microphone preview cannot record, store or upload audio', () => {
  for (const name of ['voice-input.js', 'main.js', 'orb.js', 'orb-page.js', 'audio-analysis.js']) {
    const src = stripComments(text(join(STATIC, 'js', name)));
    for (const forbidden of [/MediaRecorder/, /\bfetch\s*\(/, /XMLHttpRequest/, /sendBeacon/, /new\s+WebSocket/, /localStorage/, /indexedDB/, /createObjectURL/]) {
      assert.doesNotMatch(src, forbidden, `${name} must not use ${forbidden}`);
    }
  }
});

test('audio leaves the browser only through api.js, and only to this server', () => {
  const js = files.filter((p) => p.endsWith('.js'));
  for (const p of js) {
    const src = stripComments(text(p));
    for (const forbidden of [/MediaRecorder/, /XMLHttpRequest/, /sendBeacon/, /new\s+WebSocket/, /localStorage/, /sessionStorage/, /indexedDB/]) {
      assert.doesNotMatch(src, forbidden, `${p} must not use ${forbidden}`);
    }
    if (!p.endsWith('/api.js')) assert.doesNotMatch(src, /\bfetch\s*\(/, `${p}: network calls belong in api.js`);
  }
  const api = stripComments(text(join(STATIC, 'js', 'api.js')));
  const calls = [...api.matchAll(/request\('(?:GET|POST|PUT|DELETE)', `?'?([^'`,]+)/g)];
  assert.ok(calls.length >= 4, `expected the API calls in api.js, found ${calls.length}`);
  for (const m of api.matchAll(/request\('(?:GET|POST|PUT|DELETE)', `?'?([^'`,]+)/g)) {
    assert.match(m[1], /^\/api\//, `api.js must only call same-origin /api/ paths, found ${m[1]}`);
  }
});

test('recorder uses an AudioWorklet and asks for unprocessed mono audio', () => {
  const audio = stripComments(text(join(STATIC, 'js', 'recorder-audio.js')));
  assert.match(audio, /audioWorklet\.addModule\('js\/pcm-capture-worklet\.js'\)/);
  assert.doesNotMatch(audio, /ScriptProcessor/);
  for (const key of ['echoCancellation: false', 'noiseSuppression: false', 'autoGainControl: false']) assert.ok(audio.includes(key), key);
  const worklet = text(join(STATIC, 'js', 'pcm-capture-worklet.js'));
  assert.match(worklet, /registerProcessor\('pcm-capture'/);
  const api = text(join(STATIC, 'js', 'api.js'));
  assert.match(api, /sampleRate: String\(sampleRate\)/, 'the real sample rate is sent with each take');
  assert.match(api, /'Content-Type': 'application\/octet-stream'/);
});

test('task wording covers exactly the Java TaskType codes', async () => {
  const java = readFileSync(new URL('../../main/java/com/neurovox/model/TaskType.java', import.meta.url), 'utf8');
  const javaCodes = [...java.matchAll(/^\s+[A-Z_]+\("([a-z_]+)"/gm)].map((m) => m[1]);
  const { TASK_COPY } = await import(join(STATIC, 'js', 'tasks.js'));
  assert.deepEqual(Object.keys(TASK_COPY).sort(), [...javaCodes].sort());
  assert.equal(javaCodes.length, 4);
  for (const [code, copy] of Object.entries(TASK_COPY)) {
    assert.ok(copy.title && copy.instruction && copy.tip, `${code} needs title, instruction and tip`);
  }
});

test('shaders declare shared uniforms with matching precision', async () => {
  const { ORB_VERTEX, ORB_FRAGMENT } = await import(join(STATIC, 'js', 'shaders.js'));
  const uniforms = (src, prec) => new Map([...src.matchAll(/uniform\s+(?:(lowp|mediump|highp)\s+)?(\w+)\s+(\w+)/g)]
    .map((m) => [m[3], m[1] || prec]));
  const vs = uniforms(ORB_VERTEX, 'highp');
  const fs = uniforms(ORB_FRAGMENT, 'mediump');
  for (const [name, precision] of fs) {
    if (vs.has(name)) assert.equal(vs.get(name), precision, `uniform ${name} precision differs`);
  }
});

test('pages load their scripts as local ES modules and keep the non-diagnostic statement', () => {
  const html = text(join(STATIC, 'index.html'));
  assert.match(html, /<script type="module" src="js\/main\.js"><\/script>/);
  assert.match(html, /not a diagnosis|is not[\s\S]*diagnosis/i);
  assert.match(html, /Nothing is recorded, saved or sent anywhere/);
  assert.match(html, /href="record\.html"/, 'the landing page leads to the recorder');
  const record = text(join(STATIC, 'record.html'));
  assert.match(record, /<script type="module" src="js\/record\.js"><\/script>/);
  assert.match(text(join(STATIC, 'js', 'record.js')), /does not diagnose/);
});

test('the website itself explains the whole journey in eight steps', () => {
  const html = text(join(STATIC, 'index.html'));
  const guide = html.slice(html.indexOf('id="guide"'), html.indexOf('id="session"'));
  assert.ok(guide.length > 0, 'index.html needs the #guide section');
  assert.equal((guide.match(/<li data-reveal>/g) || []).length, 8);
  for (const phrase of [/not a diagnosis/i, /quiet room/i, /Allow microphone/, /four tasks/i, /Review this recording/, /Java/, /Read the measurements/i, /delete/i]) {
    assert.match(guide, phrase);
  }
  const record = stripComments(text(join(STATIC, 'js', 'record.js')));
  for (const label of ['Continue to room setup', 'Continue to microphone check', 'Review this recording', 'Save this take', 'Record again', 'Stop recording', 'Back']) {
    assert.ok(record.includes(label), `recorder label "${label}"`);
  }
});

test('report and history pages carry the disclaimer and never claim confidence or normal ranges', () => {
  for (const page of ['results.html', 'history.html']) {
    const html = text(join(STATIC, page));
    assert.match(html, /does not diagnose/i, page);
    assert.match(html, /<script type="module" src="js\/(results|history)\.js"><\/script>/, page);
  }
  for (const p of sources) {
    const code = stripComments(text(p));
    // Negations such as "No normal range is drawn" are fine; range data or confidence wording is not.
    assert.doesNotMatch(code, /clinical confidence|confidence score|normalRange|referenceRange|typical range|healthy range/i, p);
    assert.doesNotMatch(code, /UPDRS|Hoehn|Yahr/i, p);   // no disease-staging output anywhere
  }
});

test('pages that show server text never use innerHTML', () => {
  for (const name of ['record.js', 'results.js', 'history.js', 'ui.js', 'chart.js']) {
    assert.doesNotMatch(stripComments(text(join(STATIC, 'js', name))), /innerHTML|outerHTML|insertAdjacentHTML|document\.write/, name);
  }
});
