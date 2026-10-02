package com.neurovox.dsp;

import com.neurovox.audio.AudioClip;

/**
 * YIN pitch detector (de Cheveigné &amp; Kawahara, 2002), steps 1–5 of the paper.
 *
 * <ol>
 *   <li>Difference function over an integration window of W samples:
 *       <pre>  d(τ) = Σ_{j=0}^{W−1} (x[j] − x[j+τ])²</pre></li>
 *   <li>Cumulative mean normalised difference (removes the bias towards τ = 0):
 *       <pre>  d′(0) = 1,   d′(τ) = d(τ) / [ (1/τ) · Σ_{j=1}^{τ} d(j) ]</pre></li>
 *   <li>Absolute threshold: take the <em>first</em> τ in [1/ceiling, 1/floor] where d′(τ) &lt; {@link #THRESHOLD},
 *       then move to the bottom of that dip. Taking the first dip rather than the global minimum is what
 *       avoids reporting a sub-harmonic (an octave too low). If no dip goes below the threshold, the global
 *       minimum is used.</li>
 *   <li>Parabolic interpolation of d′ around that τ for sub-sample precision.</li>
 *   <li>F0 = sampleRate / τ.</li>
 * </ol>
 * A frame is called unvoiced when the dip is not deep enough (d′ ≥ {@link #UNVOICED_ABOVE}) or the frame is quieter
 * than the silence threshold relative to the loudest sample. YIN has no path smoothing across frames; that is
 * one of the differences studied in the pitch comparison.
 *
 * <p>Source: A. de Cheveigné and H. Kawahara (2002), "YIN, a fundamental frequency estimator for speech and
 * music", J. Acoust. Soc. Am. 111(4), 1917–1930.
 */
public final class YinPitchDetector implements PitchDetector {

    /** Absolute threshold from the paper (§II.D; 0.10 gave the lowest error rate there). */
    public static final double THRESHOLD = 0.10;
    /** Dips shallower than this are aperiodic: the frame is unvoiced. */
    public static final double UNVOICED_ABOVE = 0.35;

    private final PitchSettings settings;

    public YinPitchDetector() {
        this(PitchSettings.DEFAULT);
    }

    public YinPitchDetector(PitchSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "yin";
    }

    @Override
    public PitchContour detect(AudioClip clip) {
        double[] x = AutocorrelationPitchDetector.centred(clip.samples());
        int fs = clip.sampleRate();
        int maxLag = (int) Math.ceil(fs / settings.floorHz());
        int minLag = Math.max(2, (int) Math.floor(fs / settings.ceilingHz()));
        int w = maxLag;                                   // integration window: one period of the floor
        FrameLayout layout = FrameLayout.of(x.length, fs, w + maxLag + 2, settings.timeStep());
        int frames = layout.count();
        double[] f0 = new double[frames];
        double[] strength = new double[frames];
        double globalPeak = 0;
        for (double v : x) {
            globalPeak = Math.max(globalPeak, Math.abs(v));
        }
        double[] d = new double[maxLag + 2];
        double[] dPrime = new double[maxLag + 2];

        for (int f = 0; f < frames; f++) {
            int start = layout.startSample(f);
            double localPeak = 0;
            for (int j = 0; j < w + maxLag; j++) {
                localPeak = Math.max(localPeak, Math.abs(x[start + j]));
            }
            if (globalPeak == 0 || localPeak / globalPeak < settings.silenceThreshold()) {
                continue;                                  // silent: unvoiced
            }
            // Step 1: difference function.
            for (int tau = 0; tau <= maxLag + 1; tau++) {
                double sum = 0;
                for (int j = 0; j < w; j++) {
                    double diff = x[start + j] - x[start + j + tau];
                    sum += diff * diff;
                }
                d[tau] = sum;
            }
            // Step 2: cumulative mean normalised difference.
            dPrime[0] = 1;
            double running = 0;
            for (int tau = 1; tau <= maxLag + 1; tau++) {
                running += d[tau];
                dPrime[tau] = running == 0 ? 1 : d[tau] * tau / running;
            }
            // Step 3: first dip below the absolute threshold, else the global minimum.
            int chosen = -1;
            for (int tau = minLag; tau <= maxLag; tau++) {
                if (dPrime[tau] < THRESHOLD) {
                    while (tau + 1 <= maxLag && dPrime[tau + 1] < dPrime[tau]) {
                        tau++;
                    }
                    chosen = tau;
                    break;
                }
            }
            if (chosen < 0) {
                chosen = minLag;
                for (int tau = minLag + 1; tau <= maxLag; tau++) {
                    if (dPrime[tau] < dPrime[chosen]) {
                        chosen = tau;
                    }
                }
            }
            // Step 4: parabolic interpolation (on −d′, so the dip becomes a peak).
            double lag = chosen;
            double dip = dPrime[chosen];
            if (chosen > 0 && chosen < maxLag + 1) {
                double a = dPrime[chosen - 1];
                double b = dPrime[chosen];
                double c = dPrime[chosen + 1];
                double denominator = a - 2 * b + c;
                if (denominator > 0) {
                    double delta = 0.5 * (a - c) / denominator;
                    if (Math.abs(delta) <= 1) {
                        lag = chosen + delta;
                        dip = b - 0.25 * (a - c) * delta;
                    }
                }
            }
            if (dip >= UNVOICED_ABOVE) {
                continue;
            }
            double frequency = fs / lag;                   // Step 5
            if (frequency < settings.floorHz() || frequency > settings.ceilingHz()) {
                continue;
            }
            f0[f] = frequency;
            strength[f] = Math.max(0, Math.min(1, 1 - dip));
        }
        return new PitchContour(name(), settings.timeStep(), layout.times(), f0, strength);
    }
}
