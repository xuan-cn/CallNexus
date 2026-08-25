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
@TableName("cc_agent_assist_session")
public class AiAgentAssistSession extends TenantEntity {
    @TableId
    private Long id;
    private Long callSessionId;
    private String businessCallId;
    private Long agentId;
    private Long skillGroupId;
    private Long assistAgentId;
    private Long conversationId;
    private String sessionState;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
