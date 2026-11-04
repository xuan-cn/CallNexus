package org.dromara.customer.sms.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class SmsRequests {
    private SmsRequests() { }
    public record Channel(@NotBlank @Size(max=64) String channelName,
                          @NotBlank @Pattern(regexp="ALIBABA|TENCENT") String supplier,
                          @Size(max=256) String accessKeyId, @Size(max=256) String accessKeySecret,
                          @Size(max=64) String sdkAppId, @NotBlank @Size(max=64) String signature,
                          @NotNull Boolean enabled, @NotNull @Min(1) @Max(10) Integer minuteLimit,
                          @NotNull @Min(1) @Max(1000) Integer dailyLimit, Integer version) {
        @Override public String toString() { return "SmsChannelRequest[credentials=******]"; }
    }
    public record Variable(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{1,32}") String key,
                           @NotBlank @Pattern(regexp="MANUAL|CUSTOMER_NAME|PRIMARY_PHONE") String source) { }
    public record Template(@NotNull Long channelId, @NotBlank @Size(max=64) String templateName,
                           @NotBlank @Size(max=64) String providerTemplateId,
                           @NotBlank @Size(max=2000) String content,
                           @NotNull @Size(max=20) List<@Valid Variable> variables,
                           @NotNull Boolean enabled, Integer version) { }
    public record Send(@NotNull Long templateId, @NotBlank @Pattern(regexp="1[3-9][0-9]{9}") String phone,
                       @NotNull @Size(max=20) Map<@Pattern(regexp="[A-Za-z0-9_]{1,32}") String, @NotBlank @Size(max=200) String> parameters,
                       @Size(max=64) @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestId) { }
}
