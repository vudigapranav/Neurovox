package com.neurovox.audio;

import com.neurovox.TestSignals;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint B (planning Sprint 4): resampling, framing, windows, pre-emphasis and WAV I/O against known answers. */
class PreprocessingTest {

    // ---------- resampling ----------

    @Test
    void resampling1kHzFrom48kTo16kKeepsFrequencyAndAmplitude() {
        AudioClip in = TestSignals.sine(1000, 1.0, 48_000, 0.5);
        AudioClip out = Resampler.resample(in, 16_000);
        assertEquals(16_000, out.sampleRate());
        assertEquals(16_000, out.length());
        assertEquals(0.5, TestSignals.toneAmplitude(out, 1000), 0.005);
        // Largest component is at 1 kHz: compare with neighbours.
        assertTrue(TestSignals.toneAmplitude(out, 1000) > 100 * TestSignals.toneAmplitude(out, 1500));
    }

    @Test
    void resamplingRejectsAliasOf10kHzTone() {
        // 10 kHz is above the new 8 kHz Nyquist. Without a filter it would fold to 16 − 10 = 6 kHz.
        AudioClip in = TestSignals.sine(10_000, 1.0, 48_000, 0.5);
        AudioClip out = Resampler.resample(in, 16_000);
        double ghost = TestSignals.toneAmplitude(out, 6000);
        double rejectionDb = 20 * Math.log10(0.5 / Math.max(ghost, 1e-12));
        assertTrue(rejectionDb > 60, "alias rejection was only " + rejectionDb + " dB");
        // And for contrast: naive decimation (every 3rd sample) produces the 6 kHz ghost at full strength.
        double[] naive = new double[in.length() / 3];
        for (int i = 0; i < naive.length; i++) {
            naive[i] = in.samples()[3 * i];
        }
        assertEquals(0.5, TestSignals.toneAmplitude(new AudioClip(naive, 16_000), 6000), 0.02);
    }

    @Test
    void nonIntegerRatio44100To16000MatchesAnalyticSine() {
        AudioClip in = TestSignals.sine(440, 1.0, 44_100, 0.8);
        AudioClip out = Resampler.resample(in, 16_000);
        assertEquals(16_000, out.length());
        double maxError = 0;
        for (int i = 1000; i < out.length() - 1000; i++) {   // away from the edges, where the filter has no history
            double expected = 0.8 * Math.sin(2 * Math.PI * 440 * i / 16_000.0);
            maxError = Math.max(maxError, Math.abs(out.samples()[i] - expected));
        }
        assertTrue(maxError < 1e-3, "max error " + maxError);
    }

    @Test
    void sameRateIsACopy() {
        AudioClip in = TestSignals.sine(200, 0.1, 16_000, 0.3);
        AudioClip out = Resampler.resample(in, 16_000);
        assertArrayEquals(in.samples(), out.samples());
    }

    // ---------- framing, windows, pre-emphasis ----------

    @Test
    void framing16000SamplesGives61FullFrames() {
        assertEquals(61, Framer.frameCount(16_000, 512, 256));
        assertEquals(61, Framer.frames(new double[16_000], 512, 256).length);
        assertEquals(0, Framer.frameCount(100, 512, 256));
        double[] x = new double[1024];
        for (int i = 0; i < x.length; i++) {
            x[i] = i;
        }
        double[][] frames = Framer.frames(x, 512, 256);
        assertEquals(256.0, frames[1][0]);
        assertEquals(1023.0, frames[2][511]);
    }

    @Test
    void hammingCoefficientsHaveKnownSumAndEnds() {
        // Σ cos(2πn/(N−1)) over n = 0..N−1 equals 1, so Σ w = 0.54·N − 0.46.
        double[] w = WindowFunction.HAMMING.coefficients(512);
        double sum = 0;
        for (double v : w) {
            sum += v;
        }
        assertEquals(0.54 * 512 - 0.46, sum, 1e-9);
        assertEquals(0.08, w[0], 1e-12);
        assertEquals(0.08, w[511], 1e-12);
        assertEquals(0.0, WindowFunction.HANN.coefficients(64)[0], 1e-12);
    }

