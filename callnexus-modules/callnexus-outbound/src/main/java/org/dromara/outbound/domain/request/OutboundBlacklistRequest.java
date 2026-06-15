package org.dromara.outbound.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboundBlacklistRequest {
    @NotBlank(message = "黑名单范围不能为空")
    private String scopeType;
    private Long taskId;
    @NotBlank(message = "电话号码不能为空")
    private String phoneNumber;
    private String reason;
    @NotBlank(message = "黑名单来源不能为空")
    private String source;
    private LocalDateTime effectiveAt;
    private LocalDateTime expiresAt;
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}
