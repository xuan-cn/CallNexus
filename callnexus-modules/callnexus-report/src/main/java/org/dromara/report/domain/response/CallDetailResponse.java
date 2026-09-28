package org.dromara.report.domain.response;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ExcelIgnoreUnannotated
public class CallDetailResponse {
    private Long id;
    @ExcelProperty("业务通话ID")
    private String businessCallId;
    @ExcelProperty("方向")
    private String direction;
    @ExcelProperty("客户号码")
    private String customerNumber;
    @ExcelProperty("坐席")
    private String agentName;
    @ExcelProperty("坐席分机")
    private String agentExtension;
    @ExcelProperty("队列")
    private String queueName;
    @ExcelProperty("通话状态")
    private String callStatus;
    @ExcelProperty("开始时间")
    private LocalDateTime startedAt;
    @ExcelProperty("接通时间")
    private LocalDateTime answeredAt;
    @ExcelProperty("结束时间")
    private LocalDateTime endedAt;
    @ExcelProperty("等待时长(秒)")
    private Long waitSeconds;
    @ExcelProperty("通话时长(秒)")
    private Long talkSeconds;
    @ExcelProperty("挂断原因")
    private String hangupCause;
}
