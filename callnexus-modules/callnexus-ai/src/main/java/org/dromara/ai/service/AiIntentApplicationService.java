package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.request.AiIntentBatchUpdateRequest;
import org.dromara.ai.domain.request.AiIntentQuery;
import org.dromara.ai.domain.request.AiIntentRequest;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.domain.response.AiIntentResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

import java.util.List;

public interface AiIntentApplicationService {
    List<AiIntentResponse> intents();
    TableDataInfo<AiIntentResponse> page(AiIntentQuery query, PageQuery pageQuery);
    AiIntentResponse intent(Long id);
    Long createIntent(AiIntentRequest request);
    void updateIntent(Long id, AiIntentRequest request);
    void deleteIntent(Long id);
    void batchUpdate(AiIntentBatchUpdateRequest request);
    AiIntentRecognitionResponse recognize(AiIntentRecognitionRequest request);
}
