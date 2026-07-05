package org.dromara.ai.provider;

import org.dromara.ai.domain.AiModel;
import org.dromara.ai.domain.AiModelProvider;
import java.math.BigDecimal;
import java.util.List;

public record ChatRequest(AiModelProvider provider, AiModel model, List<ChatMessage> messages,
                          BigDecimal temperature, Integer maxTokens) {}
