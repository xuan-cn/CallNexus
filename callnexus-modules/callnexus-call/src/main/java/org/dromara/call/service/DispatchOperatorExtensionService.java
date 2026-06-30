package org.dromara.call.service;

import org.dromara.call.domain.response.DispatchOperatorExtensionResponse;

public interface DispatchOperatorExtensionService {
    DispatchOperatorExtensionResponse current();

    DispatchOperatorExtensionResponse bindCurrent(Long sipAccountId);

    DispatchOperatorExtensionResponse requireCurrent();
}
