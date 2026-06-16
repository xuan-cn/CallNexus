package org.dromara.outbound.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboundAttemptExportVo {
    @ExcelProperty("任务名称") private String taskName;
    @ExcelProperty("客户名称") private String customerName;
    @ExcelProperty("电话号码") private String phoneNumber;
    @ExcelProperty("坐席编码") private String agentCode;
    @ExcelProperty("坐席名称") private String agentName;
    @ExcelProperty("拨打次数") private Integer attemptNo;
    @ExcelProperty("拨打状态") private String status;
    @ExcelProperty("业务结果") private String result;
    @ExcelProperty("结果备注") private String resultRemark;
    @ExcelProperty("系统建议") private String suggestedResult;
    @ExcelProperty("开始时间") private LocalDateTime startedAt;
    @ExcelProperty("接听时间") private LocalDateTime answeredAt;
    @ExcelProperty("结束时间") private LocalDateTime endedAt;
    @ExcelProperty("总时长（秒）") private Integer durationSeconds;
    @ExcelProperty("接通时长（秒）") private Integer billableSeconds;
    @ExcelProperty("挂断原因") private String hangupCause;
    @ExcelProperty("通话标识") private String businessCallId;
}
