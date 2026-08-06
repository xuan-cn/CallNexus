package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.call.domain.response.CallControlResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiConsultCallResponse(
    String businessCallId,
    Long sourceAgentId,
    String sourceAgentExtension,
    String targetExtension,
    String status
) {
    public static OpenApiConsultCallResponse from(Long sourceAgentId, CallControlResponse response) {
        return new OpenApiConsultCallResponse(response.getBusinessCallId(), sourceAgentId,
            response.getAgentExtension(), response.getDestination(), response.getStatus());
    }
}
