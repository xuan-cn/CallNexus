package org.dromara.call.service;

import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.response.DispatchCallTaskResponse;

import java.util.List;

public interface DispatchCallTaskService {
    DispatchCallTaskResponse startSingleCall(String targetExtension);

    DispatchCallTaskResponse startGroupCall(List<String> targetExtensions);

    List<DispatchCallTaskResponse> listRecent(int limit);

    DispatchCallTaskResponse get(Long taskId);

    void stopUnanswered(Long taskId);

    boolean terminateByOperatorLeg(String operatorLegUuid);

    void handleEvent(TelephonyEvent event);
}
