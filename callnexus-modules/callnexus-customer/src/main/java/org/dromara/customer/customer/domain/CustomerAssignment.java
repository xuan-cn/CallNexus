package org.dromara.customer.customer.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_customer_assignment")
public class CustomerAssignment extends TenantEntity {
    @TableId
    private Long id;
    private Long customerId;
    private String customerType;
    private String sourceChannel;
    private String tags;
    private Long skillGroupId;
    private Long agentId;
    private String assignmentSource;
    private Long importBatchId;
    private String remark;
    private Boolean enabled;
    private String dialStatus;
    private Integer attemptCount;
    private LocalDateTime lastCalledAt;
    private String lastResultCode;
    private String lastResultRemark;
    private LocalDateTime nextFollowUpAt;
    private LocalDateTime completedAt;
    @TableLogic
    private Boolean deleted;
}
