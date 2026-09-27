// Microphone capture for the guided session.
//
// One microphone stream and one AudioContext are opened for the whole session:
//   mic -> MediaStreamSource -> AudioWorkletNode('pcm-capture') -> muted gain -> destination
//                            -> AudioReactor (drives the orb)
// The context runs at the device's real sample rate, which is sent to the server with each take.
// Takes are kept in memory only until they are uploaded or discarded.

import { AudioReactor } from './voice-input.js';
import { concatChunks } from './pcm.js';

export class MicSession {
  constructor() {
    this.stream = null;
    this.context = null;
    this.source = null;
    this.node = null;
    this.reactor = null;
    this.playback = null;
    this.pending = null;
    this.onInterrupted = null;
  }

  get sampleRate() {
    return this.context ? this.context.sampleRate : 0;
  }

  /** Asks for the microphone and builds the capture graph. Throws the getUserMedia error on failure. */
  async open() {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!window.isSecureContext) throw namedError('InsecureContext');
    if (!AudioCtx || !navigator.mediaDevices?.getUserMedia || !window.AudioWorkletNode) throw namedError('Unsupported');
    this.stream = await navigator.mediaDevices.getUserMedia({
      // Raw voice: browser processing would alter exactly the features Neurovox measures.
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
      video: false,
    });
    try {
      this.context = new AudioCtx();
      await this.context.resume();
      await this.context.audioWorklet.addModule('js/pcm-capture-worklet.js');
      this.source = this.context.createMediaStreamSource(this.stream);
      this.node = new AudioWorkletNode(this.context, 'pcm-capture', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
      const mute = this.context.createGain();
      mute.gain.value = 0;
      this.source.connect(this.node);
      this.node.connect(mute).connect(this.context.destination);
      this.reactor = new AudioReactor(this.context, this.source);
    } catch (error) {
      await this.close();
      throw error;
    }
    const track = this.stream.getAudioTracks()[0];
    track?.addEventListener('ended', () => {
      this.cancel();
      this.onInterrupted?.('device');
    });
  }

  /**
   * Records exactly `seconds` of audio. Resolves with a Float32Array; rejects with an AbortError
   * if cancelled. `onProgress(fraction)` is called as chunks arrive.
   */
  async record(seconds, { onProgress = () => {} } = {}) {
    if (!this.node) throw new Error('Microphone is not open');
    if (this.context.state !== 'running') await this.context.resume();
    const total = Math.round(seconds * this.context.sampleRate);
    const chunks = [];
    let received = 0;
    return new Promise((resolve, reject) => {
      this.pending = { reject };
      this.node.port.onmessage = ({ data }) => {
        if (data.type === 'samples') {
          chunks.push(data.samples);
          received += data.samples.length;
          onProgress(Math.min(1, received / total));
        } else if (data.type === 'done') {
          this.pending = null;
          resolve(concatChunks(chunks, received));
        } else if (data.type === 'cancelled') {
          this.pending = null;
          reject(new DOMException('Recording cancelled', 'AbortError'));
        }
      };
      this.node.port.postMessage({ type: 'start', samples: total });
    });
  }

  cancel() {
    if (this.pending && this.node) this.node.port.postMessage({ type: 'cancel' });
  }

  /** Plays a take back through the speakers. Returns an AudioReactor for the playback (for the orb). */
  play(samples, onEnded) {
    this.stopPlayback();
    const buffer = this.context.createBuffer(1, samples.length, this.context.sampleRate);
    buffer.copyToChannel(samples, 0);
    const node = this.context.createBufferSource();
    node.buffer = buffer;
    node.connect(this.context.destination);
    const reactor = new AudioReactor(this.context, node);
    node.onended = () => {
      if (this.playback?.node === node) this.playback = null;
      reactor.disconnect();
      onEnded?.();
    };
    node.start();
    this.playback = { node, reactor };
    return reactor;
  }

  stopPlayback() {
    if (!this.playback) return;
    const { node } = this.playback;
    this.playback = null;
    try { node.stop(); } catch { /* already stopped */ }
  }

  async close() {
    this.cancel();
    this.stopPlayback();
    this.reactor?.disconnect();
    this.reactor = null;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    if (this.context) await this.context.close().catch(() => {});
    this.context = null;
    this.node = null;
    this.source = null;
  }
}

function namedError(name) {
  const error = new Error(name);
  error.name = name;
  return error;
}
