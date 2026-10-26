package com.neurovox.dsp;

/**
 * Where analysis frames sit in a signal. Frames of {@code windowSamples} are spaced {@code timeStep} apart and
 * centred in the signal as a group (Praat's "short-term analysis" convention), so the same number of seconds
 * is left unused at both ends:
 * <pre>
 *   count = floor((duration − windowDuration) / timeStep) + 1
 *   t₀    = duration / 2 − (count − 1) · timeStep / 2         (centre of the first frame)
 * </pre>
 */
record FrameLayout(int count, double firstCentre, double timeStep, int windowSamples, int sampleRate, int signalLength) {

    static FrameLayout of(int signalLength, int sampleRate, int windowSamples, double timeStep) {
        double duration = (double) signalLength / sampleRate;
        double windowDuration = (double) windowSamples / sampleRate;
        if (signalLength < windowSamples + 1) {
            return new FrameLayout(0, 0, timeStep, windowSamples, sampleRate, signalLength);
        }
        int count = (int) Math.floor((duration - windowDuration) / timeStep + 1e-9) + 1;
        double first = duration / 2 - (count - 1) * timeStep / 2;
        return new FrameLayout(count, first, timeStep, windowSamples, sampleRate, signalLength);
    }

    double centre(int frame) {
        return firstCentre + frame * timeStep;
    }

    /** First sample of the frame's window, kept inside the signal. */
    int startSample(int frame) {
        int start = (int) Math.round(centre(frame) * sampleRate) - windowSamples / 2;
        return Math.max(0, Math.min(signalLength - windowSamples, start));
    }

    double[] times() {
        double[] t = new double[count];
        for (int i = 0; i < count; i++) {
            t[i] = centre(i);
        }
        return t;
    }
}
