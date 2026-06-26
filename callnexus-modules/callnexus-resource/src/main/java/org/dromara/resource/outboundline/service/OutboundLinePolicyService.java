package org.dromara.resource.outboundline.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyPageQuery;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyRequest;
import org.dromara.resource.outboundline.domain.response.OutboundLinePolicyResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;

public interface OutboundLinePolicyService {
    TableDataInfo<OutboundLinePolicyResponse> page(OutboundLinePolicyPageQuery query, PageQuery pageQuery);

    OutboundLinePolicyResponse get(Long id);

    Long create(OutboundLinePolicyRequest request);

    void update(Long id, OutboundLinePolicyRequest request);

    void delete(Long id);

    PhoneNumberOutboundRouteResponse selectRoute(String tenantId, Long nodeId);
}
