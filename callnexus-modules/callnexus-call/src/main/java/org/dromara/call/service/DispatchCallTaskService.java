package org.dromara.call.service;

import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.response.DispatchCallTaskResponse;

import java.util.List;

public interface DispatchCallTaskService {
    DispatchCallTaskResponse startSingleCall(String targetExtension);

    DispatchCallTaskResponse startGroupCall(List<String> targetExtensions);

    DispatchCallTaskResponse startBroadcast(Long mediaAssetId, List<String> targetExtensions);

    DispatchCallTaskResponse startIntercom(String targetExtension);

    List<DispatchCallTaskResponse> listRecent(int limit);

    DispatchCallTaskResponse get(Long taskId);

    void stopUnanswered(Long taskId);

    void terminateBroadcast(Long taskId);

    void setIntercomTalking(Long taskId, boolean talking);

    void terminateIntercom(Long taskId);

    boolean terminateByOperatorLeg(String operatorLegUuid);

    void handleEvent(TelephonyEvent event);
}
