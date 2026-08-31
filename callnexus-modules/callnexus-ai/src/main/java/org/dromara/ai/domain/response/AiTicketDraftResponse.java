package org.dromara.ai.domain.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class AiTicketDraftResponse {
    private Long id;
    private Long aiAgentId;
    private String sourceCallId;
    private Long customerId;
    private String callerNumber;
    private Long ticketTemplateId;
    private String status;
    private BigDecimal confidence;
    private String title;
    private String summary;
    private Map<String, Object> formData;
    private List<String> missingFields;
    private List<Map<String, Object>> evidence;
    private String conversation;
    private Long recordingOssId;
    private String recordingFileName;
    private String failureReason;
    private Long formalTicketId;
    private Integer version;
    private LocalDateTime createTime;
}
