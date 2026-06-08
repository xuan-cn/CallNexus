package org.dromara.resource.gateway.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.gateway.domain.request.CreateFreeSwitchGatewayRequest;
import org.dromara.resource.gateway.domain.request.FreeSwitchGatewayPageQuery;
import org.dromara.resource.gateway.domain.request.UpdateFreeSwitchGatewayRequest;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayResponse;

public interface FreeSwitchGatewayApplicationService {
    TableDataInfo<FreeSwitchGatewayResponse> page(FreeSwitchGatewayPageQuery query, PageQuery pageQuery);

    FreeSwitchGatewayResponse get(Long id);

    Long create(CreateFreeSwitchGatewayRequest request);

    void update(Long id, UpdateFreeSwitchGatewayRequest request);

    void delete(Long id);
}
