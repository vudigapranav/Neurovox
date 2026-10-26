package com.neurovox.dsp;

import com.neurovox.audio.AudioClip;
import com.neurovox.audio.WindowFunction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Autocorrelation pitch detector following Boersma (1993), the method behind Praat's "To Pitch (ac)".
 *
 * <h2>Per frame (window = 3 periods of the pitch floor, Hann-shaped)</h2>
 * <ol>
 *   <li>Subtract the frame mean and multiply by the Hann window w: a[n] = (x[n] − x̄) · w[n].</li>
 *   <li>Autocorrelation r_a(τ) = Σ_n a[n] · a[n+τ].</li>
 *   <li>Divide by the window's own autocorrelation to undo the taper (Boersma 1993, eq. 9):
 *       <pre>  r(τ) = [ r_a(τ) / r_a(0) ] / [ r_w(τ) / r_w(0) ]</pre>
 *       For a perfectly periodic signal r(T) ≈ 1 at the period T; noise lowers it.</li>
 *   <li>Every local maximum of r between 1/ceiling and 1/floor above ½·voicingThreshold is a candidate.
 *       Its lag and height are refined between samples with windowed-sinc interpolation
 *       ({@link Interpolation#sincMaximum}). F0 = sampleRate / lag.</li>
 *   <li>One "unvoiced" candidate is added with strength
 *       <pre>  voicingThreshold + max(0, 2 − (localPeak / globalPeak) / (silenceThreshold / (1 + voicingThreshold)))</pre>
 *       so quiet frames favour "unvoiced".</li>
 * </ol>
 *
 * <h2>Across frames: Viterbi path</h2>
 * A dynamic-programming search picks one candidate per frame maximising
 * <pre>  Σ strength(candidate) − Σ transitionCost</pre>
 * where a voiced candidate's strength is r − octaveCost·log2(ceiling / F0) (favours the true F0 over its
 * sub-octaves), switching voiced↔unvoiced costs voicedUnvoicedCost, and an F0 change costs
 * octaveJumpCost·|log2(F0₁ / F0₂)|. Costs are scaled by 0.01 s / timeStep. This is what stops isolated
 * octave jumps (Boersma 1993, §4).
 *
 * <p>Source: P. Boersma (1993), "Accurate short-term analysis of the fundamental frequency and the
 * harmonics-to-noise ratio of a sampled sound", IFA Proceedings 17, 97–110.
 */
public final class AutocorrelationPitchDetector implements PitchDetector {

    private static final int MAX_CANDIDATES = 15;
    private static final double PERIODS_PER_WINDOW = 3.0;

    private final PitchSettings settings;

    public AutocorrelationPitchDetector() {
        this(PitchSettings.DEFAULT);
    }

    public AutocorrelationPitchDetector(PitchSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "autocorrelation";
    }

    private record Candidate(double frequency, double strength) {
    }

    @Override
    public PitchContour detect(AudioClip clip) {
        double[] x = centred(clip.samples());
        int fs = clip.sampleRate();
        int half = (int) Math.floor(PERIODS_PER_WINDOW / settings.floorHz() * fs) / 2;
        int nW = 2 * half;
        FrameLayout layout = FrameLayout.of(x.length, fs, nW, settings.timeStep());
        if (layout.count() == 0) {
            return empty();
        }

        double[] w = WindowFunction.HANN.coefficients(nW);
        int minLag = Math.max(2, (int) Math.floor(fs / settings.ceilingHz()));
        int maxLag = Math.min((int) Math.ceil(fs / settings.floorHz()) + 2, nW - 2);
        int lagLimit = Math.min(nW - 1, maxLag + Interpolation.SINC_DEPTH);
        double[] windowR = normalisedAutocorrelation(w, lagLimit);
        int halfPeriod = (int) Math.round(0.5 * fs / settings.floorHz());
        double globalPeak = 0;
        for (double v : x) {
            globalPeak = Math.max(globalPeak, Math.abs(v));
        }

        int frames = layout.count();
        List<List<Candidate>> candidates = new ArrayList<>(frames);
        double[] unvoicedStrength = new double[frames];
        double[] a = new double[nW];
        double[] r = new double[lagLimit + 1];
        double vt = settings.voicingThreshold();

        for (int f = 0; f < frames; f++) {
            int start = layout.startSample(f);
            double mean = 0;
            for (int n = 0; n < nW; n++) {
                mean += x[start + n];
            }
            mean /= nW;
            double localPeak = 0;
            for (int n = Math.max(0, half - halfPeriod); n < Math.min(nW, half + halfPeriod); n++) {
                localPeak = Math.max(localPeak, Math.abs(x[start + n] - mean));
            }
            double intensity = globalPeak == 0 ? 0 : Math.min(1.0, localPeak / globalPeak);
            unvoicedStrength[f] = vt + Math.max(0, 2 - intensity / (settings.silenceThreshold() / (1 + vt)));

            List<Candidate> list = new ArrayList<>();
            if (localPeak > 0) {
                for (int n = 0; n < nW; n++) {
                    a[n] = (x[start + n] - mean) * w[n];
                }
                double r0 = 0;
                for (int n = 0; n < nW; n++) {
                    r0 += a[n] * a[n];
                }
                for (int lag = 0; lag <= lagLimit; lag++) {
                    double sum = 0;
                    for (int n = 0; n + lag < nW; n++) {
                        sum += a[n] * a[n + lag];
                    }
                    r[lag] = sum / (r0 * windowR[lag]);
                }
                for (int lag = minLag; lag <= maxLag && lag + 1 <= lagLimit; lag++) {
                    if (r[lag] > 0.5 * vt && r[lag] >= r[lag - 1] && r[lag] > r[lag + 1]) {
                        Interpolation.Peak peak = Interpolation.sincMaximum(r, lag, Interpolation.SINC_DEPTH);
                        double strength = peak.value() > 1.0 ? 1.0 / peak.value() : peak.value();
                        double frequency = fs / peak.position();
                        if (frequency >= settings.floorHz() && frequency <= settings.ceilingHz()) {
                            list.add(new Candidate(frequency, strength));
                        }
                    }
                }
                // Keep the strongest, judged with the octave bonus that favours higher (non-sub-harmonic) candidates.
                list.sort(Comparator.comparingDouble((Candidate c) ->
                        c.strength() + settings.octaveCost() * log2(c.frequency() / settings.floorHz())).reversed());
                if (list.size() > MAX_CANDIDATES - 1) {
                    list = new ArrayList<>(list.subList(0, MAX_CANDIDATES - 1));
                }
            }
            candidates.add(list);
        }
        return viterbi(layout, candidates, unvoicedStrength);
    }

    private PitchContour viterbi(FrameLayout layout, List<List<Candidate>> candidates, double[] unvoicedStrength) {
        int frames = candidates.size();
        double timeStepCorrection = 0.01 / settings.timeStep();
        double[][] delta = new double[frames][];
        int[][] back = new int[frames][];
        for (int f = 0; f < frames; f++) {
            List<Candidate> list = candidates.get(f);
            int n = list.size() + 1;                          // index 0 = unvoiced
            double[] score = new double[n];
            score[0] = unvoicedStrength[f];
            for (int j = 1; j < n; j++) {
                Candidate c = list.get(j - 1);
                score[j] = c.strength() - settings.octaveCost() * log2(settings.ceilingHz() / c.frequency());
            }
            delta[f] = new double[n];
            back[f] = new int[n];
            if (f == 0) {
                System.arraycopy(score, 0, delta[f], 0, n);
                continue;
            }
            List<Candidate> previous = candidates.get(f - 1);
            for (int j = 0; j < n; j++) {
                double best = Double.NEGATIVE_INFINITY;
                int bestK = 0;
                for (int k = 0; k < delta[f - 1].length; k++) {
                    double fPrev = k == 0 ? 0 : previous.get(k - 1).frequency();
                    double fNow = j == 0 ? 0 : list.get(j - 1).frequency();
                    double cost;
                    if (fPrev == 0 && fNow == 0) {
                        cost = 0;
                    } else if (fPrev == 0 || fNow == 0) {
                        cost = settings.voicedUnvoicedCost();
                    } else {
                        cost = settings.octaveJumpCost() * Math.abs(log2(fPrev / fNow));
                    }
                    double value = delta[f - 1][k] - cost * timeStepCorrection;
                    if (value > best) {
                        best = value;
                        bestK = k;
                    }
                }
                delta[f][j] = best + score[j];
                back[f][j] = bestK;
            }
        }
        double[] f0 = new double[frames];
        double[] strength = new double[frames];
        int j = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < delta[frames - 1].length; k++) {
            if (delta[frames - 1][k] > best) {
                best = delta[frames - 1][k];
                j = k;
            }
        }
        for (int f = frames - 1; f >= 0; f--) {
            if (j > 0) {
                Candidate c = candidates.get(f).get(j - 1);
                f0[f] = c.frequency();
                strength[f] = c.strength();
            }
            j = back[f][j];
        }
        return new PitchContour(name(), settings.timeStep(), layout.times(), f0, strength);
    }

    /** r_w(τ) / r_w(0) for the window itself. */
    static double[] normalisedAutocorrelation(double[] w, int maxLag) {
        double[] out = new double[maxLag + 1];
        double r0 = 0;
        for (double v : w) {
            r0 += v * v;
        }
        for (int lag = 0; lag <= maxLag; lag++) {
            double sum = 0;
            for (int n = 0; n + lag < w.length; n++) {
                sum += w[n] * w[n + lag];
            }
            out[lag] = sum / r0;
        }
        return out;
    }

    private PitchContour empty() {
        return new PitchContour(name(), settings.timeStep(), new double[0], new double[0], new double[0]);
    }

    static double[] centred(double[] x) {
        double mean = 0;
        for (double v : x) {
            mean += v;
        }
        mean = x.length == 0 ? 0 : mean / x.length;
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            y[i] = x[i] - mean;
        }
        return y;
    }

    static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }
}
