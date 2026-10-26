package analysis;

import audio.VoiceSource;
import audio.VoiceSource.VoiceException;
import java.util.*;

// Measures a sustained "aah": pitch (F0), jitter, shimmer and HNR, and compares them
// with reference limits. Runs on its own thread and can be sorted by jitter.
public class VoiceAnalyzer implements Runnable, Comparable<VoiceAnalyzer> {
    public static final String F0 = "Pitch F0 (Hz)", JITTER = "Jitter (%)", SHIMMER = "Shimmer (%)", HNR = "HNR (dB)";
    public static final double JITTER_LIMIT = 1.04, SHIMMER_LIMIT = 3.81, HNR_LIMIT = 20.0;

    private final VoiceSource voice;
    private final Map<String, Double> features = new LinkedHashMap<>();
    private final List<Double> periods = new ArrayList<>();   // length of each vocal cycle (s)
    private final List<Double> peaks = new ArrayList<>();     // height of each vocal cycle
    private String error;
    private boolean done;

    public VoiceAnalyzer(VoiceSource voice) {
        this.voice = voice;
    }

    public void run() {
        try {
            analyze();
        } catch (VoiceException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = "Analysis failed: " + e;
        } finally {
            finish();
        }
    }

    private synchronized void finish() { done = true; }
    public synchronized boolean isDone() { return done; }

    private void analyze() throws VoiceException {
        double[] all = voice.getSamples();
        float rate = voice.getRate();
        if (all.length < 2 * rate) throw new VoiceException("Too short. Record at least 2 seconds.");

        // 1. keep the steady middle (drop first and last 20 %) and remove the DC offset
        int from = all.length / 5;
        double[] x = Arrays.copyOfRange(all, from, all.length - from);
        double mean = 0, power = 0;
        for (double v : x) mean += v / x.length;
        for (int i = 0; i < x.length; i++) {
            x[i] -= mean;
            power += x[i] * x[i];
        }
        if (Math.sqrt(power / x.length) < 0.01) throw new VoiceException("Too quiet. Say 'aah' louder.");

        // 2. rough period = the lag (75-500 Hz) where the whole voice best matches itself
        int minLag = (int) (rate / 500), maxLag = (int) (rate / 75), best = minLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            int len = x.length - maxLag;
            if (corr(x, 0, lag, len) > corr(x, 0, best, len)) best = lag;
        }

        // 3. follow the voice cycle by cycle: slide the current cycle forward until it
        //    best matches the next one; that slide is the exact length of this cycle
        double t = 0, period = best, sumR = 0;
        while (t + 2.5 * period < x.length) {
            int pos = (int) Math.round(t), len = (int) period, s = (int) (0.8 * period);
            double bestC = corr(x, pos, pos + s, len);
            for (int k = s + 1; k <= 1.2 * period; k++) {
                double c = corr(x, pos, pos + k, len);
                if (c > bestC) { bestC = c; s = k; }
            }
            double a = corr(x, pos, pos + s - 1, len), c = corr(x, pos, pos + s + 1, len);
            double d = a - 2 * bestC + c;
            period = d < 0 ? s + 0.5 * (a - c) / d : s;          // parabola through the best match
            period = Math.max(minLag, Math.min(maxLag, period));  // stay inside 75-500 Hz
            double top = 0;
            for (int k = pos; k < pos + len; k++) top = Math.max(top, x[k]);
            periods.add(period / rate);
            peaks.add(top);                                     // height of this cycle
            sumR += bestC;
            t += period;
        }
        if (periods.size() < 10) throw new VoiceException("No steady voice. Hold one 'aah' for 3-5 s.");

        // 4. jitter / shimmer = average change from one cycle to the next, relative to the mean
        double sumT = 0, dT = 0, sumA = 0, dA = 0;
        for (int k = 0; k < periods.size(); k++) {
            sumT += periods.get(k);
            sumA += peaks.get(k);
            if (k > 0) {
                dT += Math.abs(periods.get(k) - periods.get(k - 1));
                dA += Math.abs(peaks.get(k) - peaks.get(k - 1));
            }
        }
        int n = periods.size();
        double r = sumR / n;                                    // how alike neighbouring cycles are
        if (r < 0.5) throw new VoiceException("No clear voice found (only noise). Try again in a quiet room.");
        features.put(F0, n / sumT);
        features.put(JITTER, 100 * (dT / (n - 1)) / (sumT / n));
        features.put(SHIMMER, 100 * (dA / (n - 1)) / (sumA / n));
        features.put(HNR, 10 * Math.log10(r / (1 - r)));
    }

    // how alike two pieces of the signal are: 1 = identical, 0 = unrelated
    private static double corr(double[] x, int a, int b, int len) {
        double xy = 0, xx = 0, yy = 0;
        for (int i = 0; i < len; i++) {
            xy += x[a + i] * x[b + i];
            xx += x[a + i] * x[a + i];
            yy += x[b + i] * x[b + i];
        }
        return xy / Math.sqrt(xx * yy);
    }

    public double get(String name) { return features.get(name); }

    public int markers() {                 // measures outside their limit (0-3)
        int m = 0;
        if (get(JITTER) > JITTER_LIMIT) m++;
        if (get(SHIMMER) > SHIMMER_LIMIT) m++;
        if (get(HNR) < HNR_LIMIT) m++;
        return m;
    }

    public boolean needsFurtherCheck() { return markers() >= 2; }

    public String report() throws VoiceException {
        if (error != null) throw new VoiceException(error);
        StringBuilder sb = new StringBuilder("Voice : " + voice + "\nCycles: " + periods.size() + "\n");
        for (Map.Entry<String, Double> e : features.entrySet()) {
            sb.append(String.format("%-14s: %7.2f%n", e.getKey(), e.getValue()));
        }
        sb.append("Limits: jitter <= 1.04 %, shimmer <= 3.81 %, HNR >= 20 dB\n");
        sb.append("Markers outside limits: " + markers() + " of 3\nRESULT: ");
        sb.append(needsFurtherCheck() ? "FURTHER CHECK SUGGESTED (see a neurologist)" : "NO CONCERN INDICATED");
        return sb.toString();
    }

    public String toCsv() {
        return String.format("%.1f,%.2f,%.2f,%.1f,%s", get(F0), get(JITTER), get(SHIMMER), get(HNR),
                needsFurtherCheck() ? "CHECK" : "OK");
    }

    public int compareTo(VoiceAnalyzer other) {     // steadier voice (lower jitter) first
        return Double.compare(get(JITTER), other.get(JITTER));
    }

    @Override
    public String toString() {
        return String.format("%-18s jitter %5.2f %%  shimmer %5.2f %%  HNR %4.1f dB -> %s",
                voice.getName(), get(JITTER), get(SHIMMER), get(HNR), needsFurtherCheck() ? "CHECK" : "OK");
    }
}
