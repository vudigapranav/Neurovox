package com.neurovox.ml;

import java.util.List;

/**
 * The recorded scientific go/no-go decision on whether Neurovox's live measurements are compatible enough with the
 * training dataset's columns for the research model to be applied to a live recording.
 *
 * <p>The decision is made by people, from the validation evidence in docs/VALIDATION.md, and written to
 * {@code model/research-gate.json}. Code never turns a no-go into a go. Without the file, the answer is no-go.
 */
public record CompatibilityGate(Decision decision, String decidedOn, String summary, List<String> reasons, String evidence) {

    public enum Decision {
        GO("go"), NO_GO("no_go");

        private final String code;

        Decision(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Decision fromCode(String code) {
            for (Decision d : values()) {
                if (d.code.equals(code)) {
                    return d;
                }
            }
            throw new IllegalArgumentException("Unknown gate decision: " + code);
        }
    }

    public CompatibilityGate {
        reasons = List.copyOf(reasons);
    }

    public static CompatibilityGate missing() {
        return new CompatibilityGate(Decision.NO_GO, "", "No validation decision has been recorded.",
                List.of("The feature-compatibility gate file is missing, so there is no evidence that live measurements "
                        + "match the training data."), "");
    }

    public boolean allowsLiveIndicator() {
        return decision == Decision.GO;
    }
}
