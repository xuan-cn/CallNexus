package org.dromara.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_openapi_event_delivery")
public class OpenApiEventDelivery extends TenantEntity {
    @TableId
    private Long id;
    private Long eventId;
    private Long applicationId;
    private String deliveryType;
    private String deliveryStatus;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private Integer lastHttpStatus;
    private String lastResponse;
    private String failureReason;
    private LocalDateTime deliveredAt;
    private LocalDateTime processingStartedAt;
}
