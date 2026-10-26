package com.neurovox.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the exported research model and the scientific-gate decision from the classpath
 * ({@code src/main/resources/model/}). Both are plain JSON so they can be read and explained by a person.
 */
public final class ModelLoader {

    public static final String MODEL_RESOURCE = "model/research-model.json";
    public static final String GATE_RESOURCE = "model/research-gate.json";

    private ModelLoader() {
    }

    /** The model, or empty when no model file is packaged. A malformed file is an error, not "no model". */
    public static Optional<ResearchModel> loadModel() {
        return read(MODEL_RESOURCE).map(ModelLoader::parseModel);
    }

    /** The gate decision; a missing file means "no evidence", which is treated as no-go. */
    public static CompatibilityGate loadGate() {
        return read(GATE_RESOURCE).map(ModelLoader::parseGate).orElse(CompatibilityGate.missing());
    }

    /** The "evaluation" summary written by the training script (grouped cross-validation results), if present. */
    public static Optional<JsonObject> loadEvaluation() {
        return read(MODEL_RESOURCE).map(json -> JsonParser.parseString(json).getAsJsonObject())
                .filter(o -> o.has("evaluation")).map(o -> o.getAsJsonObject("evaluation"));
    }

    public static ResearchModel parseModel(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return new ResearchModel(
                o.get("modelVersion").getAsString(),
                strings(o.getAsJsonArray("featureOrder")),
                doubles(o.getAsJsonArray("scalerMean")),
                doubles(o.getAsJsonArray("scalerScale")),
                doubles(o.getAsJsonArray("coefficients")),
                o.get("intercept").getAsDouble(),
                o.get("threshold").getAsDouble());
    }

    public static CompatibilityGate parseGate(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return new CompatibilityGate(
                CompatibilityGate.Decision.fromCode(o.get("decision").getAsString()),
                o.get("decidedOn").getAsString(),
                o.get("summary").getAsString(),
                strings(o.getAsJsonArray("reasons")),
                o.has("evidence") ? o.get("evidence").getAsString() : "");
    }

    private static Optional<String> read(String resource) {
        InputStream in = ModelLoader.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            return Optional.empty();
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
            }
            return Optional.of(sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static double[] doubles(JsonArray array) {
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }
}
