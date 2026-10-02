package com.neurovox.analysis;

import com.neurovox.analysis.QualityReport.Check;
import com.neurovox.analysis.QualityReport.Status;
import com.neurovox.audio.AudioClip;
import com.neurovox.audio.Resampler;
import com.neurovox.dsp.PitchContour;
import com.neurovox.model.TaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a take is usable before it is saved, and if not, tells the person how to fix it.
 *
 * <p>The thresholds are engineering choices for a laptop microphone in a quiet room. They are not clinical
 * criteria and say nothing about the voice itself; they only protect the measurements from bad audio.
 * <table>
 *   <caption>Checks</caption>
 *   <tr><th>Check</th><th>Fail</th><th>Warn</th></tr>
 *   <tr><td>sample rate</td><td>&lt; 16 kHz</td><td></td></tr>
 *   <tr><td>duration</td><td>&lt; task minimum</td><td></td></tr>
 *   <tr><td>loudness (95th-percentile 20 ms level)</td><td>&lt; −50 dBFS</td><td>&lt; −42 dBFS</td></tr>
 *   <tr><td>clipping (|x| ≥ 0.999)</td><td>&gt; 1 % of samples</td><td>&gt; 0.1 %</td></tr>
 *   <tr><td>voice present (voiced frames)</td><td>&lt; 10 % (vowel: &lt; 50 %)</td><td></td></tr>
 *   <tr><td>steady vowel stretch</td><td>&lt; 1.0 s</td><td></td></tr>
 *   <tr><td>background noise (speech tasks: loud − quiet level)</td><td></td><td>&lt; 12 dB</td></tr>
 * </table>
 */
public final class QualityGate {

    public static final double MIN_SAMPLE_RATE = Resampler.ANALYSIS_RATE;
    public static final double QUIET_FAIL_DBFS = -50;
    public static final double QUIET_WARN_DBFS = -42;
    public static final double CLIP_LEVEL = 0.999;
    public static final double CLIP_FAIL_FRACTION = 0.01;
    public static final double CLIP_WARN_FRACTION = 0.001;
    public static final double MIN_VOICED_FRACTION = 0.10;
    public static final double MIN_VOWEL_VOICED_FRACTION = 0.50;
    public static final double MIN_STEADY_SECONDS = 1.0;
    public static final double NOISY_RANGE_DB = 12;

    private static final String CLOSER = "Sit about 20–30 cm from the microphone and speak at your normal loudness, "
            + "or raise the input volume in your computer's sound settings. Then record again.";

    private QualityGate() {
    }

