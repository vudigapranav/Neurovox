package com.neurovox.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A person using Neurovox on this computer, identified only by a label they choose
 * (for example "Pranav" or "Demo"). No name, age, sex or contact details are collected,
 * because nothing in Neurovox uses them.
 */
public record LocalProfile(long id, String displayName, Instant createdAt) {

    public static final int MAX_NAME_LENGTH = 60;

    public LocalProfile {
        displayName = validateDisplayName(displayName);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Trims the label and checks it is 1–60 characters with no control characters. */
    public static String validateDisplayName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("A profile label is required.");
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A profile label must be 1 to " + MAX_NAME_LENGTH + " characters.");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A profile label cannot contain control characters.");
        }
        return trimmed;
    }
}
