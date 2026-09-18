package org.dromara.quality.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
public class QualitySamplingPlanRequest {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,32}$") private String planCode;
    @NotBlank @Size(max = 64) private String planName;
    @NotNull private Long templateId;
    @NotBlank private String samplingMethod;
    @Min(1) private Integer sampleCount;
    @DecimalMin("0.01") @DecimalMax("100.00") private BigDecimal sampleRate;
    private String stratifyDimension;
    @NotBlank private String directionScope;
    private Long queueId;
    private Long skillGroupId;
    private Long agentId;
    @Min(0) private Integer minDurationSeconds;
    @Min(0) private Integer maxDurationSeconds;
    private Boolean requireRecording;
    private Boolean requireTranscript;
    @NotNull @Min(1) @Max(365) private Integer lookbackDays;
    @NotNull @Min(0) private Integer minPerAgent;
    @Min(1) private Integer maxPerAgent;
    @NotNull @Min(0) @Max(3650) private Integer cooldownDays;
    @NotNull private Boolean excludeSampled;
    private Long reviewerId;
    @NotNull @Min(1) @Max(9) private Integer priority;
    @NotBlank private String scheduleType;
    private LocalTime scheduleTime;
    @Min(1) @Max(31) private Integer scheduleDay;
    @NotNull private Boolean enabled;
    @Size(max = 500) private String remark;
    private Integer version;
}
