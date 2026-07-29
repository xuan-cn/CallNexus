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
@TableName("cc_call_conference_member")
public class CallConferenceMember extends TenantEntity {
    @TableId
    private Long id;
    private Long conferenceId;
    private Long sessionId;
    private String businessCallId;
    private String legUuid;
    private String conferenceMemberId;
    private String memberRole;
    private Long agentId;
    private String extension;
    private String displayName;
    private String memberState;
    private Boolean muted;
    private LocalDateTime invitedAt;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
    private String failureReason;
    @Version
    private Integer version;
}
