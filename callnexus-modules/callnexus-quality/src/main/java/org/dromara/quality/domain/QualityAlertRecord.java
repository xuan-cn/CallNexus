package org.dromara.quality.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_quality_alert_record")
public class QualityAlertRecord extends TenantEntity {
    @TableId private Long id;
    private Long ruleId;
    private Long snapshotId;
    private String ruleName;
    private String periodType;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String metricCode;
    private BigDecimal actualValue;
    private String compareOperator;
    private BigDecimal thresholdValue;
    private String scopeType;
    private Long scopeId;
    private String scopeName;
    private String status;
    private LocalDateTime triggeredAt;
    private Long acknowledgedBy;
    private String acknowledgedName;
    private LocalDateTime acknowledgedAt;
    private Long closedBy;
    private String closedName;
    private LocalDateTime closedAt;
    private String handleRemark;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
