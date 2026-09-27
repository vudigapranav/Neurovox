// Live microphone analysis for the orb.
//
// AudioReactor reads amplitude and frequency bands from any Web Audio source node.
// The landing-page preview uses it with the microphone; the recorder (a later sprint)
// will attach the same reactor to its capture graph so the orb visualises the take.
//
// Nothing here records, stores or transmits audio: there is no MediaRecorder,
// no buffer retention and no network call. Samples are read into a reusable
// analysis buffer each frame and overwritten on the next one.

import { logBandRanges, bandSpectrum, summariseBands, rms, levelFromRms, follow, SPECTRUM_BANDS } from './audio-analysis.js';

export class AudioReactor {
  constructor(context, source) {
    this.context = context;
    this.analyser = context.createAnalyser();
    this.analyser.fftSize = 2048;
    this.analyser.smoothingTimeConstant = 0.55;
    this.analyser.minDecibels = -92;
    this.analyser.maxDecibels = -22;
    // A muted sink keeps the graph pulling audio in every browser without making any sound.
    this.sink = context.createGain();
    this.sink.gain.value = 0;
    source.connect(this.analyser);
    this.analyser.connect(this.sink);
    this.sink.connect(context.destination);

    this.time = new Float32Array(this.analyser.fftSize);
    this.freq = new Uint8Array(this.analyser.frequencyBinCount);
    this.ranges = logBandRanges(context.sampleRate, this.analyser.fftSize, SPECTRUM_BANDS);
    this.state = { level: 0, low: 0, mid: 0, high: 0, spectrum: new Float32Array(SPECTRUM_BANDS) };
  }

  /** Current smoothed features, all in 0..1. */
  read() {
    this.analyser.getFloatTimeDomainData(this.time);
    this.analyser.getByteFrequencyData(this.freq);
    const target = levelFromRms(rms(this.time));
    const spectrum = bandSpectrum(this.freq, this.ranges);
    const bands = summariseBands(spectrum);
    const s = this.state;
    s.level = follow(s.level, target, 0.4, 0.07);
    s.low = follow(s.low, bands.low, 0.35, 0.08);
    s.mid = follow(s.mid, bands.mid, 0.35, 0.08);
    s.high = follow(s.high, bands.high, 0.4, 0.1);
    for (let i = 0; i < spectrum.length; i++) s.spectrum[i] = follow(s.spectrum[i], spectrum[i], 0.45, 0.1);
    return s;
  }

  disconnect() {
    try { this.analyser.disconnect(); this.sink.disconnect(); } catch { /* already disconnected */ }
  }
}

/** Human-readable reason for a microphone failure. */
export function describeMicError(error) {
  switch (error?.name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return 'Microphone access was declined. The orb still responds to pointer, touch and keyboard.';
    case 'NotFoundError':
    case 'OverconstrainedError':
      return 'No microphone was found. Connect one and try again, or keep exploring with pointer and touch.';
    case 'NotReadableError':
      return 'The microphone is busy in another app. Close it there and try again.';
    case 'InsecureContext':
      return 'Microphone preview needs localhost or HTTPS. Open Neurovox from its local server.';
    case 'Suspended':
      return 'The browser paused audio. Press the button again to start listening.';
    case 'Unsupported':
      return 'This browser cannot provide microphone audio. Pointer, touch and keyboard still work.';
    default:
      return 'The microphone preview could not start. Try again, or keep exploring with pointer and touch.';
  }
}

/**
 * Microphone preview for the landing page.
 * `simulate: true` replaces the microphone with a silent synthetic voice-like
 * source (for automated visual checks); it is only enabled from a URL flag.
 */
export class MicrophonePreview {
  constructor() {
    this.stream = null;
    this.context = null;
    this.reactor = null;
    this.synth = [];
    this.onEnded = null;
  }

  get active() {
    return this.reactor !== null;
  }

  async start({ simulate = false } = {}) {
    if (this.active) return this.reactor;
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) throw namedError('Unsupported');
    if (!simulate) {
      if (!window.isSecureContext) throw namedError('InsecureContext');
      if (!navigator.mediaDevices?.getUserMedia) throw namedError('Unsupported');
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
        video: false,
      });
    }
    try {
      this.context = new AudioCtx();
      // resume() can stay pending without a user gesture; don't let the UI wait on it forever.
      await Promise.race([this.context.resume(), new Promise((r) => setTimeout(r, 1500))]);
      if (this.context.state !== 'running') throw namedError('Suspended');
      const source = simulate ? this.createSyntheticVoice() : this.context.createMediaStreamSource(this.stream);
      this.reactor = new AudioReactor(this.context, source);
      this.stream?.getAudioTracks()[0]?.addEventListener('ended', () => {
        this.stop();
        this.onEnded?.();
      }, { once: true });
      return this.reactor;
    } catch (error) {
      await this.stop();
      throw error;
    }
  }

  /** Sawtooth "glottal" source through two formant filters with a syllable-rate envelope. */
  createSyntheticVoice() {
    const ctx = this.context;
    const glottis = ctx.createOscillator();
    glottis.type = 'sawtooth';
    glottis.frequency.value = 150;
    const vibrato = ctx.createOscillator();
    vibrato.frequency.value = 5;
    const vibratoDepth = ctx.createGain();
    vibratoDepth.gain.value = 6;
    vibrato.connect(vibratoDepth).connect(glottis.frequency);

    const f1 = ctx.createBiquadFilter();
    f1.type = 'bandpass'; f1.frequency.value = 750; f1.Q.value = 4;
    const f2 = ctx.createBiquadFilter();
    f2.type = 'bandpass'; f2.frequency.value = 1200; f2.Q.value = 5;
    const envelope = ctx.createGain();
    envelope.gain.value = 0.35;
    const syllables = ctx.createOscillator();
    syllables.frequency.value = 0.8;
    const syllableDepth = ctx.createGain();
    syllableDepth.gain.value = 0.34;
    syllables.connect(syllableDepth).connect(envelope.gain);

    const mix = ctx.createGain();
    glottis.connect(f1).connect(mix);
    glottis.connect(f2).connect(mix);
    mix.connect(envelope);
    [glottis, vibrato, syllables].forEach((o) => o.start());
    this.synth = [glottis, vibrato, syllables];
    return envelope;
  }

  async stop() {
    this.reactor?.disconnect();
    this.reactor = null;
    this.synth.forEach((o) => { try { o.stop(); } catch { /* not started */ } });
    this.synth = [];
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    if (this.context) await this.context.close().catch(() => {});
    this.context = null;
  }
}

function namedError(name) {
  const error = new Error(name);
  error.name = name;
  return error;
}
