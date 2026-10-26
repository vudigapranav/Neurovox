package com.neurovox.features;

import com.neurovox.dsp.PitchSettings;
import com.neurovox.dsp.PulseTrain;

/**
 * Jitter: cycle-to-cycle variation of the vocal period, computed from successive pulse intervals
 * T₁ … T_N (never from frame-level F0, which is smoothed and would hide the variation).
 *
 * <p>Definitions from the Praat manual ("Voice 2. Jitter") and Praat's {@code VoiceAnalysis.cpp}. A term is
 * used only if every interval in it lies in [0.8/ceiling, 1.25/floor] and neighbouring intervals differ by at
 * most a factor 1.3; otherwise that term is skipped (this removes intervals that span a missed pulse).
 * <pre>
 *   mean period  T̄ = mean of the intervals accepted by {@link PulseTrain#isPeriod}
 *   local        = [ mean_i |T_i − T_{i+1}| ] / T̄                                   (fraction)
 *   absolute     =   mean_i |T_i − T_{i+1}|                                          (seconds)
 *   RAP          = [ mean_i |T_i − (T_{i−1} + T_i + T_{i+1}) / 3| ] / T̄
 *   PPQ5         = [ mean_i |T_i − (T_{i−2} + … + T_{i+2}) / 5| ] / T̄
 *   DDP          = 3 · RAP      (|(T_{i+1}−T_i) − (T_i−T_{i−1})| = 3·|T_i − mean₃|, identical by algebra)
 * </pre>
 * A value is NaN ("cannot be measured") when fewer than 2 (local), 3 (RAP) or 5 (PPQ5) accepted terms exist.
 */
public final class JitterMeasures {

    public record Jitter(double local, double absolute, double rap, double ppq5, double ddp, int periods, double meanPeriod) {
    }

    private JitterMeasures() {
    }

    public static Jitter compute(PulseTrain pulses, PitchSettings settings) {
        double pmin = settings.shortestPeriod();
        double pmax = settings.longestPeriod();
        double factor = PitchSettings.MAX_PERIOD_FACTOR;
        double[] t = pulses.times();
        int n = t.length;

        double sumPeriods = 0;
        int periods = 0;
        for (int i = 0; i + 1 < n; i++) {
            if (pulses.isPeriod(i, pmin, pmax, factor)) {
                sumPeriods += t[i + 1] - t[i];
                periods++;
            }
        }
        double meanPeriod = periods > 0 ? sumPeriods / periods : Double.NaN;

        double absolute = perturbation(t, 1, pmin, pmax, factor, 1);
        double rap = perturbation(t, 1, pmin, pmax, factor, 3);
        double ppq5 = perturbation(t, 2, pmin, pmax, factor, 5);
        double local = absolute / meanPeriod;
        rap = rap / meanPeriod;
        ppq5 = ppq5 / meanPeriod;
        return new Jitter(local, absolute, rap, ppq5, 3 * rap, periods, meanPeriod);
    }

    /**
     * Mean absolute deviation used by the jitter family.
     * For {@code points == 1}: mean |T_i − T_{i+1}| over consecutive pairs (local / absolute jitter).
     * Otherwise: mean |T_c − average(T_{c−h} … T_{c+h})| over windows of {@code points = 2h+1} intervals.
     * Returns NaN when no term qualifies (and, for the local measure, when fewer than 2 periods exist).
     */
    static double perturbation(double[] t, int h, double pmin, double pmax, double factor, int points) {
        int intervals = t.length - 1;
        if (intervals < Math.max(2, points)) {
            return Double.NaN;
        }
        double[] p = new double[intervals];
        for (int i = 0; i < intervals; i++) {
            p[i] = t[i + 1] - t[i];
        }
        double sum = 0;
        int terms = 0;
        if (points == 1) {
            for (int i = 0; i + 1 < intervals; i++) {
                if (inRange(p[i], pmin, pmax) && inRange(p[i + 1], pmin, pmax) && ratio(p[i], p[i + 1]) <= factor) {
                    sum += Math.abs(p[i] - p[i + 1]);
                    terms++;
                }
            }
        } else {
            for (int c = h; c + h < intervals; c++) {
                boolean ok = true;
                double window = 0;
                for (int k = c - h; k <= c + h && ok; k++) {
                    ok = inRange(p[k], pmin, pmax) && (k == c - h || ratio(p[k - 1], p[k]) <= factor);
                    window += p[k];
                }
                if (ok) {
                    sum += Math.abs(p[c] - window / points);
                    terms++;
                }
            }
        }
        return terms > 0 ? sum / terms : Double.NaN;
    }

    static boolean inRange(double p, double pmin, double pmax) {
        return p >= pmin && p <= pmax;
    }

    static double ratio(double a, double b) {
        return a > b ? a / b : b / a;
    }
}
