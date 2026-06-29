package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DispatchActiveCallResponse {
    private Long sessionId;
    private String businessCallId;
    private Long nodeId;
    private String direction;
    private String callerNumber;
    private String calledNumber;
    private String callStatus;
    private String currentBridgeState;
    private Long queueId;
    private String queueName;
    private Long ownerAgentId;
    private String ownerAgentExtension;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private Integer elapsedSeconds;
    private Integer activeLegCount;
    private Integer activeBridgeCount;
    private Integer visibleAgentCount;
    private List<String> agentExtensions;
    private String topologyStatus;
    private String topologyMessage;
}
