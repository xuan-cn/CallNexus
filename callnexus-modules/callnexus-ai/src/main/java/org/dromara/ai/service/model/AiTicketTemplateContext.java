package org.dromara.ai.service.model;

import java.util.List;
import java.util.Map;

public record AiTicketTemplateContext(
    Long customerId,
    String customerProfile,
    String templateName,
    List<Field> fields
) {
    public record Field(String code, String name, String type, boolean required,
                        Object defaultValue, List<String> options) {
    }

    public Map<String, Field> fieldMap() {
        return fields.stream().collect(java.util.stream.Collectors.toMap(Field::code, value -> value));
    }
}
