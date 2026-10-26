package com.neurovox.analysis;

import com.neurovox.audio.AudioClip;
import com.neurovox.audio.Resampler;
import com.neurovox.audio.SignalOps;
import com.neurovox.dsp.AutocorrelationPitchDetector;
import com.neurovox.dsp.PitchContour;
import com.neurovox.dsp.PitchSettings;
import com.neurovox.dsp.PulseExtractor;
import com.neurovox.dsp.PulseTrain;
import com.neurovox.features.F0Statistics;
import com.neurovox.features.HarmonicityMeasures;
import com.neurovox.features.JitterMeasures;
import com.neurovox.features.MfccExtractor;
import com.neurovox.features.ShimmerMeasures;
import com.neurovox.features.UciFeature;
import com.neurovox.model.FeatureValue;
import com.neurovox.model.TaskType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one recorded take into measurements. The pipeline, in order:
 * <ol>
 *   <li>make a 16 kHz copy with the anti-aliasing {@link Resampler} and remove the DC offset (used for MFCC, the
 *       speech tasks and their quality checks);</li>
 *   <li>sustained vowel: keep the native sample rate (at most {@link #MAX_VOWEL_RATE} Hz, resampled down only if
 *       higher), because cycle-to-cycle timing needs every sample — as Praat does, which analyses the file as it is;</li>
 *   <li>track pitch with {@link AutocorrelationPitchDetector} (Praat-style settings);</li>
 *   <li>run the {@link QualityGate};</li>
 *   <li>sustained vowel: find the {@link SteadyRegion}, locate glottal pulses ({@link PulseExtractor}) and compute
 *       the 16 UCI measures in fixed order, then descriptive extras and MFCC (from the 16 kHz copy);</li>
 *   <li>other tasks: descriptive speech measures (voicing, pitch variability, pauses, syllable rate for pa-ta-ka)
 *       and MFCC. These are never given to the research classifier.</li>
 * </ol>
 * A measurement that cannot be computed is stored as unavailable with the reason; nothing is guessed.
 */
public final class RecordingAnalyzer {

    /** Changes whenever a formula or setting changes, so stored results from older code are never mixed in. */
    public static final String EXTRACTOR_VERSION = "nv-features-1";

    /** Highest rate used for the vowel's period analysis; browsers deliver 44.1 or 48 kHz. */
    public static final int MAX_VOWEL_RATE = 48_000;

    private final PitchSettings settings;
    private final AutocorrelationPitchDetector detector;

    public RecordingAnalyzer() {
        this(PitchSettings.DEFAULT);
    }

    public RecordingAnalyzer(PitchSettings settings) {
        this.settings = settings;
        this.detector = new AutocorrelationPitchDetector(settings);
    }

    /**
     * The take prepared for analysis; shared by the quality gate and the feature extraction.
     *
     * @param analysis 16 kHz, DC removed: MFCC and speech measures
     * @param periodic the signal the pitch contour was computed on: native rate for the vowel, else {@code analysis}
     */
    public record Prepared(AudioClip original, AudioClip analysis, AudioClip periodic, PitchContour pitch, SteadyRegion steady) {
    }

    public Prepared prepare(TaskType task, AudioClip original) {
        AudioClip resampled = original.sampleRate() >= Resampler.ANALYSIS_RATE
                ? Resampler.toAnalysisRate(original) : original;
        AudioClip analysis = new AudioClip(SignalOps.removeDc(resampled.samples()), resampled.sampleRate());
        if (task != TaskType.SUSTAINED_VOWEL) {
            return new Prepared(original, analysis, analysis, detector.detect(analysis), null);
        }
        AudioClip capped = original.sampleRate() > MAX_VOWEL_RATE ? Resampler.resample(original, MAX_VOWEL_RATE) : original;
        AudioClip periodic = new AudioClip(SignalOps.removeDc(capped.samples()), capped.sampleRate());
        PitchContour pitch = detector.detect(periodic);
        return new Prepared(original, analysis, periodic, pitch, SteadyRegion.find(pitch));
    }

    public QualityReport check(TaskType task, AudioClip original) {
        Prepared p = prepare(task, original);
        return QualityGate.assess(task, original, p.pitch(), p.steady());
    }

    public RecordingAnalysis analyze(TaskType task, AudioClip original) {
        Prepared p = prepare(task, original);
        QualityReport quality = QualityGate.assess(task, original, p.pitch(), p.steady());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("analysisRateHz", p.periodic().sampleRate());
        details.put("durationSeconds", round(original.durationSeconds(), 3));
        details.put("voicedFraction", round(p.pitch().voicedFraction(), 4));
        details.put("pitchDetector", p.pitch().detector());
        List<FeatureValue> features = new ArrayList<>();
        if (task == TaskType.SUSTAINED_VOWEL) {
            vowelFeatures(p, features, details);
        } else {
            speechFeatures(task, p, features, details);
        }
        return new RecordingAnalysis(task, EXTRACTOR_VERSION, quality, features, details);
    }

    // ---------- sustained vowel ----------

    private void vowelFeatures(Prepared p, List<FeatureValue> out, Map<String, Object> details) {
        SteadyRegion region = p.steady();
        if (region == null || region.duration() < QualityGate.MIN_STEADY_SECONDS) {
            String reason = "No steady voiced stretch of at least 1 s was found in the vowel.";
            for (UciFeature f : UciFeature.ORDER) {
                out.add(FeatureValue.unavailable(f.key(), f.unit(), reason));
            }
            out.add(FeatureValue.unavailable("vowel.analysed_seconds", "s", reason));
            addMfcc(out, MfccExtractor.compute(p.analysis()), "the take");
            return;
        }
        details.put("regionStart", round(region.start(), 3));
        details.put("regionEnd", round(region.end(), 3));

        F0Statistics f0 = F0Statistics.of(p.pitch().voicedValues(region.start(), region.end()));
        PulseTrain all = PulseExtractor.extract(p.periodic(), p.pitch());
        PulseTrain pulses = new PulseTrain(Arrays.stream(all.times())
                .filter(t -> t >= region.start() && t <= region.end()).toArray());
        JitterMeasures.Jitter jitter = JitterMeasures.compute(pulses, settings);
        ShimmerMeasures.Shimmer shimmer = ShimmerMeasures.compute(p.periodic(), pulses, settings);
        HarmonicityMeasures.Harmonicity hnr = HarmonicityMeasures.compute(p.pitch(), region.start(), region.end());
        details.put("pulses", pulses.size());
        details.put("periods", jitter.periods());

        String noPitch = "No voiced pitch frames in the steady part.";
        String jitterReason = "Too few valid vocal cycles (" + jitter.periods() + " periods) were found in the steady part.";
        String shimmerReason = "Too few cycle amplitudes (" + shimmer.amplitudes() + ") could be measured in the steady part.";
        add(out, UciFeature.FO_MEAN, f0.mean(), noPitch);
        add(out, UciFeature.FHI, f0.max(), noPitch);
        add(out, UciFeature.FLO, f0.min(), noPitch);
        add(out, UciFeature.JITTER_LOCAL, jitter.local(), jitterReason);
        add(out, UciFeature.JITTER_ABS, jitter.absolute(), jitterReason);
        add(out, UciFeature.RAP, jitter.rap(), jitterReason);
        add(out, UciFeature.PPQ5, jitter.ppq5(), jitterReason);
        add(out, UciFeature.DDP, jitter.ddp(), jitterReason);
        add(out, UciFeature.SHIMMER_LOCAL, shimmer.local(), shimmerReason);
        add(out, UciFeature.SHIMMER_DB, shimmer.localDb(), shimmerReason);
        add(out, UciFeature.APQ3, shimmer.apq3(), shimmerReason);
        add(out, UciFeature.APQ5, shimmer.apq5(), shimmerReason);
        add(out, UciFeature.APQ11, shimmer.apq11(), shimmerReason);
        add(out, UciFeature.DDA, shimmer.dda(), shimmerReason);
        add(out, UciFeature.NHR, hnr.nhr(), noPitch);
        add(out, UciFeature.HNR, hnr.hnrDb(), noPitch);

        out.add(FeatureValue.of("vowel.analysed_seconds", round(region.duration(), 4), "s"));
        out.add(value("vowel.f0_sd", f0.sd(), "Hz", "Fewer than two voiced frames."));
        out.add(FeatureValue.of("vowel.pulses", pulses.size(), "count"));
        addMfcc(out, MfccExtractor.compute(p.analysis().slice(region.start(), region.end())), "the steady part");
    }

    private static void add(List<FeatureValue> out, UciFeature f, double v, String reason) {
        out.add(value(f.key(), v, f.unit(), reason));
    }

    // ---------- speech tasks (descriptive only) ----------

    private void speechFeatures(TaskType task, Prepared p, List<FeatureValue> out, Map<String, Object> details) {
        LevelProfile levels = LevelProfile.of(p.analysis());
        boolean[] active = levels.active();
        int first = -1;
        int last = -1;
        for (int i = 0; i < active.length; i++) {
            if (active[i]) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        double spanSeconds = first < 0 ? 0 : (last - first + 1) * LevelProfile.STEP_SECONDS;
        details.put("speechSpanSeconds", round(spanSeconds, 3));

        F0Statistics f0 = F0Statistics.of(p.pitch().voicedValues(0, Double.MAX_VALUE));
        String noVoice = "No voiced frames were found.";
        out.add(FeatureValue.of("speech.duration", round(p.original().durationSeconds(), 3), "s"));
        out.add(FeatureValue.of("speech.voiced_fraction", round(p.pitch().voicedFraction(), 4), "fraction"));
        out.add(value("speech.f0_mean", f0.mean(), "Hz", noVoice));
        out.add(value("speech.f0_sd", f0.sdSemitones(), "semitones", noVoice));
        out.add(value("speech.f0_range", f0.rangeSemitones(), "semitones", noVoice));

        double sumDb = 0;
        int n = 0;
        for (int i = 0; i < active.length; i++) {
            if (active[i]) {
                sumDb += levels.levelsDb()[i];
                n++;
            }
        }
        double meanDb = n > 0 ? sumDb / n : Double.NaN;
        double ss = 0;
        for (int i = 0; i < active.length; i++) {
            if (active[i]) {
                ss += (levels.levelsDb()[i] - meanDb) * (levels.levelsDb()[i] - meanDb);
            }
        }
        out.add(value("speech.level_sd", n > 1 ? Math.sqrt(ss / (n - 1)) : Double.NaN, "dB", "Too little speech was detected."));

        int pauses = 0;
        int pauseFrames = 0;
        int run = 0;
        int minPause = (int) Math.round(PAUSE_SECONDS / LevelProfile.STEP_SECONDS);
        for (int i = Math.max(0, first); i <= last; i++) {
            if (!active[i]) {
                run++;
            } else {
                if (run >= minPause) {
                    pauses++;
                    pauseFrames += run;
                }
                run = 0;
            }
        }
        String noSpan = "Less than one second of speech was detected.";
        boolean enough = spanSeconds >= 1.0;
        out.add(value("speech.pause_count", enough ? pauses : Double.NaN, "count", noSpan));
        out.add(value("speech.pause_fraction", enough ? pauseFrames * LevelProfile.STEP_SECONDS / spanSeconds : Double.NaN,
                "fraction", noSpan));
        if (task == TaskType.PA_TA_KA) {
            int syllables = countSyllables(levels.levelsDb(), active, first, last);
            out.add(value("ddk.syllable_rate", enough ? syllables / spanSeconds : Double.NaN, "per s", noSpan));
        }
        addMfcc(out, MfccExtractor.compute(p.analysis()), "the take");
    }

    /** Shortest silence counted as a pause. */
    static final double PAUSE_SECONDS = 0.25;
    /** A syllable peak must rise this far above the dip before it. */
    static final double SYLLABLE_PROMINENCE_DB = 6;
    static final double SYLLABLE_MIN_GAP_SECONDS = 0.08;

    /**
     * Estimated number of syllables: peaks of the (3-frame smoothed) level contour that are active, at least
     * 80 ms apart and at least 6 dB above the lowest level since the previous peak. A heuristic used only to
     * describe the pa-ta-ka rate; it is not a validated syllable counter.
     */
    static int countSyllables(double[] db, boolean[] active, int first, int last) {
        if (first < 0) {
            return 0;
        }
        double[] smooth = new double[db.length];
        for (int i = 0; i < db.length; i++) {
            double sum = 0;
            int k = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(db.length - 1, i + 1); j++) {
                sum += db[j];
                k++;
            }
            smooth[i] = sum / k;
        }
        int minGap = (int) Math.round(SYLLABLE_MIN_GAP_SECONDS / LevelProfile.STEP_SECONDS);
        int count = 0;
        int lastPeak = -minGap;
        double dip = Double.POSITIVE_INFINITY;
        for (int i = Math.max(1, first); i <= Math.min(last, db.length - 2); i++) {
            dip = Math.min(dip, smooth[i]);
            boolean peak = smooth[i] >= smooth[i - 1] && smooth[i] > smooth[i + 1];
            if (peak && active[i] && i - lastPeak >= minGap && smooth[i] - dip >= SYLLABLE_PROMINENCE_DB) {
                count++;
                lastPeak = i;
                dip = smooth[i];
            }
        }
        return count;
    }

    // ---------- shared ----------

    private static void addMfcc(List<FeatureValue> out, MfccExtractor.Mfcc mfcc, String where) {
        String reason = "No frames loud enough for spectral analysis in " + where + ".";
        for (int j = 0; j < MfccExtractor.COEFFICIENTS; j++) {
            boolean ok = mfcc.frames() > 0;
            out.add(value("mfcc.c" + (j + 1) + ".mean", ok ? mfcc.mean()[j] : Double.NaN, "log power", reason));
        }
        for (int j = 0; j < MfccExtractor.COEFFICIENTS; j++) {
            boolean ok = mfcc.frames() > 1;
            out.add(value("mfcc.c" + (j + 1) + ".sd", ok ? mfcc.sd()[j] : Double.NaN, "log power", reason));
        }
    }

    static FeatureValue value(String name, double v, String unit, String reason) {
        return Double.isFinite(v) ? FeatureValue.of(name, v, unit) : FeatureValue.unavailable(name, unit, reason);
    }

    static double round(double v, int places) {
        double scale = Math.pow(10, places);
        return Math.round(v * scale) / scale;
    }
}
