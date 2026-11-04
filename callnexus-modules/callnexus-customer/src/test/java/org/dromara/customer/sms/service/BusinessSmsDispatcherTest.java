package org.dromara.customer.sms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.customer.sms.domain.*;
import org.dromara.customer.sms.mapper.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class BusinessSmsDispatcherTest {
    @BeforeAll static void table() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "sms-test"), SmsSendRecord.class);
    }
    @Test void disabledChannelNeverCallsProvider() {
        var records = mock(SmsSendRecordMapper.class); var channels = mock(SmsChannelMapper.class); var provider = mock(BusinessSmsProvider.class);
        var record = record(); var channel = new SmsChannel(); channel.setEnabled(false); when(channels.selectById(2L)).thenReturn(channel);
        new BusinessSmsDispatcher(records, channels, provider).dispatch(record);
        verifyNoInteractions(provider);
        assertUpdate(records, "REJECTED", "CHANNEL_DISABLED_OR_CHANGED");
    }
    @Test void timeoutIsUnknownAndIsNotRetried() {
        var records = mock(SmsSendRecordMapper.class); var channels = mock(SmsChannelMapper.class); var provider = mock(BusinessSmsProvider.class);
        var record = record(); var channel = new SmsChannel(); channel.setEnabled(true); channel.setSupplier("ALIBABA"); when(channels.selectById(2L)).thenReturn(channel);
        when(provider.send(channel, record)).thenThrow(new IllegalStateException("timeout"));
        new BusinessSmsDispatcher(records, channels, provider).dispatch(record);
        verify(provider, times(1)).send(channel, record);
        assertUpdate(records, "UNKNOWN", "PROVIDER_EXCEPTION_NO_RETRY");
    }
    @Test void acceptedResultIsSavedWithNoDeliveredState() {
        var records = mock(SmsSendRecordMapper.class); var channels = mock(SmsChannelMapper.class); var provider = mock(BusinessSmsProvider.class);
        var record = record(); var channel = new SmsChannel(); channel.setEnabled(true); channel.setSupplier("ALIBABA"); when(channels.selectById(2L)).thenReturn(channel);
        when(provider.send(channel, record)).thenReturn(new BusinessSmsProvider.Outcome("ACCEPTED", "PROVIDER_ACCEPTED", "test"));
        new BusinessSmsDispatcher(records, channels, provider).dispatch(record);
        assertUpdate(records, "ACCEPTED", "PROVIDER_ACCEPTED");
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertUpdate(SmsSendRecordMapper mapper, String status, String code) {
        var captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        var wrapper = (LambdaUpdateWrapper<SmsSendRecord>) captor.getValue();
        wrapper.getSqlSet(); wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(status));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(code));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("PROCESSING"));
        assertFalse(wrapper.getParamNameValuePairs().containsValue("DELIVERED"));
    }
    private SmsSendRecord record() { var record = new SmsSendRecord(); record.setId(1L); record.setChannelId(2L); record.setSupplier("ALIBABA"); return record; }
}
