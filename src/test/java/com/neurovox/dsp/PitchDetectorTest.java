package com.neurovox.dsp;

import com.neurovox.TestSignals;
import com.neurovox.audio.AudioClip;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint C (planning Sprint 5): both pitch detectors and the pulse extractor on signals with known answers. */
class PitchDetectorTest {

    private static final Logger LOG = Logger.getLogger(PitchDetectorTest.class.getName());
    private static final int FS = 16_000;

    static List<PitchDetector> detectors() {
        return List.of(new AutocorrelationPitchDetector(), new YinPitchDetector());
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s.length == 0 ? Double.NaN : s[s.length / 2];
    }

    private static double[] middle(PitchContour c) {
        double start = c.times()[0];
        double end = c.times()[c.frames() - 1];
        double span = end - start;
        return c.voicedValues(start + 0.1 * span, end - 0.1 * span);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("detectors")
    void sine200HzIsFoundWithin1Hz(PitchDetector detector) {
        PitchContour c = detector.detect(TestSignals.sine(200, 1.0, FS, 0.5));
        double[] mid = middle(c);
        assertTrue(mid.length > 0.95 * c.frames() * 0.8, detector.name() + " voiced frames: " + mid.length);
        for (double f : mid) {
            assertEquals(200, f, 1.0, detector.name());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("detectors")
    void sawtooth200HzIsNot100Or400(PitchDetector detector) {
        PitchContour c = detector.detect(TestSignals.sawtooth(200, 1.0, FS, 0.5));
        for (double f : middle(c)) {
            assertEquals(200, f, 1.0, detector.name());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("detectors")
    void strongSecondHarmonicDoesNotCauseOctaveError(PitchDetector detector) {
        // 0.3·sin(ωt) + sin(2ωt): the period is still 1/150 s, but half-period lags correlate strongly.
        AudioClip a = TestSignals.sine(150, 1.0, FS, 0.15);
        AudioClip b = TestSignals.sine(300, 1.0, FS, 0.5);
        PitchContour c = detector.detect(TestSignals.add(a, b));
        double[] mid = middle(c);
        long octave = Arrays.stream(mid).filter(f -> Math.abs(f - 300) < 15 || Math.abs(f - 75) < 5).count();
        assertEquals(0, octave, detector.name() + " octave errors in " + mid.length + " frames");
        assertEquals(150, median(mid), 1.0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("detectors")
    void lowAndHighVoicesAreTracked(PitchDetector detector) {
        for (double hz : new double[] {90, 130, 240, 400}) {
            PitchContour c = detector.detect(TestSignals.sawtooth(hz, 0.8, FS, 0.4));
            assertEquals(hz, median(middle(c)), hz * 0.005, detector.name() + " at " + hz);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("detectors")
    void silenceIsUnvoiced(PitchDetector detector) {
        PitchContour c = detector.detect(TestSignals.silence(1.0, FS));
        assertTrue(c.frames() > 50);
        assertEquals(0, c.voicedFrames(), detector.name());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("detectors")
    void whiteNoiseIsMostlyUnvoiced(PitchDetector detector) {
        PitchContour c = detector.detect(TestSignals.whiteNoise(1.0, FS, 0.1, 7));
        assertTrue(c.voicedFraction() < 0.10, detector.name() + " voiced " + c.voicedFraction());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("detectors")
    void silenceThenVowelIsUnvoicedThenVoiced(PitchDetector detector) {
        double[] periods = TestSignals.periods(120, 120, 0, 1);
        AudioClip vowel = TestSignals.vowel(periods, TestSignals.amplitudes(120, 0, 1), FS, 0.5);
        PitchContour c = detector.detect(vowel);
        assertEquals(0.0, c.f0()[0]);
        double[] voiced = c.voicedValues(0.7, 1.3);
        assertTrue(voiced.length > 50, detector.name());
        assertEquals(120, median(voiced), 0.5, detector.name());
    }

    @Test
    void autocorrelationStrengthReflectsNoise() {
        PitchDetector ac = new AutocorrelationPitchDetector();
        AudioClip clean = TestSignals.sine(200, 1.0, FS, 0.5);
        AudioClip noisy = TestSignals.add(clean, TestSignals.whiteNoise(1.0, FS, 0.5 / Math.sqrt(2) / Math.sqrt(10), 3));  // 10 dB
        double rClean = median(strengths(ac.detect(clean)));
        double rNoisy = median(strengths(ac.detect(noisy)));
        assertTrue(rClean > 0.999, "clean r = " + rClean);
        assertEquals(10.0 / 11.0, rNoisy, 0.02);   // periodic share of power at 10 dB SNR
    }

    private static double[] strengths(PitchContour c) {
        double[] out = new double[c.voicedFrames()];
        int k = 0;
        for (int i = 0; i < c.frames(); i++) {
            if (c.isVoiced(i)) {
                out[k++] = c.strength()[i];
            }
        }
        return out;
    }

    @Test
    void pulsesOfAPeriodicVowelAreOnePerCycle() {
        double f0 = 130;                           // 369.2 samples per period at 48 kHz: not a whole number
        int cycles = 260;
        double[] periods = TestSignals.periods(f0, cycles, 0, 1);
        // Pulses are located at the native rate, as in the application (see RecordingAnalyzer).
        AudioClip vowel = TestSignals.vowel(periods, TestSignals.amplitudes(cycles, 0, 1), 48_000, 0.2);
        PitchContour pitch = new AutocorrelationPitchDetector().detect(vowel);
        PulseTrain pulses = PulseExtractor.extract(vowel, pitch);
        assertTrue(Math.abs(pulses.size() - cycles) <= 8, "pulses " + pulses.size());
        double[] p = pulses.periods(0.8 / 600, 1.25 / 75, 1.3);
        double mean = Arrays.stream(p).average().orElseThrow();
        assertEquals(1 / f0, mean, 1e-7);
        // The first and last cycle border silence, so their waveform differs (no ringing from a previous cycle);
        // the analysis region trims onset and offset, so only the steady cycles are held to the tight bound.
        double maxDeviation = Arrays.stream(p, 1, p.length - 1).map(v -> Math.abs(v - 1 / f0)).max().orElseThrow();
        // A quarter of a sample at 48 kHz is 5.2 µs; parabolic refinement of the correlation peak stays inside that.
        assertTrue(maxDeviation < 5.2e-6, "max period error " + maxDeviation + " s");
        assertTrue(com.neurovox.features.JitterMeasures.compute(pulses, PitchSettings.DEFAULT).local() < 0.001);
    }

    @Test
    void timingOfBothDetectorsIsLogged() {
        AudioClip clip = TestSignals.sawtooth(150, 5.0, FS, 0.4);
        for (PitchDetector d : detectors()) {
            d.detect(clip);                         // warm-up for the JIT
            long start = System.nanoTime();
            d.detect(clip);
            double ms = (System.nanoTime() - start) / 1e6;
            LOG.info(() -> String.format("%s: %.1f ms for 5 s of audio", d.name(), ms));
            assertTrue(ms < 5000);
        }
    }
}
