package org.dromara.quality.domain.response;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Date;
@Data
public class QualityTaskResponse {
    private Long id; private String taskCode; private String source; private Long samplingPlanId; private Long samplingExecutionId; private Long callSessionId;
    private String businessCallId; private Long templateId; private String templateName; private Integer templateVersionNo;
    private Integer templateTotalScore; private Integer templateQualifiedScore; private Boolean aiReviewEnabled;
    private Long agentId; private String agentName; private String agentExtension;
    private Long queueId; private String queueName; private Long reviewerId; private String reviewerName;
    private String status; private Integer priority; private Integer totalScore; private Boolean qualified;
    private LocalDateTime assignedAt; private LocalDateTime submittedAt; private LocalDateTime publishedAt;
    private Date createTime; private Integer version;
}
