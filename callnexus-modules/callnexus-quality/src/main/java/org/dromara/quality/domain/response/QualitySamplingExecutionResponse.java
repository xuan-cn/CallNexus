package org.dromara.quality.domain.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
public class QualitySamplingExecutionResponse {
    private Long id;
    private Long planId;
    private String executionCode;
    private String triggerType;
    private String status;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private Integer candidateCount;
    private Integer selectedCount;
    private Integer taskCount;
    private Integer excludedCount;
    private String exclusionSummary;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Date createTime;
}
