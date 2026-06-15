package org.dromara.outbound.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class OutboundMemberImportVo {
    @ExcelProperty("客户姓名")
    private String customerName;

    @ExcelProperty("电话号码")
    private String phoneNumber;
}
