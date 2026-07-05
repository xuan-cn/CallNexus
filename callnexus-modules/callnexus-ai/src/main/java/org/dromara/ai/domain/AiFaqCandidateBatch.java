package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_faq_candidate_batch")
public class AiFaqCandidateBatch extends TenantEntity {
    @TableId private Long id;
    private Long knowledgeBaseId;
    private String sourceType;
    private Long documentId;
    private Long documentVersionId;
    private Long chatModelId;
    private String sourceFileName;
    private String status;
    private Integer totalCount;
    private Integer validCount;
    private Integer invalidCount;
    private Integer confirmedCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
