package org.dromara.ai.domain.event;

import org.dromara.ai.provider.AsrSegment;

public record StreamingAsrTranscriptEvent(
    String tenantId,
    Long nodeId,
    String businessCallId,
    String legUuid,
    String speaker,
    String providerType,
    AsrSegment segment
) {
}
