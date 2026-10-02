package com.neurovox.dsp;

import java.util.ArrayList;
import java.util.List;

/**
 * The output of a pitch detector: one estimate per analysis frame.
 *
 * <ul>
 *   <li>{@code times[i]} — frame centre in seconds from the start of the analysed clip;</li>
 *   <li>{@code f0[i]} — fundamental frequency in Hz, or 0 when the frame is unvoiced;</li>
 *   <li>{@code strength[i]} — periodicity of the chosen candidate in [0, 1]: the normalised autocorrelation r
 *       for the autocorrelation detector (used for HNR), and 1 − d′ for YIN.</li>
 * </ul>
 * Individual glottal cycles are not represented here; {@link PulseExtractor} finds those, guided by this contour.
 */
public record PitchContour(String detector, double timeStep, double[] times, double[] f0, double[] strength) {

    public PitchContour {
        if (times.length != f0.length || f0.length != strength.length) {
            throw new IllegalArgumentException("times, f0 and strength must have the same length");
        }
    }

    public int frames() {
        return f0.length;
    }

    public boolean isVoiced(int i) {
        return f0[i] > 0;
    }

    public int voicedFrames() {
        int n = 0;
        for (double v : f0) {
            if (v > 0) {
                n++;
            }
        }
        return n;
    }

    /** Fraction of frames that are voiced (0 when there are no frames). */
    public double voicedFraction() {
        return f0.length == 0 ? 0 : (double) voicedFrames() / f0.length;
    }

    /** F0 values of the voiced frames whose centre lies in [from, to] seconds. */
    public double[] voicedValues(double from, double to) {
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < f0.length; i++) {
            if (f0[i] > 0 && times[i] >= from && times[i] <= to) {
                values.add(f0[i]);
            }
        }
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * F0 at time t by linear interpolation between neighbouring voiced frames. Returns NaN when t is not
     * inside a voiced stretch (within half a time step of a voiced frame).
     */
    public double valueAt(double t) {
        if (f0.length == 0) {
            return Double.NaN;
        }
        double position = (t - times[0]) / timeStep;
        int left = (int) Math.floor(position);
        int right = left + 1;
        boolean leftVoiced = left >= 0 && left < f0.length && f0[left] > 0;
        boolean rightVoiced = right >= 0 && right < f0.length && f0[right] > 0;
        if (leftVoiced && rightVoiced) {
            double frac = position - left;
            return f0[left] + frac * (f0[right] - f0[left]);
        }
        int nearest = (int) Math.round(position);
        if (nearest >= 0 && nearest < f0.length && f0[nearest] > 0 && Math.abs(position - nearest) <= 0.5) {
            return f0[nearest];
        }
        return Double.NaN;
    }

    /** A run of consecutive voiced frames, as times: from half a step before the first to half after the last. */
    public record Interval(double start, double end) {
        public double duration() {
            return end - start;
        }
    }

    public List<Interval> voicedIntervals() {
        List<Interval> out = new ArrayList<>();
        int i = 0;
        while (i < f0.length) {
            if (f0[i] <= 0) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < f0.length && f0[j + 1] > 0) {
                j++;
            }
            out.add(new Interval(times[i] - timeStep / 2, times[j] + timeStep / 2));
            i = j + 1;
        }
        return out;
    }
}
