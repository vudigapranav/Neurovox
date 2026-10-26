package com.neurovox.features;

import com.neurovox.TestSignals;
import com.neurovox.analysis.RecordingAnalysis;
import com.neurovox.analysis.RecordingAnalyzer;
import com.neurovox.audio.AudioClip;
import com.neurovox.dsp.AutocorrelationPitchDetector;
import com.neurovox.dsp.PitchContour;
import com.neurovox.dsp.PitchSettings;
import com.neurovox.dsp.PulseTrain;
import com.neurovox.model.FeatureValue;
import com.neurovox.model.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint D (planning Sprint 6): every formula on inputs whose answer is known by hand or by construction. */
class FeatureFormulaTest {

    private static final PitchSettings S = PitchSettings.DEFAULT;
    private static final int FS = 16_000;

    private static PulseTrain pulsesFromPeriods(double... periodsMs) {
        double[] t = new double[periodsMs.length + 1];
        for (int i = 0; i < periodsMs.length; i++) {
            t[i + 1] = t[i] + periodsMs[i] / 1000;
        }
        return new PulseTrain(t);
    }

    // ---------- jitter by hand ----------

    @Test
    void jitterFormulasOnHandWorkedPeriods() {
        // Periods 10, 11, 10, 11, 10 ms. Mean 10.4 ms.
        JitterMeasures.Jitter j = JitterMeasures.compute(pulsesFromPeriods(10, 11, 10, 11, 10), S);
        assertEquals(5, j.periods());
        assertEquals(0.0104, j.meanPeriod(), 1e-12);
        assertEquals(0.001, j.absolute(), 1e-12);                 // every |ΔT| = 1 ms
        assertEquals(0.001 / 0.0104, j.local(), 1e-12);
        assertEquals((2.0 / 3 / 1000) / 0.0104, j.rap(), 1e-12);  // |11 − 31/3| = |10 − 32/3| = 2/3 ms
        assertEquals((0.4 / 1000) / 0.0104, j.ppq5(), 1e-12);     // one window: |10 − 52/5| = 0.4 ms
        assertEquals(3 * j.rap(), j.ddp(), 0.0);                  // DDP is exactly 3 × RAP
    }

    @Test
    void jitterSkipsIntervalsThatSpanAMissedPulse() {
        // A 20 ms interval (a missed pulse) differs by more than the factor 1.3 from both neighbours.
        JitterMeasures.Jitter j = JitterMeasures.compute(pulsesFromPeriods(10, 10, 20, 10, 10), S);
        assertEquals(4, j.periods());
        assertEquals(0.0, j.absolute(), 1e-15);
    }

    @Test
    void jitterIsUnavailableWithTooFewPeriods() {
        JitterMeasures.Jitter j = JitterMeasures.compute(pulsesFromPeriods(10), S);
        assertTrue(Double.isNaN(j.local()));
        assertTrue(Double.isNaN(j.ppq5()));
        assertTrue(Double.isNaN(JitterMeasures.compute(pulsesFromPeriods(10, 10, 10), S).ppq5()));
        assertFalse(Double.isNaN(JitterMeasures.compute(pulsesFromPeriods(10, 10, 10), S).rap()));
    }

    // ---------- shimmer by hand ----------

    @Test
    void shimmerFormulasOnHandWorkedAmplitudes() {
        double[] t = {0, 0.01, 0.02, 0.03, 0.04, 0.05};
        double[] a = {1.0, 1.2, 1.0, 1.2, 1.0, 1.2};
        ShimmerMeasures.Shimmer s = ShimmerMeasures.compute(new ShimmerMeasures.Amplitudes(t, a), S);
        double mean = (1.0 + 1.2 + 1.0 + 1.2 + 1.0) / 5;            // all points except the last (Praat)
        assertEquals(0.2 / mean, s.local(), 1e-12);
        assertEquals(20 * Math.log10(1.2), s.localDb(), 1e-12);
        double apq3Term = Math.abs(1.2 - (1.0 + 1.2 + 1.0) / 3);     // = |1.0 − 3.4/3| as well
        assertEquals(apq3Term / mean, s.apq3(), 1e-12);
        assertEquals(3 * s.apq3(), s.dda(), 0.0);                    // DDA is exactly 3 × APQ3
        assertTrue(Double.isNaN(s.apq11()));                         // needs 11 points
    }

