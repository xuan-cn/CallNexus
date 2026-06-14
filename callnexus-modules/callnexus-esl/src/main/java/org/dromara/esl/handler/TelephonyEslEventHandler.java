package org.dromara.esl.handler;

import lombok.RequiredArgsConstructor;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.service.TelephonyEventHandler;
import org.dromara.esl.domain.FreeSwitchEslEvent;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TelephonyEslEventHandler implements EslEventHandler {
    private final TelephonyEventHandler telephonyEventHandler;

    @Override
    public boolean supports(FreeSwitchEslEvent event) {
        return EslEventNames.subscribedChannelEvents().contains(event.eventName())
            || EslEventNames.CUSTOM.equals(event.eventName());
    }

    @Override
    public void handle(FreeSwitchEslEvent event) {
        String eventName = event.eventName();
        String eventSubclass = event.firstHeader(EslHeaders.EVENT_SUBCLASS);
        // 队列 CUSTOM 事件的关键标识是 CC-Caller-UUID（业务通话 channel），而非 mod_callcenter 内部 Unique-ID。
        String uuid = EslEventNames.isCallCenterQueueEvent(eventName, eventSubclass)
            ? event.firstHeader(EslHeaders.CC_CALLER_UUID, EslHeaders.UNIQUE_ID)
            : event.firstHeader(EslHeaders.UNIQUE_ID, EslHeaders.CHANNEL_CALL_UUID);
        telephonyEventHandler.onEvent(new TelephonyEvent(
            event.nodeId(),
            eventName,
            eventSubclass,
            uuid,
            event.firstHeader(EslHeaders.CC_CALLER_CID_NUMBER, EslHeaders.CALLER_CALLER_ID_NUMBER, EslHeaders.CALLER_USERNAME),
            event.firstHeader(EslHeaders.CALLER_DESTINATION_NUMBER, EslHeaders.VARIABLE_SIP_TO_USER),
            event.header(EslHeaders.HANGUP_CAUSE),
            event.headers()
        ));
    }
}

