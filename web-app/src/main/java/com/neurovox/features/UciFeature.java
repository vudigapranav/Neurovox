package com.neurovox.features;

import java.util.Arrays;
import java.util.List;

/**
 * The 16 Oxford/UCI Parkinson's dataset columns that Neurovox can compute, in the fixed order used everywhere:
 * the database, the API, the exported model and the Python training script.
 *
 * <p><b>Scale.</b> Values follow the dataset's own numeric convention. In particular {@code MDVP:Jitter(%)},
 * {@code MDVP:RAP}, {@code MDVP:Shimmer} and the other perturbation quotients are stored as <em>fractions</em>
 * (0.0062 means 0.62 %), because that is how the dataset stores them despite the "(%)" in one column name
 * (healthy recordings have MDVP:Jitter(%) ≈ 0.003–0.006, which only makes sense as a fraction).
 *
 * <p><b>Not computed:</b> the six nonlinear columns RPDE, D2, DFA, spread1, spread2 and PPE. Neurovox never
 * fills them in, estimates them or substitutes another value; the research model is trained without them.
 *
 * <p><b>Definitions</b> follow Praat's Voice Report (Boersma &amp; Weenink), which the validation study compares
 * against. The dataset's columns carry MDVP (Kay Pentax) names; whether our values are on the same scale as those
 * columns cannot be checked from the dataset alone (see docs/VALIDATION.md).
 */
public enum UciFeature {

    FO_MEAN("MDVP:Fo(Hz)", "Hz", "Average pitch", "Average vocal pitch (fundamental frequency) during the steady vowel."),
    FHI("MDVP:Fhi(Hz)", "Hz", "Highest pitch", "Highest pitch measured in the steady part of the vowel."),
    FLO("MDVP:Flo(Hz)", "Hz", "Lowest pitch", "Lowest pitch measured in the steady part of the vowel."),
    JITTER_LOCAL("MDVP:Jitter(%)", "fraction", "Cycle timing variation",
            "Average difference in length between neighbouring vocal cycles, relative to the average cycle length."),
    JITTER_ABS("MDVP:Jitter(Abs)", "s", "Cycle timing variation (absolute)",
            "Average difference in length between neighbouring vocal cycles, in seconds."),
    RAP("MDVP:RAP", "fraction", "Cycle timing variation (3-cycle)",
            "How much each cycle's length differs from the average of itself and its two neighbours."),
    PPQ5("MDVP:PPQ", "fraction", "Cycle timing variation (5-cycle)",
            "How much each cycle's length differs from the average of the five cycles around it."),
    DDP("Jitter:DDP", "fraction", "Cycle timing variation (difference of differences)",
            "Average change between consecutive cycle-length differences; by definition three times the 3-cycle value."),
    SHIMMER_LOCAL("MDVP:Shimmer", "fraction", "Cycle loudness variation",
            "Average difference in amplitude between neighbouring vocal cycles, relative to the average amplitude."),
    SHIMMER_DB("MDVP:Shimmer(dB)", "dB", "Cycle loudness variation (dB)",
            "Average amplitude ratio between neighbouring cycles, expressed in decibels."),
    APQ3("Shimmer:APQ3", "fraction", "Cycle loudness variation (3-cycle)",
            "How much each cycle's amplitude differs from the average of itself and its two neighbours."),
    APQ5("Shimmer:APQ5", "fraction", "Cycle loudness variation (5-cycle)",
            "How much each cycle's amplitude differs from the average of the five cycles around it."),
    APQ11("MDVP:APQ", "fraction", "Cycle loudness variation (11-cycle)",
            "How much each cycle's amplitude differs from the average of the eleven cycles around it."),
    DDA("Shimmer:DDA", "fraction", "Cycle loudness variation (difference of differences)",
            "Average change between consecutive amplitude differences; by definition three times the 3-cycle value."),
    NHR("NHR", "ratio", "Noise-to-harmonics ratio",
            "Share of non-periodic (noise) energy relative to periodic (voice) energy; lower means a clearer tone."),
    HNR("HNR", "dB", "Harmonics-to-noise ratio",
            "Periodic (voice) energy relative to noise energy, in decibels; higher means a clearer tone.");

    /** Fixed order of the 16 computed columns. */
    public static final List<UciFeature> ORDER = List.of(values());

    /** Columns of the dataset Neurovox deliberately does not compute. */
    public static final List<String> NOT_COMPUTED = List.of("RPDE", "D2", "DFA", "spread1", "spread2", "PPE");

    private final String key;
    private final String unit;
    private final String plainName;
    private final String explanation;

    UciFeature(String key, String unit, String plainName, String explanation) {
        this.key = key;
        this.unit = unit;
        this.plainName = plainName;
        this.explanation = explanation;
    }

    /** Exact dataset column name, e.g. {@code MDVP:Jitter(%)}. */
    public String key() {
        return key;
    }

    public String unit() {
        return unit;
    }

    public String plainName() {
        return plainName;
    }

    public String explanation() {
        return explanation;
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(UciFeature::key).toList();
    }

    public static UciFeature fromKey(String key) {
        for (UciFeature f : values()) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown UCI feature: " + key);
    }
}
