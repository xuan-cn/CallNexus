package org.dromara.resource.businesshours.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_business_hours_period")
public class BusinessHoursPeriod extends TenantEntity {
    @TableId
    private Long id;
    private Long planId;
    private Integer dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer sortOrder;
    @TableLogic
    private Boolean deleted;
}
