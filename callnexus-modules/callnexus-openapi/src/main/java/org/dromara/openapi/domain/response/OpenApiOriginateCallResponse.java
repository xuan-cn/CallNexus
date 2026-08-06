package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.call.domain.response.CallControlResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiOriginateCallResponse(
    Long agentId,
    String businessCallId,
    String agentExtension,
    String destination,
    Boolean external,
    String gatewayCode,
    String callerIdNumber,
    String status
) {
    public static OpenApiOriginateCallResponse from(Long agentId, CallControlResponse value) {
        return new OpenApiOriginateCallResponse(agentId, value.getBusinessCallId(), value.getAgentExtension(),
            value.getDestination(), value.getExternal(), value.getGatewayCode(), value.getCallerIdNumber(),
            value.getStatus());
    }
}
