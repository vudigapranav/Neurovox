package com.neurovox.analysis;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of the audio-quality gate for one take.
 *
 * <p>{@code FAIL} means the take cannot be analysed and is not saved; the first failing check's message and
 * recovery tell the person exactly what to change. {@code WARN} means it is saved and analysed, with a note.
 */
public record QualityReport(Status status, List<Check> checks) {

    public enum Status {
        PASS("pass"), WARN("warn"), FAIL("fail");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Status fromCode(String code) {
            for (Status s : values()) {
                if (s.code.equals(code)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("Unknown quality status: " + code);
        }
    }

    /**
     * One check.
     *
     * @param code     stable id, e.g. {@code too_quiet}
     * @param value    the measured number behind the decision (for display and tests), or NaN
     * @param message  what was found, in plain language
     * @param recovery what to do about it (empty when the check passed)
     */
    public record Check(String code, Status status, double value, String message, String recovery) {
    }

    public QualityReport {
        checks = List.copyOf(checks);
    }

    public static QualityReport of(List<Check> checks) {
        Status worst = Status.PASS;
        for (Check c : checks) {
            if (c.status().ordinal() > worst.ordinal()) {
                worst = c.status();
            }
        }
        return new QualityReport(worst, checks);
    }

    public Optional<Check> firstFailure() {
        return checks.stream().filter(c -> c.status() == Status.FAIL).findFirst();
    }

    public List<Check> problems() {
        return checks.stream().filter(c -> c.status() != Status.PASS).toList();
    }
}
