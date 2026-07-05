package org.dromara.ai.vector;

import java.util.List;
import java.util.Map;

public interface VectorStore {
    void ensureCollection(String collection, int dimension);
    void upsert(String collection, List<VectorPoint> points);
    List<VectorSearchHit> search(String collection, List<Double> vector, Map<String, Object> filters, int limit);
    void setPayload(String collection, List<String> pointIds, Map<String, Object> payload);
    void deleteByFilter(String collection, Map<String, Object> filters);
}
