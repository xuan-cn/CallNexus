package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_knowledge_document_version")
public class AiKnowledgeDocumentVersion extends TenantEntity {
    @TableId private Long id;
    private Long knowledgeBaseId;
    private Long documentId;
    private Integer versionNo;
    private Long ossId;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private String checksum;
    private String parseStatus;
    private String indexStatus;
    private Integer pageCount;
    private Integer characterCount;
    private Integer chunkCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
