package com.neurovox.tools;

import com.google.gson.GsonBuilder;
import com.neurovox.analysis.SteadyRegion;
import com.neurovox.audio.AudioClip;
import com.neurovox.audio.Resampler;
import com.neurovox.audio.SignalOps;
import com.neurovox.audio.WavCodec;
import com.neurovox.dsp.AutocorrelationPitchDetector;
import com.neurovox.dsp.PitchContour;
import com.neurovox.dsp.PitchDetector;
import com.neurovox.dsp.PitchSettings;
import com.neurovox.dsp.PulseExtractor;
import com.neurovox.dsp.PulseTrain;
import com.neurovox.dsp.YinPitchDetector;
import com.neurovox.features.JitterMeasures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The autocorrelation-versus-YIN study (Sprint I). For every WAV file and three listening conditions it runs both
 * detectors and compares each frame with a reference contour.
 *
 * <ul>
 *   <li><b>Conditions:</b> {@code clean} (the file as is), {@code highpass} (4th-order 300 Hz high-pass, which removes
 *       the fundamental of most voices — the classic trap for octave errors), and {@code noise} (white noise added at
 *       6 dB SNR). Conditions are applied at the file's native rate; both detectors then run on the 16 kHz analysis
 *       copy, and pulses for the jitter comparison are found on the native-rate signal, as in the application.</li>
 *   <li><b>References:</b> the true F0 from the synthesis ({@code <name>.truth_f0.csv}) and, for the clean condition,
 *       Praat's "To Pitch (ac)" contour ({@code <name>.praat_f0.csv}).</li>
 *   <li><b>Metrics:</b> mean absolute error in Hz over frames voiced in both; octave-error rate (estimate within 5 % of
 *       half or double the reference); gross-error rate (more than 20 % off); voicing recall; time per second of
 *       audio, the median of 10 runs after 3 warm-up runs (the JIT makes the first runs slow); and local jitter
 *       computed with pulses guided by each detector's contour, to show how the choice propagates.</li>
 * </ul>
 * <pre>
 *   java -cp target/neurovox.jar com.neurovox.tools.PitchStudy --refs validation/synthetic --truth validation/synthetic/truth.csv \
 *        --out docs/results/pitch_study validation/synthetic/syn*.wav
 * </pre>
 */
public final class PitchStudy {

    private static final double TOLERANCE_S = 0.0051;

    private PitchStudy() {
    }

