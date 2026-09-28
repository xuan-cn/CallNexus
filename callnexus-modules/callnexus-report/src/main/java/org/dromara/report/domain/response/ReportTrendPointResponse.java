package org.dromara.report.domain.response;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
@ExcelIgnoreUnannotated
public class ReportTrendPointResponse {
    @ExcelProperty("统计时间")
    private String bucket;
    @ExcelProperty("通话总量")
    private Long totalCalls;
    @ExcelProperty("呼入量")
    private Long inboundCalls;
    @ExcelProperty("呼出量")
    private Long outboundCalls;
    @ExcelProperty("接通量")
    private Long answeredCalls;
}
