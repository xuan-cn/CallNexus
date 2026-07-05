package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_knowledge_chunk")
public class AiKnowledgeChunk extends TenantEntity {
    @TableId private Long id;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long documentVersionId;
    private Integer chunkIndex;
    private String titlePath;
    private Integer pageNumber;
    private String sheetName;
    private Integer rowStart;
    private Integer rowEnd;
    private String textContent;
    private String textHash;
    private Integer tokenEstimate;
    private String qdrantPointId;
    private String indexState;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
