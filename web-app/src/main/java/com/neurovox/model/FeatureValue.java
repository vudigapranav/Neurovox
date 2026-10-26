package com.neurovox.model;

import java.util.Objects;

/**
 * One acoustic measurement. It is either available with a finite value, or explicitly
 * unavailable with a reason (for example "fewer than 3 valid vocal pulses"). A NaN or
 * infinite value is never stored in place of an honest "unavailable".
 *
 * @param unit for example {@code Hz}, {@code %}, {@code dB}, {@code s} or {@code ratio}
 */
public record FeatureValue(String name, Double value, String unit, String unavailableReason) {

    public FeatureValue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unit, "unit");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Feature name is blank");
        }
        if ((value == null) == (unavailableReason == null)) {
            throw new IllegalArgumentException(name + ": exactly one of value or unavailableReason must be set");
        }
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + ": value must be finite; mark it unavailable instead");
        }
        if (unavailableReason != null && unavailableReason.isBlank()) {
            throw new IllegalArgumentException(name + ": unavailable reason is blank");
        }
    }

    public static FeatureValue of(String name, double value, String unit) {
        return new FeatureValue(name, value, unit, null);
    }

    public static FeatureValue unavailable(String name, String unit, String reason) {
        return new FeatureValue(name, null, unit, reason);
    }

    public boolean isAvailable() {
        return value != null;
    }
}