    /**
     * @param original  the take as captured (original sample rate), for loudness and clipping
     * @param pitch     autocorrelation pitch contour of the analysis-rate signal
     * @param steady    the steady vowel stretch, or null for speech tasks / when none was found
     */
    public static QualityReport assess(TaskType task, AudioClip original, PitchContour pitch, SteadyRegion steady) {
        List<Check> checks = new ArrayList<>();

        if (original.sampleRate() < MIN_SAMPLE_RATE) {
            checks.add(new Check("low_sample_rate", Status.FAIL, original.sampleRate(),
                    "The microphone delivered audio at " + original.sampleRate() + " Hz; analysis needs at least 16000 Hz.",
                    "Choose a different microphone, or set its sample rate to 44.1 kHz or 48 kHz in your sound settings."));
            return QualityReport.of(checks);
        }

        double seconds = original.durationSeconds();
        checks.add(seconds < task.minSeconds()
                ? new Check("too_short", Status.FAIL, seconds,
                        String.format(Locale.ROOT, "This take is %.1f s long; this task needs at least %d s.", seconds, task.minSeconds()),
                        "Record again and keep going until the timer ends.")
                : pass("duration", seconds, String.format(Locale.ROOT, "%.1f s recorded.", seconds)));

        LevelProfile levels = LevelProfile.of(original);
        double loud = levels.percentile(0.95);
        if (loud < QUIET_FAIL_DBFS) {
            checks.add(new Check("too_quiet", Status.FAIL, loud,
                    "We could hardly hear anything in this take (loudness " + db(loud) + ").", CLOSER));
        } else if (loud < QUIET_WARN_DBFS) {
            checks.add(new Check("quiet", Status.WARN, loud,
                    "This take is quiet (loudness " + db(loud) + "), so measurements may be less reliable.", CLOSER));
        } else {
            checks.add(pass("loudness", loud, "Loudness " + db(loud) + "."));
        }

        double clipped = clippedFraction(original.samples());
        String clipRecovery = "Move a little further from the microphone or speak a little softer, "
                + "or lower the input volume. Then record again.";
        if (clipped > CLIP_FAIL_FRACTION) {
            checks.add(new Check("clipped", Status.FAIL, clipped,
                    String.format(Locale.ROOT, "The sound was too loud for the microphone: %.1f %% of samples hit the limit.", clipped * 100),
                    clipRecovery));
        } else if (clipped > CLIP_WARN_FRACTION) {
            checks.add(new Check("slightly_clipped", Status.WARN, clipped,
                    String.format(Locale.ROOT, "A few loud peaks hit the microphone's limit (%.2f %% of samples).", clipped * 100),
                    clipRecovery));
        } else {
            checks.add(pass("clipping", clipped, "No clipping."));
        }

        double voiced = pitch.voicedFraction();
        boolean vowel = task == TaskType.SUSTAINED_VOWEL;
        double minVoiced = vowel ? MIN_VOWEL_VOICED_FRACTION : MIN_VOICED_FRACTION;
        if (voiced < minVoiced) {
            checks.add(new Check("no_voice", Status.FAIL, voiced,
                    vowel ? String.format(Locale.ROOT, "We found a steady voice in only %.0f %% of this take.", voiced * 100)
                            : "We could not hear a voice in this take.",
                    vowel ? "Take a breath, then hold one steady “aah” for the whole timer without stopping."
                            : "Check that the right microphone is selected, speak clearly, and record again."));
        } else {
            checks.add(pass("voice", voiced, String.format(Locale.ROOT, "Voice detected in %.0f %% of frames.", voiced * 100)));
        }

        if (vowel) {
            double steadySeconds = steady == null ? 0 : steady.duration();
            if (steadySeconds < MIN_STEADY_SECONDS && voiced >= minVoiced) {
                checks.add(new Check("no_steady_region", Status.FAIL, steadySeconds,
                        String.format(Locale.ROOT, "The steady middle of your longest unbroken “aah” was %.1f s; "
                                + "at least %.1f s is needed.", steadySeconds, MIN_STEADY_SECONDS),
                        "Hold the “aah” without breaks or pauses until the timer ends."));
            } else if (steady != null) {
                checks.add(pass("steady_region", steadySeconds,
                        String.format(Locale.ROOT, "Steady part used for analysis: %.1f s.", steadySeconds)));
            }
        } else {
            double range = levels.percentile(0.95) - levels.percentile(0.10);
            checks.add(range < NOISY_RANGE_DB
                    ? new Check("noisy", Status.WARN, range,
                            String.format(Locale.ROOT, "Your voice was only %.0f dB louder than the background.", range),
                            "If you can, move somewhere quieter, turn off fans or music, and record again.")
                    : pass("background", range, String.format(Locale.ROOT, "Voice %.0f dB above background.", range)));
        }
        return QualityReport.of(checks);
    }

    static double clippedFraction(double[] x) {
        if (x.length == 0) {
            return 0;
        }
        int n = 0;
        for (double v : x) {
            if (Math.abs(v) >= CLIP_LEVEL) {
                n++;
            }
        }
        return (double) n / x.length;
    }

    private static Check pass(String code, double value, String message) {
        return new Check(code, Status.PASS, value, message, "");
    }

    private static String db(double value) {
        return String.format(Locale.ROOT, "%.0f dBFS", value);
    }
}
