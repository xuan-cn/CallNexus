package org.dromara.call.service;

import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.request.CallRecordPageQuery;
import org.dromara.call.domain.response.CallRecordResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

public interface CallRecordApplicationService {
    void handleEvent(TelephonyEvent event);
    TableDataInfo<CallRecordResponse> page(CallRecordPageQuery query, PageQuery pageQuery);
    CallRecordResponse get(Long id);
}
