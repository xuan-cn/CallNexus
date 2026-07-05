package org.dromara.ai.vector;

import java.util.List;
import java.util.Map;

public record VectorPoint(String id, List<Double> vector, Map<String, Object> payload) {}
