package org.dromara.ai.service.model;

import java.time.LocalDateTime;

public record AiTicketCallCompletedContext(
    String tenantId,
    Long callSessionId,
    String businessCallId,
    LocalDateTime startedAt,
    LocalDateTime answeredAt,
    LocalDateTime endedAt,
    Integer durationSeconds,
    Integer billableSeconds,
    String hangupCause
) {
}
