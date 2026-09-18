package org.dromara.quality.domain.request;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

@Data
public class QualityTaskPageQuery {
    private String keyword;
    private String status;
    private Long reviewerId;
    private Long agentId;
    private Long templateId;
    private Boolean mine;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime createdAtFrom;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime createdAtTo;
}
