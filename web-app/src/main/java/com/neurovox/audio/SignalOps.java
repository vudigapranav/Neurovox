package com.neurovox.audio;

/** Small, stateless signal operations used before analysis. None of them changes the input array. */
public final class SignalOps {

    /** Pre-emphasis coefficient used by the MFCC front end (Young et al., HTK Book, §5.2). */
    public static final double PRE_EMPHASIS = 0.97;

    private SignalOps() {
    }

    /** Subtracts the mean: y[n] = x[n] − mean(x). Removes a constant (DC) offset from the microphone. */
    public static double[] removeDc(double[] x) {
        double mean = mean(x);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            y[i] = x[i] - mean;
        }
        return y;
    }

    /**
     * First-order high-pass "pre-emphasis": y[0] = x[0], y[n] = x[n] − α·x[n−1].
     * It boosts high frequencies so the spectrum is flatter; a constant signal becomes ≈ (1 − α)·c.
     */
    public static double[] preEmphasis(double[] x, double alpha) {
        double[] y = new double[x.length];
        if (x.length == 0) {
            return y;
        }
        y[0] = x[0];
        for (int i = 1; i < x.length; i++) {
            y[i] = x[i] - alpha * x[i - 1];
        }
        return y;
    }

    /** Scales so the largest absolute value is 1.0 (a silent signal is returned unchanged). */
    public static double[] peakNormalize(double[] x) {
        double peak = peak(x);
        double[] y = x.clone();
        if (peak > 0) {
            for (int i = 0; i < y.length; i++) {
                y[i] /= peak;
            }
        }
        return y;
    }

    public static double mean(double[] x) {
        double sum = 0;
        for (double v : x) {
            sum += v;
        }
        return x.length == 0 ? 0 : sum / x.length;
    }

    public static double peak(double[] x) {
        double peak = 0;
        for (double v : x) {
            peak = Math.max(peak, Math.abs(v));
        }
        return peak;
    }

    /** Root-mean-square level of x[from..to): sqrt(Σ x² / N). */
    public static double rms(double[] x, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += x[i] * x[i];
        }
        return to > from ? Math.sqrt(sum / (to - from)) : 0;
    }

    public static double rms(double[] x) {
        return rms(x, 0, x.length);
    }

    /** Level in decibels relative to full scale: 20·log10(rms). A silent signal gives −∞. */
    public static double dbfs(double rms) {
        return rms > 0 ? 20 * Math.log10(rms) : Double.NEGATIVE_INFINITY;
    }
}
