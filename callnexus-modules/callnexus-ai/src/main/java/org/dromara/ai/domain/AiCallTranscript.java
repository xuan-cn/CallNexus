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
@TableName("cc_ai_call_transcript")
public class AiCallTranscript extends TenantEntity {
    @TableId
    private Long id;
    private Long callSessionId;
    private String businessCallId;
    private Long providerId;
    private String providerType;
    private Long inputMediaId;
    private Long recordingOssId;
    private String status;
    private String fullText;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
