package com.neurovox.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Decodes the browser's upload format: mono 32-bit IEEE float samples, little-endian.
 *
 * <p>Byte order matters. JavaScript's {@code Float32Array} uses the machine's order, which is
 * little-endian on every common platform, while Java's {@link ByteBuffer} defaults to big-endian.
 * Reading with the wrong order does not fail loudly; it produces garbage values (often tiny numbers
 * that sound like silence), so the order is set explicitly here.
 *
 * <p>Structural checks only: a whole number of samples, every value finite, and every value within
 * {@code ±MAX_ABS_SAMPLE}. Web Audio delivers samples in about [-1, 1]; a value far outside that means
 * the data is not float PCM. Silence, clipping and duration are judged later by the quality gate.
 */
public final class PcmDecoder {

    public static final int BYTES_PER_SAMPLE = 4;

    /** Generous bound: real microphone floats sit in [-1, 1]; values beyond this are not audio. */
    public static final float MAX_ABS_SAMPLE = 4.0f;

    private PcmDecoder() {
    }

    public static float[] decodeF32le(byte[] data) {
        if (data == null || data.length == 0) {
            throw new InvalidAudioException("The recording is empty.");
        }
        if (data.length % BYTES_PER_SAMPLE != 0) {
            throw new InvalidAudioException("The recording is " + data.length
                    + " bytes, which is not a whole number of 32-bit samples.");
        }
        FloatBuffer floats = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] samples = new float[floats.remaining()];
        floats.get(samples);
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i];
            if (!Float.isFinite(s)) {
                throw new InvalidAudioException("Sample " + i + " is not a finite number, so the recording is corrupted.");
            }
            if (Math.abs(s) > MAX_ABS_SAMPLE) {
                throw new InvalidAudioException("Sample " + i + " has value " + s
                        + ", which is outside the range of float audio. Was the data sent as 32-bit float, little-endian?");
            }
        }
        return samples;
    }

    /** Encodes samples in the same format (used by tests and, later, by WAV export helpers). */
    public static byte[] encodeF32le(float[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(samples);
        return buffer.array();
    }
}
