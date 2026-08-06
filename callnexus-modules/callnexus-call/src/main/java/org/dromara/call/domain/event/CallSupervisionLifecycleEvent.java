package org.dromara.call.domain.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Dispatch supervision event consumed by optional integration modules.
 */
public record CallSupervisionLifecycleEvent(
    String tenantId,
    String eventType,
    String businessCallId,
    Long nodeId,
    LocalDateTime occurredAt,
    Map<String, Object> payload
) {
}
