package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class CallDistributionResponse {
    private String category;
    private Long count;
}
