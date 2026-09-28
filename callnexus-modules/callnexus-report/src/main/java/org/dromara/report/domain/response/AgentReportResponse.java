package org.dromara.report.domain.response;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@ExcelIgnoreUnannotated
public class AgentReportResponse {
    private Long agentId;
    @ExcelProperty("坐席编码")
    private String agentCode;
    @ExcelProperty("坐席名称")
    private String agentName;
    @ExcelProperty("分机")
    private String extension;
    @ExcelProperty("技能组")
    private String skillGroupNames;
    @ExcelProperty("处理量")
    private Long handledCount;
    @ExcelProperty("呼入接听")
    private Long inboundAnsweredCount;
    @ExcelProperty("呼出接听")
    private Long outboundAnsweredCount;
    @ExcelProperty("未接量")
    private Long missedCount;
    @ExcelProperty("总通话时长(秒)")
    private Long totalTalkSeconds;
    @ExcelProperty("平均通话时长(秒)")
    private Long averageTalkSeconds;
    @ExcelProperty("平均响应时长(秒)")
    private Long averageResponseSeconds;
    @ExcelProperty("在线时长(秒)")
    private Long onlineSeconds;
    @ExcelProperty("示忙时长(秒)")
    private Long notReadySeconds;
    @ExcelProperty("话后时长(秒)")
    private Long afterCallSeconds;
    @ExcelProperty("利用率(%)")
    private BigDecimal utilizationRate;
}
