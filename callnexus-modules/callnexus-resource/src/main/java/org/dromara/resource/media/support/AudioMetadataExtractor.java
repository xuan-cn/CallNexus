package org.dromara.resource.media.support;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class AudioMetadataExtractor {

    private AudioMetadataExtractor() {
    }

    public static AudioMetadata extract(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream();
             AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(inputStream)) {
            AudioFormat format = audioInputStream.getFormat();
            long frameLength = audioInputStream.getFrameLength();
            float frameRate = format.getFrameRate();
            if (frameLength > 0 && frameRate > 0) {
                long durationMs = Math.round(frameLength * 1000D / frameRate);
                if (durationMs > 0) {
                    return new AudioMetadata(durationMs, Math.round(format.getSampleRate()),
                        format.getChannels(), format.getEncoding().toString());
                }
            }
        } catch (Exception ignored) {
            // Fall through to the RIFF parser for WAV files unsupported by the installed Java codecs.
        }

        AudioMetadata wavMetadata = extractWav(file);
        if (wavMetadata != null) {
            return wavMetadata;
        }
        throw new ServiceException("无法解析声音文件时长，请上传有效的标准 WAV 音频");
    }

    private static AudioMetadata extractWav(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length < 44 || !"RIFF".equals(ascii(bytes, 0, 4)) || !"WAVE".equals(ascii(bytes, 8, 4))) {
                return null;
            }
            int offset = 12;
            Integer sampleRate = null;
            Integer channels = null;
            Integer byteRate = null;
            Integer dataSize = null;
            while (offset + 8 <= bytes.length) {
                String chunkId = ascii(bytes, offset, 4);
                int chunkSize = littleEndianInt(bytes, offset + 4);
                if (chunkSize < 0) {
                    return null;
                }
                int chunkDataStart = offset + 8;
                if ("fmt ".equals(chunkId) && chunkDataStart + 16 <= bytes.length) {
                    channels = littleEndianShort(bytes, chunkDataStart + 2);
                    sampleRate = littleEndianInt(bytes, chunkDataStart + 4);
                    byteRate = littleEndianInt(bytes, chunkDataStart + 8);
                } else if ("data".equals(chunkId)) {
                    dataSize = Math.min(chunkSize, bytes.length - chunkDataStart);
                    break;
                }
                long next = (long) chunkDataStart + chunkSize + (chunkSize & 1);
                if (next > bytes.length || next > Integer.MAX_VALUE) {
                    return null;
                }
                offset = (int) next;
            }
            if (byteRate == null || byteRate <= 0 || dataSize == null || dataSize <= 0
                || sampleRate == null || sampleRate <= 0 || channels == null || channels <= 0) {
                return null;
            }
            long durationMs = Math.round(dataSize * 1000D / byteRate);
            return durationMs > 0 ? new AudioMetadata(durationMs, sampleRate, channels, "PCM") : null;
        } catch (IOException exception) {
            throw new ServiceException("读取声音文件失败");
        }
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
            | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16)
            | ((bytes[offset + 3] & 0xff) << 24);
    }

    public record AudioMetadata(long durationMs, int sampleRate, int channels, String codec) {
    }
}
