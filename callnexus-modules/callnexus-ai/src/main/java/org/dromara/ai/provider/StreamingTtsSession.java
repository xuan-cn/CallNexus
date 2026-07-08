package org.dromara.ai.provider;

public interface StreamingTtsSession extends AutoCloseable {
    void append(String text);

    void commit();

    void finish();

    @Override
    void close();
}
