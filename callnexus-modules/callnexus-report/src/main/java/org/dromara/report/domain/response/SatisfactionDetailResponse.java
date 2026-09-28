package org.dromara.report.domain.response;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ExcelIgnoreUnannotated
public class SatisfactionDetailResponse {
    private Long id;
    private Long sessionId;
    @ExcelProperty("业务通话ID")
    private String businessCallId;
    @ExcelProperty("评价时间")
    private LocalDateTime evaluatedAt;
    @ExcelProperty("通话开始时间")
    private LocalDateTime callStartedAt;
    private Long queueId;
    @ExcelProperty("队列")
    private String queueName;
    @ExcelProperty("技能组")
    private String skillGroupName;
    private Long agentId;
    @ExcelProperty("坐席")
    private String agentName;
    @ExcelProperty("坐席分机")
    private String agentExtension;
    @ExcelProperty("客户号码")
    private String customerNumber;
    @ExcelProperty("评分")
    private Integer score;
    @ExcelProperty("评价按键")
    private String digit;
    @ExcelProperty("评价状态")
    private String status;
    @ExcelProperty("通话时长(秒)")
    private Long talkSeconds;
}
