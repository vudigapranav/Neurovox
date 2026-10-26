package com.neurovox.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata for one stored recording. The audio itself lives in a file on this computer;
 * {@code audioPath} is relative to the Neurovox data folder (for example
 * {@code recordings/session-3/sustained_vowel-20260926T101500123Z-a1b2c3.f32le}).
 *
 * @param sampleRateHz the browser's real capture rate (commonly 48000 or 44100), not a resampled rate
 * @param sampleCount  number of mono samples in the file
 * @param byteSize     file size in bytes, always {@code sampleCount * format.bytesPerSample()}
 * @param sha256       hex SHA-256 of the file, to detect corruption or tampering
 */
public record VoiceSample(long id, long sessionId, TaskType taskType, String audioPath, AudioFormat format,
                          int sampleRateHz, long sampleCount, long byteSize, String sha256, Instant recordedAt) {

    public static final int MIN_SAMPLE_RATE = 8_000;
    public static final int MAX_SAMPLE_RATE = 192_000;

    /** How samples are encoded in the file. */
    public enum AudioFormat {
        /** Mono 32-bit IEEE float, little-endian: exactly what the browser's Float32Array holds. */
        PCM_F32LE("pcm_f32le", 4, "f32le");

        private final String code;
        private final int bytesPerSample;
        private final String extension;

        AudioFormat(String code, int bytesPerSample, String extension) {
            this.code = code;
            this.bytesPerSample = bytesPerSample;
            this.extension = extension;
        }

        public String code() {
            return code;
        }

        public int bytesPerSample() {
            return bytesPerSample;
        }

        public String extension() {
            return extension;
        }

        public static AudioFormat fromCode(String code) {
            for (AudioFormat f : values()) {
                if (f.code.equals(code)) {
                    return f;
                }
            }
            throw new IllegalArgumentException("Unknown audio format: " + code);
        }
    }

    public VoiceSample {
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(audioPath, "audioPath");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(recordedAt, "recordedAt");
        validateSampleRate(sampleRateHz);
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive");
        }
        if (byteSize != sampleCount * format.bytesPerSample()) {
            throw new IllegalArgumentException("byteSize does not match sampleCount for " + format.code());
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
    }

    /** Recording length in seconds: sampleCount / sampleRateHz. */
    public double durationSeconds() {
        return (double) sampleCount / sampleRateHz;
    }

    public static void validateSampleRate(int sampleRateHz) {
        if (sampleRateHz < MIN_SAMPLE_RATE || sampleRateHz > MAX_SAMPLE_RATE) {
            throw new IllegalArgumentException("Sample rate must be between " + MIN_SAMPLE_RATE + " and " + MAX_SAMPLE_RATE
                    + " Hz, got " + sampleRateHz);
        }
    }
}
