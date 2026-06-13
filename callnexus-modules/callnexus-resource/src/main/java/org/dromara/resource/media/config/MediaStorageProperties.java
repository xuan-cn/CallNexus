package org.dromara.resource.media.config;

import lombok.Data;
import org.dromara.resource.media.domain.MediaAssetCategory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "callnexus.audio-storage")
public class MediaStorageProperties {
    private String callRecordingConfigKey = "call-recording";
    private String ringbackToneConfigKey = "ringback-tone";
    private String queueWaitMusicConfigKey = "queue-wait-music";
    private String ivrPromptConfigKey = "ivr-prompt";
    private String userMusicConfigKey = "user-music";

    public String getConfigKey(MediaAssetCategory category) {
        return switch (category) {
            case CALL_RECORDING -> callRecordingConfigKey;
            case RINGBACK_TONE -> ringbackToneConfigKey;
            case QUEUE_WAIT_MUSIC -> queueWaitMusicConfigKey;
            case IVR_PROMPT -> ivrPromptConfigKey;
            case USER_MUSIC -> userMusicConfigKey;
        };
    }
}
