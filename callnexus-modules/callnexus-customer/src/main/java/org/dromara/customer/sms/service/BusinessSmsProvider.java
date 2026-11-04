package org.dromara.customer.sms.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.customer.sms.domain.SmsChannel;
import org.dromara.customer.sms.domain.SmsSendRecord;
import org.dromara.sms4j.api.SmsBlend;
import org.dromara.sms4j.aliyun.config.AlibabaConfig;
import org.dromara.sms4j.aliyun.config.AlibabaFactory;
import org.dromara.sms4j.tencent.config.TencentConfig;
import org.dromara.sms4j.tencent.config.TencentFactory;
import org.dromara.sms4j.provider.config.BaseConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

@Component
public class BusinessSmsProvider {
    private static final ThreadLocal<Boolean> SMS_REQUEST = new ThreadLocal<>();
    static {
        cn.hutool.http.GlobalInterceptor.INSTANCE.addRequestInterceptor(BusinessSmsProvider::applyRequestTimeout);
    }
    public record Outcome(String status, String code, String reference) { }

    public Outcome send(SmsChannel channel, SmsSendRecord record) {
        return withTimeouts(() -> sendInternal(channel, record));
    }

    private Outcome sendInternal(SmsChannel channel, SmsSendRecord record) {
        // Isolated instances: never replace config1/config2 or register business credentials in SmsFactory.
        SmsBlend blend;
        if ("ALIBABA".equals(record.getSupplier())) {
            AlibabaConfig config = new AlibabaConfig();
            configure(config, channel, record);
            blend = AlibabaFactory.instance().createSms(config);
        } else if ("TENCENT".equals(record.getSupplier())) {
            TencentConfig config = new TencentConfig();
            configure(config, channel, record);
            config.setConnTimeout(10000);
            blend = TencentFactory.instance().createSms(config);
        } else {
            return new Outcome("REJECTED", "UNSUPPORTED_SUPPLIER", null);
        }
        LinkedHashMap<String,String> parameters = JsonUtils.parseObject(record.getParametersJson(),
            new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,String>>() { });
        var response = blend.sendMessage(record.getPhone(), record.getProviderTemplateId(), parameters);
        if (response == null) return new Outcome("UNKNOWN", "EMPTY_PROVIDER_RESPONSE", null);
        return outcome(response);
    }

    static Outcome outcome(org.dromara.sms4j.api.entity.SmsResponse response) {
        if (response == null) return new Outcome("UNKNOWN", "EMPTY_PROVIDER_RESPONSE", null);
        String reference = null;
        String code = null;
        try {
            JsonNode data = JsonUtils.getObjectMapper().valueToTree(response.getData());
            JsonNode referenceNode = data.findValue("BizId");
            if (referenceNode == null) referenceNode = data.findValue("SerialNo");
            if (referenceNode == null) referenceNode = data.findValue("RequestId");
            reference = referenceNode == null ? null : safe(referenceNode.asText());
            JsonNode codeNode = data.findValue("Code");
            code = codeNode == null ? null : safe(codeNode.asText());
        } catch (RuntimeException ignored) { /* Provider payloads are intentionally not persisted or logged. */ }
        // A false SDK result can also represent a transport failure, so it is not safe to automatically resend.
        if (response.isSuccess()) return new Outcome("ACCEPTED", "PROVIDER_ACCEPTED", reference);
        if (code != null && !"OK".equalsIgnoreCase(code)) return new Outcome("REJECTED", code, reference);
        return new Outcome("UNKNOWN", "PROVIDER_NOT_CONFIRMED", reference);
    }

    static void configure(BaseConfig config, SmsChannel channel, SmsSendRecord record) {
        config.setConfigId("business-sms-" + record.getTenantId() + "-" + record.getId());
        config.setAccessKeyId(channel.getAccessKeyId());
        config.setAccessKeySecret(channel.getAccessKeySecret());
        config.setSdkAppId(channel.getSdkAppId());
        config.setSignature(record.getSignature());
        config.setMaxRetries(0);
    }

    private static String safe(String text) { return text != null && text.matches("[A-Za-z0-9_.-]{1,96}") ? text : null; }

    static void applyRequestTimeout(cn.hutool.http.HttpRequest request) {
        if (Boolean.TRUE.equals(SMS_REQUEST.get())) {
            request.setConnectionTimeout(10000);
            request.setReadTimeout(20000);
        }
    }

    static <T> T withTimeouts(java.util.function.Supplier<T> action) {
        Boolean previous = SMS_REQUEST.get();
        SMS_REQUEST.set(true);
        try { return action.get(); }
        finally { if (previous == null) SMS_REQUEST.remove(); else SMS_REQUEST.set(previous); }
    }
}
