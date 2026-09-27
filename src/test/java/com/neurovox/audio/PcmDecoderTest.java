package com.neurovox.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmDecoderTest {

    @Test
    void decodesLittleEndianFloats() {
        // 1.0f = 0x3F800000, stored little-endian as 00 00 80 3F; -0.5f = 0xBF000000 -> 00 00 00 BF.
        byte[] bytes = {0x00, 0x00, (byte) 0x80, 0x3F, 0x00, 0x00, 0x00, (byte) 0xBF};
        assertArrayEquals(new float[] {1.0f, -0.5f}, PcmDecoder.decodeF32le(bytes));
    }

    @Test
    void encodeAndDecodeRoundTrip() {
        float[] samples = {0f, 0.25f, -0.999f, 1e-7f, -1f, 3.99f};
        assertArrayEquals(samples, PcmDecoder.decodeF32le(PcmDecoder.encodeF32le(samples)));
    }

    @Test
    void bigEndianDataDoesNotDecodeToTheOriginalSignal() {
        // Documents why the byte order is explicit: the same bytes read the wrong way give different values.
        float[] original = {0.3f, -0.3f, 0.6f};
        ByteBuffer big = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        for (float f : original) {
            big.putFloat(f);
        }
        float[] decoded = PcmDecoder.decodeF32le(big.array());
        for (int i = 0; i < original.length; i++) {
            assertTrue(Math.abs(decoded[i] - original[i]) > 0.1, "sample " + i + " decoded as " + decoded[i]);
        }
    }

    @Test
    void rejectsStructurallyInvalidData() {
        assertThrows(InvalidAudioException.class, () -> PcmDecoder.decodeF32le(new byte[0]));
        assertThrows(InvalidAudioException.class, () -> PcmDecoder.decodeF32le(null));
        assertThrows(InvalidAudioException.class, () -> PcmDecoder.decodeF32le(new byte[7]));
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 4.5f, -1e9f}) {
            InvalidAudioException e = assertThrows(InvalidAudioException.class,
                    () -> PcmDecoder.decodeF32le(PcmDecoder.encodeF32le(new float[] {0.1f, bad, 0.1f})));
            assertTrue(e.getMessage().startsWith("Sample 1 "), e.getMessage());
        }
    }

    @Test
    void summaryReportsLevels() {
        float[] sine = new float[48_000];
        for (int i = 0; i < sine.length; i++) {
            sine[i] = (float) (0.5 * Math.sin(2 * Math.PI * 200 * i / 48_000.0));
        }
        PcmSummary s = PcmSummary.of(sine, 48_000);
        assertEquals(1.0, s.durationSeconds(), 1e-12);
        assertEquals(0.5, s.peak(), 1e-4);
        assertEquals(0.5 / Math.sqrt(2), s.rms(), 1e-4);
        assertEquals(20 * Math.log10(0.5 / Math.sqrt(2)), s.rmsDbfs(), 1e-3);
        assertEquals(null, PcmSummary.of(new float[10], 48_000).rmsDbfs());
    }
}
