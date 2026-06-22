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
@TableName("cc_call_bridge")
public class CallBridge extends TenantEntity {
    @TableId
    private Long id;
    private Long sessionId;
    private String businessCallId;
    private Long nodeId;
    private String leftLegUuid;
    private String rightLegUuid;
    private String bridgeType;
    private String bridgeState;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    @Version
    private Integer version;
}
