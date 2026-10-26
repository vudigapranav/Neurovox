package com.neurovox.ml;

import com.neurovox.features.UciFeature;
import com.neurovox.model.FeatureValue;
import com.neurovox.model.SpeechFeatures;

import java.util.Optional;

/**
 * Decides whether the experimental research indicator may be shown for a session, and computes it if so.
 *
 * <p>Order of checks: (1) the gate must say go; (2) a model must be packaged; (3) all 16 sustained-vowel features
 * must be available. Only the sustained vowel is ever used. The result is a binary "above / below the model's
 * threshold" indicator plus the raw model output, both labelled as research output.
 */
public final class ResearchIndicator {

    /** Outcome for one session. {@code score} and {@code aboveThreshold} are set only when {@code shown}. */
    public record Outcome(boolean shown, String reason, Double score, Boolean aboveThreshold, String modelVersion) {

        static Outcome notShown(String reason, String modelVersion) {
            return new Outcome(false, reason, null, null, modelVersion);
        }
    }

    private final CompatibilityGate gate;
    private final Optional<ResearchModel> model;

    public ResearchIndicator(CompatibilityGate gate, Optional<ResearchModel> model) {
        this.gate = gate;
        this.model = model;
    }

    public static ResearchIndicator fromClasspath() {
        return new ResearchIndicator(ModelLoader.loadGate(), ModelLoader.loadModel());
    }

    public CompatibilityGate gate() {
        return gate;
    }

    public Optional<ResearchModel> model() {
        return model;
    }

    public Outcome evaluate(SpeechFeatures vowelFeatures) {
        String version = model.map(ResearchModel::modelVersion).orElse(null);
        if (!gate.allowsLiveIndicator()) {
            return Outcome.notShown("The feature-compatibility check did not support live use: " + gate.summary(), version);
        }
        if (model.isEmpty()) {
            return Outcome.notShown("No research model is packaged with this build.", null);
        }
        double[] x = new double[UciFeature.ORDER.size()];
        for (int i = 0; i < x.length; i++) {
            UciFeature f = UciFeature.ORDER.get(i);
            Optional<FeatureValue> v = vowelFeatures.get(f.key());
            if (v.isEmpty() || !v.get().isAvailable()) {
                return Outcome.notShown(f.plainName() + " (" + f.key() + ") could not be measured, so the model cannot be applied.", version);
            }
            x[i] = v.get().value();
        }
        ResearchModel m = model.get();
        double score = m.score(x);
        return new Outcome(true, "", score, score >= m.threshold(), m.modelVersion());
    }
}
