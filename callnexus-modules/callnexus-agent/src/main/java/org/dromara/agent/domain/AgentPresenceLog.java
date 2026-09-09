package org.dromara.agent.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_agent_presence_log")
public class AgentPresenceLog extends TenantEntity {
    @TableId
    private Long id;
    private Long agentId;
    private String previousStatus;
    private String status;
    private String source;
    private String businessCallId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationSeconds;
}