    @Test
    void preEmphasisOfConstantSignalIsSmall() {
        double[] dc = new double[100];
        java.util.Arrays.fill(dc, 0.5);
        double[] y = SignalOps.preEmphasis(dc, 0.97);
        for (int i = 1; i < y.length; i++) {
            assertEquals(0.5 * 0.03, y[i], 1e-12);   // (1 − α)·c: 97 % of a constant is removed
        }
        double[] centred = SignalOps.removeDc(dc);
        for (double v : SignalOps.preEmphasis(centred, 0.97)) {
            assertEquals(0.0, v, 1e-12);
        }
    }

    @Test
    void peakNormalizeAndDbfs() {
        double[] y = SignalOps.peakNormalize(new double[] {0.1, -0.25, 0.2});
        assertEquals(-1.0, y[1], 1e-12);
        assertEquals(-6.0206, SignalOps.dbfs(0.5), 1e-4);
        assertEquals(Double.NEGATIVE_INFINITY, SignalOps.dbfs(0));
    }

    // ---------- WAV ----------

    @Test
    void wav16BitRoundTrip() {
        AudioClip in = TestSignals.sine(300, 0.2, 44_100, 0.7);
        AudioClip back = WavCodec.decode(WavCodec.encode16(in));
        assertEquals(44_100, back.sampleRate());
        assertEquals(in.length(), back.length());
        for (int i = 0; i < in.length(); i++) {
            assertEquals(in.samples()[i], back.samples()[i], 1.0 / 32768);
        }
    }

    @Test
    void wavFloatStereoAnd24BitAreDecoded() {
        byte[] stereoFloat = wav(3, 2, 48_000, 32, buf -> buf.putFloat(0.5f).putFloat(-0.25f).putFloat(1f).putFloat(0f));
        AudioClip a = WavCodec.decode(stereoFloat);
        assertEquals(2, a.length());
        assertEquals(0.125, a.samples()[0], 1e-9);
        assertEquals(0.5, a.samples()[1], 1e-9);

        byte[] pcm24 = wav(1, 1, 16_000, 24, buf -> buf.put((byte) 0).put((byte) 0).put((byte) 0x40));  // 0x400000 = 0.5
        assertEquals(0.5, WavCodec.decode(pcm24).samples()[0], 1e-9);
    }

    @Test
    void invalidWavIsRejectedWithAClearMessage() {
        assertThrows(InvalidAudioException.class, () -> WavCodec.decode("hello".getBytes()));
        byte[] adpcm = wav(2, 1, 16_000, 4, buf -> buf.put((byte) 0).put((byte) 0));
        InvalidAudioException e = assertThrows(InvalidAudioException.class, () -> WavCodec.decode(adpcm));
        assertTrue(e.getMessage().contains("Unsupported"));
        byte[] header = java.util.Arrays.copyOf(WavCodec.encode16(TestSignals.silence(0.01, 16_000)), 36);
        assertThrows(InvalidAudioException.class, () -> WavCodec.decode(header));
    }

    private interface Body {
        void write(ByteBuffer buf);
    }

    private static byte[] wav(int format, int channels, int rate, int bits, Body body) {
        ByteBuffer data = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        body.write(data);
        int dataBytes = data.position();
        // A "LIST" chunk before "fmt " checks that unknown chunks are skipped.
        ByteBuffer buf = ByteBuffer.allocate(12 + 12 + 24 + 8 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes()).putInt(buf.capacity() - 8).put("WAVE".getBytes());
        buf.put("LIST".getBytes()).putInt(4).put("INFO".getBytes());
        int blockAlign = Math.max(1, bits / 8) * channels;
        buf.put("fmt ".getBytes()).putInt(16).putShort((short) format).putShort((short) channels).putInt(rate)
                .putInt(rate * blockAlign).putShort((short) blockAlign).putShort((short) bits);
        buf.put("data".getBytes()).putInt(dataBytes).put(data.array(), 0, dataBytes);
        return buf.array();
    }
}
