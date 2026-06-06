package org.dromara.resource.sip.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.sip.domain.request.CreateSipAccountRequest;
import org.dromara.resource.sip.domain.request.SipAccountPageQuery;
import org.dromara.resource.sip.domain.request.UpdateSipAccountRequest;
import org.dromara.resource.sip.domain.response.SipAccountResponse;

public interface SipAccountApplicationService extends SipAccountQueryService {
    TableDataInfo<SipAccountResponse> page(SipAccountPageQuery query, PageQuery pageQuery);
    SipAccountResponse get(Long id);
    Long create(CreateSipAccountRequest request);
    void update(Long id, UpdateSipAccountRequest request);
    void delete(Long id);
}
