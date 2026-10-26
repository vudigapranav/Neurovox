package com.neurovox.features;

import com.neurovox.audio.AudioClip;
import com.neurovox.audio.Framer;
import com.neurovox.audio.SignalOps;
import com.neurovox.audio.WindowFunction;
import org.jtransforms.fft.DoubleFFT_1D;

/**
 * Mel-frequency cepstral coefficients: a compact description of the spectral envelope (the "colour" of the
 * voice, shaped by the vocal tract). Neurovox reports them descriptively; they are not classifier inputs.
 *
 * <h2>Per frame (512 samples = 32 ms at 16 kHz, hop 256)</h2>
 * <ol>
 *   <li>Pre-emphasis y[n] = x[n] − 0.97·x[n−1] (applied to the whole signal first), Hamming window.</li>
 *   <li>FFT (JTransforms, used only as the FFT primitive) → power spectrum P[k] = |X[k]|² / N, k = 0 … N/2.</li>
 *   <li>26 triangular filters equally spaced on the mel scale from 0 Hz to 8000 Hz,
 *       <pre>  mel(f) = 2595 · log10(1 + f / 700)</pre>
 *       filter energy E_m = Σ_k P[k] · H_m[k].</li>
 *   <li>Log: L_m = ln(max(E_m, 1e-10)).</li>
 *   <li>DCT-II: c_j = Σ_{m=0}^{M−1} L_m · cos(π·j·(m + ½) / M),  j = 1 … 13 (c₀, overall loudness, is dropped).</li>
 * </ol>
 * Only frames with energy within 40 dB of the loudest frame are used, so pauses do not dominate the averages.
 * Mean and standard deviation of each coefficient across those frames are reported.
 *
 * <p>Sources: Davis &amp; Mermelstein (1980), IEEE Trans. ASSP 28(4); Young et al., The HTK Book, §5.4–5.6.
 */
public final class MfccExtractor {

    public static final int FILTERS = 26;
    public static final int COEFFICIENTS = 13;
    public static final double ACTIVE_RANGE_DB = 40;

    public record Mfcc(double[] mean, double[] sd, int frames) {
    }

    private MfccExtractor() {
    }

    public static double hzToMel(double hz) {
        return 2595 * Math.log10(1 + hz / 700);
    }

    public static double melToHz(double mel) {
        return 700 * (Math.pow(10, mel / 2595) - 1);
    }

    public static Mfcc compute(AudioClip clip) {
        int n = Framer.DEFAULT_FRAME;
        int fs = clip.sampleRate();
        double[] x = SignalOps.preEmphasis(SignalOps.removeDc(clip.samples()), SignalOps.PRE_EMPHASIS);
        double[][] frames = Framer.frames(x, n, Framer.DEFAULT_HOP);
        double[][] filters = filterBank(n, fs, FILTERS, 0, Math.min(8000, fs / 2.0));
        double[] window = WindowFunction.HAMMING.coefficients(n);
        DoubleFFT_1D fft = new DoubleFFT_1D(n);

        double[][] coefficients = new double[frames.length][];
        double[] energies = new double[frames.length];
        double maxEnergy = 0;
        for (int f = 0; f < frames.length; f++) {
            double[] buffer = new double[n];
            for (int i = 0; i < n; i++) {
                buffer[i] = frames[f][i] * window[i];
            }
            double[] power = powerSpectrum(fft, buffer);
            double energy = 0;
            for (double p : power) {
                energy += p;
            }
            energies[f] = energy;
            maxEnergy = Math.max(maxEnergy, energy);
            coefficients[f] = cepstrum(power, filters);
        }
        double threshold = maxEnergy * Math.pow(10, -ACTIVE_RANGE_DB / 10);
        double[] mean = new double[COEFFICIENTS];
        double[] sumSq = new double[COEFFICIENTS];
        int used = 0;
        for (int f = 0; f < frames.length; f++) {
            if (maxEnergy == 0 || energies[f] < threshold) {
                continue;
            }
            used++;
            for (int j = 0; j < COEFFICIENTS; j++) {
                mean[j] += coefficients[f][j];
            }
        }
        if (used == 0) {
            return new Mfcc(new double[0], new double[0], 0);
        }
        for (int j = 0; j < COEFFICIENTS; j++) {
            mean[j] /= used;
        }
        for (int f = 0; f < frames.length; f++) {
            if (energies[f] < threshold) {
                continue;
            }
            for (int j = 0; j < COEFFICIENTS; j++) {
                double d = coefficients[f][j] - mean[j];
                sumSq[j] += d * d;
            }
        }
        double[] sd = new double[COEFFICIENTS];
        for (int j = 0; j < COEFFICIENTS; j++) {
            sd[j] = used > 1 ? Math.sqrt(sumSq[j] / (used - 1)) : 0;
        }
        return new Mfcc(mean, sd, used);
    }

