package org.dromara.call.domain.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Stable call-domain event consumed by optional integration modules.
 */
public record CallLifecycleEvent(
    String tenantId,
    String eventType,
    String businessCallId,
    Long nodeId,
    LocalDateTime occurredAt,
    Map<String, Object> payload
) {
}
