package org.dromara.ai.domain.request;

public record AiAgentAssistSegmentRequest(
    String tenantId,
    Long callSessionId,
    String businessCallId,
    Long transcriptSegmentId,
    String customerText,
    Long agentId,
    Long skillGroupId,
    Long assistAgentId
) {
}
