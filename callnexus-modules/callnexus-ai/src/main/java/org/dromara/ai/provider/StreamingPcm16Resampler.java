package org.dromara.ai.provider;

import org.dromara.common.core.exception.ServiceException;

import java.io.ByteArrayOutputStream;

/** Stateful mono PCM16 little-endian resampler for HTTP audio streams. */
final class StreamingPcm16Resampler {

    private final double sourceSamplesPerTargetSample;
    private double nextOutputPosition;
    private long currentSourceIndex = -1;
    private short previousSample;
    private boolean initialized;
    private Byte trailingByte;

    StreamingPcm16Resampler(int sourceSampleRate, int targetSampleRate) {
        if (sourceSampleRate <= 0 || targetSampleRate <= 0) {
            throw new ServiceException("PCM 重采样率必须大于 0");
        }
        this.sourceSamplesPerTargetSample = sourceSampleRate / (double) targetSampleRate;
    }

    synchronized byte[] accept(byte[] bytes, int length) {
        if (bytes == null || length <= 0) {
            return new byte[0];
        }
        int offset = 0;
        ByteArrayOutputStream output = new ByteArrayOutputStream(
            Math.max(32, (int) Math.ceil(length / sourceSamplesPerTargetSample)));
        if (trailingByte != null) {
            short sample = (short) ((trailingByte & 0xff) | ((bytes[0] & 0xff) << 8));
            appendSample(sample, output);
            trailingByte = null;
            offset = 1;
        }
        int evenEnd = offset + ((length - offset) & ~1);
        while (offset < evenEnd) {
            short sample = (short) ((bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8));
            appendSample(sample, output);
            offset += 2;
        }
        if (offset < length) {
            trailingByte = bytes[offset];
        }
        return output.toByteArray();
    }

    private void appendSample(short sample, ByteArrayOutputStream output) {
        if (!initialized) {
            initialized = true;
            currentSourceIndex = 0;
            previousSample = sample;
            write(output, sample);
            nextOutputPosition = sourceSamplesPerTargetSample;
            return;
        }

        currentSourceIndex++;
        long leftIndex = currentSourceIndex - 1;
        while (nextOutputPosition <= currentSourceIndex) {
            double fraction = nextOutputPosition - leftIndex;
            int interpolated = (int) Math.round(previousSample + (sample - previousSample) * fraction);
            write(output, clamp(interpolated));
            nextOutputPosition += sourceSamplesPerTargetSample;
        }
        previousSample = sample;
    }

    private void write(ByteArrayOutputStream output, int sample) {
        output.write(sample & 0xff);
        output.write((sample >>> 8) & 0xff);
    }

    private short clamp(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }
}
