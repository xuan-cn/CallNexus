package org.dromara.resource.outboundauth.service;

import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationRejection;

public interface OutboundAuthorizationRule {

    OutboundAuthorizationRejection validate(OutboundAuthorizationCommand command, String normalizedCallee);
}
