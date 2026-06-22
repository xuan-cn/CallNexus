package org.dromara.call.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_agent_call_session")
public class AgentCallSession extends TenantEntity {
    @TableId
    private Long id;
    private Long sessionId;
    private String businessCallId;
    private Long nodeId;
    private Long agentId;
    private String agentExtension;
    private String agentLegUuid;
    private String role;
    private String sessionState;
    private Boolean visible;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
    @Version
    private Integer version;
}
