package org.dromara.report.domain.response;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@ExcelIgnoreUnannotated
public class CallAgentStatisticResponse {
    @ExcelProperty("统计时段")
    private String bucket;
    private Long agentId;
    @ExcelProperty("坐席")
    private String agentName;
    @ExcelProperty("分机")
    private String agentExtension;
    @ExcelProperty("总话务量")
    private Long totalCalls;
    @ExcelProperty("总接通量")
    private Long answeredCalls;
    @ExcelProperty("总接通率(%)")
    private BigDecimal answerRate;
    @ExcelProperty("呼出总量")
    private Long outboundCalls;
    @ExcelProperty("呼出接通量")
    private Long outboundAnsweredCalls;
    @ExcelProperty("呼出接通率(%)")
    private BigDecimal outboundAnswerRate;
    @ExcelProperty("呼出总时长(秒)")
    private Long outboundTalkSeconds;
    @ExcelProperty("呼出平均时长(秒)")
    private Long outboundAverageTalkSeconds;
    @ExcelProperty("呼入总量")
    private Long inboundCalls;
    @ExcelProperty("呼入接通量")
    private Long inboundAnsweredCalls;
    @ExcelProperty("呼入接通率(%)")
    private BigDecimal inboundAnswerRate;
    @ExcelProperty("呼入总时长(秒)")
    private Long inboundTalkSeconds;
    @ExcelProperty("呼入平均时长(秒)")
    private Long inboundAverageTalkSeconds;
}
