package org.dromara.ai.domain.response;

import lombok.Data;

@Data
public class AiAgentAssistCustomerSummaryResponse {
    private Long templateId;
    private String fieldCode;
    private String summary;
}
