package org.dromara.agent.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CallQueueRequest {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") private String queueCode;
    @NotBlank @Size(max = 64) private String queueName;
    @NotNull private Long nodeGroupId;
    @NotNull private Long skillGroupId;
    @NotBlank private String strategy;
    private Long waitMediaId;
    @NotNull @Min(10) @Max(86400) private Integer maxWaitSeconds;
    @NotNull @Min(5) @Max(300) private Integer ringTimeoutSeconds;
    @NotNull @Min(0) @Max(100) private Integer maxNoAnswer;
    @NotNull @Min(0) @Max(3600) private Integer wrapUpSeconds;
    @NotNull private Boolean enabled;
    @Size(max = 500) private String remark;
    private Integer version;
}
