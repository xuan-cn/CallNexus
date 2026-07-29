package org.dromara.customer.customer.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_customer_phone")
public class CustomerPhone extends TenantEntity {
    @TableId
    private Long id;
    private Long customerId;
    private String phoneNumber;
    private String normalizedPhone;
    private String phoneType;
    private String phoneLabel;
    private Boolean primaryFlag;
    private Boolean enabled;
    private Integer sortOrder;
    @Version
    private Integer version;
}
