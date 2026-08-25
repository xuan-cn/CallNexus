package org.dromara.ai.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class AiAgentAssistSuggestionResponse {
    private Long id;
    private Long transcriptSegmentId;
    private String customerText;
    private String suggestedReply;
    private String sourceType;
    private String status;
    private String failureReason;
    private Long processingMs;
    private Date createTime;
}
