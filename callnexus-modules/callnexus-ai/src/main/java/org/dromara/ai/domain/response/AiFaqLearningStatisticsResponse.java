package org.dromara.ai.domain.response;

public record AiFaqLearningStatisticsResponse(long pending, long approved, long merged, long rejected) {
}
