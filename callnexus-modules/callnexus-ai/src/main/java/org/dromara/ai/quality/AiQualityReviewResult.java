package org.dromara.ai.quality;

import java.math.BigDecimal;
import java.util.List;

public record AiQualityReviewResult(
    Long modelId,
    String rawResponse,
    String summary,
    String improvementSuggestion,
    List<Item> items
) {
    public record Item(String itemCode, String result, BigDecimal confidence, String reason,
                       List<Evidence> evidence) {}

    public record Evidence(Long segmentId, Integer startMs, Integer endMs, String quote) {}
}
