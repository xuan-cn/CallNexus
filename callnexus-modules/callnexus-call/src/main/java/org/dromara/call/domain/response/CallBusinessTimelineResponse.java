package org.dromara.call.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallBusinessTimelineResponse {
    private String id;
    private LocalDateTime occurredAt;
    private String type;
    private String title;
    private String description;
    private String actor;
    private String target;
    private String tone;
    private String channelUuid;
    private String relatedChannelUuid;
}
