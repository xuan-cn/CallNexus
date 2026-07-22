package org.dromara.resource.number.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.number.domain.request.AreaCodePageQuery;
import org.dromara.resource.number.domain.request.AreaCodeRequest;
import org.dromara.resource.number.domain.response.AreaCodeResponse;

public interface AreaCodeService {

    TableDataInfo<AreaCodeResponse> page(AreaCodePageQuery query, PageQuery pageQuery);

    AreaCodeResponse get(Long id);

    Long create(AreaCodeRequest request);

    void update(Long id, AreaCodeRequest request);

    void delete(Long id);
}
