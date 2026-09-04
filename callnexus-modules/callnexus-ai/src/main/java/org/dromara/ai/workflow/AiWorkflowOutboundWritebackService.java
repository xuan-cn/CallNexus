package org.dromara.ai.workflow;

public interface AiWorkflowOutboundWritebackService {

    void writeBack(Long taskId, Long memberId, String businessCallId, String resultCode);
}