    @Test
    void hannWindowedRmsOfSineIsAmplitudeOverRoot2() {
        AudioClip sine = TestSignals.sine(100, 0.5, FS, 0.6);
        // A window covering whole cycles symmetrically: RMS of a sine is A/√2 only on average, so use a long window.
        double rms = ShimmerMeasures.hannWindowedRms(sine, 0.25, 0.1, 0.1);
        assertEquals(0.6 / Math.sqrt(2), rms, 0.01);
    }

    // ---------- HNR ----------

    @Test
    void hnrOfSinePlusNoiseMatchesTheSignalToNoiseRatio() {
        for (double snrDb : new double[] {5, 10, 20}) {
            double noiseRms = (0.5 / Math.sqrt(2)) / Math.pow(10, snrDb / 20);
            AudioClip clip = TestSignals.add(TestSignals.sine(160, 2.0, FS, 0.5), TestSignals.whiteNoise(2.0, FS, noiseRms, 11));
            PitchContour pitch = new AutocorrelationPitchDetector().detect(clip);
            HarmonicityMeasures.Harmonicity h = HarmonicityMeasures.compute(pitch, 0.2, 1.8);
            assertEquals(snrDb, h.hnrDb(), 1.0, "SNR " + snrDb);
            double r = Math.pow(10, snrDb / 10) / (1 + Math.pow(10, snrDb / 10));
            assertEquals((1 - r) / r, h.nhr(), 0.35 * (1 - r) / r);
        }
    }

    @Test
    void hnrOfPureSineIsHighAndFinite() {
        PitchContour pitch = new AutocorrelationPitchDetector().detect(TestSignals.sine(200, 1.0, FS, 0.5));
        HarmonicityMeasures.Harmonicity h = HarmonicityMeasures.compute(pitch, 0, 1);
        assertTrue(h.hnrDb() > 20 && Double.isFinite(h.hnrDb()), "HNR " + h.hnrDb());
    }

    @Test
    void hnrOfNoiseOrSilenceIsUnavailable() {
        PitchContour silent = new AutocorrelationPitchDetector().detect(TestSignals.silence(1, FS));
        assertTrue(Double.isNaN(HarmonicityMeasures.compute(silent, 0, 1).hnrDb()));
    }

    // ---------- MFCC ----------

    @Test
    void melScaleAndDct() {
        assertEquals(1000, MfccExtractor.hzToMel(1000), 0.1);       // the mel scale is anchored at 1 kHz ≈ 1000 mel
        assertEquals(440, MfccExtractor.melToHz(MfccExtractor.hzToMel(440)), 1e-9);
        double[] flat = new double[26];
        java.util.Arrays.fill(flat, 3.0);
        for (double c : MfccExtractor.dct2(flat, 13)) {
            assertEquals(0.0, c, 1e-9);                              // a flat log spectrum has no shape
        }
    }

    @Test
    void mfccIs13DimensionalAndFinite() {
        MfccExtractor.Mfcc m = MfccExtractor.compute(TestSignals.sawtooth(140, 1.0, FS, 0.4));
        assertEquals(13, m.mean().length);
        assertEquals(13, m.sd().length);
        assertTrue(m.frames() > 50);
        for (int j = 0; j < 13; j++) {
            assertTrue(Double.isFinite(m.mean()[j]) && Double.isFinite(m.sd()[j]));
        }
        assertEquals(0, MfccExtractor.compute(TestSignals.silence(1, FS)).frames());
    }

    @Test
    void filterBankTrianglesCoverTheBand() {
        double[][] h = MfccExtractor.filterBank(512, FS, 26, 0, 8000);
        assertEquals(26, h.length);
        for (double[] filter : h) {
            double peak = java.util.Arrays.stream(filter).max().orElseThrow();
            assertTrue(peak > 0.3 && peak <= 1.0);
        }
    }

