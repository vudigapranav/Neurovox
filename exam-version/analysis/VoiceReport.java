package analysis;

import audio.VoiceSource;
import audio.VoiceSource.VoiceException;
import java.util.*;

// Turns the cycles into F0, jitter, shimmer and HNR, compares them with reference limits
// and builds the report. Runs on its own thread (Runnable); sortable by jitter (Comparable).
public class VoiceReport implements Runnable, Comparable<VoiceReport> {
    public static final String F0 = "Pitch F0 (Hz)", JITTER = "Jitter (%)", SHIMMER = "Shimmer (%)", HNR = "HNR (dB)";
    public static final double JITTER_LIMIT = 1.04, SHIMMER_LIMIT = 3.81, HNR_LIMIT = 20.0;

    private final VoiceSource voice;
    private final Map<String, Double> features = new LinkedHashMap<>();
    private String error;
    private boolean done;

    public VoiceReport(VoiceSource voice) { this.voice = voice; }

    public void run() {
        try {
            PitchTracker p = new PitchTracker(voice);
            features.put(F0, 1 / mean(p.periods));
            features.put(JITTER, 100 * change(p.periods) / mean(p.periods));
            features.put(SHIMMER, 100 * change(p.peaks) / mean(p.peaks));
            features.put(HNR, 10 * Math.log10(p.similarity / (1 - p.similarity)));
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

    static double mean(List<Double> v) {
        double s = 0;
        for (double d : v) s += d;
        return s / v.size();
    }

    static double change(List<Double> v) {       // average difference between neighbours
        double s = 0;
        for (int i = 1; i < v.size(); i++) s += Math.abs(v.get(i) - v.get(i - 1));
        return s / (v.size() - 1);
    }

    public double get(String name) { return features.get(name); }

    public int markers() {                       // measures outside their limit (0-3)
        int m = 0;
        if (get(JITTER) > JITTER_LIMIT) m++;
        if (get(SHIMMER) > SHIMMER_LIMIT) m++;
        if (get(HNR) < HNR_LIMIT) m++;
        return m;
    }

    public boolean needsFurtherCheck() { return markers() >= 2; }

    public String report() throws VoiceException {
        if (error != null) throw new VoiceException(error);
        StringBuilder sb = new StringBuilder("Voice : " + voice + "\n");
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

    public int compareTo(VoiceReport other) {   // steadier voice (lower jitter) first
        return Double.compare(get(JITTER), other.get(JITTER));
    }

    @Override
    public String toString() {
        return String.format("%-18s jitter %5.2f %%  shimmer %5.2f %%  HNR %4.1f dB -> %s",
                voice.getName(), get(JITTER), get(SHIMMER), get(HNR), needsFurtherCheck() ? "CHECK" : "OK");
    }
}
