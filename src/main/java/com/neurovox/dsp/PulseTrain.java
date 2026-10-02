package com.neurovox.dsp;

import java.util.ArrayList;
import java.util.List;

/**
 * The glottal pulses found in a voiced recording: one time per vocal-fold cycle, in seconds, ascending.
 * The time between consecutive pulses is a period; jitter is computed from these periods, and shimmer from
 * the amplitude around each pulse.
 */
public record PulseTrain(double[] times) {

    public int size() {
        return times.length;
    }

    /** Intervals between consecutive pulses: periods[i] = times[i+1] − times[i]. Not filtered. */
    public double[] intervals() {
        double[] out = new double[Math.max(0, times.length - 1)];
        for (int i = 0; i < out.length; i++) {
            out[i] = times[i + 1] - times[i];
        }
        return out;
    }

    /**
     * Whether the interval from pulse i to pulse i+1 counts as a vocal period (Praat's rule,
     * {@code PointProcess_isPeriod}): it lies within [shortest, longest], and it is not more than
     * {@code maxFactor} times different from <em>both</em> of its neighbouring intervals.
     */
    public boolean isPeriod(int i, double shortest, double longest, double maxFactor) {
        if (i < 0 || i + 1 >= times.length) {
            return false;
        }
        double interval = times[i + 1] - times[i];
        if (interval <= 0 || interval < shortest || interval > longest) {
            return false;
        }
        double previous = i >= 1 ? times[i] - times[i - 1] : Double.NaN;
        double next = i + 2 < times.length ? times[i + 2] - times[i + 1] : Double.NaN;
        double fPrev = factor(interval, previous);
        double fNext = factor(interval, next);
        if (Double.isNaN(fPrev) && Double.isNaN(fNext)) {
            return true;
        }
        return !(!Double.isNaN(fPrev) && fPrev > maxFactor && !Double.isNaN(fNext) && fNext > maxFactor);
    }

    /** The accepted periods (see {@link #isPeriod}). */
    public double[] periods(double shortest, double longest, double maxFactor) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i + 1 < times.length; i++) {
            if (isPeriod(i, shortest, longest, maxFactor)) {
                out.add(times[i + 1] - times[i]);
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double factor(double a, double b) {
        if (Double.isNaN(b) || b <= 0) {
            return Double.NaN;
        }
        return a > b ? a / b : b / a;
    }
}
