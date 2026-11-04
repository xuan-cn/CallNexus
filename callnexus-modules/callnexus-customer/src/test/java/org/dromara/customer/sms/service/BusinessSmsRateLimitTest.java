package org.dromara.customer.sms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.customer.sms.domain.*;
import org.dromara.customer.sms.mapper.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class BusinessSmsRateLimitTest {
    @BeforeAll static void json() { var factory = new DefaultListableBeanFactory(); factory.registerSingleton("objectMapper", new ObjectMapper()); new SpringUtils().postProcessBeanFactory(factory); }

    @ParameterizedTest @ValueSource(booleans={false,true}) @SuppressWarnings("unchecked")
    void minuteOrDailyQuotaRejectsBeforeInsert(boolean daily) throws Exception {
        var channels = mock(SmsChannelMapper.class); var templates = mock(SmsTemplateMapper.class); var records = mock(SmsSendRecordMapper.class);
        var redis = mock(RedissonClient.class); var lock = mock(RLock.class); var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true); when(lock.isHeldByCurrentThread()).thenReturn(true);
        var channel = new SmsChannel(); channel.setId(1L); channel.setEnabled(true); channel.setSupplier("ALIBABA"); channel.setSignature("Test"); channel.setMinuteLimit(1); channel.setDailyLimit(30);
        var template = new SmsTemplate(); template.setId(2L); template.setChannelId(1L); template.setEnabled(true); template.setContent("Notice"); template.setVariablesJson("[]");
        when(channels.selectById(1L)).thenReturn(channel); when(templates.selectById(2L)).thenReturn(template);
        when(records.selectCount(any(LambdaQueryWrapper.class))).thenReturn(daily ? 0L : 1L, daily ? 30L : 0L);
        var service = new BusinessSmsService(channels, templates, records, null, redis, transaction);
        try (var tenant = mockStatic(TenantHelper.class)) {
            tenant.when(TenantHelper::getTenantId).thenReturn("000001");
            assertThrows(ServiceException.class, () -> service.enqueue(null, 1L,
                new SmsRequests.Send(2L, "13800138000", Map.of(), "request-1234567890")));
        }
        verify(records, never()).insert(any(SmsSendRecord.class)); verify(lock).unlock();
    }
}
