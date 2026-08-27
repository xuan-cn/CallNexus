package org.dromara.ai.domain.response;

import java.util.List;

public record AiFaqLearningBatchResponse(int total, int success, int failed, List<Item> items) {
    public record Item(Long candidateId, boolean success, String message) {
    }
}
