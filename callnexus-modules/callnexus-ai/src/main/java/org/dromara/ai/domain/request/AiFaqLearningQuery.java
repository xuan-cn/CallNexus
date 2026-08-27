package org.dromara.ai.domain.request;

import lombok.Data;

@Data
public class AiFaqLearningQuery {
    private String status;
    private Long knowledgeBaseId;
    private Long agentId;
    private String keyword;
}
