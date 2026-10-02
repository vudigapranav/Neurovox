package com.neurovox.dsp;

import com.neurovox.audio.AudioClip;

import java.util.Arrays;

/**
 * Finds individual glottal pulses (one per vocal-fold cycle) by waveform cross-correlation, guided by a
 * pitch contour. This follows the procedure of Praat's "To PointProcess (periodic, cc)".
 *
 * <p>A pitch contour alone is not enough for jitter: it gives one smoothed F0 per 10 ms frame, while jitter
 * needs the length of every single cycle. So, for each voiced stretch:
 * <ol>
 *   <li>Start at the largest absolute sample within half a period of the stretch's midpoint.</li>
 *   <li>Step forward one cycle at a time. With T = 1 / F0(t) at the current pulse t, look in
 *       [t + 0.8T, t + 1.25T] for the position t′ where one period of waveform around t′ best matches one period
 *       around t, by normalised cross-correlation
 *       <pre>  ρ(t′) = Σ_k x[t+k]·x[t′+k] / √(Σ_k x[t+k]² · Σ_k x[t′+k]²),   k ∈ [−T/2, T/2]</pre>
 *       refined between samples with a parabola. Accept t′ as the next pulse when ρ &gt; 0.3 and the waveform
 *       there is not tiny (&gt; 1 % of the loudest sample).</li>
 *   <li>Repeat backwards from the start pulse.</li>
 * </ol>
 * Because every pulse is placed where the waveform repeats the previous cycle, the spacing between pulses is the
 * cycle length, which is exactly what jitter measures.
 *
 * <p>Run it on the signal at its native sample rate (up to 48 kHz), not on the 16 kHz analysis copy: the
 * parabolic refinement of the correlation peak is only as precise as the sample spacing, and at 16 kHz (62.5 µs per
 * sample) that imprecision alone roughly doubled the jitter measured on noisy synthetic vowels compared with Praat.
 * (An earlier variant that moved each pulse to the nearest sinc-interpolated waveform peak was more sensitive to
 * noise and was dropped; see docs/VALIDATION.md.)
 *
 * <p>Source: Boersma &amp; Weenink, Praat (Pitch_to_PointProcess.cpp, Sound_Pitch_to_PointProcess_cc), and the
 * Praat manual "Voice 1. Voice breaks" for the pulse-based view of voice.
 */
public final class PulseExtractor {

    private static final double ACCEPT_CORRELATION = 0.3;
    private static final double EDGE_CORRELATION = 0.7;

    private PulseExtractor() {
    }

    public static PulseTrain extract(AudioClip clip, PitchContour pitch) {
        double[] x = AutocorrelationPitchDetector.centred(clip.samples());
        int fs = clip.sampleRate();
        double globalPeak = 0;
        for (double v : x) {
            globalPeak = Math.max(globalPeak, Math.abs(v));
        }
        double[] points = new double[64];
        int count = 0;
        double addedRight = Double.NEGATIVE_INFINITY;

        for (PitchContour.Interval interval : pitch.voicedIntervals()) {
            double middle = 0.5 * (interval.start() + interval.end());
            double f0Middle = pitch.valueAt(middle);
            if (Double.isNaN(f0Middle)) {
                continue;
            }
            double first = absoluteExtremum(x, fs, middle - 0.5 / f0Middle, middle + 0.5 / f0Middle);
            if (Double.isNaN(first)) {
                continue;
            }
            points = grow(points, count + 1);
            points[count++] = first;

            // Backwards.
            double t = first;
            while (true) {
                double f0 = pitch.valueAt(t);
                if (Double.isNaN(f0)) {
                    break;
                }
                Match m = bestMatch(x, fs, t, 1 / f0, t - 1.25 / f0, t - 0.8 / f0);
                t = m.correlation == -1 ? t - 1 / f0 : m.time;
                if (t < interval.start()) {
                    if (m.correlation > EDGE_CORRELATION && m.peak > 0.023333 * globalPeak && t - addedRight > 0.8 / f0) {
                        points = grow(points, count + 1);
                        points[count++] = t;
                    }
                    break;
                }
                if (m.correlation > ACCEPT_CORRELATION && m.peak > 0.01 * globalPeak && t - addedRight > 0.8 / f0) {
                    points = grow(points, count + 1);
                    points[count++] = t;
                }
            }
            // Forwards.
            t = first;
            while (true) {
                double f0 = pitch.valueAt(t);
                if (Double.isNaN(f0)) {
                    break;
                }
                Match m = bestMatch(x, fs, t, 1 / f0, t + 0.8 / f0, t + 1.25 / f0);
                t = m.correlation == -1 ? t + 1 / f0 : m.time;
                if (t > interval.end()) {
                    if (m.correlation > EDGE_CORRELATION && m.peak > 0.023333 * globalPeak) {
                        points = grow(points, count + 1);
                        points[count++] = t;
                        addedRight = t;
                    }
                    break;
                }
                if (m.correlation > ACCEPT_CORRELATION && m.peak > 0.01 * globalPeak) {
                    points = grow(points, count + 1);
                    points[count++] = t;
                    addedRight = t;
                }
            }
        }
        double[] times = Arrays.copyOf(points, count);
        Arrays.sort(times);
        // Remove accidental duplicates (two passes meeting at the same cycle).
        int unique = 0;
        for (int i = 0; i < times.length; i++) {
            if (unique == 0 || times[i] - times[unique - 1] > 0.5 / pitchCeiling(pitch)) {
                times[unique++] = times[i];
            }
        }
        return new PulseTrain(Arrays.copyOf(times, unique));
    }

