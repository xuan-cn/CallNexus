package org.dromara.ai.workflow.handler;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class PlayMediaNodeHandler implements AiWorkflowNodeHandler {

    @Override
    public Set<String> nodeTypes() {
        return Set.of("PLAY_MEDIA");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String mediaId = context.node().path("config").path("mediaId").asText("").trim();
        if (!mediaId.matches("^[0-9]{1,20}$")) {
            throw new ServiceException("播放声音媒体节点未选择有效媒体");
        }
        return new AiWorkflowNodeResult("PLAY_MEDIA", null, mediaId, "MEDIA", Map.of());
    }
}
