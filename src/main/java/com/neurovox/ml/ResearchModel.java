package com.neurovox.ml;

import com.neurovox.features.UciFeature;

import java.util.List;
import java.util.Objects;

/**
 * The offline-trained research logistic-regression model, exactly as exported by
 * {@code training/train_model.py}: feature order, standardisation, coefficients and intercept.
 *
 * <pre>
 *   z_i = (x_i − mean_i) / scale_i                   standardise each feature (scikit-learn StandardScaler)
 *   s   = intercept + Σ_i coefficient_i · z_i         linear score ("log-odds")
 *   p   = 1 / (1 + e^(−s))                            logistic function
 * </pre>
 * {@code p} is the model's output on a 0–1 scale. It is a research score from a model trained on 31 people's
 * recordings; it is not a diagnosis, not a probability of having a disease, and never "clinical confidence".
 *
 * @param featureOrder must equal the 16 UCI keys in {@link UciFeature#ORDER}; checked on load
 */
public record ResearchModel(String modelVersion, List<String> featureOrder, double[] mean, double[] scale,
                            double[] coefficients, double intercept, double threshold) {

    public ResearchModel {
        Objects.requireNonNull(modelVersion, "modelVersion");
        featureOrder = List.copyOf(featureOrder);
        int n = featureOrder.size();
        if (mean.length != n || scale.length != n || coefficients.length != n) {
            throw new IllegalArgumentException("Model arrays do not match the " + n + " features");
        }
        if (!featureOrder.equals(UciFeature.keys())) {
            throw new IllegalArgumentException("Model feature order differs from Neurovox's fixed UCI order: " + featureOrder);
        }
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(mean[i]) || !Double.isFinite(coefficients[i]) || !(scale[i] > 0) || !Double.isFinite(scale[i])) {
                throw new IllegalArgumentException("Invalid model parameter for " + featureOrder.get(i));
            }
        }
        if (!Double.isFinite(intercept) || !(threshold > 0 && threshold < 1)) {
            throw new IllegalArgumentException("Invalid intercept or threshold");
        }
    }

    /** The linear score s = intercept + Σ coefficient_i · (x_i − mean_i) / scale_i. */
    public double linearScore(double[] x) {
        if (x.length != coefficients.length) {
            throw new IllegalArgumentException("Expected " + coefficients.length + " features, got " + x.length);
        }
        double s = intercept;
        for (int i = 0; i < x.length; i++) {
            if (!Double.isFinite(x[i])) {
                throw new IllegalArgumentException(featureOrder.get(i) + " is missing; the model cannot be applied.");
            }
            s += coefficients[i] * (x[i] - mean[i]) / scale[i];
        }
        return s;
    }

    /** The model output p = 1 / (1 + e^(−s)), computed without overflow for large |s|. */
    public double score(double[] x) {
        return sigmoid(linearScore(x));
    }

    static double sigmoid(double s) {
        if (s >= 0) {
            return 1 / (1 + Math.exp(-s));
        }
        double e = Math.exp(s);
        return e / (1 + e);
    }
}
