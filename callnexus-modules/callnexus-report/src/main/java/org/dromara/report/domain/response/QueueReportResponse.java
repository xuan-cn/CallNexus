package org.dromara.report.domain.response;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@ExcelIgnoreUnannotated
public class QueueReportResponse {
    private Long queueId;
    @ExcelProperty("队列编码")
    private String queueCode;
    @ExcelProperty("队列名称")
    private String queueName;
    @ExcelProperty("技能组")
    private String skillGroupName;
    @ExcelProperty("进入量")
    private Long enteredCount;
    @ExcelProperty("接听量")
    private Long answeredCount;
    @ExcelProperty("放弃量")
    private Long abandonedCount;
    @ExcelProperty("超时量")
    private Long timeoutCount;
    @ExcelProperty("接通率(%)")
    private BigDecimal answerRate;
    @ExcelProperty("放弃率(%)")
    private BigDecimal abandonRate;
    @ExcelProperty("平均等待(秒)")
    private Long averageWaitSeconds;
    @ExcelProperty("最大等待(秒)")
    private Long maximumWaitSeconds;
    @ExcelProperty("20秒服务水平(%)")
    private BigDecimal serviceLevel;
}
