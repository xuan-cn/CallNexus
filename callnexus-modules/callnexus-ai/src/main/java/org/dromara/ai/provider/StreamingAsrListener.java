package org.dromara.ai.provider;

public interface StreamingAsrListener {
    void onResult(AsrSegment segment);

    void onCompleted(AsrTranscribeResult result);

    void onError(String message);
}
