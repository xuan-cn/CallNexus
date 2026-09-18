package org.dromara.report.domain.response;

import lombok.Data;

@Data
public class SatisfactionScoreDistributionResponse {
    private Integer score;
    private Long count;
}
