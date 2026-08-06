package org.dromara.ai.domain.event;

import java.time.LocalDateTime;
import java.util.Map;

public record AiTranscriptLifecycleEvent(
    String tenantId,
    String eventType,
    String businessCallId,
    Long nodeId,
    LocalDateTime occurredAt,
    Map<String, Object> payload
) {
}
