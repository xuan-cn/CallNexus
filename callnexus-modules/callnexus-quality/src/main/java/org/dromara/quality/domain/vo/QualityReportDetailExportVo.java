package org.dromara.quality.domain.vo;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityReportDetailExportVo {
    @ExcelProperty("质检时间") private LocalDateTime publishedAt;
    @ExcelProperty("任务编号") private String taskCode;
    @ExcelProperty("业务通话ID") private String businessCallId;
    @ExcelProperty("坐席") private String agentName;
    @ExcelProperty("坐席分机") private String agentExtension;
    @ExcelProperty("技能组") private String skillGroupName;
    @ExcelProperty("队列") private String queueName;
    @ExcelProperty("评分模板") private String templateName;
    @ExcelProperty("模板版本") private Integer templateVersionNo;
    @ExcelProperty("质检员") private String reviewerName;
    @ExcelProperty("得分") private Integer totalScore;
    @ExcelProperty("质检结果") private String qualified;
    @ExcelProperty("致命项") private String fatalFlag;
    @ExcelProperty("申诉状态") private String appealStatus;
    @ExcelProperty("质检总结") private String summary;
}
