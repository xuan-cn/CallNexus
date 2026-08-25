package org.dromara.outbound.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AutoOutboundTaskRequest {
    @NotBlank(message = "任务编码不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$", message = "任务编码只能包含字母、数字、下划线和短横线")
    private String taskCode;
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 64, message = "任务名称不能超过64个字符")
    private String taskName;
    @Size(max = 500, message = "任务说明不能超过500个字符")
    private String description;
    private Long callerNumberId;
    @NotBlank(message = "请选择拨打模式")
    @Pattern(regexp = "AGENTLESS_AI|AGENTLESS_IVR|PROGRESSIVE", message = "拨打模式不正确")
    private String dialMode;
    @NotBlank(message = "请选择接听目标类型")
    @Pattern(regexp = "AI_AGENT|IVR_FLOW|SKILL_GROUP", message = "接听目标类型不正确")
    private String targetType;
    @NotNull(message = "请选择接听目标")
    private Long targetId;
    private Long skillGroupId;
    @Min(value = 1, message = "并发数不能小于1")
    @Max(value = 500, message = "并发数不能超过500")
    private Integer concurrencyLimit = 1;
    @Min(value = 1, message = "每分钟呼叫数不能小于1")
    @Max(value = 3000, message = "每分钟呼叫数不能超过3000")
    private Integer callsPerMinute = 10;
    @Min(value = 1, message = "每日呼叫次数不能小于1")
    @Max(value = 100, message = "每日呼叫次数不能超过100")
    private Integer maxCallsPerDay = 1;
    @Min(value = 1, message = "任务总呼叫次数不能小于1")
    @Max(value = 1000, message = "任务总呼叫次数不能超过1000")
    private Integer maxCallsTotal = 3;
    @Min(value = 1, message = "最小呼叫间隔不能小于1分钟")
    @Max(value = 43200, message = "最小呼叫间隔不能超过30天")
    private Integer minCallIntervalMinutes = 30;
    @NotBlank(message = "任务时区不能为空")
    private String scheduleTimezone = "Asia/Shanghai";
    private Boolean resultWritebackEnabled = true;
    @Size(max = 64, message = "接通标签不能超过64个字符")
    private String connectedTag;
    @Size(max = 64, message = "未接通标签不能超过64个字符")
    private String failedTag;
    @Valid
    @NotEmpty(message = "至少配置一个呼叫时段")
    private List<CallWindow> callWindows = new ArrayList<>();
    @Valid
    private List<RetryRule> retryRules = new ArrayList<>();
    private Integer version;

    @Data
    public static class CallWindow {
        @NotEmpty(message = "呼叫时段至少选择一个星期")
        private List<@Min(1) @Max(7) Integer> weekdays = new ArrayList<>();
        @NotNull(message = "呼叫时段开始时间不能为空")
        private LocalTime startTime;
        @NotNull(message = "呼叫时段结束时间不能为空")
        private LocalTime endTime;
        private Boolean enabled = true;
    }

    @Data
    public static class RetryRule {
        @NotBlank(message = "重试结果不能为空")
        @Pattern(regexp = "NO_ANSWER|BUSY|FAILED|OTHER", message = "重试结果不正确")
        private String resultCode;
        private Boolean retryEnabled = true;
        @Min(0)
        @Max(10)
        private Integer maxRetryCount = 1;
        @Min(1)
        @Max(10080)
        private Integer retryIntervalMinutes = 30;
    }
}
