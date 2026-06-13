package org.dromara.call.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_call_event")
public class CallEvent extends TenantEntity {
    @TableId
    private Long id;
    private Long sessionId;
    private String channelUuid;
    private String relatedChannelUuid;
    private String eventType;
    private String fromTarget;
    private String toTarget;
    private LocalDateTime occurredAt;
    private String metadataJson;
}
