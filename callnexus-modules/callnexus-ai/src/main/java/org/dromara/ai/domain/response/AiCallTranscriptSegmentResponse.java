package org.dromara.ai.domain.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiCallTranscriptSegmentResponse {
    private Long id;
    private String speaker;
    private String sourceType;
    private String legUuid;
    private Long agentId;
    private Integer sentenceIndex;
    private Integer startMs;
    private Integer endMs;
    private LocalDateTime messageTime;
    private String textContent;
    private Boolean finalResult;
    private BigDecimal confidence;
}
