package org.dromara.call.service;

public interface DispatchCallControlService {
    void forceHangup(String businessCallId);

    void forceTransferToExtension(String businessCallId, String targetExtension);
    String startMonitor(String businessCallId, String targetExtension);
    String startMonitor(String businessCallId, String targetExtension, Long supervisorAgentId);
    void stopMonitor(String businessCallId, Long supervisorAgentId);
    String startWhisper(String businessCallId, String targetExtension);
    String startWhisper(String businessCallId, String targetExtension, Long supervisorAgentId);
    void stopWhisper(String businessCallId, Long supervisorAgentId);
    String startBarge(String businessCallId, String targetExtension);
    String startBarge(String businessCallId, String targetExtension, Long supervisorAgentId);
    void stopBarge(String businessCallId, Long supervisorAgentId);
    void forceHangup(String businessCallId, Long supervisorAgentId);
    String pickupRingingCall(String businessCallId, String targetExtension);
}
