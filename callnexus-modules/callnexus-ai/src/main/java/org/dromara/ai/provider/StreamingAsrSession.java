package org.dromara.ai.provider;

public interface StreamingAsrSession extends AutoCloseable {
    void send(byte[] audioBytes);

    void finish();

    @Override
    void close();
}

