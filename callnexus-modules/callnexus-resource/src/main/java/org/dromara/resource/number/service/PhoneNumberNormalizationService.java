package org.dromara.resource.number.service;

import org.dromara.resource.number.domain.request.PhoneNumberNormalizeRequest;
import org.dromara.resource.number.domain.response.PhoneNumberNormalizeResponse;

public interface PhoneNumberNormalizationService {

    PhoneNumberNormalizeResponse normalize(String tenantId, PhoneNumberNormalizeRequest request);
}
