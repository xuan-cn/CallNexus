package org.dromara.ai.domain.response;

import java.util.List;

public record AiTicketDraftBatchReviewResponse(int total, int success, int failed, List<Item> items) {
    public record Item(Long draftId, boolean success, Long ticketId, String message) {
    }
}
