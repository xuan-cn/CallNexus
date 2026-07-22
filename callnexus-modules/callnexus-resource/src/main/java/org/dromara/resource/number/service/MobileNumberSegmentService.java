package org.dromara.resource.number.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.number.domain.request.MobileNumberSegmentPageQuery;
import org.dromara.resource.number.domain.request.MobileNumberSegmentRequest;
import org.dromara.resource.number.domain.response.MobileNumberSegmentResponse;

public interface MobileNumberSegmentService {

    TableDataInfo<MobileNumberSegmentResponse> page(MobileNumberSegmentPageQuery query, PageQuery pageQuery);

    MobileNumberSegmentResponse get(Long id);

    Long create(MobileNumberSegmentRequest request);

    void update(Long id, MobileNumberSegmentRequest request);

    void delete(Long id);
}
