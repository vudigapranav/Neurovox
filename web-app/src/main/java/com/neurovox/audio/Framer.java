package com.neurovox.audio;

/**
 * Cuts a signal into overlapping frames. Only complete frames are produced, so the count is
 * <pre>  frames = 1 + floor((N − frameSize) / hop)   (0 when N &lt; frameSize)</pre>
 * Example: 16000 samples, frame 512, hop 256 → 1 + floor(15488 / 256) = 61 frames.
 * (The planning document said 62; that would require padding the last partial frame, which Neurovox does
 * not do, because a zero-padded frame would add artificial silence to the measurements.)
 */
public final class Framer {

    /** 32 ms at 16 kHz. */
    public static final int DEFAULT_FRAME = 512;
    /** 50 % overlap. */
    public static final int DEFAULT_HOP = 256;

    private Framer() {
    }

    public static int frameCount(int length, int frameSize, int hop) {
        if (frameSize < 1 || hop < 1) {
            throw new IllegalArgumentException("frameSize and hop must be positive");
        }
        return length < frameSize ? 0 : 1 + (length - frameSize) / hop;
    }

    /** frames[i] = x[i·hop … i·hop + frameSize). */
    public static double[][] frames(double[] x, int frameSize, int hop) {
        int count = frameCount(x.length, frameSize, hop);
        double[][] frames = new double[count][frameSize];
        for (int i = 0; i < count; i++) {
            System.arraycopy(x, i * hop, frames[i], 0, frameSize);
        }
        return frames;
    }
}
