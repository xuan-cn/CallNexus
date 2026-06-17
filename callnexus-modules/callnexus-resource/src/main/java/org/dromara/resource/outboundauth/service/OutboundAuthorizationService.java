package org.dromara.resource.outboundauth.service;

import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationResult;

public interface OutboundAuthorizationService {

    OutboundAuthorizationResult authorize(OutboundAuthorizationCommand command);
}
