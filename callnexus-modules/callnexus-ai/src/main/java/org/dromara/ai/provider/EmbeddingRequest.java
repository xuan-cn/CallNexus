package org.dromara.ai.provider;

import org.dromara.ai.domain.AiModel;
import org.dromara.ai.domain.AiModelProvider;
import java.util.List;

public record EmbeddingRequest(AiModelProvider provider, AiModel model, List<String> inputs) {}
