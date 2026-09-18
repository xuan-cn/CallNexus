package org.dromara.quality.domain.request;

import lombok.Data;

@Data
public class QualityReportQuery {
    private String beginDate;
    private String endDate;
    private Long agentId;
    private Long queueId;
    private Long skillGroupId;
    private Boolean qualified;
    private Boolean fatalFlag;
    private String keyword;
}
