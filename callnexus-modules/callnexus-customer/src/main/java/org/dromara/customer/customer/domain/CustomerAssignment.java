package org.dromara.customer.customer.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

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
    @TableLogic
    private Boolean deleted;
}
