package org.dromara.resource.businesshours.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_phone_business_hours_route")
public class PhoneBusinessHoursRoute extends TenantEntity {
    @TableId
    private Long id;
    private Long phoneNumberId;
    private Long planId;
    private String inHoursTargetType;
    private String inHoursTarget;
    private String outHoursTargetType;
    private String outHoursTarget;
    @TableLogic
    private Boolean deleted;
}
