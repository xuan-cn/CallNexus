package org.dromara.ai.provider;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Converts WAV or raw PCM input to mono, signed PCM16 little-endian audio. */
@Component
public class Pcm16AudioNormalizer {

    public NormalizedAudio normalize(byte[] audioBytes, String format, Integer inputSampleRate,
                                     int targetSampleRate) {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new ServiceException("输入音频不能为空");
        }
        if (targetSampleRate <= 0) {
            throw new ServiceException("目标采样率必须大于 0");
        }

        String normalizedFormat = StringUtils.blankToDefault(format, "pcm")
            .replace(".", "").toLowerCase(Locale.ROOT);
        PcmData pcm;
        if ("wav".equals(normalizedFormat) || isWave(audioBytes)) {
            pcm = parseWave(audioBytes);
        } else if ("pcm".equals(normalizedFormat)) {
            int sampleRate = inputSampleRate == null ? 0 : inputSampleRate;
            if (sampleRate <= 0) {
                throw new ServiceException("裸 PCM 音频必须提供采样率");
            }
            if ((audioBytes.length & 1) != 0) {
                throw new ServiceException("PCM16 音频字节数必须是 2 的倍数");
            }
            pcm = new PcmData(audioBytes, sampleRate, 1);
        } else {
            throw new ServiceException("当前仅支持 WAV/PCM 音频，不支持：" + normalizedFormat);
        }

