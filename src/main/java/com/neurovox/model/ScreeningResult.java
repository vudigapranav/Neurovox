package com.neurovox.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Output of the experimental research classifier for one session.
 *
 * <p>It is computed only from that session's {@link TaskType#SUSTAINED_VOWEL} recording (the database
 * enforces this). {@code researchScore} is the logistic-regression output in [0, 1] of a model trained on
 * a small public dataset. It is <b>not</b> a diagnosis, a probability of having Parkinson's disease or a
 * "clinical confidence", and whether it is shown at all depends on the Sprint 7 and 9 validation decisions.
 */
public record ScreeningResult(long id, long sessionId, long sampleId, String modelVersion, double researchScore, Instant createdAt) {

    public ScreeningResult {
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        validateScore(researchScore);
        if (modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion is blank");
        }
    }

    public static void validateScore(double score) {
        if (!(score >= 0.0 && score <= 1.0)) {
            throw new IllegalArgumentException("researchScore must be within [0, 1], got " + score);
        }
    }
}
