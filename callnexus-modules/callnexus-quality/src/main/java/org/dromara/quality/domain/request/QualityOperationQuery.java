package org.dromara.quality.domain.request;

import lombok.Data;

@Data
public class QualityOperationQuery {
    private String periodType;
    private String metricCode;
    private String status;
    private String scopeType;
    private String beginDate;
    private String endDate;
    private String keyword;
}
