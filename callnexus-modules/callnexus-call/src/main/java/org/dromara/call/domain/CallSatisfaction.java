package org.dromara.call.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_call_satisfaction")
public class CallSatisfaction extends TenantEntity {
    @TableId
    private Long id;
    private Long sessionId;
    private String businessCallId;
    private Long queueId;
    private String customerLegUuid;
    private Integer score;
    private String digit;
    private String status;
    private LocalDateTime submittedAt;
}
