// AudioWorklet processor that captures mono Float32 PCM on the audio rendering thread.
//
// The main thread sends {type: 'start', samples: N}. The processor then copies exactly N samples
// (downmixing to mono by averaging channels), posting them in chunks, and finishes with {type: 'done'}.
// Counting samples here, rather than using a timer on the main thread, makes every take exactly
// the requested length at the context's real sample rate.

class PcmCaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.remaining = 0;
    this.chunk = new Float32Array(4096);
    this.filled = 0;
    this.port.onmessage = ({ data }) => {
      if (data.type === 'start') {
        this.remaining = data.samples;
        this.filled = 0;
      } else if (data.type === 'cancel') {
        this.remaining = 0;
        this.filled = 0;
        this.port.postMessage({ type: 'cancelled' });
      }
    };
  }

  process(inputs) {
    if (this.remaining > 0) {
      const input = inputs[0] || [];
      const channels = input.length;
      const frames = channels ? input[0].length : 128;
      for (let i = 0; i < frames && this.remaining > 0; i++) {
        let sum = 0;
        for (let c = 0; c < channels; c++) sum += input[c][i];
        // With no input connected, silence is recorded so the take still has the right length.
        this.chunk[this.filled++] = channels ? sum / channels : 0;
        this.remaining--;
        if (this.filled === this.chunk.length) this.flush();
      }
      if (this.remaining === 0) {
        this.flush();
        this.port.postMessage({ type: 'done' });
      }
    }
    return true;
  }

  flush() {
    if (this.filled === 0) return;
    const out = this.chunk.slice(0, this.filled);
    this.port.postMessage({ type: 'samples', samples: out }, [out.buffer]);
    this.filled = 0;
  }
}

registerProcessor('pcm-capture', PcmCaptureProcessor);
