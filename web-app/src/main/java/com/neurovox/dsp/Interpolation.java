package com.neurovox.dsp;

/**
 * Sub-sample peak refinement. A peak found on integer sample positions can be off by up to half a sample
 * (31 µs at 16 kHz), which is large compared with the cycle-to-cycle differences jitter measures, so peak
 * positions and heights are refined between samples.
 */
public final class Interpolation {

    /** Hann-windowed sinc half-width (in samples) used by {@link #sinc}. */
    public static final int SINC_DEPTH = 30;

    private Interpolation() {
    }

    /** Result of a refinement: position (in samples, fractional) and value there. */
    public record Peak(double position, double value) {
    }

    /**
     * Fits a parabola through (i−1, y₋), (i, y₀), (i+1, y₊) and returns its vertex:
     * <pre>
     *   δ = ½ · (y₋ − y₊) / (y₋ − 2y₀ + y₊)      offset from i, in [−½, ½] for a true peak
     *   value = y₀ − ¼ · (y₋ − y₊) · δ
     * </pre>
     * Source: J. O. Smith, Spectral Audio Signal Processing, "Quadratic Interpolation of Spectral Peaks".
     */
    public static Peak parabolic(double[] y, int i) {
        if (i <= 0 || i >= y.length - 1) {
            return new Peak(i, y[i]);
        }
        double a = y[i - 1];
        double b = y[i];
        double c = y[i + 1];
        double denominator = a - 2 * b + c;
        if (denominator == 0) {
            return new Peak(i, b);
        }
        double delta = 0.5 * (a - c) / denominator;
        if (delta > 0.5 || delta < -0.5) {
            return new Peak(i, b);
        }
        return new Peak(i + delta, b - 0.25 * (a - c) * delta);
    }

    /**
     * Band-limited value of a sampled function at fractional position x:
     * <pre>  y(x) = Σ_k y[k] · sinc(x − k) · w(x − k),   w = Hann window over ±depth samples</pre>
     * Used to read an autocorrelation curve between lags, as Praat does (Boersma 1993, §3).
     */
    public static double sinc(double[] y, double x, int depth) {
        int left = (int) Math.floor(x);
        if (x == left && left >= 0 && left < y.length) {
            return y[left];
        }
        double sum = 0;
        for (int k = left - depth + 1; k <= left + depth; k++) {
            if (k < 0 || k >= y.length) {
                continue;
            }
            double d = x - k;
            double window = 0.5 + 0.5 * Math.cos(Math.PI * d / depth);
            double a = Math.PI * d;
            sum += y[k] * (Math.sin(a) / a) * window;
        }
        return sum;
    }

    /**
     * Maximises the sinc-interpolated curve within [i − 1, i + 1] by golden-section search
     * (Press et al., Numerical Recipes, §10.2). Starts from an integer local maximum i.
     */
    public static Peak sincMaximum(double[] y, int i, int depth) {
        if (i <= 0 || i >= y.length - 1) {
            return new Peak(i, y[i]);
        }
        final double ratio = (Math.sqrt(5) - 1) / 2;
        double lo = i - 1;
        double hi = i + 1;
        double x1 = hi - ratio * (hi - lo);
        double x2 = lo + ratio * (hi - lo);
        double f1 = sinc(y, x1, depth);
        double f2 = sinc(y, x2, depth);
        for (int iter = 0; iter < 40 && hi - lo > 1e-6; iter++) {
            if (f1 < f2) {
                lo = x1;
                x1 = x2;
                f1 = f2;
                x2 = lo + ratio * (hi - lo);
                f2 = sinc(y, x2, depth);
            } else {
                hi = x2;
                x2 = x1;
                f2 = f1;
                x1 = hi - ratio * (hi - lo);
                f1 = sinc(y, x1, depth);
            }
        }
        double x = 0.5 * (lo + hi);
        double value = sinc(y, x, depth);
        // Never return something worse than the sample we started from.
        return value >= y[i] ? new Peak(x, value) : new Peak(i, y[i]);
    }
}
