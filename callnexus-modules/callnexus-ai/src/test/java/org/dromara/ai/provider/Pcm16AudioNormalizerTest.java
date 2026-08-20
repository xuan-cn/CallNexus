package org.dromara.ai.provider;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class Pcm16AudioNormalizerTest {

    private final Pcm16AudioNormalizer normalizer = new Pcm16AudioNormalizer();

    @Test
    void shouldConvertEightKhzStereoWaveToSixteenKhzMonoPcm() {
        short[] stereoSamples = {
            1000, 3000,
            -1000, 1000,
            2000, 2000,
            -2000, -2000
        };

        Pcm16AudioNormalizer.NormalizedAudio result = normalizer.normalize(
            wave(stereoSamples, 8000, 2), "wav", null, 16000);

        assertThat(result.sampleRate()).isEqualTo(16000);
        assertThat(result.channels()).isEqualTo(1);
        assertThat(result.bitsPerSample()).isEqualTo(16);
        assertThat(result.bytes()).hasSize(16);
        assertThat(sample(result.bytes(), 0)).isEqualTo((short) 2000);
        assertThat(sample(result.bytes(), 2)).isEqualTo((short) 0);
        assertThat(sample(result.bytes(), 4)).isEqualTo((short) 2000);
        assertThat(sample(result.bytes(), 6)).isEqualTo((short) -2000);
    }

    @Test
    void shouldKeepSixteenKhzMonoPcmUnchanged() {
        byte[] pcm = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort((short) 1).putShort((short) -2).putShort((short) 3).array();

        Pcm16AudioNormalizer.NormalizedAudio result = normalizer.normalize(pcm, "pcm", 16000, 16000);

        assertThat(result.bytes()).containsExactly(pcm);
    }

    @Test
    void shouldEncodeNormalizedPcmAsWave() {
        byte[] pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort((short) 100).putShort((short) -100).array();

        byte[] wave = normalizer.toWave(new Pcm16AudioNormalizer.NormalizedAudio(pcm, 16000, 1, 16));

        assertThat(new String(wave, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(wave, 8, 4)).isEqualTo("WAVE");
        assertThat(ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).getInt(24)).isEqualTo(16000);
        assertThat(ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).getInt(40)).isEqualTo(pcm.length);
        assertThat(wave).hasSize(44 + pcm.length);
    }

    @Test
    void shouldUseAvailableWavePayloadWhenDataLengthIsEstimated() {
        byte[] wave = wave(new short[] {100, -100}, 16000, 1);
        ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Integer.MAX_VALUE);

        Pcm16AudioNormalizer.NormalizedAudio result = normalizer.normalize(wave, "wav", null, 16000);

        assertThat(result.bytes()).hasSize(4);
        assertThat(sample(result.bytes(), 0)).isEqualTo((short) 100);
        assertThat(sample(result.bytes(), 1)).isEqualTo((short) -100);
    }

    private byte[] wave(short[] samples, int sampleRate, int channels) {
        int dataLength = samples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'R', 'I', 'F', 'F'});
        buffer.putInt(36 + dataLength);
        buffer.put(new byte[] {'W', 'A', 'V', 'E'});
        buffer.put(new byte[] {'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * 2);
        buffer.putShort((short) (channels * 2));
        buffer.putShort((short) 16);
        buffer.put(new byte[] {'d', 'a', 't', 'a'});
        buffer.putInt(dataLength);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    private short sample(byte[] bytes, int index) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(index * 2);
    }
}
