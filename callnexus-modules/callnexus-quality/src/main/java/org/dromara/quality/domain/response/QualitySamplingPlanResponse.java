package org.dromara.quality.domain.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

@Data
public class QualitySamplingPlanResponse {
    private Long id;
    private String planCode;
    private String planName;
    private Long templateId;
    private String templateName;
    private String samplingMethod;
    private Integer sampleCount;
    private BigDecimal sampleRate;
    private String stratifyDimension;
    private String directionScope;
    private Long queueId;
    private String queueName;
    private Long skillGroupId;
    private String skillGroupName;
    private Long agentId;
    private String agentName;
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
    private String reviewerName;
    private Integer priority;
    private String scheduleType;
    private LocalTime scheduleTime;
    private Integer scheduleDay;
    private Boolean enabled;
    private LocalDateTime lastExecutedAt;
    private String remark;
    private Integer version;
    private Date createTime;
}
