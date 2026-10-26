package com.neurovox.model;

import java.util.Arrays;

/**
 * The four guided voice tasks. One recording session contains one recording of each.
 *
 * <p>Only {@link #SUSTAINED_VOWEL} may feed the Oxford/UCI research classifier; the other three
 * tasks contribute descriptive measurements only.
 */
public enum TaskType {

    SUSTAINED_VOWEL("sustained_vowel", 1, 5, 3, 15, "Sustained vowel", true),
    PA_TA_KA("pa_ta_ka", 2, 6, 4, 15, "Syllable repetition", false),
    READING_PASSAGE("reading_passage", 3, 20, 10, 45, "Reading passage", false),
    FREE_SPEECH("free_speech", 4, 30, 10, 60, "Free speech", false);

    /** Shortest upload accepted for any task; anything shorter cannot be a real attempt. */
    public static final double MIN_UPLOAD_SECONDS = 0.5;

    private final String code;
    private final int order;
    private final int targetSeconds;
    private final int minSeconds;
    private final int maxUploadSeconds;
    private final String label;
    private final boolean feedsResearchClassifier;

    TaskType(String code, int order, int targetSeconds, int minSeconds, int maxUploadSeconds, String label,
             boolean feedsResearchClassifier) {
        this.code = code;
        this.order = order;
        this.targetSeconds = targetSeconds;
        this.minSeconds = minSeconds;
        this.maxUploadSeconds = maxUploadSeconds;
        this.label = label;
        this.feedsResearchClassifier = feedsResearchClassifier;
    }

    /** Stable identifier used in the database, file names and the HTTP API. */
    public String code() {
        return code;
    }

    /** Position in the guided session, 1 to 4. */
    public int order() {
        return order;
    }

    /** Intended recording length in seconds (approximate). */
    public int targetSeconds() {
        return targetSeconds;
    }

    /**
     * Shortest take the quality gate accepts for analysis. A take may be stopped early, but below this length
     * the measurements would rest on too little voice.
     */
    public int minSeconds() {
        return minSeconds;
    }

    /** Longest recording the server accepts for this task (a size limit, not a quality judgement). */
    public int maxUploadSeconds() {
        return maxUploadSeconds;
    }

    public String label() {
        return label;
    }

    public boolean feedsResearchClassifier() {
        return feedsResearchClassifier;
    }

    public static TaskType fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown task type: " + code));
    }
}
