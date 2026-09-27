package com.neurovox.audio;

/**
 * Basic level figures for a decoded recording, returned to the browser after an upload so the user and
 * the tests can see that the saved audio is real sound. These are not acoustic features.
 *
 * @param peak    largest absolute sample value (1.0 = full scale)
 * @param rms     root-mean-square level: sqrt(mean(x²))
 * @param rmsDbfs rms in decibels relative to full scale: 20·log10(rms); negative infinity is reported as null
 */
public record PcmSummary(int sampleRateHz, int sampleCount, double durationSeconds, double peak, double rms, Double rmsDbfs) {

    public static PcmSummary of(float[] samples, int sampleRateHz) {
        double peak = 0;
        double sumSquares = 0;
        for (float s : samples) {
            double a = Math.abs(s);
            if (a > peak) {
                peak = a;
            }
            sumSquares += (double) s * s;
        }
        double rms = samples.length == 0 ? 0 : Math.sqrt(sumSquares / samples.length);
        Double dbfs = rms > 0 ? 20 * Math.log10(rms) : null;
        return new PcmSummary(sampleRateHz, samples.length, (double) samples.length / sampleRateHz, peak, rms, dbfs);
    }
}
