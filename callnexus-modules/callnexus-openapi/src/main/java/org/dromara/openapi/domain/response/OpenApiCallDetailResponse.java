package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.call.domain.response.AgentCallSessionResponse;
import org.dromara.call.domain.response.CallDiagnosticBridgeResponse;
import org.dromara.call.domain.response.CallDiagnosticLegResponse;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;

import java.time.LocalDateTime;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiCallDetailResponse(
    OpenApiCallResponse call,
    List<Leg> legs,
    List<Bridge> bridges,
    List<AgentParticipant> agentParticipants
) {
    public static OpenApiCallDetailResponse from(DispatchCallTopologyResponse value) {
        return new OpenApiCallDetailResponse(
            OpenApiCallResponse.from(value.getCall()),
            value.getLegs().stream().map(Leg::from).toList(),
            value.getBridges().stream().map(Bridge::from).toList(),
            value.getAgentSessions().stream().map(AgentParticipant::from).toList());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Leg(Long legId, String legUuid, String legRole, String endpointExtension, Long agentId,
                      String agentExtension, String callerNumber, String calledNumber, String legState,
                      Boolean active, LocalDateTime ringingAt, LocalDateTime answeredAt,
                      LocalDateTime bridgedAt, LocalDateTime heldAt, LocalDateTime parkedAt,
                      LocalDateTime endedAt, String hangupCause) {
        private static Leg from(CallDiagnosticLegResponse value) {
            return new Leg(value.getId(), value.getLegUuid(), value.getLegRole(), value.getEndpointExtension(),
                value.getAgentId(), value.getAgentExtension(), value.getCallerNumber(), value.getCalledNumber(),
                value.getLegState(), value.getActive(), value.getRingingAt(), value.getAnsweredAt(),
                value.getBridgedAt(), value.getHeldAt(), value.getParkedAt(), value.getEndedAt(),
                value.getHangupCause());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Bridge(Long bridgeId, String leftLegUuid, String rightLegUuid, String bridgeType,
                         String bridgeState, LocalDateTime startedAt, LocalDateTime endedAt) {
        private static Bridge from(CallDiagnosticBridgeResponse value) {
            return new Bridge(value.getId(), value.getLeftLegUuid(), value.getRightLegUuid(), value.getBridgeType(),
                value.getBridgeState(), value.getStartedAt(), value.getEndedAt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentParticipant(Long participantId, Long agentId, String agentExtension, String agentLegUuid,
                                   String role, String sessionState, Boolean visible,
                                   LocalDateTime joinedAt, LocalDateTime leftAt) {
        private static AgentParticipant from(AgentCallSessionResponse value) {
            return new AgentParticipant(value.getId(), value.getAgentId(), value.getAgentExtension(),
                value.getAgentLegUuid(), value.getRole(), value.getSessionState(), value.getVisible(),
                value.getJoinedAt(), value.getLeftAt());
        }
    }
}
