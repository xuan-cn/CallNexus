package org.dromara.ai.domain.response;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class AiCitationResponse {
    private Long id;
    private String sourceType;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long faqId;
    private String sourceName;
    private String sourceLocation;
    private String quotedContent;
    private BigDecimal score;
}
