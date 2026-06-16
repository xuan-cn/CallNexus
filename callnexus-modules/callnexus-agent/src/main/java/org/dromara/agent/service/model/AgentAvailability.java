package org.dromara.agent.service.model;

public record AgentAvailability(
    Long agentId,
    Long userId,
    String agentName,
    boolean enabled,
    boolean idle
) {
}

