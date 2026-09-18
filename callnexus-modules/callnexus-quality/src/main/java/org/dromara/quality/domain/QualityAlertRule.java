package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_alert_rule")
public class QualityAlertRule extends TenantEntity {
    @TableId private Long id;
    private String ruleName;
    private String periodType;
    private String metricCode;
    private String compareOperator;
    private BigDecimal thresholdValue;
    private String scopeType;
    private Long scopeId;
    private String scopeName;
    private Boolean enabled;
    private String remark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
