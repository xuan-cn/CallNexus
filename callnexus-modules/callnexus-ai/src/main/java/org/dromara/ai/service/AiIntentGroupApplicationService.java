package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiIntentGroupRequest;
import org.dromara.ai.domain.response.AiIntentGroupResponse;

import java.util.List;

public interface AiIntentGroupApplicationService {
    List<AiIntentGroupResponse> groups();
    Long create(AiIntentGroupRequest request);
    void update(Long id, AiIntentGroupRequest request);
    void delete(Long id);
}
