package org.dromara.call.service;

import org.dromara.call.domain.CallSessionCompletedEvent;

/**
 * 通话聚合结束后的业务扩展点。
 */
public interface CallSessionCompletedListener {

    void onCompleted(CallSessionCompletedEvent event);
}
