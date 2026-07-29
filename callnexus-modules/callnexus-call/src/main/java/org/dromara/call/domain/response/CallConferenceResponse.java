package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CallConferenceResponse {
    private Long id;
    private Long sessionId;
    private String businessCallId;
    private Long nodeId;
    private String conferenceName;
    private Long ownerAgentId;
    private String ownerExtension;
    private String conferenceState;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private List<CallConferenceMemberResponse> members;
}
