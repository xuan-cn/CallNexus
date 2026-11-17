package org.dromara.ai.domain.response;

import java.util.List;

public record AiWorkflowTicketPreview(String templateId, String templateName, List<Field> fields) {
    public record Field(String code, String label, boolean required, boolean mapped, Object value) {}
}
