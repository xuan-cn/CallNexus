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
    public static final String CHANNEL_HANGUP = "CHANNEL_HANGUP";
    public static final String CHANNEL_HANGUP_COMPLETE = "CHANNEL_HANGUP_COMPLETE";
    public static final String CHANNEL_DESTROY = "CHANNEL_DESTROY";

    private EslEventNames() {
    }

    public static List<String> subscribedChannelEvents() {
        return List.of(CHANNEL_CREATE, CHANNEL_PROGRESS, CHANNEL_PROGRESS_MEDIA, CHANNEL_ANSWER, CHANNEL_BRIDGE,
            CHANNEL_UNBRIDGE, CHANNEL_HOLD, CHANNEL_UNHOLD, CHANNEL_HANGUP, CHANNEL_HANGUP_COMPLETE, CHANNEL_DESTROY);
    }

    public static boolean isTerminalEvent(String eventName) {
        return CHANNEL_HANGUP.equals(eventName)
            || CHANNEL_HANGUP_COMPLETE.equals(eventName)
            || CHANNEL_DESTROY.equals(eventName);
    }
}
