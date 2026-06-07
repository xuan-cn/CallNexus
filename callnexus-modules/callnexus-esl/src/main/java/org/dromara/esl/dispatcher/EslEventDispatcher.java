package org.dromara.esl.dispatcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.constant.EslHeaders;
import org.dromara.esl.domain.FreeSwitchEslEvent;
import org.dromara.esl.handler.EslEventHandler;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EslEventDispatcher {
    private final List<EslEventHandler> handlers;

    public void dispatch(FreeSwitchEslEvent event) {
        for (EslEventHandler handler : handlers) {
            if (!handler.supports(event)) continue;
            try {
                handler.handle(event);
            } catch (Exception exception) {
                log.error("Failed to process FreeSWITCH ESL event, nodeId={}, eventName={}, uuid={}",
                    event.nodeId(), event.eventName(), event.firstHeader(EslHeaders.UNIQUE_ID), exception);
            }
        }
    }
}
