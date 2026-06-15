package org.dromara.outbound.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class OutboundBlacklistImportVo {
    @ExcelProperty("电话号码")
    private String phoneNumber;
    @ExcelProperty("拦截原因")
    private String reason;
}
