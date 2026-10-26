package com.neurovox;

import com.neurovox.audio.AudioClip;

import java.util.Random;

/** Synthetic signals with known answers, used by the DSP and feature tests. */
public final class TestSignals {

    private TestSignals() {
    }

    public static AudioClip sine(double hz, double seconds, int fs, double amplitude) {
        double[] x = new double[(int) Math.round(seconds * fs)];
        for (int i = 0; i < x.length; i++) {
            x[i] = amplitude * Math.sin(2 * Math.PI * hz * i / fs);
        }
        return new AudioClip(x, fs);
    }

    /** Band-limited sawtooth: all harmonics below Nyquist with amplitude 1/k (harmonically rich, like a voice source). */
    public static AudioClip sawtooth(double hz, double seconds, int fs, double amplitude) {
        double[] x = new double[(int) Math.round(seconds * fs)];
        int harmonics = (int) Math.floor((fs / 2.0 - 1) / hz);
        for (int i = 0; i < x.length; i++) {
            double sum = 0;
            for (int k = 1; k <= harmonics; k++) {
                sum += Math.sin(2 * Math.PI * k * hz * i / fs) / k;
            }
            x[i] = amplitude * 0.55 * sum;
        }
        return new AudioClip(x, fs);
    }

    public static AudioClip silence(double seconds, int fs) {
        return new AudioClip(new double[(int) Math.round(seconds * fs)], fs);
    }

    public static AudioClip whiteNoise(double seconds, int fs, double rms, long seed) {
        Random random = new Random(seed);
        double[] x = new double[(int) Math.round(seconds * fs)];
        for (int i = 0; i < x.length; i++) {
            x[i] = rms * random.nextGaussian();
        }
        return new AudioClip(x, fs);
    }

    public static AudioClip add(AudioClip a, AudioClip b) {
        double[] x = new double[Math.min(a.length(), b.length())];
        for (int i = 0; i < x.length; i++) {
            x[i] = a.samples()[i] + b.samples()[i];
        }
        return new AudioClip(x, a.sampleRate());
    }

    /** Periods T·(1 + jitter·N(0,1)), seeded. jitter = 0 gives a perfectly periodic train. */
    public static double[] periods(double f0, int count, double jitter, long seed) {
        Random random = new Random(seed);
        double[] p = new double[count];
        for (int i = 0; i < count; i++) {
            p[i] = (1 / f0) * (1 + jitter * random.nextGaussian());
        }
        return p;
    }

    /** Amplitudes 1 + shimmer·N(0,1), seeded. */
    public static double[] amplitudes(int count, double shimmer, long seed) {
        Random random = new Random(seed);
        double[] a = new double[count];
        for (int i = 0; i < count; i++) {
            a[i] = Math.max(0.2, 1 + shimmer * random.nextGaussian());
        }
        return a;
    }

    /**
     * A vowel-like signal: one Rosenberg glottal-flow derivative per cycle with the given periods and amplitudes,
     * evaluated at exact (sub-sample) times, then passed through three formant resonators (/a/: 730, 1090, 2440 Hz).
     * Each cycle ends with glottal closure, and closures are exactly {@code periods[c]} apart, so the true
     * excitation-to-excitation intervals are the {@code periods} array.
     * The glottal waveform has a sharp closure, so it is synthesised at 8× the target rate and band-limited down
     * with the (separately tested) {@link com.neurovox.audio.Resampler}; sampling it directly would alias differently
     * in every cycle and fake some jitter.
     */
    public static AudioClip vowel(double[] periods, double[] amplitudes, int targetFs, double leadSeconds) {
        int fs = 8 * targetFs;
        double total = leadSeconds;
        for (double p : periods) {
            total += p;
        }
        double[] source = new double[(int) Math.ceil((total + 0.05) * fs)];
        // Cycle c ends with glottal closure at closure[c]; closures are exactly periods[c] apart. Acoustic pulse
        // methods lock onto the closure (the main excitation), so closure-to-closure intervals are the true periods.
        double closure = leadSeconds + periods[0];
        for (int c = 0; c < periods.length; c++) {
            double t0 = periods[c];
            double tp = 0.40 * t0;
            double tn = 0.16 * t0;
            double start = closure - tp - tn;
            int from = (int) Math.ceil(start * fs);
            int to = (int) Math.floor((start + tp + tn) * fs);
            for (int i = from; i <= to && i < source.length; i++) {
                double t = (double) i / fs - start;
                double d;
                if (t < tp) {
                    d = 0.5 * Math.PI / tp * Math.sin(Math.PI * t / tp);              // rising flow
                } else {
                    d = -Math.PI / (2 * tn) * Math.sin(Math.PI * (t - tp) / (2 * tn)); // closing flow
                }
                source[i] += amplitudes[c] * d * t0 * 0.02;
            }
            if (c + 1 < periods.length) {
                closure += periods[c + 1];
            }
        }
        double[] y = source;
        double[][] formants = {{730, 90}, {1090, 110}, {2440, 170}};
        for (double[] f : formants) {
            y = resonate(y, fs, f[0], f[1]);
        }
        double peak = 0;
        for (double v : y) {
            peak = Math.max(peak, Math.abs(v));
        }
        for (int i = 0; i < y.length; i++) {
            y[i] = 0.5 * y[i] / peak;
        }
        return com.neurovox.audio.Resampler.resample(new AudioClip(y, fs), targetFs);
    }

    /** Two-pole resonator with unity gain at DC: y[n] = g·x[n] + 2r·cos(θ)·y[n−1] − r²·y[n−2]. */
    static double[] resonate(double[] x, int fs, double hz, double bandwidth) {
        double r = Math.exp(-Math.PI * bandwidth / fs);
        double theta = 2 * Math.PI * hz / fs;
        double a1 = 2 * r * Math.cos(theta);
        double a2 = -r * r;
        double gain = 1 - a1 - a2;
        double[] y = new double[x.length];
        for (int n = 0; n < x.length; n++) {
            y[n] = gain * x[n] + a1 * (n > 0 ? y[n - 1] : 0) + a2 * (n > 1 ? y[n - 2] : 0);
        }
        return y;
    }

    /** The Praat local-jitter formula applied directly to known periods (all accepted). */
    public static double trueLocalJitter(double[] p) {
        double diff = 0;
        double mean = 0;
        for (int i = 0; i < p.length; i++) {
            mean += p[i];
            if (i > 0) {
                diff += Math.abs(p[i] - p[i - 1]);
            }
        }
        return (diff / (p.length - 1)) / (mean / p.length);
    }

    /** Magnitude of the DFT of x at frequency hz (Goertzel), normalised so a sine of amplitude A gives ≈ A. */
    public static double toneAmplitude(AudioClip clip, double hz) {
        double[] x = clip.samples();
        double w = 2 * Math.PI * hz / clip.sampleRate();
        double re = 0;
        double im = 0;
        for (int i = 0; i < x.length; i++) {
            double h = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (x.length - 1));   // Hann, coherent gain 0.5
            re += x[i] * h * Math.cos(w * i);
            im -= x[i] * h * Math.sin(w * i);
        }
        return 2 * Math.hypot(re, im) / (0.5 * x.length);
    }
}
