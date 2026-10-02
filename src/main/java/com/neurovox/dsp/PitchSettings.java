package com.neurovox.dsp;

/**
 * Pitch-analysis parameters. The defaults are Praat's defaults for "To Pitch (ac)" and the Voice Report
 * (Boersma &amp; Weenink, Praat manual: "Sound: To Pitch (ac)..."), so the Neurovox and Praat comparison
 * uses the same settings on both sides.
 *
 * @param floorHz             lowest F0 searched; also sets the window length (3 periods of the floor)
 * @param ceilingHz           highest F0 searched
 * @param timeStep            seconds between frame centres
 * @param voicingThreshold    autocorrelation strength below which a frame tends to be called unvoiced
 * @param silenceThreshold    frames quieter than this fraction of the loudest sample tend to be unvoiced
 * @param octaveCost          bonus per octave for higher candidates (prevents reporting a sub-harmonic)
 * @param octaveJumpCost      path cost per octave of frame-to-frame F0 change
 * @param voicedUnvoicedCost  path cost of switching between voiced and unvoiced
 */
public record PitchSettings(double floorHz, double ceilingHz, double timeStep, double voicingThreshold,
                            double silenceThreshold, double octaveCost, double octaveJumpCost, double voicedUnvoicedCost) {

    public static final PitchSettings DEFAULT = new PitchSettings(75, 600, 0.01, 0.45, 0.03, 0.01, 0.35, 0.14);

    /** Jitter/shimmer acceptance rules from Praat's Voice Report. */
    public static final double MAX_PERIOD_FACTOR = 1.3;
    public static final double MAX_AMPLITUDE_FACTOR = 1.6;

    public PitchSettings {
        if (!(floorHz > 0 && ceilingHz > floorHz)) {
            throw new IllegalArgumentException("Need 0 < floor < ceiling");
        }
        if (!(timeStep > 0)) {
            throw new IllegalArgumentException("timeStep must be positive");
        }
    }

    /** Shortest period accepted for jitter/shimmer: 0.8 / ceiling (Praat Voice Report). */
    public double shortestPeriod() {
        return 0.8 / ceilingHz;
    }

    /** Longest period accepted for jitter/shimmer: 1.25 / floor (Praat Voice Report). */
    public double longestPeriod() {
        return 1.25 / floorHz;
    }
}