        byte[] mono = pcm.channels() == 1 ? pcm.bytes() : downmixToMono(pcm.bytes(), pcm.channels());
        byte[] output = pcm.sampleRate() == targetSampleRate
            ? mono : resampleMonoPcm16(mono, pcm.sampleRate(), targetSampleRate);
        return new NormalizedAudio(output, targetSampleRate, 1, 16);
    }

    public byte[] toWave(NormalizedAudio audio) {
        if (audio == null || audio.bytes() == null || audio.bytes().length == 0) {
            throw new ServiceException("输入音频不能为空");
        }
        int dataLength = audio.bytes().length;
        int blockAlign = audio.channels() * audio.bitsPerSample() / 8;
        int byteRate = audio.sampleRate() * blockAlign;
        ByteBuffer wave = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(36 + dataLength);
        wave.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        wave.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(16);
        wave.putShort((short) 1);
        wave.putShort((short) audio.channels());
        wave.putInt(audio.sampleRate());
        wave.putInt(byteRate);
        wave.putShort((short) blockAlign);
        wave.putShort((short) audio.bitsPerSample());
        wave.put("data".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(dataLength);
        wave.put(audio.bytes());
        return wave.array();
    }

    private PcmData parseWave(byte[] audio) {
        if (!isWave(audio) || audio.length < 44) {
            throw new ServiceException("WAV 文件头无效");
        }
        int audioFormat = -1;
        int channels = -1;
        int sampleRate = -1;
        int bitsPerSample = -1;
        int dataOffset = -1;
        int dataLength = -1;
        int offset = 12;
        while (offset + 8 <= audio.length) {
            String chunkId = new String(audio, offset, 4, StandardCharsets.US_ASCII);
            long chunkLengthLong = readUnsignedInt(audio, offset + 4);
            int chunkDataOffset = offset + 8;
            if (chunkDataOffset > audio.length) {
                throw new ServiceException("WAV 数据块偏移无效，chunk=" + chunkId);
            }
            int availableLength = audio.length - chunkDataOffset;
            int chunkLength;
            if (chunkLengthLong > availableLength) {
                if (!"data".equals(chunkId)) {
                    throw new ServiceException("WAV 数据块长度无效，chunk=" + chunkId
                        + "，声明长度=" + chunkLengthLong + "，实际剩余=" + availableLength);
                }
                // Streaming encoders may leave an estimated data length in the header.
                chunkLength = availableLength;
            } else {
                chunkLength = (int) chunkLengthLong;
            }
            if ("fmt ".equals(chunkId)) {
                if (chunkLength < 16) {
                    throw new ServiceException("WAV fmt 数据块无效");
                }
                audioFormat = readUnsignedShort(audio, chunkDataOffset);
                channels = readUnsignedShort(audio, chunkDataOffset + 2);
                sampleRate = (int) readUnsignedInt(audio, chunkDataOffset + 4);
                bitsPerSample = readUnsignedShort(audio, chunkDataOffset + 14);
            } else if ("data".equals(chunkId)) {
                dataOffset = chunkDataOffset;
                dataLength = chunkLength;
                if (audioFormat >= 0) {
                    break;
                }
            }
            long next = (long) chunkDataOffset + chunkLength + (chunkLength & 1);
            if (next > audio.length) {
                break;
            }
            offset = (int) next;
        }
        if (audioFormat != 1) {
            throw new ServiceException("仅支持未压缩 PCM WAV，audioFormat=" + audioFormat);
        }
        if (channels < 1 || channels > 2) {
            throw new ServiceException("WAV 仅支持单声道或双声道，channels=" + channels);
        }
        if (bitsPerSample != 16) {
            throw new ServiceException("WAV 仅支持 16bit PCM，bitsPerSample=" + bitsPerSample);
        }
        if (sampleRate <= 0 || dataOffset < 0 || dataLength <= 0) {
            throw new ServiceException("WAV 缺少有效的采样率或音频数据");
        }
        int frameBytes = channels * 2;
        int alignedLength = dataLength - dataLength % frameBytes;
        byte[] pcm = new byte[alignedLength];
        System.arraycopy(audio, dataOffset, pcm, 0, alignedLength);
        return new PcmData(pcm, sampleRate, channels);
    }

    private byte[] downmixToMono(byte[] pcm, int channels) {
        int frames = pcm.length / (channels * 2);
        byte[] mono = new byte[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += readSignedShort(pcm, (frame * channels + channel) * 2);
            }
            writeSignedShort(mono, frame * 2, clamp(sum / channels));
        }
        return mono;
    }

    private byte[] resampleMonoPcm16(byte[] pcm, int sourceRate, int targetRate) {
        if (sourceRate <= 0) {
            throw new ServiceException("输入音频采样率必须大于 0");
        }
        int sourceSamples = pcm.length / 2;
        if (sourceSamples == 0) {
            return new byte[0];
        }
        int targetSamples = Math.max(1, (int) Math.round(sourceSamples * (double) targetRate / sourceRate));
        byte[] output = new byte[targetSamples * 2];
        for (int i = 0; i < targetSamples; i++) {
            double sourcePosition = i * (double) sourceRate / targetRate;
            int leftIndex = Math.min((int) sourcePosition, sourceSamples - 1);
            int rightIndex = Math.min(leftIndex + 1, sourceSamples - 1);
            double fraction = sourcePosition - leftIndex;
            int left = readSignedShort(pcm, leftIndex * 2);
            int right = readSignedShort(pcm, rightIndex * 2);
            int sample = (int) Math.round(left + (right - left) * fraction);
            writeSignedShort(output, i * 2, clamp(sample));
        }
        return output;
    }

    private boolean isWave(byte[] audio) {
        return audio.length >= 12
            && "RIFF".equals(new String(audio, 0, 4, StandardCharsets.US_ASCII))
            && "WAVE".equals(new String(audio, 8, 4, StandardCharsets.US_ASCII));
    }

    private int readUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private long readUnsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
            | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16)
            | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private int readSignedShort(byte[] bytes, int offset) {
        return (short) readUnsignedShort(bytes, offset);
    }

    private void writeSignedShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xff);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    private int clamp(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    public record NormalizedAudio(byte[] bytes, int sampleRate, int channels, int bitsPerSample) {
    }

    private record PcmData(byte[] bytes, int sampleRate, int channels) {
    }
}
