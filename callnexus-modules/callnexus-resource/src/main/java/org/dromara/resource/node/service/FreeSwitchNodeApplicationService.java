package org.dromara.resource.node.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.node.domain.request.CreateFreeSwitchNodeRequest;
import org.dromara.resource.node.domain.request.FreeSwitchNodePageQuery;
import org.dromara.resource.node.domain.request.UpdateFreeSwitchNodeRequest;
import org.dromara.resource.node.domain.response.FreeSwitchNodeResponse;

public interface FreeSwitchNodeApplicationService {
    TableDataInfo<FreeSwitchNodeResponse> page(FreeSwitchNodePageQuery query, PageQuery pageQuery);
    FreeSwitchNodeResponse get(Long id);
    Long create(CreateFreeSwitchNodeRequest request);
    void update(Long id, UpdateFreeSwitchNodeRequest request);
    void delete(Long id);
    String resetAgentToken(Long id);
}
