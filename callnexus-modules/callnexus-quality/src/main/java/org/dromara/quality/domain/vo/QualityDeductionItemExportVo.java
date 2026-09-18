package org.dromara.quality.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class QualityDeductionItemExportVo {
    @ExcelProperty("评分项编码") private String itemCode;
    @ExcelProperty("评分项") private String itemName;
    @ExcelProperty("命中次数") private Long occurrenceCount;
    @ExcelProperty("影响通话数") private Long affectedCallCount;
    @ExcelProperty("累计扣分") private Long totalDeduction;
}
