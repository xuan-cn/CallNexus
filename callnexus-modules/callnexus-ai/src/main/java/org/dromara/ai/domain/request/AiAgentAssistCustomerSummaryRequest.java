package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AiAgentAssistCustomerSummaryRequest {
    @NotNull(message = "客户不能为空")
    private Long customerId;
}
