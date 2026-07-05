package org.dromara.ai.realtime;

public record AiRealtimeClaims(
    String tenantId,
    Long agentId,
    Long flowId,
    Long nodeId,
    long expiresAt
) {
}
