package org.dromara.report.domain.request;

import lombok.Data;

@Data
public class ReportQuery {
    private String beginDate;
    private String endDate;
    private String granularity;
    private String direction;
    private String answerResult;
    private String keyword;
    private Long agentId;
    private Long skillGroupId;
    private Long queueId;
    private Long taskId;
    private String taskType;
    private String resultCode;
    private String satisfactionStatus;
    private Integer satisfactionScore;
}
