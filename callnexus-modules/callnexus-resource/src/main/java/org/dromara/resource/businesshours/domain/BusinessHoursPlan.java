package org.dromara.resource.businesshours.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_business_hours_plan")
public class BusinessHoursPlan extends TenantEntity {
    @TableId
    private Long id;
    private String planCode;
    private String planName;
    private String timezone;
    private Boolean enabled;
    private String remark;
    @TableLogic
    private Boolean deleted;
}
