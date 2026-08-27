package org.dromara.ai.service;

import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

public interface AiFaqLearningApplicationService {
    void captureFallbackAsync(String tenantId, AiAgent agent, AiMessage userMessage, AiMessage assistantMessage,
                              Double bestFaqScore, Double bestDocumentScore, String sourceChannel);
    TableDataInfo<AiFaqLearningCandidateResponse> page(AiFaqLearningQuery query, PageQuery pageQuery);
    AiFaqLearningCandidateResponse detail(Long id);
    AiFaqLearningStatisticsResponse statistics();
    void approve(Long id, AiFaqLearningApproveRequest request);
    void merge(Long id, AiFaqLearningMergeRequest request);
    void reject(Long id, AiFaqLearningRejectRequest request);
    void reopen(Long id);
    AiFaqLearningBatchResponse batchApprove(AiFaqLearningBatchRequest request);
    AiFaqLearningBatchResponse batchMerge(AiFaqLearningBatchRequest request);
    AiFaqLearningBatchResponse batchReject(AiFaqLearningBatchRequest request);
}
