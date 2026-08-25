package org.dromara.outbound.domain.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AutoOutboundSourceRequest {
    @NotNull(message = "请选择客户资料导入任务")
    private Long importTaskId;
    private Long importBatchId;
    private String customerType;
    private String tags;
    private Long skillGroupId;
    private Long agentId;
    @Pattern(regexp = "^(ALL|ASSIGNED|UNASSIGNED)$", message = "客户归属状态不正确")
    private String assignmentState = "ALL";
    @Pattern(regexp = "^(PRIMARY_ONLY|PRIMARY_OR_FIRST|LABEL_OR_PRIMARY)$", message = "号码选择策略不正确")
    private String phoneStrategy = "PRIMARY_OR_FIRST";
    private String phoneLabel;
    private Boolean enabled = true;
}
