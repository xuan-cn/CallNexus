package org.dromara.ai.domain.response;

import lombok.Data;
import java.util.Date;

@Data
public class AiKnowledgeDocumentResponse {
    private Long id;
    private Long knowledgeBaseId;
    private String documentName;
    private String documentType;
    private Long currentVersionId;
    private Integer versionNo;
    private String status;
    private String parseStatus;
    private String indexStatus;
    private Integer chunkCount;
    private String failureReason;
    private Boolean enabled;
    private Date createTime;
}
