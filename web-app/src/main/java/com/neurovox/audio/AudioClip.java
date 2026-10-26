package com.neurovox.audio;

import java.util.Objects;

/**
 * Mono audio held in memory as double-precision samples (about ±1.0 full scale) plus its sample rate.
 *
 * <p>Every analysis step takes and returns an {@code AudioClip}, so the sample rate always travels with
 * the samples and can never be assumed. The array is not copied on construction for speed; callers must
 * not modify it afterwards.
 */
public record AudioClip(double[] samples, int sampleRate) {

    public AudioClip {
        Objects.requireNonNull(samples, "samples");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got " + sampleRate);
        }
    }

    /** Converts the browser's float samples to doubles. */
    public static AudioClip ofFloats(float[] samples, int sampleRate) {
        double[] out = new double[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = samples[i];
        }
        return new AudioClip(out, sampleRate);
    }

    public int length() {
        return samples.length;
    }

    /** Length in seconds: samples / sampleRate. */
    public double durationSeconds() {
        return (double) samples.length / sampleRate;
    }

    /** The part between two times in seconds (clamped to the clip). */
    public AudioClip slice(double startSeconds, double endSeconds) {
        int from = clampIndex((int) Math.round(startSeconds * sampleRate));
        int to = clampIndex((int) Math.round(endSeconds * sampleRate));
        if (to < from) {
            to = from;
        }
        double[] out = new double[to - from];
        System.arraycopy(samples, from, out, 0, out.length);
        return new AudioClip(out, sampleRate);
    }

    private int clampIndex(int i) {
        return Math.max(0, Math.min(samples.length, i));
    }
}
