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
@TableName("cc_ai_speech_task")
public class AiSpeechTask extends TenantEntity {
    @TableId
    private Long id;
    private String taskType;
    private String businessType;
    private Long businessId;
    private Long providerId;
    private String providerType;
    private String voiceName;
    private String textContent;
    private Long inputMediaId;
    private Long outputMediaId;
    private String status;
    private Integer retryCount;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
