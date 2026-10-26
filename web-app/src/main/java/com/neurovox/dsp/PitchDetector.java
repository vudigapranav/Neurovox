package com.neurovox.dsp;

import com.neurovox.audio.AudioClip;

/** Estimates the fundamental frequency (F0) frame by frame. Two implementations are compared in the pitch study. */
public interface PitchDetector {

    PitchContour detect(AudioClip clip);

    /** Short identifier used in reports, e.g. {@code autocorrelation} or {@code yin}. */
    String name();
}
