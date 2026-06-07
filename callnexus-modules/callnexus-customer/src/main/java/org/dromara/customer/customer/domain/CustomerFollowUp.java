package org.dromara.customer.customer.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_customer_follow_up")
public class CustomerFollowUp extends TenantEntity {
    @TableId
    private Long id;
    private Long customerId;
    private String content;
    private String followUpByName;
    @TableLogic
    private Boolean deleted;
}
