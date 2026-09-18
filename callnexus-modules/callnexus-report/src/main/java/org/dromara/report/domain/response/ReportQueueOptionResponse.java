package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class ReportQueueOptionResponse {
    private Long id;
    private String queueCode;
    private String queueName;
    private Boolean enabled;
}
