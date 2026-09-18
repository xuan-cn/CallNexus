package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_task")
public class QualityTask extends TenantEntity {
    @TableId private Long id;
    private String taskCode;
    private String source;
    private Long samplingPlanId;
    private Long samplingExecutionId;
    private Long callSessionId;
    private String businessCallId;
    private Long templateId;
    private Long templateVersionId;
    private Long agentId;
    private String agentName;
    private String agentExtension;
    private Long queueId;
    private String queueName;
    private Long reviewerId;
    private String reviewerName;
    private String status;
    private Integer priority;
    private LocalDateTime assignedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime closedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
