package org.dromara.resource.media.support;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class AudioMetadataExtractorTest {

    @Test
    void extractsDurationFromPcmWav() {
        byte[] wav = pcmWav(8000, 1, 16, 8000);
        MockMultipartFile file = new MockMultipartFile("file", "prompt.wav", "audio/wav", wav);

        AudioMetadataExtractor.AudioMetadata metadata = AudioMetadataExtractor.extract(file);

        assertThat(metadata.durationMs()).isEqualTo(1000L);
        assertThat(metadata.sampleRate()).isEqualTo(8000);
        assertThat(metadata.channels()).isEqualTo(1);
    }

    @Test
    void rejectsAudioWithoutReadableDuration() {
        MockMultipartFile file = new MockMultipartFile("file", "broken.wav", "audio/wav", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> AudioMetadataExtractor.extract(file))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("无法解析声音文件时长");
    }

    private byte[] pcmWav(int sampleRate, int channels, int bitsPerSample, int sampleCount) {
        int blockAlign = channels * bitsPerSample / 8;
        int byteRate = sampleRate * blockAlign;
        int dataSize = sampleCount * blockAlign;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataSize);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1);
        buffer.putShort((short) channels).putInt(sampleRate).putInt(byteRate);
        buffer.putShort((short) blockAlign).putShort((short) bitsPerSample);
        buffer.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataSize);
        return buffer.array();
    }
}
