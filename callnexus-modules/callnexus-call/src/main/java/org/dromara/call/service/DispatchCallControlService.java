package org.dromara.call.service;

public interface DispatchCallControlService {
    void forceHangup(String businessCallId);

    void forceTransferToExtension(String businessCallId, String targetExtension);
    String startMonitor(String businessCallId, String targetExtension);
    String startWhisper(String businessCallId, String targetExtension);
    String startBarge(String businessCallId, String targetExtension);
}
