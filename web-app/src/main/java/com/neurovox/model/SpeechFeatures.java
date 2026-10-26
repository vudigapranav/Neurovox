package com.neurovox.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The measurements extracted from one {@link VoiceSample} by a specific version of the extractor.
 * The list order is preserved exactly as given (the feature order is defined centrally in Sprint 6).
 *
 * @param extractorVersion identifies the code that produced the values, so results from older code
 *                         are never silently mixed with newer ones
 */
public record SpeechFeatures(long sampleId, String extractorVersion, Instant computedAt, List<FeatureValue> values) {

    public SpeechFeatures {
        Objects.requireNonNull(extractorVersion, "extractorVersion");
        Objects.requireNonNull(computedAt, "computedAt");
        values = List.copyOf(values);
        if (extractorVersion.isBlank()) {
            throw new IllegalArgumentException("extractorVersion is blank");
        }
        Set<String> seen = new HashSet<>();
        for (FeatureValue v : values) {
            if (!seen.add(v.name())) {
                throw new IllegalArgumentException("Duplicate feature: " + v.name());
            }
        }
    }

    public Optional<FeatureValue> get(String name) {
        return values.stream().filter(v -> v.name().equals(name)).findFirst();
    }
}
