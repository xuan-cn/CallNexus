package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dromara.agent.domain.response.AgentResponse;
import org.dromara.agent.domain.response.CurrentAgentResponse;

import java.time.LocalDateTime;

public record OpenApiAgentResponse(
    @JsonProperty("agent_id") Long agentId,
    @JsonProperty("agent_code") String agentCode,
    @JsonProperty("agent_name") String agentName,
    @JsonProperty("user_id") Long userId,
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("sip_account_id") Long sipAccountId,
    @JsonProperty("node_id") Long nodeId,
    @JsonProperty("extension") String extension,
    @JsonProperty("sip_display_name") String sipDisplayName,
    @JsonProperty("sip_domain") String sipDomain,
    @JsonProperty("presence_status") String presenceStatus,
    @JsonProperty("active_call_id") String activeCallId,
    @JsonProperty("active_call_number") String activeCallNumber,
    @JsonProperty("after_call_remaining_seconds") Long afterCallRemainingSeconds,
    @JsonProperty("signed_in_at") LocalDateTime signedInAt,
    @JsonProperty("updated_at") LocalDateTime updatedAt
) {
    public static OpenApiAgentResponse from(AgentResponse agent, CurrentAgentResponse session) {
        return new OpenApiAgentResponse(
            agent.getId(), agent.getAgentCode(), agent.getAgentName(), agent.getUserId(), agent.getEnabled(),
            session.getSipAccountId(), session.getNodeId(), session.getExtension(), session.getSipDisplayName(),
            session.getSipDomain(), session.getStatus() == null ? null : session.getStatus().name(),
            session.getActiveCallId(), session.getActiveCallNumber(), session.getAfterCallRemainingSeconds(),
            session.getSignedInAt(), session.getUpdatedAt());
    }
}
