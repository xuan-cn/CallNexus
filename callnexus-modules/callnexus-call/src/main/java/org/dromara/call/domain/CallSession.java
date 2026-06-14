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
@TableName("cc_call_session")
public class CallSession extends TenantEntity {
    @TableId
    private Long id;
    private String businessCallId;
    private Long nodeId;
    private String direction;
    private String callerNumber;
    private String calledNumber;
    private Long agentId;
    private String agentExtension;
    private Long handlingQueueId;
    private String handlingQueueName;
    private Long customerId;
    private Long ticketId;
    private Long outboundTaskId;
    private Long outboundMemberId;
    private String callStatus;
    private LocalDateTime startedAt;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private Integer billableSeconds;
    private String hangupCause;
    private Long recordingOssId;
    private Long recordingMediaId;
    private String recordingFileName;
    private String recordingStatus;
    @Version
    private Integer version;
}
