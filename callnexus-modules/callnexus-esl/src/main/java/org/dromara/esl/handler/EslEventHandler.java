package org.dromara.esl.handler;

import org.dromara.esl.domain.FreeSwitchEslEvent;

public interface EslEventHandler {
    boolean supports(FreeSwitchEslEvent event);

    void handle(FreeSwitchEslEvent event);
}
