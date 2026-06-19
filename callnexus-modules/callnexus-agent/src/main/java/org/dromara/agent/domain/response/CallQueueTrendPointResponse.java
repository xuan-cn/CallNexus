package org.dromara.agent.domain.response;

import lombok.Data;

@Data
public class CallQueueTrendPointResponse {
    private Integer hour;
    private Long enteredCount;
    private Long answeredCount;
    private Long abandonedCount;
    private Long timeoutCount;
}
