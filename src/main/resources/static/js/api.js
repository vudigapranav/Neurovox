// Calls to the local Neurovox Java server. Errors carry the server's plain-language message.

import { toFloat32LittleEndian } from './pcm.js';

export class ApiError extends Error {
  constructor(status, code, message, details = null) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;   // the full JSON body, e.g. the quality report of a rejected take
  }
}

async function request(method, path, { body, headers } = {}) {
  let response;
  try {
    response = await fetch(path, { method, body, headers, cache: 'no-store' });
  } catch {
    throw new ApiError(0, 'network', 'The Neurovox server is not responding. Check that it is still running, then try again.');
  }
  let json = null;
  try {
    json = await response.json();
  } catch {
    // Non-JSON replies (e.g. from the JDK for malformed requests) fall through to the generic message.
  }
  if (!response.ok) {
    const error = json?.error;
    throw new ApiError(response.status, error?.code || 'http_' + response.status,
      error?.message || `The server returned an error (${response.status}).`, json);
  }
  return json;
}

export function getTasks() {
  return request('GET', '/api/tasks');
}

export function createSession() {
  return request('POST', '/api/sessions');
}

export function getSession(id) {
  return request('GET', `/api/sessions/${encodeURIComponent(id)}`);
}

export function listSessions() {
  return request('GET', '/api/sessions');
}

export function deleteSession(id) {
  return request('DELETE', `/api/sessions/${encodeURIComponent(id)}`);
}

/** Runs the Java analysis of a complete session; resolves with {report}. */
export function analyseSession(id) {
  return request('POST', `/api/sessions/${encodeURIComponent(id)}/analysis`);
}

/** The stored report; rejects with code 'not_analysed' (409) if the session has not been analysed. */
export function getReport(id) {
  return request('GET', `/api/sessions/${encodeURIComponent(id)}/report`);
}

export function getModel() {
  return request('GET', '/api/model');
}

export function deleteAllData() {
  return request('DELETE', '/api/data', {
    body: JSON.stringify({ confirm: 'delete all' }),
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Where the browser can download a take as a WAV file (for opening in Praat). */
export function wavUrl(sessionId, taskCode) {
  return `/api/sessions/${encodeURIComponent(sessionId)}/recordings/${encodeURIComponent(taskCode)}/audio.wav`;
}

/** Uploads one take: mono float32 little-endian PCM plus its real sample rate. */
export function uploadRecording(sessionId, taskCode, sampleRate, samples) {
  const query = new URLSearchParams({ sampleRate: String(sampleRate), format: 'pcm_f32le', channels: '1' });
  return request('PUT', `/api/sessions/${encodeURIComponent(sessionId)}/recordings/${encodeURIComponent(taskCode)}?${query}`, {
    body: toFloat32LittleEndian(samples),
    headers: { 'Content-Type': 'application/octet-stream' },
  });
}
