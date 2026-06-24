package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_generated_media")
public class AiGeneratedMedia extends TenantEntity {
    @TableId
    private Long id;
    private String businessType;
    private Long businessId;
    private Long mediaId;
    private Long taskId;
    private String textHash;
    private String generationStatus;
    private LocalDateTime generatedAt;
    private String failureReason;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
