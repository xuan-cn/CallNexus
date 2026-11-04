package org.dromara.customer.sms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.customer.customer.domain.response.*;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.sms.domain.*;
import org.dromara.customer.sms.mapper.*;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class BusinessSmsServiceTest {
    @BeforeAll static void json() {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("objectMapper", new ObjectMapper());
        new SpringUtils().postProcessBeanFactory(factory);
    }

    @Test void customerPhoneCheckRejectsUnrelatedAndDisabledPhones() {
        var customer = new CustomerResponse(); customer.setPrimaryPhone("13800138000");
        BusinessSmsService.requireCustomerPhone(customer, "13800138000");
        assertThrows(ServiceException.class, () -> BusinessSmsService.requireCustomerPhone(customer, "13900139000"));
        var phone = new CustomerPhoneResponse(); phone.setNormalizedPhone("13800138000"); phone.setEnabled(false);
        customer.setPhones(List.of(phone));
        assertThrows(ServiceException.class, () -> BusinessSmsService.requireCustomerPhone(customer, "13800138000"));
        phone.setEnabled(true);
        BusinessSmsService.requireCustomerPhone(customer, "13800138000");
    }

    @Test void fingerprintIsStableAcrossMapOrderAndChangesWithMessage() {
        var a = new BusinessSmsService.Preview(1L, "13800138000", "A", Map.of("a", "1", "b", "2"));
        var b = new BusinessSmsService.Preview(1L, "13800138000", "A", Map.of("b", "2", "a", "1"));
        assertEquals(BusinessSmsService.fingerprint(1L, a, 1L), BusinessSmsService.fingerprint(1L, b, 1L));
        assertNotEquals(BusinessSmsService.fingerprint(1L, a, 1L), BusinessSmsService.fingerprint(2L, a, 1L));
    }

    @Test @SuppressWarnings("unchecked") void identicalRequestReservesOnlyOneMessageAndNoProviderCall() throws Exception {
        var channels = mock(SmsChannelMapper.class); var templates = mock(SmsTemplateMapper.class); var records = mock(SmsSendRecordMapper.class);
        var customers = mock(CustomerApplicationService.class); var redis = mock(RedissonClient.class); var lock = mock(RLock.class);
        var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true); when(lock.isHeldByCurrentThread()).thenReturn(true);
        var channel = new SmsChannel(); channel.setId(1L); channel.setEnabled(true); channel.setSupplier("ALIBABA"); channel.setSignature("Test"); channel.setChannelName("Channel"); channel.setMinuteLimit(1); channel.setDailyLimit(30);
        var template = new SmsTemplate(); template.setId(2L); template.setChannelId(1L); template.setEnabled(true); template.setContent("Hello ${name}"); template.setTemplateName("Template"); template.setProviderTemplateId("EXTERNAL"); template.setVariablesJson("[{\"key\":\"name\",\"source\":\"CUSTOMER_NAME\"}]");
        var customer = new CustomerResponse(); customer.setCustomerName("Customer"); customer.setPrimaryPhone("13800138000");
        when(channels.selectById(1L)).thenReturn(channel); when(templates.selectById(2L)).thenReturn(template); when(customers.get(3L)).thenReturn(customer);
        final SmsSendRecord[] saved = new SmsSendRecord[1];
        when(records.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(call -> saved[0]);
        when(records.insert(any(SmsSendRecord.class))).thenAnswer(call -> { saved[0] = call.getArgument(0); saved[0].setId(4L); return 1; });
        var service = new BusinessSmsService(channels, templates, records, customers, redis, transaction);
        var request = new SmsRequests.Send(2L, "13800138000", Map.of(), "same-request-123456789");
        try (var tenant = mockStatic(TenantHelper.class); var login = mockStatic(LoginHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("000001"); login.when(LoginHelper::getUsername).thenReturn("agent");
            assertEquals(4L, service.enqueue(3L, null, request));
            assertEquals(4L, service.enqueue(3L, null, request));
        }
        verify(records, times(1)).insert(any(SmsSendRecord.class));
        verify(lock, times(2)).unlock();
        assertEquals("QUEUED", saved[0].getStatus()); assertEquals("000001", saved[0].getTenantId());
    }

    @Test void inaccessibleCustomerStopsBeforeTemplateLookup() {
        var customers = mock(CustomerApplicationService.class); when(customers.get(1L)).thenThrow(new ServiceException("denied"));
        var templates = mock(SmsTemplateMapper.class);
        var service = new BusinessSmsService(null, templates, null, customers, null, null);
        assertThrows(ServiceException.class, () -> service.preview(1L, new SmsRequests.Send(1L, "13800138000", Map.of(), null)));
        verifyNoInteractions(templates);
    }
}
