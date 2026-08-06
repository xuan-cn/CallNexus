package org.dromara.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_openapi_event")
public class OpenApiEvent extends TenantEntity {
    @TableId
    private Long id;
    private String eventType;
    private String businessCallId;
    private Long nodeId;
    private LocalDateTime occurredAt;
    private String payloadJson;
}
