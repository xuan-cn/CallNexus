package org.dromara.ai.service;

public interface AiKnowledgeTaskDispatcher {
    void dispatchAfterCommit(Long taskId, String tenantId);
    void dispatch(Long taskId, String tenantId);
}
