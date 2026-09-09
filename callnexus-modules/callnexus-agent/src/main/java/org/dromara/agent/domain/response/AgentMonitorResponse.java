package org.dromara.agent.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentMonitorResponse {
    private Long agentId;
    private String agentCode;
    private String agentName;
    private Long userId;
    private String extension;
    private String skillGroupIds;
    private String skillGroupNames;
    private Boolean enabled;
    private String status;
    private String statusText;
    private LocalDateTime signedInAt;
    private LocalDateTime statusUpdatedAt;
    private Long todayHandledCount;
    private Long inboundAnsweredCount;
    private Long outboundAnsweredCount;
    private Long missedOfferCount;
    private Long totalTalkSeconds;
    private Long averageTalkSeconds;
    private Long onlineSeconds;
    private Long idleSeconds;
    private Long notReadySeconds;
    private Long busySeconds;
    private Long afterCallSeconds;
    private Double utilizationRate;
    private String currentBusinessCallId;
    private String currentDirection;
    private String currentPeerNumber;
    private String currentCallState;
    private LocalDateTime currentCallStartedAt;
}
