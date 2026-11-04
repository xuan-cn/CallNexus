package org.dromara.customer.sms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.customer.sms.domain.*;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.dromara.sms4j.aliyun.config.AlibabaConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class BusinessSmsProviderTest {
    @BeforeAll static void json() { var factory = new DefaultListableBeanFactory(); factory.registerSingleton("objectMapper", new ObjectMapper()); new SpringUtils().postProcessBeanFactory(factory); }
    @Test void configIsIsolatedAndDisablesSdkRetries() {
        var channel = new SmsChannel(); channel.setAccessKeyId("test-key"); channel.setAccessKeySecret("test-secret");
        var record = new SmsSendRecord(); record.setId(1L); record.setTenantId("000001"); record.setSignature("Test");
        var config = new AlibabaConfig(); BusinessSmsProvider.configure(config, channel, record);
        assertEquals(0, config.getMaxRetries()); assertEquals("business-sms-000001-1", config.getConfigId());
        assertEquals("Test", config.getSignature());
    }
    @Test void acceptedDoesNotMeanDelivered() {
        var response = new SmsResponse(); response.setSuccess(true); response.setData(Map.of("Code", "OK", "BizId", "test-123"));
        var result = BusinessSmsProvider.outcome(response);
        assertEquals("ACCEPTED", result.status()); assertEquals("test-123", result.reference());
    }
    @Test void falseTransportResponseIsUnknownAndBusinessErrorIsRejected() {
        var response = new SmsResponse(); response.setSuccess(false); response.setData(Map.of("message", "timeout"));
        assertEquals("UNKNOWN", BusinessSmsProvider.outcome(response).status());
        response.setData(Map.of("Code", "isv.BUSINESS_LIMIT_CONTROL"));
        assertEquals("REJECTED", BusinessSmsProvider.outcome(response).status());
        assertEquals("UNKNOWN", BusinessSmsProvider.outcome(null).status());
    }
    @Test void credentialsAreNeverIncludedInRequestToString() {
        var request = new SmsRequests.Channel("Test", "ALIBABA", "secret-id", "secret-value", "", "Test", true, 1, 30, null);
        assertFalse(request.toString().contains("secret-value")); assertFalse(request.toString().contains("secret-id"));
    }
    @Test void httpTimeoutsOnlyApplyInsideBusinessSmsScopeAndAreClearedAfterException() {
        var ordinary = mock(cn.hutool.http.HttpRequest.class);
        BusinessSmsProvider.applyRequestTimeout(ordinary);
        verifyNoInteractions(ordinary);
        var sms = mock(cn.hutool.http.HttpRequest.class);
        assertThrows(IllegalStateException.class, () -> BusinessSmsProvider.withTimeouts(() -> {
            BusinessSmsProvider.applyRequestTimeout(sms);
            throw new IllegalStateException("test");
        }));
        verify(sms).setConnectionTimeout(10000); verify(sms).setReadTimeout(20000);
        BusinessSmsProvider.applyRequestTimeout(ordinary);
        verifyNoInteractions(ordinary);
    }
}
