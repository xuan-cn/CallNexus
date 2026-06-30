package org.dromara.call.service;

import org.dromara.call.domain.response.DispatchActiveCallResponse;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;
import org.dromara.call.domain.response.DispatchExtensionStatusResponse;

import java.util.List;

public interface DispatchCallMonitorService {
    List<DispatchActiveCallResponse> listActiveCalls();

    DispatchCallTopologyResponse getTopology(String businessCallId);
    List<DispatchExtensionStatusResponse> listExtensionStatuses();
}
