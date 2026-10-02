package com.neurovox.analysis;

import com.neurovox.audio.AudioClip;
import com.neurovox.audio.SignalOps;

import java.util.Arrays;

/**
 * Short-term loudness of a take: the RMS level of 20 ms frames every 10 ms, in dBFS (0 dBFS = full scale).
 * Used by the quality gate (too quiet? mostly silent? noisy?) and for pause and syllable detection.
 *
 * <p>Speech activity rule (an engineering heuristic, not a standard): a frame is "active" when its level is
 * at least {@link #ACTIVE_ABOVE_FLOOR_DB} above the quiet floor (10th percentile) and within
 * {@link #ACTIVE_BELOW_PEAK_DB} of the loud level (95th percentile); when the take has no quiet floor (the floor
 * is within 6 dB of the loud level), frames within 6 dB of the loud level count as active.
 */
public record LevelProfile(double frameStep, double[] levelsDb) {

    public static final double FRAME_SECONDS = 0.020;
    public static final double STEP_SECONDS = 0.010;
    public static final double ACTIVE_ABOVE_FLOOR_DB = 9;
    public static final double ACTIVE_BELOW_PEAK_DB = 35;
    /** Level reported for an all-zero frame instead of −∞. */
    public static final double SILENT_DB = -120;

    public static LevelProfile of(AudioClip clip) {
        double[] x = clip.samples();
        int fs = clip.sampleRate();
        int frame = Math.max(1, (int) Math.round(FRAME_SECONDS * fs));
        int hop = Math.max(1, (int) Math.round(STEP_SECONDS * fs));
        int count = x.length < frame ? 0 : 1 + (x.length - frame) / hop;
        double[] db = new double[count];
        for (int i = 0; i < count; i++) {
            double rms = SignalOps.rms(x, i * hop, i * hop + frame);
            db[i] = rms > 0 ? Math.max(SILENT_DB, SignalOps.dbfs(rms)) : SILENT_DB;
        }
        return new LevelProfile(STEP_SECONDS, db);
    }

    public int frames() {
        return levelsDb.length;
    }

    public double percentile(double q) {
        if (levelsDb.length == 0) {
            return SILENT_DB;
        }
        double[] sorted = levelsDb.clone();
        Arrays.sort(sorted);
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    public double activeThresholdDb() {
        double loud = percentile(0.95);
        // Without a clear quiet floor (e.g. a vowel held for the whole take), most frames count as active.
        return Math.min(Math.max(percentile(0.10) + ACTIVE_ABOVE_FLOOR_DB, loud - ACTIVE_BELOW_PEAK_DB), loud - 6);
    }

    public boolean[] active() {
        double threshold = activeThresholdDb();
        boolean[] out = new boolean[levelsDb.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = levelsDb[i] >= threshold;
        }
        return out;
    }

    public double activeFraction() {
        if (levelsDb.length == 0) {
            return 0;
        }
        int n = 0;
        for (boolean a : active()) {
            if (a) {
                n++;
            }
        }
        return (double) n / levelsDb.length;
    }
}
