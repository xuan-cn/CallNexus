package org.dromara.call.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IvrTransferRequest {
    @NotNull(message = "请选择 IVR 流程")
    private Long flowId;
}