    // ---------- whole vowel pipeline ----------

    private static Map<String, FeatureValue> analyse(double[] periods, double[] amps, int fs) {
        AudioClip vowel = TestSignals.vowel(periods, amps, fs, 0.3);
        RecordingAnalysis analysis = new RecordingAnalyzer().analyze(TaskType.SUSTAINED_VOWEL, vowel);
        return analysis.features().stream().collect(Collectors.toMap(FeatureValue::name, f -> f));
    }

    @Test
    void perfectlyPeriodicVowelHasNearZeroJitterAndShimmer() {
        int n = 600;   // 5 s at 120 Hz
        Map<String, FeatureValue> f = analyse(TestSignals.periods(120, n, 0, 1), TestSignals.amplitudes(n, 0, 1), 48_000);
        assertEquals(120, f.get("MDVP:Fo(Hz)").value(), 0.2);
        assertTrue(f.get("MDVP:Jitter(%)").value() < 0.0005, "jitter " + f.get("MDVP:Jitter(%)").value());
        assertTrue(f.get("MDVP:Shimmer").value() < 0.005, "shimmer " + f.get("MDVP:Shimmer").value());
        // Window-corrected autocorrelation is exact only for stationary signals; for a pulse-like periodic vowel it
        // saturates near 29 dB (a pure sine reaches > 40 dB, see hnrOfPureSineIsHighAndFinite).
        assertTrue(f.get("HNR").value() > 25, "HNR " + f.get("HNR").value());
    }

    @Test
    void injectedJitterIsRecovered() {
        int n = 600;
        for (double jitter : new double[] {0.005, 0.01, 0.02}) {
            double[] periods = TestSignals.periods(120, n, jitter, 42);
            Map<String, FeatureValue> f = analyse(periods, TestSignals.amplitudes(n, 0, 1), 48_000);
            double expected = TestSignals.trueLocalJitter(periods);
            double measured = f.get("MDVP:Jitter(%)").value();
            // Cross-correlation pulses (Praat's method) compare one-period windows that overlap the neighbouring
            // cycles, which smooths cycle-to-cycle differences slightly: expect a small under-estimate.
            assertEquals(expected, measured, 0.20 * expected, "injected σ=" + jitter);
            assertTrue(measured < expected * 1.05, "cross-correlation pulses should not exaggerate jitter");
        }
    }

    @Test
    void injectedShimmerRaisesShimmer() {
        int n = 600;
        double[] periods = TestSignals.periods(120, n, 0, 1);
        double low = analyse(periods, TestSignals.amplitudes(n, 0.02, 5), 48_000).get("MDVP:Shimmer").value();
        double high = analyse(periods, TestSignals.amplitudes(n, 0.08, 5), 48_000).get("MDVP:Shimmer").value();
        assertTrue(high > 2.5 * low, "low " + low + " high " + high);
    }

    @Test
    void vowelFeaturesComeInTheFixedUciOrder() {
        int n = 400;
        AudioClip vowel = TestSignals.vowel(TestSignals.periods(150, n, 0.01, 3), TestSignals.amplitudes(n, 0.03, 3), 44_100, 0.3);
        List<FeatureValue> features = new RecordingAnalyzer().analyze(TaskType.SUSTAINED_VOWEL, vowel).features();
        assertEquals(UciFeature.keys(), features.subList(0, 16).stream().map(FeatureValue::name).toList());
        for (FeatureValue v : features.subList(0, 16)) {
            assertTrue(v.isAvailable(), v.name());
        }
        assertTrue(features.stream().noneMatch(v -> UciFeature.NOT_COMPUTED.contains(v.name())));
    }

    @Test
    void silentVowelGivesUnavailableValuesWithAReason() {
        RecordingAnalysis a = new RecordingAnalyzer().analyze(TaskType.SUSTAINED_VOWEL, TestSignals.silence(4, 48_000));
        for (FeatureValue v : a.features().subList(0, 16)) {
            assertFalse(v.isAvailable(), v.name());
            assertTrue(v.unavailableReason().contains("steady"), v.unavailableReason());
        }
    }
}
