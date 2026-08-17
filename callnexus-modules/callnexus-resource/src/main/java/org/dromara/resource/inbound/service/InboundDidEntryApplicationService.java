package org.dromara.resource.inbound.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.inbound.domain.request.CreateInboundDidEntryRequest;
import org.dromara.resource.inbound.domain.request.InboundDidEntryPageQuery;
import org.dromara.resource.inbound.domain.request.InboundRouteTestRequest;
import org.dromara.resource.inbound.domain.request.UpdateInboundDidEntryRequest;
import org.dromara.resource.inbound.domain.response.InboundDidEntryResponse;
import org.dromara.resource.inbound.domain.response.InboundRouteMatchResponse;

public interface InboundDidEntryApplicationService {
    TableDataInfo<InboundDidEntryResponse> page(InboundDidEntryPageQuery query, PageQuery pageQuery);

    InboundDidEntryResponse get(Long id);

    Long create(CreateInboundDidEntryRequest request);

    void update(Long id, UpdateInboundDidEntryRequest request);

    void delete(Long id);

    InboundRouteMatchResponse test(InboundRouteTestRequest request);
}
