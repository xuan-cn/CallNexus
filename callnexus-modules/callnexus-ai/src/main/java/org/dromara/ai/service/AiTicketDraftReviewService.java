package org.dromara.ai.service;

import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.AiTicketDraftResponse;
import org.dromara.ai.domain.response.AiTicketDraftBatchReviewResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

public interface AiTicketDraftReviewService {
    TableDataInfo<AiTicketDraftResponse> page(AiTicketDraftQuery query, PageQuery pageQuery);
    AiTicketDraftResponse get(Long id);
    void update(Long id, AiTicketDraftUpdateRequest request);
    Long approve(Long id, AiTicketDraftReviewRequest request);
    void reject(Long id, AiTicketDraftReviewRequest request);
    AiTicketDraftBatchReviewResponse batchApprove(AiTicketDraftBatchReviewRequest request);
    AiTicketDraftBatchReviewResponse batchReject(AiTicketDraftBatchReviewRequest request);
    void regenerate(Long id, AiTicketDraftReviewRequest request);
}
