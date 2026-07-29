package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CallConferenceMuteRequest {
    @NotNull(message = "静音状态不能为空")
    private Boolean muted;
}
