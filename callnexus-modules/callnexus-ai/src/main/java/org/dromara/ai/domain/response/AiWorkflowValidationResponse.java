package org.dromara.ai.domain.response;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiWorkflowValidationResponse {
    private boolean valid;
    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<Issue> issues = new ArrayList<>();
    public record Issue(String message, String nodeId, String edgeId, String field, String condition) { }
}
