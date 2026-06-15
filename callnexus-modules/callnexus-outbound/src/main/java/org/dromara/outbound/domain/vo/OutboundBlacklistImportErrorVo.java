package org.dromara.outbound.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class OutboundBlacklistImportErrorVo {
    @ExcelProperty("行号") private Integer rowNumber;
    @ExcelProperty("原始号码") private String originalPhone;
    @ExcelProperty("标准化号码") private String normalizedPhone;
    @ExcelProperty("拦截原因") private String reason;
    @ExcelProperty("预检结果") private String status;
    @ExcelProperty("说明") private String errorMessage;
}
