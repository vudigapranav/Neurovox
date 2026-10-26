package com.neurovox.features;

import com.neurovox.dsp.PitchContour;

/**
 * Harmonics-to-noise ratio (HNR) and noise-to-harmonics ratio (NHR) from the autocorrelation pitch analysis.
 *
 * <p>If a frame is a periodic signal plus independent noise, the window-corrected normalised autocorrelation at the
 * period, r = r(T), equals the periodic share of the power. So, per voiced frame (Boersma 1993, eq. 4):
 * <pre>
 *   HNR_frame = 10 · log10( r / (1 − r) )      dB
 *   NHR_frame = (1 − r) / r                    linear
 * </pre>
 * Neurovox reports the mean over the voiced frames in the analysis region, as Praat's Voice Report does
 * ("Mean harmonics-to-noise ratio", "Mean noise-to-harmonics ratio").
 * r is clamped to [{@link #R_MIN}, {@link #R_MAX}] first, so a perfectly periodic synthetic frame (r = 1) gives a
 * large finite HNR (≈ 60 dB) instead of infinity.
 *
 * <p>Note: MDVP's NHR (the dataset's column name) is defined differently (a spectral energy ratio); the name is
 * kept for the column, the formula above is the Praat one. See docs/VALIDATION.md.
 */
public final class HarmonicityMeasures {

    public static final double R_MIN = 1e-6;
    public static final double R_MAX = 1 - 1e-6;

    public record Harmonicity(double hnrDb, double nhr, double meanR, int frames) {
    }

    private HarmonicityMeasures() {
    }

    public static Harmonicity compute(PitchContour pitch, double from, double to) {
        double sumHnr = 0;
        double sumNhr = 0;
        double sumR = 0;
        int n = 0;
        for (int i = 0; i < pitch.frames(); i++) {
            double t = pitch.times()[i];
            if (!pitch.isVoiced(i) || t < from || t > to) {
                continue;
            }
            double r = Math.max(R_MIN, Math.min(R_MAX, pitch.strength()[i]));
            sumHnr += 10 * Math.log10(r / (1 - r));
            sumNhr += (1 - r) / r;
            sumR += r;
            n++;
        }
        if (n == 0) {
            return new Harmonicity(Double.NaN, Double.NaN, Double.NaN, 0);
        }
        return new Harmonicity(sumHnr / n, sumNhr / n, sumR / n, n);
    }
}
