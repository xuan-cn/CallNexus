package org.dromara.call.service;

import org.dromara.call.domain.TelephonyEvent;

public interface TelephonyEventHandler {
    void onEvent(TelephonyEvent event);
}
