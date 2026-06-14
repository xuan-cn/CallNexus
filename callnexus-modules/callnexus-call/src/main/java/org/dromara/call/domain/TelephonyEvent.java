package org.dromara.call.domain;

import java.util.Map;

/**
 * 通话/队列事件统一抽象。
 *
 * @param nodeId         FreeSWITCH 节点 ID
 * @param eventName      事件名，例如 CHANNEL_ANSWER、CUSTOM
 * @param eventSubclass  CUSTOM 事件子类，例如 callcenter::call-coming；普通 CHANNEL_* 事件为 null
 * @param uuid           当前 channel 的 Unique-ID，CUSTOM 队列事件为 CC-Caller-UUID
 * @param callerNumber   主叫号码
 * @param destinationNumber 被叫号码
 * @param hangupCause    挂断原因，仅结束事件携带
 * @param headers        原始事件头
 */
public record TelephonyEvent(Long nodeId, String eventName, String eventSubclass, String uuid, String callerNumber,
                             String destinationNumber, String hangupCause, Map<String, String> headers) {

    /**
     * 兼容旧调用：未携带 eventSubclass 时默认为 null。
     */
    public TelephonyEvent(Long nodeId, String eventName, String uuid, String callerNumber,
                          String destinationNumber, String hangupCause, Map<String, String> headers) {
        this(nodeId, eventName, null, uuid, callerNumber, destinationNumber, hangupCause, headers);
    }
}
