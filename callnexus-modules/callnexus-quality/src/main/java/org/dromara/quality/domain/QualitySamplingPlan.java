package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_sampling_plan")
public class QualitySamplingPlan extends TenantEntity {
    @TableId private Long id;
    private String planCode;
    private String planName;
    private Long templateId;
    private String samplingMethod;
    private Integer sampleCount;
    private BigDecimal sampleRate;
    private String stratifyDimension;
    private String directionScope;
    private Long queueId;
    private Long skillGroupId;
    private Long agentId;
    private Integer minDurationSeconds;
    private Integer maxDurationSeconds;
    private Boolean requireRecording;
    private Boolean requireTranscript;
    private Integer lookbackDays;
    private Integer minPerAgent;
    private Integer maxPerAgent;
    private Integer cooldownDays;
    private Boolean excludeSampled;
    private Long reviewerId;
    private Integer priority;
    private String scheduleType;
    private LocalTime scheduleTime;
    private Integer scheduleDay;
    private Boolean enabled;
    private LocalDateTime lastExecutedAt;
    private String remark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
