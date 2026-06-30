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
@TableName("cc_dispatch_call_target")
public class DispatchCallTarget extends TenantEntity {
    @TableId
    private Long id;
    private Long taskId;
    private Long nodeId;
    private Long sipAccountId;
    private String targetExtension;
    private String targetLegUuid;
    private String targetState;
    private Boolean answered;
    private String failureReason;
    private LocalDateTime submittedAt;
    private LocalDateTime ringingAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    @Version
    private Integer version;
}
