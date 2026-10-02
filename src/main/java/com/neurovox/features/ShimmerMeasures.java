package com.neurovox.features;

import com.neurovox.audio.AudioClip;
import com.neurovox.dsp.PitchSettings;
import com.neurovox.dsp.PulseTrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Shimmer: cycle-to-cycle variation of amplitude.
 *
 * <h2>Amplitude of each cycle</h2>
 * For a pulse at t with neighbouring intervals p₁ (before) and p₂ (after), both valid periods, the amplitude is
 * the Hann-windowed RMS of the waveform from t − 0.2·p₁ to t + 0.2·p₂ (Praat's
 * {@code PointProcess_Sound_to_AmplitudeTier_period}):
 * <pre>  A = √( Σ (x[n]·w[n])² / Σ w[n]² ),   w = Hann window peaking at t</pre>
 *
 * <h2>Measures</h2> (Praat manual "Voice 3. Shimmer"; terms with a gap outside [0.8/ceiling, 1.25/floor] or a
 * neighbouring-amplitude ratio above 1.6 are skipped)
 * <pre>
 *   Ā           = mean of A₁ … A_{M−1}      (Praat averages all points except the last)
 *   local       = [ mean |A_i − A_{i+1}| ] / Ā                               (fraction)
 *   local, dB   =   mean |20 · log10(A_{i+1} / A_i)|                         (dB)
 *   APQ3        = [ mean |A_i − (A_{i−1} + A_i + A_{i+1}) / 3| ] / Ā
 *   APQ5        = same with a 5-point average,  APQ11 = with an 11-point average
 *   DDA         = 3 · APQ3
 * </pre>
 */
public final class ShimmerMeasures {

    public record Shimmer(double local, double localDb, double apq3, double apq5, double apq11, double dda, int amplitudes) {
    }

    /** Pulse times that received an amplitude, and the amplitudes. */
    public record Amplitudes(double[] times, double[] values) {
    }

    private ShimmerMeasures() {
    }

    public static Shimmer compute(AudioClip clip, PulseTrain pulses, PitchSettings settings) {
        return compute(amplitudes(clip, pulses, settings), settings);
    }

    public static Amplitudes amplitudes(AudioClip clip, PulseTrain pulses, PitchSettings settings) {
        double[] t = pulses.times();
        double pmin = settings.shortestPeriod();
        double pmax = settings.longestPeriod();
        List<double[]> points = new ArrayList<>();
        for (int i = 1; i + 1 < t.length; i++) {
            double p1 = t[i] - t[i - 1];
            double p2 = t[i + 1] - t[i];
            if (JitterMeasures.inRange(p1, pmin, pmax) && JitterMeasures.inRange(p2, pmin, pmax)
                    && JitterMeasures.ratio(p1, p2) <= PitchSettings.MAX_PERIOD_FACTOR) {
                double a = hannWindowedRms(clip, t[i], 0.2 * p1, 0.2 * p2);
                if (a > 0) {
                    points.add(new double[] {t[i], a});
                }
            }
        }
        double[] times = new double[points.size()];
        double[] values = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            times[i] = points.get(i)[0];
            values[i] = points.get(i)[1];
        }
        return new Amplitudes(times, values);
    }

    public static Shimmer compute(Amplitudes amps, PitchSettings settings) {
        double[] t = amps.times();
        double[] a = amps.values();
        double pmin = settings.shortestPeriod();
        double pmax = settings.longestPeriod();
        double maxFactor = PitchSettings.MAX_AMPLITUDE_FACTOR;
        int m = a.length;
        if (m < 3) {
            return new Shimmer(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, m);
        }
        double mean = 0;
        for (int i = 0; i < m - 1; i++) {
            mean += a[i];
        }
        mean /= (m - 1);

        double sumLocal = 0;
        double sumDb = 0;
        int pairs = 0;
        for (int i = 1; i < m; i++) {
            double gap = t[i] - t[i - 1];
            if (JitterMeasures.inRange(gap, pmin, pmax) && JitterMeasures.ratio(a[i - 1], a[i]) <= maxFactor) {
                sumLocal += Math.abs(a[i - 1] - a[i]);
                sumDb += Math.abs(Math.log10(a[i - 1] / a[i]));
                pairs++;
            }
        }
        double local = pairs > 0 && mean > 0 ? (sumLocal / pairs) / mean : Double.NaN;
        double localDb = pairs > 0 ? 20 * sumDb / pairs : Double.NaN;
        double apq3 = quotient(t, a, 1, pmin, pmax, maxFactor, mean);
        double apq5 = quotient(t, a, 2, pmin, pmax, maxFactor, mean);
        double apq11 = quotient(t, a, 5, pmin, pmax, maxFactor, mean);
        return new Shimmer(local, localDb, apq3, apq5, apq11, 3 * apq3, m);
    }

    /** Amplitude perturbation quotient over windows of 2h+1 points, divided by the mean amplitude. */
    private static double quotient(double[] t, double[] a, int h, double pmin, double pmax, double maxFactor, double mean) {
        double sum = 0;
        int terms = 0;
        for (int c = h; c + h < a.length; c++) {
            boolean ok = true;
            double window = 0;
            for (int k = c - h; k <= c + h && ok; k++) {
                if (k > c - h) {
                    ok = JitterMeasures.inRange(t[k] - t[k - 1], pmin, pmax) && JitterMeasures.ratio(a[k - 1], a[k]) <= maxFactor;
                }
                window += a[k];
            }
            if (ok) {
                sum += Math.abs(a[c] - window / (2 * h + 1));
                terms++;
            }
        }
        return terms > 0 && mean > 0 ? (sum / terms) / mean : Double.NaN;
    }

    /** RMS of x around t with a Hann window that reaches zero widthLeft before and widthRight after t. */
    static double hannWindowedRms(AudioClip clip, double t, double widthLeft, double widthRight) {
        double[] x = clip.samples();
        int fs = clip.sampleRate();
        int from = Math.max(0, (int) Math.ceil((t - widthLeft) * fs));
        int to = Math.min(x.length - 1, (int) Math.floor((t + widthRight) * fs));
        if (to - from + 1 < 3) {
            return Double.NaN;
        }
        double sum = 0;
        double windowSum = 0;
        for (int i = from; i <= to; i++) {
            double ti = (double) i / fs;
            double width = ti < t ? widthLeft : widthRight;
            double w = 0.5 + 0.5 * Math.cos(Math.PI * (ti - t) / width);
            sum += (x[i] * w) * (x[i] * w);
            windowSum += w * w;
        }
        return Math.sqrt(sum / windowSum);
    }
}
