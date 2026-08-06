package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.call.domain.response.CallConferenceMemberResponse;

import java.time.LocalDateTime;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiConferenceMemberResponse(
    Long memberId,
    String memberRole,
    Long agentId,
    String extension,
    String displayName,
    String memberState,
    Boolean muted,
    LocalDateTime joinedAt,
    LocalDateTime leftAt
) {
    public static OpenApiConferenceMemberResponse from(CallConferenceMemberResponse response) {
        return new OpenApiConferenceMemberResponse(response.getId(), response.getMemberRole(), response.getAgentId(),
            response.getExtension(), response.getDisplayName(), response.getMemberState(), response.getMuted(),
            response.getJoinedAt(), response.getLeftAt());
    }
}
