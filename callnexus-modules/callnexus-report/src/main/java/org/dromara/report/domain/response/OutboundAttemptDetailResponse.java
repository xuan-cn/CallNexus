package org.dromara.report.domain.response;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ExcelIgnoreUnannotated
public class OutboundAttemptDetailResponse {
    private Long id;
    private Long taskId;
    @ExcelProperty("任务名称")
    private String taskName;
    @ExcelProperty("外呼类型")
    private String taskType;
    @ExcelProperty("客户名称")
    private String customerName;
    @ExcelProperty("客户号码")
    private String phoneNumber;
    @ExcelProperty("坐席")
    private String agentName;
    @ExcelProperty("拨打次数")
    private Integer attemptNo;
    @ExcelProperty("拨打状态")
    private String status;
    @ExcelProperty("业务结果")
    private String resultCode;
    @ExcelProperty("开始时间")
    private LocalDateTime startedAt;
    @ExcelProperty("接通时间")
    private LocalDateTime answeredAt;
    @ExcelProperty("结束时间")
    private LocalDateTime endedAt;
    @ExcelProperty("呼叫时长(秒)")
    private Integer durationSeconds;
    @ExcelProperty("接通时长(秒)")
    private Integer billableSeconds;
    @ExcelProperty("挂断原因")
    private String hangupCause;
    @ExcelProperty("失败分类")
    private String failureCategory;
}
