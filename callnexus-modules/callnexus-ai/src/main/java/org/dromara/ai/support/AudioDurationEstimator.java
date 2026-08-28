package org.dromara.ai.support;

import org.dromara.common.core.utils.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AudioDurationEstimator {

    private static final int[] MPEG1_LAYER3_BITRATES = {
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    };
    private static final int[] MPEG2_LAYER3_BITRATES = {
        0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
    };

    private AudioDurationEstimator() {
    }

    public static Long estimate(byte[] audio, String contentType, String fileSuffix, Integer sampleRate) {
        if (audio == null || audio.length == 0) {
            return null;
        }
        String type = StringUtils.isBlank(contentType) ? "" : contentType.toLowerCase(Locale.ROOT);
        String suffix = StringUtils.isBlank(fileSuffix) ? "" : fileSuffix.toLowerCase(Locale.ROOT);
        if (isWav(audio, type, suffix)) {
            return wavDuration(audio);
        }
        if (type.contains("mpeg") || suffix.endsWith(".mp3")) {
            return mp3Duration(audio);
        }
        if (type.contains("pcm") || suffix.endsWith(".pcm") || suffix.endsWith(".raw")) {
            return pcm16MonoDuration(audio, sampleRate);
        }
        return null;
    }

    private static boolean isWav(byte[] audio, String contentType, String suffix) {
        return (audio.length >= 12 && "RIFF".equals(ascii(audio, 0, 4)) && "WAVE".equals(ascii(audio, 8, 4)))
            || contentType.contains("wav") || suffix.endsWith(".wav");
    }

    private static Long wavDuration(byte[] audio) {
        if (audio.length < 44 || !"RIFF".equals(ascii(audio, 0, 4)) || !"WAVE".equals(ascii(audio, 8, 4))) {
            return null;
        }
        int offset = 12;
        long byteRate = 0;
        long dataSize = 0;
        while (offset + 8 <= audio.length) {
            String chunkId = ascii(audio, offset, 4);
            long chunkSize = unsignedLittleEndianInt(audio, offset + 4);
            int dataStart = offset + 8;
            if ("fmt ".equals(chunkId) && dataStart + 16 <= audio.length) {
                byteRate = unsignedLittleEndianInt(audio, dataStart + 8);
            } else if ("data".equals(chunkId)) {
                dataSize = Math.min(chunkSize, Math.max(0, audio.length - dataStart));
                break;
            }
            long next = dataStart + chunkSize + (chunkSize & 1L);
            if (next <= offset || next > audio.length) {
                break;
            }
            offset = (int) next;
        }
        return byteRate > 0 && dataSize > 0 ? Math.round(dataSize * 1000D / byteRate) : null;
    }

    private static Long pcm16MonoDuration(byte[] audio, Integer sampleRate) {
        int rate = sampleRate == null || sampleRate <= 0 ? 8000 : sampleRate;
        return Math.round(audio.length * 1000D / (rate * 2D));
    }

    private static Long mp3Duration(byte[] audio) {
        int offset = id3Offset(audio);
        long durationMicros = 0;
        int frames = 0;
        while (offset + 4 <= audio.length) {
            int header = bigEndianInt(audio, offset);
            Mp3Frame frame = mp3Frame(header);
            if (frame == null || offset + frame.length() > audio.length) {
                offset++;
                continue;
            }
            durationMicros += Math.round(frame.samples() * 1_000_000D / frame.sampleRate());
            frames++;
            offset += frame.length();
        }
        return frames == 0 ? null : Math.round(durationMicros / 1000D);
    }

    private static Mp3Frame mp3Frame(int header) {
        if ((header & 0xFFE00000) != 0xFFE00000) {
            return null;
        }
        int versionBits = (header >>> 19) & 0x3;
        int layerBits = (header >>> 17) & 0x3;
        int bitrateIndex = (header >>> 12) & 0xF;
        int sampleRateIndex = (header >>> 10) & 0x3;
        if (versionBits == 1 || layerBits != 1 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return null;
        }
        boolean mpeg1 = versionBits == 3;
        int bitrate = (mpeg1 ? MPEG1_LAYER3_BITRATES : MPEG2_LAYER3_BITRATES)[bitrateIndex] * 1000;
        int[] rates = mpeg1 ? new int[]{44100, 48000, 32000}
            : versionBits == 2 ? new int[]{22050, 24000, 16000}
            : new int[]{11025, 12000, 8000};
        int sampleRate = rates[sampleRateIndex];
        int padding = (header >>> 9) & 0x1;
        int length = (mpeg1 ? 144 : 72) * bitrate / sampleRate + padding;
        return length > 4 ? new Mp3Frame(length, sampleRate, mpeg1 ? 1152 : 576) : null;
    }

    private static int id3Offset(byte[] audio) {
        if (audio.length < 10 || !"ID3".equals(ascii(audio, 0, 3))) {
            return 0;
        }
        int size = ((audio[6] & 0x7F) << 21) | ((audio[7] & 0x7F) << 14)
            | ((audio[8] & 0x7F) << 7) | (audio[9] & 0x7F);
        return Math.min(audio.length, size + 10);
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return offset >= 0 && offset + length <= bytes.length
            ? new String(bytes, offset, length, StandardCharsets.US_ASCII) : "";
    }

    private static long unsignedLittleEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            return 0;
        }
        return (bytes[offset] & 0xFFL) | ((bytes[offset + 1] & 0xFFL) << 8)
            | ((bytes[offset + 2] & 0xFFL) << 16) | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static int bigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private record Mp3Frame(int length, int sampleRate, int samples) {
    }
}
