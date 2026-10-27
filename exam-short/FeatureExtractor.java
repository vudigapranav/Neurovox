public class FeatureExtractor {

    // Find pitch period (in samples) using autocorrelation
    public static int findPeriod(double[] frame, int minLag, int maxLag) {
        int bestLag = minLag;
        double bestCorr = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < frame.length; i++) {
                sum += frame[i] * frame[i + lag];
            }
            if (sum > bestCorr) {
                bestCorr = sum;
                bestLag = lag;
            }
        }
        return bestLag;
    }

    // Fundamental frequency F0 = sampleRate / period
    public static double pitch(int sampleRate, int period) {
        return (double) sampleRate / period;
    }

    // Variation % = avg change between neighbours / average value * 100
    // Used for Jitter (on periods) and Shimmer (on amplitudes)
    public static double variation(double[] x) {
        double diff = 0, total = 0;
        for (int i = 1; i < x.length; i++) {
            diff += Math.abs(x[i] - x[i - 1]);
        }
        for (double v : x) {
            total += v;
        }
        double avgDiff = diff / (x.length - 1);
        double avg = total / x.length;
        return (avgDiff / avg) * 100;
    }
}
