package analysis;

import audio.VoiceSource;
import audio.VoiceSource.VoiceException;
import java.util.*;

// Finds every vocal cycle in a sustained "aah": how long it lasts and how high its peak is.
public class PitchTracker {
    final List<Double> periods = new ArrayList<>();   // cycle lengths in seconds
    final List<Double> peaks = new ArrayList<>();     // cycle peak heights
    double similarity;                                // how alike neighbouring cycles are (0-1)

    public PitchTracker(VoiceSource voice) throws VoiceException {
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
        int minLag = (int) (rate / 500), maxLag = (int) (rate / 75), best = minLag, len = x.length - maxLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (corr(x, 0, lag, len) > corr(x, 0, best, len)) best = lag;
        }

        // 3. slide each cycle forward until it best matches the next one: that slide is its length
        double t = 0, period = best, sum = 0;
        while (t + 2.5 * period < x.length) {
            int pos = (int) Math.round(t), n = (int) period, s = (int) (0.8 * period);
            double b = corr(x, pos, pos + s, n);
            for (int k = s + 1; k <= 1.2 * period; k++) {
                double c = corr(x, pos, pos + k, n);
                if (c > b) { b = c; s = k; }
            }
            double a = corr(x, pos, pos + s - 1, n), c = corr(x, pos, pos + s + 1, n), d = a - 2 * b + c;
            period = d < 0 ? s + 0.5 * (a - c) / d : s;           // parabola through the best match
            period = Math.max(minLag, Math.min(maxLag, period));   // stay inside 75-500 Hz
            double top = 0;
            for (int k = pos; k < pos + n; k++) top = Math.max(top, x[k]);
            periods.add(period / rate);
            peaks.add(top);
            sum += b;
            t += period;
        }
        if (periods.size() < 10) throw new VoiceException("No steady voice. Hold one 'aah' for 3-5 s.");
        similarity = sum / periods.size();
        if (similarity < 0.5) throw new VoiceException("No clear voice found (only noise). Try a quiet room.");
    }

    // how alike two pieces of the signal are: 1 = identical, 0 = unrelated
    static double corr(double[] x, int a, int b, int len) {
        double xy = 0, xx = 0, yy = 0;
        for (int i = 0; i < len; i++) {
            xy += x[a + i] * x[b + i];
            xx += x[a + i] * x[a + i];
            yy += x[b + i] * x[b + i];
        }
        return xy / Math.sqrt(xx * yy);
    }
}
