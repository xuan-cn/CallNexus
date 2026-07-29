package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallConferenceMemberResponse {
    private Long id;
    private String legUuid;
    private String conferenceMemberId;
    private String memberRole;
    private Long agentId;
    private String extension;
    private String displayName;
    private String memberState;
    private Boolean muted;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
}
