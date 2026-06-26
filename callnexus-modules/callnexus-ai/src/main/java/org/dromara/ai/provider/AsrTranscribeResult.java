package org.dromara.ai.provider;

import java.util.List;

public record AsrTranscribeResult(
    String fullText,
    List<AsrSegment> segments
) {
}
