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
@TableName("cc_call_record")
public class CallRecord extends TenantEntity {
    @TableId
    private Long id;
    private Long sessionId;
    private Long nodeId;
    private String channelUuid;
    private String callUuid;
    private String direction;
    private String callerNumber;
    private String calledNumber;
    private Long agentId;
    private String agentExtension;
    private String callStatus;
    private LocalDateTime startedAt;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
    @Version
    private Integer version;
}
