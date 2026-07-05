package org.dromara.ai.service;

import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import java.util.*;

public interface AiModelConfigurationService {
    List<AiModelProviderResponse> providers();
    Long createProvider(AiModelProviderRequest request);
    void updateProvider(Long id, AiModelProviderRequest request);
    void deleteProvider(Long id);
    Map<String, Object> testProvider(Long id);
    List<AiModelResponse> models(String capability);
    Long createModel(AiModelRequest request);
    void updateModel(Long id, AiModelRequest request);
    void deleteModel(Long id);
    Map<String, Object> testModel(Long id);
}
