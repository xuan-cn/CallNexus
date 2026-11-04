package org.dromara.customer.sms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.customer.sms.domain.SmsSendRecord;
import org.dromara.customer.sms.mapper.SmsChannelMapper;
import org.dromara.customer.sms.mapper.SmsSendRecordMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class BusinessSmsDispatcher {
    private final SmsSendRecordMapper records;
    private final SmsChannelMapper channels;
    private final BusinessSmsProvider provider;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(), runnable -> { Thread thread = new Thread(runnable, "business-sms-sender"); thread.setDaemon(true); return thread; });

    @Scheduled(fixedDelay = 3000, initialDelay = 10000)
    public void scan() {
        try {
            // Only the internal scanner crosses tenants; every send is claimed and processed within its owning tenant.
            TenantHelper.ignore(() -> records.update(null, new LambdaUpdateWrapper<SmsSendRecord>()
                .eq(SmsSendRecord::getStatus, "PROCESSING").lt(SmsSendRecord::getProcessingAt, LocalDateTime.now().minusMinutes(5))
                .set(SmsSendRecord::getStatus, "UNKNOWN").set(SmsSendRecord::getResultCode, "PROCESSING_TIMEOUT_NO_RETRY")));
            var pending = TenantHelper.ignore(() -> records.selectList(new LambdaQueryWrapper<SmsSendRecord>()
                .eq(SmsSendRecord::getStatus, "QUEUED").orderByAsc(SmsSendRecord::getCreateTime).last("LIMIT 10")));
            for (SmsSendRecord record : pending) {
                TenantHelper.dynamic(record.getTenantId(), () -> {
                    if (records.update(null, new LambdaUpdateWrapper<SmsSendRecord>().eq(SmsSendRecord::getId, record.getId())
                        .eq(SmsSendRecord::getStatus, "QUEUED").set(SmsSendRecord::getStatus, "PROCESSING")
                        .set(SmsSendRecord::getProcessingAt, LocalDateTime.now())) != 1) return;
                    try {
                        executor.execute(() -> TenantHelper.dynamic(record.getTenantId(), () -> dispatch(record)));
                    } catch (RejectedExecutionException exception) {
                        records.update(null, new LambdaUpdateWrapper<SmsSendRecord>().eq(SmsSendRecord::getId, record.getId())
                            .eq(SmsSendRecord::getStatus, "PROCESSING").set(SmsSendRecord::getStatus, "QUEUED")
                            .set(SmsSendRecord::getProcessingAt, null));
                    }
                });
            }
        } catch (Exception exception) {
            // No credentials, message body, phone numbers or provider exception bodies in application logs.
            log.warn("业务短信队列扫描失败，异常类型={}", exception.getClass().getSimpleName());
        }
    }

    void dispatch(SmsSendRecord record) {
        BusinessSmsProvider.Outcome outcome;
        try {
            var channel = channels.selectById(record.getChannelId());
            if (channel == null || !Boolean.TRUE.equals(channel.getEnabled()) || !record.getSupplier().equals(channel.getSupplier())) {
                outcome = new BusinessSmsProvider.Outcome("REJECTED", "CHANNEL_DISABLED_OR_CHANGED", null);
            } else {
                outcome = provider.send(channel, record);
            }
        } catch (Exception exception) {
            outcome = new BusinessSmsProvider.Outcome("UNKNOWN", "PROVIDER_EXCEPTION_NO_RETRY", null);
        }
        records.update(null, new LambdaUpdateWrapper<SmsSendRecord>().eq(SmsSendRecord::getId, record.getId())
            .eq(SmsSendRecord::getStatus, "PROCESSING").set(SmsSendRecord::getStatus, outcome.status())
            .set(SmsSendRecord::getResultCode, outcome.code()).set(SmsSendRecord::getProviderReference, outcome.reference())
            .set(SmsSendRecord::getSubmittedAt, LocalDateTime.now()));
    }

    @PreDestroy
    void shutdown() { executor.shutdown(); }
}