    /** |X[k]|² / N for k = 0 … N/2 from JTransforms' packed real-FFT layout. */
    static double[] powerSpectrum(DoubleFFT_1D fft, double[] frame) {
        int n = frame.length;
        double[] a = frame.clone();
        fft.realForward(a);
        double[] power = new double[n / 2 + 1];
        power[0] = a[0] * a[0] / n;                     // DC
        power[n / 2] = a[1] * a[1] / n;                 // Nyquist (n even)
        for (int k = 1; k < n / 2; k++) {
            double re = a[2 * k];
            double im = a[2 * k + 1];
            power[k] = (re * re + im * im) / n;
        }
        return power;
    }

    /** Log mel energies followed by a DCT-II; returns c₁ … c₁₃. */
    static double[] cepstrum(double[] power, double[][] filters) {
        int m = filters.length;
        double[] logEnergy = new double[m];
        for (int i = 0; i < m; i++) {
            double e = 0;
            for (int k = 0; k < power.length; k++) {
                e += power[k] * filters[i][k];
            }
            logEnergy[i] = Math.log(Math.max(e, 1e-10));
        }
        return dct2(logEnergy, COEFFICIENTS);
    }

    /** c_j = Σ_m L_m · cos(π j (m + ½) / M) for j = 1 … count. */
    static double[] dct2(double[] values, int count) {
        int m = values.length;
        double[] c = new double[count];
        for (int j = 1; j <= count; j++) {
            double sum = 0;
            for (int i = 0; i < m; i++) {
                sum += values[i] * Math.cos(Math.PI * j * (i + 0.5) / m);
            }
            c[j - 1] = sum;
        }
        return c;
    }

    /**
     * Triangular filters H_m[k] over FFT bins k = 0 … N/2. Filter m rises from edge m to centre m+1 and falls to
     * edge m+2, where the M+2 edges are equally spaced in mel between lowHz and highHz.
     */
    static double[][] filterBank(int fftSize, int fs, int count, double lowHz, double highHz) {
        double lowMel = hzToMel(lowHz);
        double highMel = hzToMel(highHz);
        double[] edgesHz = new double[count + 2];
        for (int i = 0; i < edgesHz.length; i++) {
            edgesHz[i] = melToHz(lowMel + (highMel - lowMel) * i / (count + 1));
        }
        int bins = fftSize / 2 + 1;
        double[][] h = new double[count][bins];
        for (int m = 0; m < count; m++) {
            double left = edgesHz[m];
            double centre = edgesHz[m + 1];
            double right = edgesHz[m + 2];
            for (int k = 0; k < bins; k++) {
                double f = (double) k * fs / fftSize;
                if (f > left && f <= centre) {
                    h[m][k] = (f - left) / (centre - left);
                } else if (f > centre && f < right) {
                    h[m][k] = (right - f) / (right - centre);
                }
            }
        }
        return h;
    }
}
