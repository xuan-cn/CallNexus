package org.dromara.ai.provider;

import java.math.BigDecimal;

public record AsrSegment(
    Integer sentenceIndex,
    Integer startMs,
    Integer endMs,
    String text,
    BigDecimal confidence,
    boolean finalResult
) {
}
