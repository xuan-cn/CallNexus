package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BindDispatchOperatorExtensionRequest {
    @NotNull(message = "调度分机不能为空")
    private Long sipAccountId;
}
