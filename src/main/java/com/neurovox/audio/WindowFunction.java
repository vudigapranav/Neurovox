package com.neurovox.audio;

/**
 * Tapering windows applied to a frame before spectral or correlation analysis, so the frame edges do not
 * create false high-frequency energy ("spectral leakage"). Symmetric form, n = 0 … N−1
 * (Harris, "On the use of windows for harmonic analysis with the DFT", Proc. IEEE 66(1), 1978):
 * <pre>
 *   Hamming: w[n] = 0.54 − 0.46 · cos(2πn / (N − 1))
 *   Hann:    w[n] = 0.5  − 0.5  · cos(2πn / (N − 1))
 * </pre>
 */
public enum WindowFunction {

    HAMMING(0.54, 0.46),
    HANN(0.5, 0.5);

    private final double a0;
    private final double a1;

    WindowFunction(double a0, double a1) {
        this.a0 = a0;
        this.a1 = a1;
    }

    /** The N coefficients. A one-point window is {1}. */
    public double[] coefficients(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Window length must be at least 1");
        }
        double[] w = new double[n];
        if (n == 1) {
            w[0] = 1;
            return w;
        }
        for (int i = 0; i < n; i++) {
            w[i] = a0 - a1 * Math.cos(2 * Math.PI * i / (n - 1));
        }
        return w;
    }

    /** Returns frame[i] · w[i] in a new array. */
    public double[] apply(double[] frame) {
        double[] w = coefficients(frame.length);
        double[] out = new double[frame.length];
        for (int i = 0; i < frame.length; i++) {
            out[i] = frame[i] * w[i];
        }
        return out;
    }
}
