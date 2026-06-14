package org.dromara.call.constant;

import java.util.ArrayList;
import java.util.List;

public final class EslEventNames {
    public static final String CHANNEL_CREATE = "CHANNEL_CREATE";
    public static final String CHANNEL_PROGRESS = "CHANNEL_PROGRESS";
    public static final String CHANNEL_PROGRESS_MEDIA = "CHANNEL_PROGRESS_MEDIA";
    public static final String CHANNEL_ANSWER = "CHANNEL_ANSWER";
    public static final String CHANNEL_BRIDGE = "CHANNEL_BRIDGE";
    public static final String CHANNEL_UNBRIDGE = "CHANNEL_UNBRIDGE";
    public static final String CHANNEL_HOLD = "CHANNEL_HOLD";
    public static final String CHANNEL_UNHOLD = "CHANNEL_UNHOLD";
    public static final String CHANNEL_HANGUP = "CHANNEL_HANGUP";
    public static final String CHANNEL_HANGUP_COMPLETE = "CHANNEL_HANGUP_COMPLETE";
    public static final String CHANNEL_DESTROY = "CHANNEL_DESTROY";

    /**
     * FreeSWITCH 自定义事件，mod_callcenter 队列事件通过 CUSTOM + Event-Subclass 携带。
     * 订阅 CUSTOM 会带出全部 subclass，由 Handler 按 Event-Subclass 过滤。
     */
    public static final String CUSTOM = "CUSTOM";

    /**
     * mod_callcenter 队列事件 subclass，对应 Event-Subclass 头。
     */
    public static final String SUBCLASS_CC_COMING = "callcenter::call-coming";
    public static final String SUBCLASS_CC_QUEUE = "callcenter::call-queue";
    public static final String SUBCLASS_CC_RING_AGENT = "callcenter::call-ring-a-agent";
    public static final String SUBCLASS_CC_AGENT_ANSWER = "callcenter::agent-answer";
    public static final String SUBCLASS_CC_TIMEOUT = "callcenter::call-timeout";
    public static final String SUBCLASS_CC_ABANDON = "callcenter::call-abandon";
    public static final String SUBCLASS_CC_REJECTED = "callcenter::call-rejected";
    public static final String SUBCLASS_CC_NO_ANSWER = "callcenter::call-no-answer";

    private EslEventNames() {
    }

    public static List<String> subscribedChannelEvents() {
        return List.of(CHANNEL_CREATE, CHANNEL_PROGRESS, CHANNEL_PROGRESS_MEDIA, CHANNEL_ANSWER, CHANNEL_BRIDGE,
            CHANNEL_UNBRIDGE, CHANNEL_HOLD, CHANNEL_UNHOLD, CHANNEL_HANGUP, CHANNEL_HANGUP_COMPLETE, CHANNEL_DESTROY);
    }

    /**
     * ESL 实际订阅的事件列表，在通道事件基础上追加 CUSTOM，用于接收 mod_callcenter 队列事件。
     */
    public static List<String> subscribedEvents() {
        List<String> events = new ArrayList<>(subscribedChannelEvents());
        events.add(CUSTOM);
        return events;
    }

    public static boolean isTerminalEvent(String eventName) {
        return CHANNEL_HANGUP.equals(eventName)
            || CHANNEL_HANGUP_COMPLETE.equals(eventName)
            || CHANNEL_DESTROY.equals(eventName);
    }

    /**
     * 判断是否为 mod_callcenter 队列事件（CUSTOM + callcenter:: subclass）。
     */
    public static boolean isCallCenterQueueEvent(String eventName, String eventSubclass) {
        if (!CUSTOM.equals(eventName) || eventSubclass == null) return false;
        return eventSubclass.startsWith("callcenter::");
    }
}
