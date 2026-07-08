package org.dromara.ai.provider;

public interface StreamingTtsListener {
    void onStarted();

    void onAudio(byte[] audioBytes);

    void onCompleted();

    void onError(String message);
}
