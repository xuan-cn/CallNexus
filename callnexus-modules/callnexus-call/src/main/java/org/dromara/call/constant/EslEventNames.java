package org.dromara.call.constant;

import java.util.List;

public final class EslEventNames {
    public static final String CHANNEL_CREATE = "CHANNEL_CREATE";
    public static final String CHANNEL_PROGRESS = "CHANNEL_PROGRESS";
    public static final String CHANNEL_PROGRESS_MEDIA = "CHANNEL_PROGRESS_MEDIA";
    public static final String CHANNEL_ANSWER = "CHANNEL_ANSWER";
    public static final String CHANNEL_BRIDGE = "CHANNEL_BRIDGE";
    public static final String CHANNEL_HANGUP_COMPLETE = "CHANNEL_HANGUP_COMPLETE";

    private EslEventNames() {
    }

    public static List<String> subscribedChannelEvents() {
        return List.of(CHANNEL_CREATE, CHANNEL_PROGRESS, CHANNEL_PROGRESS_MEDIA, CHANNEL_ANSWER, CHANNEL_BRIDGE, CHANNEL_HANGUP_COMPLETE);
    }
}
