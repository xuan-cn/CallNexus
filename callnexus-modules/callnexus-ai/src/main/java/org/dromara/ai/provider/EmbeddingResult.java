package org.dromara.ai.provider;

import java.util.List;

public record EmbeddingResult(List<List<Double>> vectors, Integer inputTokens) {
    public int dimension() {
        return vectors == null || vectors.isEmpty() ? 0 : vectors.get(0).size();
    }
}
