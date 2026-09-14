package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class OutboundTrendPointResponse {
    private String bucket;
    private Long attemptCount;
    private Long answeredCount;
    private Long businessSuccessCount;
}
