package org.dromara.ai.realtime;

public record StreamingAsrClaims(
    String tenantId,
    Long nodeId,
    String businessCallId,
    String legUuid,
    String speaker,
    long expiresAt
) {
}
