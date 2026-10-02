package com.neurovox.audio;

/**
 * Changes the sample rate with an anti-aliasing low-pass filter, so frequencies the new rate cannot
 * represent are removed instead of folding back as false ("aliased") tones.
 *
 * <h2>Method: band-limited (windowed-sinc) interpolation</h2>
 * Each output sample m sits at input position {@code t = m · fsIn / fsOut} (usually between two input
 * samples). Its value is a weighted sum of the input samples around t:
 * <pre>
 *   y[m] = Σ_k  x[k] · g · sinc(g · (t − k)) · w((t − k) / H)
 *
 *   sinc(u) = sin(πu) / (πu)          ideal low-pass impulse response
 *   g       = cutoff / (fsIn / 2)      cutoff as a fraction of the input Nyquist frequency
 *   w       = Blackman window          w(v) = 0.42 + 0.5·cos(πv) + 0.08·cos(2πv), |v| ≤ 1
 *   H       = ZERO_CROSSINGS / g       filter half-length in input samples
 * </pre>
 * For down-sampling the cutoff is {@link #CUTOFF_FRACTION} of the <em>output</em> Nyquist frequency
 * (7.6 kHz when going to 16 kHz), so the filter both interpolates and removes aliases in one step.
 * Because the kernel is evaluated at the exact fractional position t, non-integer ratios such as
 * 44100 → 16000 are handled exactly; no linear interpolation is needed.
 *
 * <p>Source: J. O. Smith, "Digital Audio Resampling Home Page" (bandlimited interpolation), CCRMA, Stanford;
 * window from Oppenheim &amp; Schafer, Discrete-Time Signal Processing, §7.5. The Blackman window gives
 * about 74 dB of stop-band attenuation.
 *
 * <p>For speed, when the two rates share a common factor (48000/16000 = 3/1, 44100/16000 = 441/160) the kernel
 * weights are computed once per distinct fractional phase and reused.
 */
public final class Resampler {

    /** Analysis rate used by every Neurovox feature: enough for voice up to 8 kHz. */
    public static final int ANALYSIS_RATE = 16_000;

    /** Pass band edge as a fraction of the lower Nyquist frequency. */
    public static final double CUTOFF_FRACTION = 0.95;

    /** Sinc zero crossings on each side of the centre (at the cutoff frequency). */
    public static final int ZERO_CROSSINGS = 32;

    private static final int MAX_CACHED_PHASES = 4096;

    private Resampler() {
    }

    public static AudioClip toAnalysisRate(AudioClip clip) {
        return resample(clip, ANALYSIS_RATE);
    }

    public static AudioClip resample(AudioClip clip, int targetRate) {
        if (targetRate <= 0) {
            throw new IllegalArgumentException("targetRate must be positive");
        }
        int fsIn = clip.sampleRate();
        if (fsIn == targetRate) {
            return new AudioClip(clip.samples().clone(), fsIn);
        }
        double[] x = clip.samples();
        int outLength = (int) Math.floor((long) x.length * (double) targetRate / fsIn);
        double[] y = new double[outLength];
        double g = CUTOFF_FRACTION * Math.min(1.0, (double) targetRate / fsIn);
        double halfLength = ZERO_CROSSINGS / g;                        // H, in input samples
        int reach = (int) Math.ceil(halfLength);

        int gcd = gcd(fsIn, targetRate);
        int phases = targetRate / gcd;                                 // distinct fractional positions
        double[][] cache = phases <= MAX_CACHED_PHASES ? new double[phases][] : null;

        for (int m = 0; m < outLength; m++) {
            // t = m · fsIn / fsOut, computed from integers so it does not drift over long signals.
            long numerator = (long) m * fsIn;
            int base = (int) (numerator / targetRate);
            int phase = (int) (numerator % targetRate) / gcd;
            double frac = (double) (numerator % targetRate) / targetRate;
            double[] weights;
            if (cache != null) {
                weights = cache[phase];
                if (weights == null) {
                    weights = kernel(frac, g, halfLength, reach);
                    cache[phase] = weights;
                }
            } else {
                weights = kernel(frac, g, halfLength, reach);
            }
            double sum = 0;
            for (int j = 0; j < weights.length; j++) {
                int k = base - reach + j;
                if (k >= 0 && k < x.length) {
                    sum += x[k] * weights[j];
                }
            }
            y[m] = sum;
        }
        return new AudioClip(y, targetRate);
    }

    /** Kernel weights for input samples base−reach … base+reach+1, when t = base + frac. */
    private static double[] kernel(double frac, double g, double halfLength, int reach) {
        double[] w = new double[2 * reach + 2];
        for (int j = 0; j < w.length; j++) {
            double d = frac - (j - reach);                              // t − k
            if (Math.abs(d) >= halfLength) {
                continue;
            }
            double v = d / halfLength;
            double window = 0.42 + 0.5 * Math.cos(Math.PI * v) + 0.08 * Math.cos(2 * Math.PI * v);
            w[j] = g * sinc(g * d) * window;
        }
        return w;
    }

    static double sinc(double u) {
        if (Math.abs(u) < 1e-12) {
            return 1.0;
        }
        double a = Math.PI * u;
        return Math.sin(a) / a;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
