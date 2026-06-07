package org.dromara.customer.customer.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_customer")
public class Customer extends TenantEntity {
    @TableId
    private Long id;
    private String primaryPhone;
    private String customerName;
    private Long templateId;
    private String sourceCallId;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
