package org.dromara.quality.domain.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityAppealPageQuery {
    private String keyword;
    private String status;
    private Long appellantId;
    private LocalDateTime appealedAtFrom;
    private LocalDateTime appealedAtTo;
}
