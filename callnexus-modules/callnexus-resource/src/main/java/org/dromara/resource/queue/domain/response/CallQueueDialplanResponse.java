package org.dromara.resource.queue.domain.response;

import lombok.Data;

@Data
public class CallQueueDialplanResponse {
    private Long id;
    private String queueCode;
    private String queueName;
}
