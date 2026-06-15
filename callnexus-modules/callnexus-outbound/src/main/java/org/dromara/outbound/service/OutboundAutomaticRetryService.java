package org.dromara.outbound.service;

public interface OutboundAutomaticRetryService {

    void applySystemSuggestion(Long memberId, String businessCallId, String suggestedResultCode);
}
