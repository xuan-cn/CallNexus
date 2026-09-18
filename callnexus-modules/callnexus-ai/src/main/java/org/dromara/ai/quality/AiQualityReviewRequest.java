package org.dromara.ai.quality;

import java.util.List;

public record AiQualityReviewRequest(
    Integer totalScore,
    Integer qualifiedScore,
    List<Item> items,
    List<Segment> segments
) {
    public record Item(String itemCode, String itemName, String itemType, Integer scoreValue,
                       Boolean fatalFlag, Boolean allowNotApplicable, String ruleDescription,
                       String promptHint) {}

    public record Segment(Long segmentId, String speaker, Integer startMs, Integer endMs, String text) {}
}
