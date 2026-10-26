package org.dromara.call.constant;

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
    public static final String CHANNEL_EXECUTE = "CHANNEL_EXECUTE";
    public static final String CHANNEL_EXECUTE_COMPLETE = "CHANNEL_EXECUTE_COMPLETE";
    public static final String CHANNEL_HANGUP = "CHANNEL_HANGUP";
    public static final String CHANNEL_HANGUP_COMPLETE = "CHANNEL_HANGUP_COMPLETE";
    public static final String CHANNEL_DESTROY = "CHANNEL_DESTROY";

    /**
     * FreeSWITCH detect_speech / UniMRCP 识别结果事件。
     */
    public static final String DETECTED_SPEECH = "DETECTED_SPEECH";

    /**
     * DTMF 接收事件。通道收到 DTMF 按键时由 FreeSWITCH 上报，事件头 DTMF-Digit/DTMF-Duration 携带按键信息。
     */
    public static final String DTMF = "DTMF";

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
    public static final String SUBCLASS_QUEUE_SATISFACTION = "callnexus::queue-satisfaction";
    public static final String SUBCLASS_SOFIA_REGISTER = "sofia::register";
    public static final String SUBCLASS_SOFIA_REGISTER_FAILURE = "sofia::register_failure";

    private EslEventNames() {
    }

    public static List<String> subscribedChannelEvents() {
        return List.of(CHANNEL_CREATE, CHANNEL_PROGRESS, CHANNEL_PROGRESS_MEDIA, CHANNEL_ANSWER, CHANNEL_BRIDGE,
            CHANNEL_UNBRIDGE, CHANNEL_HOLD, CHANNEL_UNHOLD, CHANNEL_EXECUTE, CHANNEL_EXECUTE_COMPLETE,
            CHANNEL_HANGUP, CHANNEL_HANGUP_COMPLETE, CHANNEL_DESTROY, DETECTED_SPEECH, DTMF);
    }

    /**
     * CUSTOM 必须在独立的 ESL event 命令中显式指定 subclass。
     */
    public static List<String> subscribedCustomEventSubclasses() {
        return List.of(
            SUBCLASS_CC_COMING,
            SUBCLASS_CC_QUEUE,
            SUBCLASS_CC_RING_AGENT,
            SUBCLASS_CC_AGENT_ANSWER,
            SUBCLASS_CC_TIMEOUT,
            SUBCLASS_CC_ABANDON,
            SUBCLASS_CC_REJECTED,
            SUBCLASS_CC_NO_ANSWER,
            SUBCLASS_QUEUE_SATISFACTION,
            SUBCLASS_SOFIA_REGISTER,
            SUBCLASS_SOFIA_REGISTER_FAILURE
        );
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
        if (!CUSTOM.equals(eventName) || eventSubclass == null) {
            return false;
        }
        return eventSubclass.startsWith("callcenter::");
    }

    public static boolean isQueueSatisfactionEvent(String eventName, String eventSubclass) {
        return CUSTOM.equals(eventName) && SUBCLASS_QUEUE_SATISFACTION.equals(eventSubclass);
    }
}
