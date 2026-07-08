package org.dromara.ai.service;

import java.util.Map;

public interface AiRealtimeTelephonyGateway {

    void speak(Long nodeId, String customerLegUuid, String text, String voice);

    void recognize(Long nodeId, String customerLegUuid);

    boolean callExists(Long nodeId, String customerLegUuid);

    Map<String, String> getChannelVariables(Long nodeId, String customerLegUuid, String... names);
}
