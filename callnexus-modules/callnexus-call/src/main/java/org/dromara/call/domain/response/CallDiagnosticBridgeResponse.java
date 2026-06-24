package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallDiagnosticBridgeResponse {
    private Long id;
    private String businessCallId;
    private Long nodeId;
    private String leftLegUuid;
    private String rightLegUuid;
    private String bridgeType;
    private String bridgeState;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
