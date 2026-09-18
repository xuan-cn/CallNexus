package org.dromara.quality.domain.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityAppealResponse {
    private Long id;
    private Long taskId;
    private String taskCode;
    private String businessCallId;
    private Long qualityResultId;
    private Integer originalScore;
    private Boolean originalQualified;
    private String appealReason;
    private String attachmentOssIds;
    private String status;
    private Long appellantId;
    private String appellantName;
    private LocalDateTime appealedAt;
    private LocalDateTime appealDeadline;
    private Long reviewerId;
    private String reviewerName;
    private LocalDateTime reviewedAt;
    private String reviewConclusion;
    private Long reviewResultId;
    private Integer reviewScore;
    private Boolean reviewQualified;
    private Integer version;
}
