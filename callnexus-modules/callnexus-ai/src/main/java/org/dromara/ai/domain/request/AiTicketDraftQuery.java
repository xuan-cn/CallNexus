package org.dromara.ai.domain.request;

import lombok.Data;

@Data
public class AiTicketDraftQuery {
    private String status;
    private String callerNumber;
    private Long aiAgentId;
}
