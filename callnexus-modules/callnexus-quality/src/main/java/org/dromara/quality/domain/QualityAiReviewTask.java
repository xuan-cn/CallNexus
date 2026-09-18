package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_ai_review_task")
public class QualityAiReviewTask extends TenantEntity {
    @TableId private Long id;
    private Long qualityTaskId;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private Long modelId;
    private String requestJson;
    private String responseJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
