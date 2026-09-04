package org.dromara.ai.workflow;

import java.util.List;
import java.util.Map;

public interface AiWorkflowSlotExtractionService {
    Map<String, Object> extract(Long aiAgentId, String sourceText, List<Target> targets);

    record Target(String key, String label, String type) {
    }
}
