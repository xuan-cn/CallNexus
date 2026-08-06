package org.dromara.openapi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.openapi.domain.OpenApiApplication;
import org.dromara.openapi.domain.OpenApiEvent;
import org.dromara.openapi.domain.OpenApiEventDelivery;
import org.dromara.openapi.domain.response.OpenApiEventResponse;
import org.dromara.openapi.mapper.OpenApiApplicationMapper;
import org.dromara.openapi.mapper.OpenApiEventDeliveryMapper;
import org.dromara.openapi.mapper.OpenApiEventMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenApiWebhookDeliveryService {
    private static final int MAX_ATTEMPTS = 8;
    private static final long[] RETRY_SECONDS = {30, 120, 600, 1800, 3600, 10800, 21600, 43200};
    private final OpenApiEventDeliveryMapper deliveryMapper;
    private final OpenApiEventMapper eventMapper;
    private final OpenApiApplicationMapper applicationMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Scheduled(fixedDelay = 5000L)
    public void deliverPending() {
        LocalDateTime now = LocalDateTime.now();
        List<OpenApiEventDelivery> values = TenantHelper.ignore(() -> deliveryMapper.selectList(
            new LambdaQueryWrapper<OpenApiEventDelivery>()
                .and(wrapper -> wrapper
                    .in(OpenApiEventDelivery::getDeliveryStatus, List.of("PENDING", "RETRY"))
                    .le(OpenApiEventDelivery::getNextRetryAt, now)
                    .or(nested -> nested.eq(OpenApiEventDelivery::getDeliveryStatus, "PROCESSING")
                        .le(OpenApiEventDelivery::getProcessingStartedAt, now.minusMinutes(5))))
                .orderByAsc(OpenApiEventDelivery::getNextRetryAt).last("limit 50")));
        for (OpenApiEventDelivery delivery : values) {
            TenantHelper.dynamic(delivery.getTenantId(), () -> deliver(delivery.getId()));
        }
    }

    private void deliver(Long deliveryId) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = deliveryMapper.update(null, new LambdaUpdateWrapper<OpenApiEventDelivery>()
            .eq(OpenApiEventDelivery::getId, deliveryId)
            .in(OpenApiEventDelivery::getDeliveryStatus, List.of("PENDING", "RETRY", "PROCESSING"))
            .set(OpenApiEventDelivery::getDeliveryStatus, "PROCESSING")
            .set(OpenApiEventDelivery::getProcessingStartedAt, now));
        if (claimed != 1) return;
        OpenApiEventDelivery delivery = deliveryMapper.selectById(deliveryId);
        OpenApiApplication application = applicationMapper.selectById(delivery.getApplicationId());
        OpenApiEvent event = eventMapper.selectById(delivery.getEventId());
        if (application == null || event == null || !Boolean.TRUE.equals(application.getWebhookEnabled())) {
            failPermanently(delivery, "Webhook application or event is unavailable.");
            return;
        }
        try {
            String body = envelope(event);
            long timestamp = Instant.now().getEpochSecond();
            String signature = sign(application.getWebhookSecret(), timestamp + "." + body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(application.getWebhookUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-CallNexus-Event-Id", event.getId().toString())
                .header("X-CallNexus-Timestamp", Long.toString(timestamp))
                .header("X-CallNexus-Signature", "v1=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.setDeliveryStatus("SUCCESS");
                delivery.setAttemptCount(delivery.getAttemptCount() + 1);
                delivery.setLastHttpStatus(response.statusCode());
                delivery.setLastResponse(excerpt(response.body()));
                delivery.setFailureReason(null);
                delivery.setDeliveredAt(LocalDateTime.now());
                deliveryMapper.updateById(delivery);
            } else {
                retry(delivery, response.statusCode(), excerpt(response.body()), "Webhook returned non-2xx status.");
            }
        } catch (Exception exception) {
            retry(delivery, null, null, exception.getMessage());
        }
    }

    private void retry(OpenApiEventDelivery delivery, Integer status, String response, String reason) {
        int attempts = delivery.getAttemptCount() + 1;
        delivery.setAttemptCount(attempts);
        delivery.setLastHttpStatus(status);
        delivery.setLastResponse(response);
        delivery.setFailureReason(excerpt(reason));
        delivery.setProcessingStartedAt(null);
        if (attempts >= MAX_ATTEMPTS) {
            delivery.setDeliveryStatus("FAILED");
            delivery.setNextRetryAt(null);
        } else {
            delivery.setDeliveryStatus("RETRY");
            delivery.setNextRetryAt(LocalDateTime.now().plusSeconds(RETRY_SECONDS[Math.min(attempts - 1, RETRY_SECONDS.length - 1)]));
        }
        deliveryMapper.updateById(delivery);
    }

    private void failPermanently(OpenApiEventDelivery delivery, String reason) {
        delivery.setDeliveryStatus("FAILED");
        delivery.setFailureReason(reason);
        delivery.setProcessingStartedAt(null);
        deliveryMapper.updateById(delivery);
    }

    private String envelope(OpenApiEvent event) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("event_id", event.getId().toString());
        value.put("event_type", event.getEventType());
        value.put("business_call_id", event.getBusinessCallId());
        value.put("node_id", event.getNodeId());
        value.put("occurred_at", event.getOccurredAt());
        value.put("data", JsonUtils.parseMap(event.getPayloadJson()));
        return JsonUtils.toJsonString(value);
    }

    private String sign(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String excerpt(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
