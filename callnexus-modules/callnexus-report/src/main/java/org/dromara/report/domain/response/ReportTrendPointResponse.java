package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class ReportTrendPointResponse {
    private String bucket;
    private Long totalCalls;
    private Long inboundCalls;
    private Long outboundCalls;
    private Long answeredCalls;
}
