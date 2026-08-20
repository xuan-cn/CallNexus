package org.dromara.ai.provider;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingPcm16ResamplerTest {

    @Test
    void shouldResampleTwentyFourKhzToEightKhzAcrossOddChunks() {
        short[] samples = new short[240];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (i * 10);
        }
        ByteBuffer source = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        source.asShortBuffer().put(samples);
        byte[] pcm = source.array();
        StreamingPcm16Resampler resampler = new StreamingPcm16Resampler(24000, 8000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        output.writeBytes(resampler.accept(pcm, 101));
        byte[] second = new byte[pcm.length - 101];
        System.arraycopy(pcm, 101, second, 0, second.length);
        output.writeBytes(resampler.accept(second, second.length));

        byte[] converted = output.toByteArray();
        assertThat(converted).hasSize(80 * 2);
        ByteBuffer result = ByteBuffer.wrap(converted).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(result.getShort(0)).isEqualTo((short) 0);
        assertThat(result.getShort(2)).isEqualTo((short) 30);
        assertThat(result.getShort(4)).isEqualTo((short) 60);
    }
}
