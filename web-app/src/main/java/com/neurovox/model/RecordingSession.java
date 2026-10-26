package com.neurovox.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One guided sitting in which a person records the four voice tasks.
 * It becomes {@link Status#COMPLETE} once a recording exists for every {@link TaskType}.
 *
 * @param completedAt when the fourth task was recorded, or {@code null} while in progress
 */
public record RecordingSession(long id, long profileId, Instant startedAt, Instant completedAt, Status status) {

    public enum Status {
        IN_PROGRESS("in_progress"),
        COMPLETE("complete");

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
            throw new IllegalArgumentException("Unknown session status: " + code);
        }
    }

    public RecordingSession {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(status, "status");
        if ((status == Status.COMPLETE) != (completedAt != null)) {
            throw new IllegalArgumentException("completedAt must be set exactly when the session is complete");
        }
        if (completedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt cannot be before startedAt");
        }
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }
}
