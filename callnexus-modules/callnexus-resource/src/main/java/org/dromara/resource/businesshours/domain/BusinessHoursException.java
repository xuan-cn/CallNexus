package org.dromara.resource.businesshours.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_business_hours_exception")
public class BusinessHoursException extends TenantEntity {
    @TableId
    private Long id;
    private Long planId;
    private LocalDate exceptionDate;
    private String exceptionType;
    private LocalTime startTime;
    private LocalTime endTime;
    private String description;
    @TableLogic
    private Boolean deleted;
}
