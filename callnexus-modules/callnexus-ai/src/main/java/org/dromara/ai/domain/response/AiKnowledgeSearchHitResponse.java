package org.dromara.ai.domain.response;

import lombok.Data;
import java.util.Map;

@Data
public class AiKnowledgeSearchHitResponse {
    private String sourceType;
    private Double score;
    private String title;
    private String content;
    private String location;
    private Map<String, Object> metadata;
}
