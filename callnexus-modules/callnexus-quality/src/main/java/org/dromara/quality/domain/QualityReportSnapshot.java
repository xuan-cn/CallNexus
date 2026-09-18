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
@TableName("cc_quality_report_snapshot")
public class QualityReportSnapshot extends TenantEntity {
    @TableId private Long id;
    private String periodType;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String scopeType;
    private Long scopeId;
    private String scopeName;
    private Long eligibleCallCount;
    private Long reviewedCallCount;
    private BigDecimal coverageRate;
    private Long resultCount;
    private BigDecimal averageScore;
    private Long qualifiedCount;
    private BigDecimal qualifiedRate;
    private Long fatalCount;
    private BigDecimal fatalRate;
    private BigDecimal aiAdoptionRate;
    private BigDecimal appealRate;
    private LocalDateTime generatedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
