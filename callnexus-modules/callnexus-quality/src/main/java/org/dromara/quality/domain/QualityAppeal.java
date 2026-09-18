package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_appeal")
public class QualityAppeal extends TenantEntity {
    @TableId private Long id;
    private Long taskId;
    private Long qualityResultId;
    private String appealReason;
    private String attachmentOssIds;
    private String status;
    private Long appellantId;
    private String appellantName;
    private LocalDateTime appealedAt;
    private LocalDateTime appealDeadline;
    private Long reviewerId;
    private String reviewerName;
    private LocalDateTime reviewedAt;
    private String reviewConclusion;
    private Long reviewResultId;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
