package org.dromara.call.domain;

import java.time.LocalDateTime;

/**
 * 业务通话聚合结束事件。
 */
public record CallSessionCompletedEvent(
    String tenantId,
    Long sessionId,
    String businessCallId,
    Long outboundTaskId,
    Long outboundMemberId,
    LocalDateTime startedAt,
    LocalDateTime answeredAt,
    LocalDateTime endedAt,
    LocalDateTime destinationAnsweredAt,
    Integer durationSeconds,
    Integer billableSeconds,
    Integer destinationBillableSeconds,
    String hangupCause
) {
}
