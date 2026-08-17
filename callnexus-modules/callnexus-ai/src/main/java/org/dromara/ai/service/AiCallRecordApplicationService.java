package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiCallRecordQuery;
import org.dromara.ai.domain.response.AiCallRecordResponse;
import org.dromara.common.mybatis.core.page.TableDataInfo;

public interface AiCallRecordApplicationService {

    TableDataInfo<AiCallRecordResponse> page(AiCallRecordQuery query);
}