    private record Match(double time, double correlation, double peak) {
    }

    /**
     * Where, between {@code from} and {@code to} seconds, one period of waveform best matches the period
     * centred on {@code t}. Correlation is −1 when the search window falls outside the signal.
     */
    private static Match bestMatch(double[] x, int fs, double t, double period, double from, double to) {
        int half = Math.max(1, (int) Math.round(0.5 * period * fs));
        int centre = (int) Math.round(t * fs);
        int lo = (int) Math.ceil(from * fs);
        int hi = (int) Math.floor(to * fs);
        if (centre - half < 0 || centre + half >= x.length || lo - half - 1 < 0 || hi + half + 1 >= x.length || hi < lo) {
            return new Match(Double.NaN, -1, 0);
        }
        double energy1 = 0;
        for (int k = -half; k <= half; k++) {
            energy1 += x[centre + k] * x[centre + k];
        }
        double[] rho = new double[hi - lo + 3];                // rho[0] ↔ lo − 1
        for (int c = lo - 1; c <= hi + 1; c++) {
            double cross = 0;
            double energy2 = 0;
            for (int k = -half; k <= half; k++) {
                cross += x[centre + k] * x[c + k];
                energy2 += x[c + k] * x[c + k];
            }
            double denominator = Math.sqrt(energy1 * energy2);
            rho[c - lo + 1] = denominator == 0 ? 0 : cross / denominator;
        }
        int best = 1;
        for (int i = 2; i < rho.length - 1; i++) {
            if (rho[i] > rho[best]) {
                best = i;
            }
        }
        Interpolation.Peak refined = Interpolation.parabolic(rho, best);
        // The window was centred on the nearest sample to t, so the match position is a shift from that sample;
        // adding the shift to the exact (fractional) t keeps sub-sample precision from one cycle to the next
        // (as Praat does: peakTime = t1 + shift). Using the match position directly would drop t's fraction every step.
        double shift = lo - 1 + refined.position() - centre;
        double position = t * fs + shift;
        int c = lo - 1 + best;
        double peak = 0;
        for (int k = -half; k <= half; k++) {
            peak = Math.max(peak, Math.abs(x[c + k]));
        }
        return new Match(position / fs, Math.min(1.0, refined.value()), peak);
    }

    /** Time (s) of the largest |x| in [from, to], refined with a parabola; NaN if the range is empty. */
    private static double absoluteExtremum(double[] x, int fs, double from, double to) {
        int lo = Math.max(1, (int) Math.ceil(from * fs));
        int hi = Math.min(x.length - 2, (int) Math.floor(to * fs));
        if (hi < lo) {
            return Double.NaN;
        }
        int best = lo;
        for (int i = lo + 1; i <= hi; i++) {
            if (Math.abs(x[i]) > Math.abs(x[best])) {
                best = i;
            }
        }
        double[] around = {Math.abs(x[best - 1]), Math.abs(x[best]), Math.abs(x[best + 1])};
        return (best - 1 + Interpolation.parabolic(around, 1).position()) / fs;
    }

    private static double pitchCeiling(PitchContour pitch) {
        double max = 0;
        for (double f : pitch.f0()) {
            max = Math.max(max, f);
        }
        return max > 0 ? max : PitchSettings.DEFAULT.ceilingHz();
    }

    private static double[] grow(double[] a, int needed) {
        return needed <= a.length ? a : Arrays.copyOf(a, Math.max(needed, a.length * 2));
    }
}
