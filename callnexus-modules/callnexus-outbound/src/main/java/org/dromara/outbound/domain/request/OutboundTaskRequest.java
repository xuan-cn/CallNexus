package org.dromara.outbound.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OutboundTaskRequest {
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$", message = "任务编码只能包含字母、数字、下划线和短横线")
    private String taskCode;
    @NotBlank
    @Size(max = 64)
    private String taskName;
    @Size(max = 500)
    private String description;
    private Boolean autoRetryEnabled;
    @Min(0)
    @Max(10)
    private Integer maxRetryCount;
    @Min(1)
    @Max(10080)
    private Integer retryIntervalMinutes;
    @Pattern(regexp = "^(|NO_ANSWER|BUSY|OTHER)(,(NO_ANSWER|BUSY|OTHER))*$",
        message = "自动重呼结果配置不合法")
    private String retryResultCodes;
    private Boolean autoAssignDueRetry;
    private Long retryAssigneeAgentId;
    private Integer version;
}
