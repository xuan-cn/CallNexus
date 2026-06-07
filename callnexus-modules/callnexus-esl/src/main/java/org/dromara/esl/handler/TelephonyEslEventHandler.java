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
        return EslEventNames.subscribedChannelEvents().contains(event.eventName());
    }

    @Override
    public void handle(FreeSwitchEslEvent event) {
        telephonyEventHandler.onEvent(new TelephonyEvent(
            event.nodeId(),
            event.eventName(),
            event.firstHeader(EslHeaders.UNIQUE_ID, EslHeaders.CHANNEL_CALL_UUID),
            event.firstHeader(EslHeaders.CALLER_CALLER_ID_NUMBER, EslHeaders.CALLER_USERNAME),
            event.firstHeader(EslHeaders.CALLER_DESTINATION_NUMBER, EslHeaders.VARIABLE_SIP_TO_USER),
            event.header(EslHeaders.HANGUP_CAUSE),
            event.headers()
        ));
    }
}