    public static void main(String[] args) throws IOException {
        Path refs = null;
        Path truthCsv = null;
        Path out = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--refs" -> refs = Path.of(args[++i]);
                case "--truth" -> truthCsv = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> files.add(Path.of(args[i]));
            }
        }
        if (refs == null || out == null || files.isEmpty()) {
            System.err.println("Usage: PitchStudy --refs DIR --truth truth.csv --out PREFIX file.wav ...");
            System.exit(2);
        }
        Map<String, Double> trueJitter = truthCsv == null ? Map.of() : readTrueJitter(truthCsv);
        List<PitchDetector> detectors = List.of(new AutocorrelationPitchDetector(), new YinPitchDetector());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString().replaceFirst("\\.wav$", "");
            AudioClip wav = WavCodec.read(file);
            AudioClip native_ = new AudioClip(SignalOps.removeDc(wav.samples()), wav.sampleRate());
            double[][] truth = readContour(refs.resolve(name + ".truth_f0.csv"));
            Path praatPath = refs.resolve(name + ".praat_f0.csv");
            double[][] praat = Files.exists(praatPath) ? readContour(praatPath) : null;
            // Conditions are applied at the native rate; detectors run on the 16 kHz analysis copy.
            Map<String, AudioClip> conditions = new LinkedHashMap<>();
            conditions.put("clean", native_);
            conditions.put("highpass", highPass(highPass(native_, 300), 300));
            conditions.put("noise", addNoise(native_, 6, name.hashCode()));
            SteadyRegion region = SteadyRegion.find(new AutocorrelationPitchDetector().detect(Resampler.toAnalysisRate(native_)));
            for (Map.Entry<String, AudioClip> condition : conditions.entrySet()) {
                AudioClip clip = Resampler.toAnalysisRate(condition.getValue());
                for (PitchDetector d : detectors) {
                    for (int w = 0; w < 3; w++) {
                        d.detect(clip);
                    }
                    double[] times = new double[10];
                    PitchContour contour = null;
                    for (int r = 0; r < times.length; r++) {
                        long t0 = System.nanoTime();
                        contour = d.detect(clip);
                        times[r] = (System.nanoTime() - t0) / 1e6;
                    }
                    Arrays.sort(times);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file", name);
                    row.put("condition", condition.getKey());
                    row.put("detector", d.name());
                    row.putAll(prefix("truth_", compare(contour, truth)));
                    if (praat != null && condition.getKey().equals("clean")) {
                        row.putAll(prefix("praat_", compare(contour, praat)));
                    }
                    row.put("ms_per_second", round(times[times.length / 2] / clip.durationSeconds(), 3));
                    if (region != null) {
                        PulseTrain all = PulseExtractor.extract(condition.getValue(), contour);
                        PulseTrain pulses = new PulseTrain(Arrays.stream(all.times())
                                .filter(t -> t >= region.start() && t <= region.end()).toArray());
                        double jitter = JitterMeasures.compute(pulses, PitchSettings.DEFAULT).local();
                        row.put("jitter_local", Double.isFinite(jitter) ? jitter : null);
                    }
                    row.put("true_jitter_local", trueJitter.get(name));
                    rows.add(row);
                }
            }
        }
        writeCsv(rows, Path.of(out + ".csv"));
        Files.writeString(Path.of(out + ".json"), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(rows) + "\n",
                StandardCharsets.UTF_8);
        System.out.println("Wrote " + rows.size() + " rows to " + out + ".csv/.json");
    }

    /** Frame-by-frame comparison with a reference contour (rows of {time, f0}, NaN = unvoiced). */
    static Map<String, Object> compare(PitchContour c, double[][] ref) {
        int both = 0;
        int octave = 0;
        int gross = 0;
        int refVoiced = 0;
        int detected = 0;
        int refUnvoiced = 0;
        int falseVoiced = 0;
        double absError = 0;
        for (int i = 0; i < c.frames(); i++) {
            double r = lookup(ref, c.times()[i]);
            if (Double.isInfinite(r)) {
                continue;                                  // no reference frame within 5 ms
            }
            if (Double.isNaN(r)) {
                refUnvoiced++;
                if (c.isVoiced(i)) {
                    falseVoiced++;
                }
                continue;
            }
            refVoiced++;
            if (!c.isVoiced(i)) {
                continue;
            }
            detected++;
            double f = c.f0()[i];
            both++;
            absError += Math.abs(f - r);
            double ratio = f / r;
            if (Math.abs(ratio - 2) < 0.1 || Math.abs(ratio - 0.5) < 0.025) {
                octave++;
            }
            if (Math.abs(ratio - 1) > 0.2) {
                gross++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frames", both);
        m.put("mae_hz", both > 0 ? round(absError / both, 4) : null);
        m.put("octave_errors", octave);
        m.put("gross_errors", gross);
        m.put("voicing_recall", refVoiced > 0 ? round((double) detected / refVoiced, 4) : null);
        m.put("false_voicing", refUnvoiced > 0 ? round((double) falseVoiced / refUnvoiced, 4) : null);
        return m;
    }

    /** Reference F0 at time t: the nearest row within 5 ms; NaN if unvoiced there; +∞ if no row is near. */
    static double lookup(double[][] ref, double t) {
        int lo = 0;
        int hi = ref.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (ref[mid][0] < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int best = lo;
        if (lo > 0 && Math.abs(ref[lo - 1][0] - t) < Math.abs(ref[lo][0] - t)) {
            best = lo - 1;
        }
        return Math.abs(ref[best][0] - t) <= TOLERANCE_S ? ref[best][1] : Double.POSITIVE_INFINITY;
    }

    static double[][] readContour(Path csv) throws IOException {
        List<double[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(csv)) {
            if (line.startsWith("time")) {
                continue;
            }
            String[] p = line.split(",", -1);
            double f = p.length < 2 || p[1].isBlank() || p[1].equals("nan") ? Double.NaN : Double.parseDouble(p[1]);
            rows.add(new double[] {Double.parseDouble(p[0]), f > 0 ? f : Double.NaN});
        }
        return rows.toArray(new double[0][]);
    }

    static Map<String, Double> readTrueJitter(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int name = header.indexOf("name");
        int jitter = header.indexOf("true_jitter_local");
        Map<String, Double> out = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] p = line.split(",");
            out.put(p[name], Double.parseDouble(p[jitter]));
        }
        return out;
    }

    /** RBJ audio-cookbook 2nd-order high-pass (Q = 0.707); applied twice for 4th order. */
    static AudioClip highPass(AudioClip clip, double cutoff) {
        double w = 2 * Math.PI * cutoff / clip.sampleRate();
        double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
        double cos = Math.cos(w);
        double a0 = 1 + alpha;
        double b0 = (1 + cos) / 2 / a0;
        double b1 = -(1 + cos) / a0;
        double b2 = (1 + cos) / 2 / a0;
        double a1 = -2 * cos / a0;
        double a2 = (1 - alpha) / a0;
        double[] x = clip.samples();
        double[] y = new double[x.length];
        for (int n = 0; n < x.length; n++) {
            y[n] = b0 * x[n] + (n > 0 ? b1 * x[n - 1] - a1 * y[n - 1] : 0) + (n > 1 ? b2 * x[n - 2] - a2 * y[n - 2] : 0);
        }
        return new AudioClip(y, clip.sampleRate());
    }

    static AudioClip addNoise(AudioClip clip, double snrDb, long seed) {
        double power = 0;
        int n = 0;
        for (double v : clip.samples()) {
            if (Math.abs(v) > 1e-4) {
                power += v * v;
                n++;
            }
        }
        double rms = Math.sqrt(power / Math.max(1, n) / Math.pow(10, snrDb / 10));
        Random random = new Random(seed);
        double[] y = clip.samples().clone();
        for (int i = 0; i < y.length; i++) {
            y[i] += rms * random.nextGaussian();
        }
        return new AudioClip(y, clip.sampleRate());
    }

    private static Map<String, Object> prefix(String p, Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(p + k, v));
        return out;
    }

    private static void writeCsv(List<Map<String, Object>> rows, Path path) throws IOException {
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            for (String k : r.keySet()) {
                if (!columns.contains(k)) {
                    columns.add(k);
                }
            }
        }
        StringBuilder sb = new StringBuilder(String.join(",", columns)).append('\n');
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : columns) {
                Object v = r.get(c);
                cells.add(v == null ? "" : String.format(Locale.ROOT, "%s", v));
            }
            sb.append(String.join(",", cells)).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static double round(double v, int places) {
        double s = Math.pow(10, places);
        return Math.round(v * s) / s;
    }
}
