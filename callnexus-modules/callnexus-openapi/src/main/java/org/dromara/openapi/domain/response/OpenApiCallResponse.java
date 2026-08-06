package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.call.domain.response.DispatchActiveCallResponse;

import java.time.LocalDateTime;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiCallResponse(
    Long sessionId,
    String businessCallId,
    Long nodeId,
    String direction,
    String callerNumber,
    String calledNumber,
    String callStatus,
    String currentBridgeState,
    Long queueId,
    String queueName,
    Long ownerAgentId,
    String ownerAgentExtension,
    LocalDateTime startedAt,
    LocalDateTime answeredAt,
    LocalDateTime endedAt,
    Integer elapsedSeconds,
    Integer durationSeconds,
    Integer billableSeconds,
    String hangupCause,
    Integer activeLegCount,
    Integer activeBridgeCount,
    Integer visibleAgentCount,
    List<String> agentExtensions,
    String topologyStatus,
    String topologyMessage
) {
    public static OpenApiCallResponse from(DispatchActiveCallResponse value) {
        return new OpenApiCallResponse(value.getSessionId(), value.getBusinessCallId(), value.getNodeId(),
            value.getDirection(), value.getCallerNumber(), value.getCalledNumber(), value.getCallStatus(),
            value.getCurrentBridgeState(), value.getQueueId(), value.getQueueName(), value.getOwnerAgentId(),
            value.getOwnerAgentExtension(), value.getStartedAt(), value.getAnsweredAt(), value.getEndedAt(),
            value.getElapsedSeconds(), value.getDurationSeconds(), value.getBillableSeconds(), value.getHangupCause(),
            value.getActiveLegCount(), value.getActiveBridgeCount(), value.getVisibleAgentCount(),
            value.getAgentExtensions(), value.getTopologyStatus(), value.getTopologyMessage());
    }
}
