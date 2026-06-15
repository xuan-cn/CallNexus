package org.dromara.outbound.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class OutboundImportErrorExportVo {
    @ExcelProperty("行号")
    private Integer rowNumber;

    @ExcelProperty("客户姓名")
    private String customerName;

    @ExcelProperty("原始电话号码")
    private String originalPhone;

    @ExcelProperty("清洗后电话号码")
    private String normalizedPhone;

    @ExcelProperty("预检状态")
    private String status;

    @ExcelProperty("失败原因")
    private String errorMessage;
}
