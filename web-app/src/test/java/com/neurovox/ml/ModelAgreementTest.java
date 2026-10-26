package com.neurovox.ml;

import com.neurovox.features.UciFeature;
import com.neurovox.model.FeatureValue;
import com.neurovox.model.SpeechFeatures;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint G (planning Sprint 8): the Java model gives the same output as scikit-learn on known dataset rows. */
class ModelAgreementTest {

    private record Row(String name, double[] features, double sklearn) {
    }

    private static List<Row> referenceRows() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                ModelAgreementTest.class.getClassLoader().getResourceAsStream("model/reference-predictions.csv")), StandardCharsets.UTF_8))) {
            String[] header = in.readLine().split(",");
            assertEquals(UciFeature.keys(), Arrays.asList(header).subList(1, 17), "CSV columns are in the fixed UCI order");
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.split(",");
                double[] x = new double[16];
                for (int i = 0; i < 16; i++) {
                    x[i] = Double.parseDouble(p[i + 1]);
                }
                rows.add(new Row(p[0], x, Double.parseDouble(p[17])));
            }
        }
        return rows;
    }

    @Test
    void javaMatchesScikitLearnOnDatasetRows() throws IOException {
        ResearchModel model = ModelLoader.loadModel().orElseThrow();
        List<Row> rows = referenceRows();
        assertTrue(rows.size() >= 20);
        double worst = 0;
        for (Row r : rows) {
            double java = model.score(r.features());
            worst = Math.max(worst, Math.abs(java - r.sklearn()));
            assertEquals(r.sklearn(), java, 1e-9, r.name());
        }
        assertTrue(worst < 1e-9, "worst difference " + worst);
    }

    @Test
    void packagedModelHasTheFixedFeatureOrderAndAValidThreshold() {
        ResearchModel model = ModelLoader.loadModel().orElseThrow();
        assertEquals(UciFeature.keys(), model.featureOrder());
        assertEquals(0.5, model.threshold());
        assertFalse(model.modelVersion().isBlank());
    }

    @Test
    void featureOrderMismatchIsRejected() {
        List<String> swapped = new ArrayList<>(UciFeature.keys());
        java.util.Collections.swap(swapped, 0, 1);
        double[] ones = new double[16];
        Arrays.fill(ones, 1);
        assertThrows(IllegalArgumentException.class, () -> new ResearchModel("x", swapped, ones, ones, ones, 0, 0.5));
        double[] zeroScale = new double[16];
        assertThrows(IllegalArgumentException.class, () -> new ResearchModel("x", UciFeature.keys(), ones, zeroScale, ones, 0, 0.5));
    }

    @Test
    void sigmoidIsStableForLargeScores() {
        assertEquals(1.0, ResearchModel.sigmoid(800), 0);
        assertEquals(0.0, ResearchModel.sigmoid(-800), 1e-300);
        assertEquals(0.5, ResearchModel.sigmoid(0), 0);
    }

    @Test
    void packagedGateIsNoGoAndTheIndicatorIsWithheld() throws IOException {
        CompatibilityGate gate = ModelLoader.loadGate();
        assertEquals(CompatibilityGate.Decision.NO_GO, gate.decision());
        assertFalse(gate.reasons().isEmpty());
        ResearchIndicator indicator = new ResearchIndicator(gate, ModelLoader.loadModel());
        Row r = referenceRows().get(0);
        ResearchIndicator.Outcome o = indicator.evaluate(features(r.features()));
        assertFalse(o.shown());
        assertEquals(null, o.score());
    }

    @Test
    void indicatorNeedsAll16Features() {
        CompatibilityGate go = new CompatibilityGate(CompatibilityGate.Decision.GO, "t", "t", List.of("t"), "");
        ResearchIndicator indicator = new ResearchIndicator(go, ModelLoader.loadModel());
        double[] x = new double[16];
        Arrays.fill(x, 0.01);
        List<FeatureValue> values = new ArrayList<>(features(x).values());
        values.set(4, FeatureValue.unavailable(UciFeature.JITTER_ABS.key(), "s", "Too few cycles."));
        ResearchIndicator.Outcome o = indicator.evaluate(new SpeechFeatures(1, "v", Instant.EPOCH, values));
        assertFalse(o.shown());
        assertTrue(o.reason().contains("MDVP:Jitter(Abs)"));
        assertFalse(new ResearchIndicator(go, Optional.empty()).evaluate(features(x)).shown());
    }

    private static SpeechFeatures features(double[] x) {
        List<FeatureValue> values = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            UciFeature f = UciFeature.ORDER.get(i);
            values.add(FeatureValue.of(f.key(), x[i], f.unit()));
        }
        return new SpeechFeatures(1, "v", Instant.EPOCH, values);
    }
}
