package org.dromara.chat.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

public final class ChatRequests {
    private ChatRequests() {
    }

    @Data
    public static class SaveChannel {
        @NotBlank
        @Size(max = 128)
        private String channelName;
        private Long skillGroupId;
        @Size(max = 1000)
        private String welcomeMessage;
        @Size(max = 1000)
        private String offlineMessage;
        private String allowedOrigins;
        private Boolean enabled;
        private Integer version;
    }

    @Data
    public static class CreateConversation {
        @Size(max = 128)
        private String externalId;
        @Size(max = 128)
        private String visitorName;
        @Size(max = 64)
        private String phone;
        @Size(max = 255)
        private String email;
        @Size(max = 4000)
        private String initialMessage;
    }

    @Data
    public static class SendMessage {
        @NotBlank
        @Size(max = 4000)
        private String content;
        @Size(max = 64)
        private String clientMessageId;
    }
}
