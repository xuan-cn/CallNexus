package org.dromara.resource.phone.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.phone.domain.request.CreatePhoneNumberRequest;
import org.dromara.resource.phone.domain.request.PhoneNumberPageQuery;
import org.dromara.resource.phone.domain.request.UpdatePhoneNumberRequest;
import org.dromara.resource.phone.domain.response.PhoneNumberResponse;

public interface PhoneNumberApplicationService {
    TableDataInfo<PhoneNumberResponse> page(PhoneNumberPageQuery query, PageQuery pageQuery);

    PhoneNumberResponse get(Long id);

    Long create(CreatePhoneNumberRequest request);

    void update(Long id, UpdatePhoneNumberRequest request);

    void delete(Long id);
}
