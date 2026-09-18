package org.dromara.quality.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityReportDetailResponse {
    private Long taskId;
    private String taskCode;
    private Long callSessionId;
    private String businessCallId;
    private LocalDateTime callEndedAt;
    private Long agentId;
    private String agentName;
    private String agentExtension;
    private Long queueId;
    private String queueName;
    private Long skillGroupId;
    private String skillGroupName;
    private Long reviewerId;
    private String reviewerName;
    private String templateName;
    private Integer templateVersionNo;
    private Integer totalScore;
    private Boolean qualified;
    private Boolean fatalFlag;
    private String summary;
    private String appealStatus;
    private LocalDateTime publishedAt;
}
