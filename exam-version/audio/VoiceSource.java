package audio;

import javax.sound.sampled.*;
import java.io.*;
import java.util.Random;

// A voice to be screened. Abstract: each kind of source (microphone, WAV file, demo)
// loads its samples differently, so load() is overridden in each subclass.
public abstract class VoiceSource {
    public static final float RATE = 44100f;   // samples per second
    protected double[] samples;                // values between -1 and +1
    protected float rate;

    protected VoiceSource(float rate) { this.rate = rate; }

    public abstract void load() throws VoiceException;
    public abstract String getName();

    public double[] getSamples() { return samples; }
    public float getRate() { return rate; }

    @Override
    public String toString() {                 // overrides Object.toString()
        return String.format("%s (%.1f s at %.0f Hz)", getName(), samples.length / rate, rate);
    }

    // 16-bit little-endian bytes -> numbers between -1 and +1 (first channel only)
    protected static double[] toSamples(byte[] b, int channels) {
        double[] x = new double[b.length / (2 * channels)];
        for (int i = 0; i < x.length; i++) {
            int j = i * 2 * channels;
            x[i] = ((b[j + 1] << 8) | (b[j] & 0xFF)) / 32768.0;
        }
        return x;
    }

    // ---------- user-defined exception ----------
    public static class VoiceException extends Exception {
        public VoiceException(String message) { super(message); }
    }

    // ---------- 1. live microphone ----------
    public static class Microphone extends VoiceSource {
        private final int seconds;

        public Microphone() { this(5); }                     // constructor overloading
        public Microphone(int seconds) { super(RATE); this.seconds = seconds; }

        public String getName() { return "Microphone"; }

        public void load() throws VoiceException {
            try {
                AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
                TargetDataLine mic = AudioSystem.getTargetDataLine(fmt);
                mic.open(fmt);
                mic.start();
                byte[] buf = new byte[(int) RATE * 2 * seconds];
                for (int got = 0; got < buf.length; ) got += mic.read(buf, got, buf.length - got);
                mic.close();
                AudioInputStream out = new AudioInputStream(new ByteArrayInputStream(buf), fmt, buf.length / 2);
                AudioSystem.write(out, AudioFileFormat.Type.WAVE, new File("recording.wav"));
                samples = toSamples(buf, 1);
            } catch (LineUnavailableException | IOException | IllegalArgumentException e) {
                throw new VoiceException("Microphone not available: " + e.getMessage());
            }
        }
    }

    // ---------- 2. WAV file (final class: cannot be extended) ----------
    public static final class WavFile extends VoiceSource {
        private final String path;

        public WavFile(String path) { super(RATE); this.path = path; }

        public String getName() { return "WAV " + new File(path).getName(); }

        public void load() throws VoiceException {
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(path))) {
                AudioFormat f = in.getFormat();
                if (f.getSampleSizeInBits() != 16 || f.isBigEndian()) {
                    throw new VoiceException("Please use a 16-bit PCM WAV file");
                }
                rate = f.getSampleRate();
                samples = toSamples(in.readAllBytes(), f.getChannels());
            } catch (UnsupportedAudioFileException | IOException e) {
                throw new VoiceException("Cannot read " + path + ": " + e.getMessage());
            }
        }
    }

    // ---------- 3. artificial 3-second "aah" at 140 Hz (demo without a microphone) ----------
    public static class Demo extends VoiceSource {
        private final boolean shaky;

        public Demo() { this(false); }
        public Demo(boolean shaky) { super(RATE); this.shaky = shaky; }

        public String getName() { return shaky ? "Demo shaky voice" : "Demo steady voice"; }

        public void load() {
            Random r = new Random(7);
            double jit = shaky ? 0.025 : 0.004;     // variation in cycle length
            double shim = shaky ? 0.12 : 0.02;      // variation in cycle loudness
            double noise = shaky ? 0.05 : 0.005;    // breathiness
            samples = new double[(int) (3 * RATE)];
            for (int start = 0; start < samples.length; ) {
                int n = (int) Math.round(RATE / 140 * (1 + jit * r.nextGaussian()));
                double amp = 0.5 * (1 + shim * r.nextGaussian());
                for (int k = 0; k < n && start + k < samples.length; k++) {
                    double ph = 2 * Math.PI * k / n;
                    samples[start + k] = amp * (Math.sin(ph) + 0.5 * Math.sin(2 * ph) + 0.25 * Math.sin(3 * ph))
                            + noise * r.nextGaussian();
                }
                start += n;
            }
        }
    }
}
