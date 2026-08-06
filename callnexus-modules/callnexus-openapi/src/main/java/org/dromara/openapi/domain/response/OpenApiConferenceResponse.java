package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.dromara.call.domain.response.CallConferenceResponse;

import java.time.LocalDateTime;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenApiConferenceResponse(
    Long conferenceId,
    String businessCallId,
    String displayName,
    Long ownerAgentId,
    String ownerExtension,
    String conferenceState,
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    List<OpenApiConferenceMemberResponse> members
) {
    public static OpenApiConferenceResponse from(CallConferenceResponse response) {
        if (response == null) {
            return null;
        }
        List<OpenApiConferenceMemberResponse> members = response.getMembers() == null ? List.of()
            : response.getMembers().stream().map(OpenApiConferenceMemberResponse::from).toList();
        return new OpenApiConferenceResponse(response.getId(), response.getBusinessCallId(), response.getDisplayName(),
            response.getOwnerAgentId(),
            response.getOwnerExtension(), response.getConferenceState(), response.getStartedAt(), response.getEndedAt(), members);
    }
}
