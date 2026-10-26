package com.neurovox.audio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes RIFF/WAVE files, the format Praat and most audio tools exchange.
 *
 * <p>Neurovox stores browser takes as raw float32 PCM; WAV is used to export a take for Praat, and to read
 * the WAV files used in the validation studies.
 *
 * <p>Reading accepts uncompressed integer PCM (8, 16, 24 or 32 bit) and IEEE float (32 or 64 bit), plain or
 * {@code WAVE_FORMAT_EXTENSIBLE}, with any number of channels (downmixed to mono by averaging).
 * Integer samples are scaled to ±1.0: 16-bit value {@code v} becomes {@code v / 32768}.
 * Unknown chunks (LIST, fact, ...) are skipped. Anything else is rejected with {@link InvalidAudioException}.
 *
 * <p>Writing produces 16-bit PCM mono, the most widely readable form. Values are rounded, and clipped to
 * the 16-bit range; no dither is added.
 */
public final class WavCodec {

    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_IEEE_FLOAT = 3;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;

    private WavCodec() {
    }

    public static AudioClip read(Path file) {
        try {
            return decode(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    public static AudioClip decode(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 12 || !ascii(data, 0, "RIFF") || !ascii(data, 8, "WAVE")) {
            throw new InvalidAudioException("Not a WAV file (missing RIFF/WAVE header).");
        }
        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int blockAlign = 0;
        int dataOffset = -1;
        int dataLength = 0;
        int pos = 12;
        while (pos + 8 <= data.length) {
            String id = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = Integer.toUnsignedLong(buf.getInt(pos + 4));
            int body = pos + 8;
            if (id.equals("fmt ")) {
                if (size < 16 || body + 16 > data.length) {
                    throw new InvalidAudioException("The WAV format chunk is too short.");
                }
                format = Short.toUnsignedInt(buf.getShort(body));
                channels = Short.toUnsignedInt(buf.getShort(body + 2));
                sampleRate = buf.getInt(body + 4);
                blockAlign = Short.toUnsignedInt(buf.getShort(body + 12));
                bits = Short.toUnsignedInt(buf.getShort(body + 14));
                if (format == FORMAT_EXTENSIBLE) {
                    if (size < 40 || body + 26 > data.length) {
                        throw new InvalidAudioException("The WAV extensible format chunk is too short.");
                    }
                    // The first two bytes of the sub-format GUID hold the real format code.
                    format = Short.toUnsignedInt(buf.getShort(body + 24));
                }
            } else if (id.equals("data")) {
                dataOffset = body;
                // Some writers put 0 or a too-large size for streamed files; use what is actually present.
                dataLength = (int) Math.min(size, data.length - body);
                break;
            }
            pos = (int) Math.min((long) data.length, body + size + (size & 1));   // chunks are word aligned
        }
        if (format < 0) {
            throw new InvalidAudioException("The WAV file has no format chunk.");
        }
        if (dataOffset < 0) {
            throw new InvalidAudioException("The WAV file has no data chunk.");
        }
        if (channels < 1 || sampleRate <= 0) {
            throw new InvalidAudioException("The WAV file declares " + channels + " channels at " + sampleRate + " Hz.");
        }
        boolean supported = (format == FORMAT_PCM && (bits == 8 || bits == 16 || bits == 24 || bits == 32))
                || (format == FORMAT_IEEE_FLOAT && (bits == 32 || bits == 64));
        if (!supported) {
            throw new InvalidAudioException("Unsupported WAV encoding (format " + format + ", " + bits + " bits). "
                    + "Use uncompressed PCM or 32-bit float.");
        }
        int bytesPerSample = bits / 8;
        if (blockAlign != bytesPerSample * channels) {
            throw new InvalidAudioException("The WAV block size does not match its channels and bit depth.");
        }
        int frames = dataLength / blockAlign;
        double[] mono = new double[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += sampleAt(buf, dataOffset + f * blockAlign + c * bytesPerSample, format, bits);
            }
            double value = sum / channels;
            if (!Double.isFinite(value)) {
                throw new InvalidAudioException("Sample " + f + " of the WAV file is not a finite number.");
            }
            mono[f] = value;
        }
        return new AudioClip(mono, sampleRate);
    }

    private static double sampleAt(ByteBuffer buf, int offset, int format, int bits) {
        if (format == FORMAT_IEEE_FLOAT) {
            return bits == 32 ? buf.getFloat(offset) : buf.getDouble(offset);
        }
        return switch (bits) {
            case 8 -> (Byte.toUnsignedInt(buf.get(offset)) - 128) / 128.0;   // 8-bit WAV is unsigned
            case 16 -> buf.getShort(offset) / 32768.0;
            case 24 -> {
                int v = (buf.get(offset) & 0xFF) | ((buf.get(offset + 1) & 0xFF) << 8) | (buf.get(offset + 2) << 16);
                yield v / 8388608.0;
            }
            default -> buf.getInt(offset) / 2147483648.0;
        };
    }

    /** Encodes mono samples as a 16-bit PCM WAV file. */
    public static byte[] encode16(AudioClip clip) {
        double[] x = clip.samples();
        int dataBytes = x.length * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + dataBytes)
                .put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) FORMAT_PCM).putShort((short) 1).putInt(clip.sampleRate())
                .putInt(clip.sampleRate() * 2).putShort((short) 2).putShort((short) 16);
        buf.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(dataBytes);
        for (double v : x) {
            long q = Math.round(v * 32768.0);
            buf.putShort((short) Math.max(-32768, Math.min(32767, q)));
        }
        return buf.array();
    }

    public static void write16(AudioClip clip, Path file) {
        try {
            Files.write(file, encode16(clip));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    private static boolean ascii(byte[] data, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (data[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
