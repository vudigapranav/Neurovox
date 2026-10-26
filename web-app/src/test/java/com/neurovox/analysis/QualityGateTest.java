package com.neurovox.analysis;

import com.neurovox.TestSignals;
import com.neurovox.audio.AudioClip;
import com.neurovox.model.TaskType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint B (planning Sprint 12, item 2): the audio-quality gate rejects unusable takes with a way to recover. */
class QualityGateTest {

    private final RecordingAnalyzer analyzer = new RecordingAnalyzer();

    private static AudioClip vowel(double seconds, double gain) {
        int n = (int) (seconds * 130);
        AudioClip v = TestSignals.vowel(TestSignals.periods(130, n, 0.004, 1), TestSignals.amplitudes(n, 0.02, 1), 48_000, 0.2);
        double[] x = v.samples().clone();
        for (int i = 0; i < x.length; i++) {
            x[i] = Math.max(-1, Math.min(1, x[i] * gain));
        }
        return new AudioClip(x, 48_000);
    }

    private String failure(QualityReport r) {
        return r.firstFailure().map(QualityReport.Check::code).orElse("none");
    }

    @Test
    void goodVowelPasses() {
        QualityReport r = analyzer.check(TaskType.SUSTAINED_VOWEL, vowel(5, 1));
        assertEquals(QualityReport.Status.PASS, r.status(), r.problems().toString());
    }

    @Test
    void silenceFailsAsTooQuiet() {
        QualityReport r = analyzer.check(TaskType.SUSTAINED_VOWEL, TestSignals.silence(5, 48_000));
        assertEquals(QualityReport.Status.FAIL, r.status());
        assertEquals("too_quiet", failure(r));
        assertFalse(r.firstFailure().orElseThrow().recovery().isBlank());
    }

    @Test
    void veryQuietTakeFails() {
        QualityReport r = analyzer.check(TaskType.SUSTAINED_VOWEL, vowel(5, 0.001));   // about −66 dBFS
        assertEquals("too_quiet", failure(r));
    }

    @Test
    void clippedTakeFails() {
        QualityReport r = analyzer.check(TaskType.SUSTAINED_VOWEL, vowel(5, 20));
        assertEquals("clipped", failure(r));
        assertTrue(r.firstFailure().orElseThrow().recovery().contains("further"));
    }

    @Test
    void shortTakeFails() {
        assertEquals("too_short", failure(analyzer.check(TaskType.SUSTAINED_VOWEL, vowel(1.5, 1))));
    }

    @Test
    void noiseInsteadOfVowelFails() {
        QualityReport r = analyzer.check(TaskType.SUSTAINED_VOWEL, TestSignals.whiteNoise(5, 48_000, 0.05, 2));
        assertEquals("no_voice", failure(r));
    }

    @Test
    void lowSampleRateFails() {
        assertEquals("low_sample_rate", failure(analyzer.check(TaskType.READING_PASSAGE, TestSignals.sine(200, 12, 8000, 0.3))));
    }

    @Test
    void steadyNoiseUnderSpeechTaskWarns() {
        AudioClip speechLike = TestSignals.add(vowel(12, 0.5), TestSignals.whiteNoise(12, 48_000, 0.05, 4));
        QualityReport r = analyzer.check(TaskType.READING_PASSAGE, speechLike);
        assertTrue(r.problems().stream().anyMatch(c -> c.code().equals("noisy")), r.problems().toString());
        assertEquals(QualityReport.Status.WARN, r.status());
    }
}
