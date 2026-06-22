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
@TableName("cc_call_leg")
public class CallLeg extends TenantEntity {
    @TableId
    private Long id;
    private Long sessionId;
    private String businessCallId;
    private Long nodeId;
    private String legUuid;
    private String legRole;
    private Long agentId;
    private String agentExtension;
    private String callerNumber;
    private String calledNumber;
    private String legState;
    private Boolean active;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime bridgedAt;
    private LocalDateTime heldAt;
    private LocalDateTime parkedAt;
    private LocalDateTime endedAt;
    private String hangupCause;
    @Version
    private Integer version;
}
