// Calls to the local Neurovox Java server. Errors carry the server's plain-language message.

import { toFloat32LittleEndian } from './pcm.js';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
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
      error?.message || `The server returned an error (${response.status}).`);
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

/** Uploads one take: mono float32 little-endian PCM plus its real sample rate. */
export function uploadRecording(sessionId, taskCode, sampleRate, samples) {
  const query = new URLSearchParams({ sampleRate: String(sampleRate), format: 'pcm_f32le', channels: '1' });
  return request('PUT', `/api/sessions/${encodeURIComponent(sessionId)}/recordings/${encodeURIComponent(taskCode)}?${query}`, {
    body: toFloat32LittleEndian(samples),
    headers: { 'Content-Type': 'application/octet-stream' },
  });
}
