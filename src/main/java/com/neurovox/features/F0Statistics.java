package com.neurovox.features;

import java.util.Arrays;

/**
 * Summary of the voiced-frame F0 values in a region.
 * <pre>
 *   mean = Σ f / n,   sd = √( Σ (f − mean)² / (n − 1) ),   min, max
 *   sdSemitones = sd of 12·log2(f / 100 Hz)   (pitch variability independent of voice range)
 * </pre>
 */
public record F0Statistics(int n, double mean, double sd, double min, double max, double sdSemitones,
                           double p05, double p95) {

    public static F0Statistics of(double[] f0) {
        int n = f0.length;
        if (n == 0) {
            return new F0Statistics(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double mean = Arrays.stream(f0).average().orElse(Double.NaN);
        double min = Arrays.stream(f0).min().orElse(Double.NaN);
        double max = Arrays.stream(f0).max().orElse(Double.NaN);
        double sd = Double.NaN;
        double sdSt = Double.NaN;
        if (n > 1) {
            double ss = 0;
            double stMean = 0;
            for (double f : f0) {
                ss += (f - mean) * (f - mean);
                stMean += semitones(f);
            }
            sd = Math.sqrt(ss / (n - 1));
            stMean /= n;
            double ssSt = 0;
            for (double f : f0) {
                ssSt += (semitones(f) - stMean) * (semitones(f) - stMean);
            }
            sdSt = Math.sqrt(ssSt / (n - 1));
        }
        double[] sorted = f0.clone();
        Arrays.sort(sorted);
        return new F0Statistics(n, mean, sd, min, max, sdSt, quantile(sorted, 0.05), quantile(sorted, 0.95));
    }

    static double semitones(double hz) {
        return 12 * Math.log(hz / 100.0) / Math.log(2);
    }

    /** Linear-interpolated quantile of sorted values. */
    static double quantile(double[] sorted, double q) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    /** Range between the 5th and 95th percentile, in semitones (robust pitch range). */
    public double rangeSemitones() {
        return n == 0 ? Double.NaN : semitones(p95) - semitones(p05);
    }
}
