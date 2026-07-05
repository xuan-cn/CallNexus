package org.dromara.ai.vector;

import java.util.Map;

public record VectorSearchHit(String id, double score, Map<String, Object> payload) {}
