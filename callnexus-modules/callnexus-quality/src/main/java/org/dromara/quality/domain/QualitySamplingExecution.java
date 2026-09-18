package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_sampling_execution")
public class QualitySamplingExecution extends TenantEntity {
    @TableId private Long id;
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
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
