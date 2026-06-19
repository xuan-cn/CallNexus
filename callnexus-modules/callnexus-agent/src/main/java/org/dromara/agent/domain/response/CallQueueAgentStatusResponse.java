package org.dromara.agent.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallQueueAgentStatusResponse {
    private Long agentId;
    private String agentCode;
    private String agentName;
    private Long userId;
    private String extension;
    private String status;
    private String statusText;
    private Boolean enabled;
    private Boolean assignable;
    private LocalDateTime signedInAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastAnsweredAt;
}
