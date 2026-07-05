package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_realtime_call_session")
public class AiRealtimeCallSession extends TenantEntity {
    @TableId
    private Long id;
    private String businessCallId;
    private String customerLegUuid;
    private Long nodeId;
    private Long flowId;
    private Long aiAgentId;
    private Long conversationId;
    private Long asrProviderId;
    private Long ttsProviderId;
    private String sessionState;
    private LocalDateTime connectedAt;
    private LocalDateTime lastActivityAt;
    private LocalDateTime endedAt;
    private String failureReason;
    @Version
    private Integer version;
}
