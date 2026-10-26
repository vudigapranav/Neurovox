package com.neurovox.tools;

import com.google.gson.GsonBuilder;
import com.neurovox.analysis.RecordingAnalysis;
import com.neurovox.analysis.RecordingAnalyzer;
import com.neurovox.audio.AudioClip;
import com.neurovox.audio.WavCodec;
import com.neurovox.model.FeatureValue;
import com.neurovox.model.TaskType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line tool for the validation study: analyses sustained-vowel WAV files with exactly the same code the
 * application uses ({@link RecordingAnalyzer}) and prints JSON — the steady region chosen, the quality checks,
 * and every feature (or the reason it is unavailable).
 *
 * <pre>
 *   java -cp target/neurovox.jar com.neurovox.tools.AnalyzeWav [--out results.json] file1.wav file2.wav ...
 * </pre>
 * The Praat comparison script runs Praat on the same files and the same {@code regionStart..regionEnd}.
 */
public final class AnalyzeWav {

    private AnalyzeWav() {
    }

    public static void main(String[] args) throws IOException {
        Path out = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out") && i + 1 < args.length) {
                out = Path.of(args[++i]);
            } else {
                files.add(Path.of(args[i]));
            }
        }
        if (files.isEmpty()) {
            System.err.println("Usage: AnalyzeWav [--out results.json] file.wav ...");
            System.exit(2);
        }
        RecordingAnalyzer analyzer = new RecordingAnalyzer();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Path file : files) {
            AudioClip clip = WavCodec.read(file);
            long start = System.nanoTime();
            RecordingAnalysis a = analyzer.analyze(TaskType.SUSTAINED_VOWEL, clip);
            double ms = (System.nanoTime() - start) / 1e6;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("file", file.getFileName().toString());
            r.put("sampleRate", clip.sampleRate());
            r.put("extractorVersion", a.extractorVersion());
            r.put("quality", a.quality().status().code());
            r.put("qualityProblems", a.quality().problems().stream().map(c -> c.code() + ": " + c.message()).toList());
            r.put("details", a.details());
            r.put("analysisMs", Math.round(ms * 10) / 10.0);
            Map<String, Object> values = new LinkedHashMap<>();
            Map<String, String> reasons = new LinkedHashMap<>();
            for (FeatureValue v : a.features()) {
                values.put(v.name(), v.value());
                if (!v.isAvailable()) {
                    reasons.put(v.name(), v.unavailableReason());
                }
            }
            r.put("features", values);
            r.put("unavailable", reasons);
            results.add(r);
        }
        String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(results);
        if (out == null) {
            System.out.println(json);
        } else {
            Files.writeString(out, json + "\n", StandardCharsets.UTF_8);
        }
    }
}
