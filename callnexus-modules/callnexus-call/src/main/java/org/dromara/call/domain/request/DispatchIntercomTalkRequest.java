package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DispatchIntercomTalkRequest {
    @NotNull(message = "对讲发言状态不能为空")
    private Boolean talking;
}
