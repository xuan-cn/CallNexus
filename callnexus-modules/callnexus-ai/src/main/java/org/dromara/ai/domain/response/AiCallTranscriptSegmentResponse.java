package org.dromara.ai.domain.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AiCallTranscriptSegmentResponse {
    private Long id;
    private String speaker;
    private Integer sentenceIndex;
    private Integer startMs;
    private Integer endMs;
    private String textContent;
    private Boolean finalResult;
    private BigDecimal confidence;
}
