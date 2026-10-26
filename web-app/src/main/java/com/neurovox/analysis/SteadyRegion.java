package com.neurovox.analysis;

import com.neurovox.dsp.PitchContour;

/**
 * The part of a sustained-vowel take used for the 16 voice measurements: the longest unbroken voiced stretch,
 * with up to {@link #MAX_TRIM_SECONDS} removed at each end (onset and offset are unstable; at most 15 % of the
 * stretch is removed per side). Praat's Voice Report is run on exactly the same time range in the validation study.
 */
public record SteadyRegion(double start, double end) {

    public static final double MAX_TRIM_SECONDS = 0.25;

    public double duration() {
        return end - start;
    }

    /** Null when the take has no voiced frames. */
    public static SteadyRegion find(PitchContour pitch) {
        PitchContour.Interval longest = null;
        for (PitchContour.Interval interval : pitch.voicedIntervals()) {
            if (longest == null || interval.duration() > longest.duration()) {
                longest = interval;
            }
        }
        if (longest == null) {
            return null;
        }
        double trim = Math.min(MAX_TRIM_SECONDS, 0.15 * longest.duration());
        return new SteadyRegion(longest.start() + trim, longest.end() - trim);
    }
}
