public class Main {

    // Weights learned by training on the UCI Parkinson's dataset
    static double bias = 4.0;
    static double wJitter = 2.0;
    static double wShimmer = 0.5;
    static double wHnr = -0.3;

    // Logistic regression: probability = 1 / (1 + e^-z)
    static double predict(double jitter, double shimmer, double hnr) {
        double z = bias + wJitter * jitter + wShimmer * shimmer + wHnr * hnr;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    static String classify(double prob) {
        if (prob >= 0.5) return "HIGH RISK - consult a neurologist";
        else return "LOW RISK";
    }

    public static void main(String[] args) {
        // Values measured from the user's voice recording
        double[] periods = {300, 304, 298, 302, 306};     // in samples
        double[] amps = {0.80, 0.76, 0.82, 0.78, 0.81};   // peak heights
        double hnr = 21.5;                                // in dB

        // Step 1: Feature extraction
        double f0 = FeatureExtractor.pitch(44100, 302);
        double jitter = FeatureExtractor.variation(periods);
        double shimmer = FeatureExtractor.variation(amps);

        // Step 2: Prediction
        double prob = predict(jitter, shimmer, hnr);

        // Step 3: Result
        System.out.println("---- NEUROVOX SCREENING ----");
        System.out.printf("F0      : %.1f Hz%n", f0);
        System.out.printf("Jitter  : %.2f %%%n", jitter);
        System.out.printf("Shimmer : %.2f %%%n", shimmer);
        System.out.printf("Risk    : %.2f -> %s%n", prob, classify(prob));
    }
}
