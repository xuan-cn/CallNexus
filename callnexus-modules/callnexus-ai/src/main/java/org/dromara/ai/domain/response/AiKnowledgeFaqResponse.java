package org.dromara.ai.domain.response;

import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
public class AiKnowledgeFaqResponse {
    private Long id;
    private Long knowledgeBaseId;
    private String faqCode;
    private String faqName;
    private Long currentVersionId;
    private Integer versionNo;
    private String standardQuestion;
    private String standardAnswer;
    private List<String> aliases;
    private String status;
    private String indexStatus;
    private String answerMode;
    private String failureReason;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
