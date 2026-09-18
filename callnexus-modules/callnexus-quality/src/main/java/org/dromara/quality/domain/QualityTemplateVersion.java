package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_template_version")
public class QualityTemplateVersion extends TenantEntity {
    @TableId private Long id;
    private Long templateId;
    private Integer versionNo;
    private String status;
    private String templateSnapshotJson;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
