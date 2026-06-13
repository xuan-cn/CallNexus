package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallEventResponse {
    private Long id;
    private String channelUuid;
    private String relatedChannelUuid;
    private String eventType;
    private String fromTarget;
    private String toTarget;
    private LocalDateTime occurredAt;
}
