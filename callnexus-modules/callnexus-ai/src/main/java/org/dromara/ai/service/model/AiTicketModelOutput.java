package org.dromara.ai.service.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AiTicketModelOutput(boolean shouldCreate, String title, String summary,
                                  Map<String, Object> formData, List<String> missingFields,
                                  List<Map<String, Object>> evidence, BigDecimal confidence) {
}
