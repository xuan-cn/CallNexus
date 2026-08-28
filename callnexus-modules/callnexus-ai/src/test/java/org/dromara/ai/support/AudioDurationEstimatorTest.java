package org.dromara.ai.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class AudioDurationEstimatorTest {

    @Test
    void estimatesPcm16MonoDuration() {
        assertEquals(1000L, AudioDurationEstimator.estimate(new byte[16000], "audio/pcm", ".pcm", 8000));
    }

    @Test
    void estimatesWavDurationFromHeader() {
        byte[] wav = pcmWav(8000, 8000);
        assertEquals(1000L, AudioDurationEstimator.estimate(wav, "audio/wav", ".wav", 8000));
    }

    @Test
    void estimatesMp3DurationFromFrames() {
        byte[] mp3 = new byte[417 * 38];
        for (int offset = 0; offset < mp3.length; offset += 417) {
            mp3[offset] = (byte) 0xFF;
            mp3[offset + 1] = (byte) 0xFB;
            mp3[offset + 2] = (byte) 0x90;
        }
        assertEquals(993L, AudioDurationEstimator.estimate(mp3, "audio/mpeg", ".mp3", null));
    }

    private byte[] pcmWav(int sampleRate, int sampleCount) {
        int dataSize = sampleCount * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes());
        buffer.put("fmt ".getBytes());
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes());
        buffer.putInt(dataSize);
        return buffer.array();
    }
}
