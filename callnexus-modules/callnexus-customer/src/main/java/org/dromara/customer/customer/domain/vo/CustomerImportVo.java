package org.dromara.customer.customer.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class CustomerImportVo {
    @ExcelProperty("客户姓名")
    private String customerName;

    @ExcelProperty("主号码")
    private String primaryPhone;

    @ExcelProperty("其他号码")
    private String additionalPhones;
}
