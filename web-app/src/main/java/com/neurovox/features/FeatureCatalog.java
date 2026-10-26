package com.neurovox.features;

import java.util.Map;
import java.util.Optional;

/**
 * Plain-language names and one-sentence explanations for every stored measurement, so the results page can say
 * "Cycle timing variation" with the technical name alongside. One place, used by the API.
 */
public final class FeatureCatalog {

    /** How a measurement is described to the person. {@code group} decides where the results page shows it. */
    public record Description(String group, String plainName, String explanation) {
    }

    private static final Map<String, Description> DESCRIPTIVE = Map.ofEntries(
            Map.entry("vowel.analysed_seconds", new Description("vowel_extra", "Steady part analysed",
                    "Length of the steady middle of the vowel that the 16 measurements were taken from.")),
            Map.entry("vowel.f0_sd", new Description("vowel_extra", "Pitch steadiness (SD)",
                    "Standard deviation of the pitch during the steady vowel.")),
            Map.entry("vowel.pulses", new Description("vowel_extra", "Vocal cycles found",
                    "Number of individual vocal-fold cycles located in the steady part.")),
            Map.entry("speech.duration", new Description("speech", "Recording length", "Length of the take.")),
            Map.entry("speech.voiced_fraction", new Description("speech", "Voiced share",
                    "Share of the take in which the vocal folds were vibrating (a pitch was found).")),
            Map.entry("speech.f0_mean", new Description("speech", "Average pitch", "Average pitch while speaking.")),
            Map.entry("speech.f0_sd", new Description("speech", "Pitch variability",
                    "How much the pitch moved up and down, in semitones (12 semitones = one octave).")),
            Map.entry("speech.f0_range", new Description("speech", "Pitch range",
                    "Distance between the low (5th percentile) and high (95th percentile) pitch, in semitones.")),
            Map.entry("speech.level_sd", new Description("speech", "Loudness variability",
                    "How much the loudness varied while speaking, in decibels.")),
            Map.entry("speech.pause_count", new Description("speech", "Pauses",
                    "Number of silences of at least 0.25 s between the first and last speech sound.")),
            Map.entry("speech.pause_fraction", new Description("speech", "Time in pauses",
                    "Share of the speaking span spent in those pauses.")),
            Map.entry("ddk.syllable_rate", new Description("speech", "Syllable rate (estimated)",
                    "Estimated syllables per second, counted from loudness peaks. A rough description, not a validated count.")));

    private FeatureCatalog() {
    }

    public static Optional<Description> describe(String name) {
        for (UciFeature f : UciFeature.values()) {
            if (f.key().equals(name)) {
                return Optional.of(new Description("uci", f.plainName(), f.explanation()));
            }
        }
        if (name.startsWith("mfcc.c")) {
            String[] parts = name.split("\\.");
            String stat = parts.length > 2 && parts[2].equals("sd") ? "variation" : "average";
            return Optional.of(new Description("mfcc", "Spectral shape " + parts[1].substring(1) + " (" + stat + ")",
                    "Mel-frequency cepstral coefficient " + parts[1].substring(1)
                            + ": one number describing the overall shape of the voice spectrum."));
        }
        return Optional.ofNullable(DESCRIPTIVE.get(name));
    }
}
