package org.dromara.outbound.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class OutboundResultSuggestionService {
    private static final Set<String> CUSTOMER_RETRYABLE = Set.of(
        "USER_BUSY", "NO_ANSWER", "NO_USER_RESPONSE", "CALL_REJECTED", "ORIGINATOR_CANCEL"
    );
    private static final Set<String> NUMBER_FAILURES = Set.of("UNALLOCATED_NUMBER", "INVALID_NUMBER_FORMAT");
    private static final Set<String> NETWORK_FAILURES = Set.of(
        "NORMAL_TEMPORARY_FAILURE", "NETWORK_OUT_OF_ORDER", "DESTINATION_OUT_OF_ORDER", "RECOVERY_ON_TIMER_EXPIRE"
    );
    private static final Set<String> PLATFORM_FAILURES = Set.of("SYSTEM_RECOVERED", "ORIGINATE_FAILED");
    private static final Map<String, String> HANGUP_CAUSE_LABELS = Map.ofEntries(
        Map.entry("NORMAL_CLEARING", "正常挂断"),
        Map.entry("USER_BUSY", "客户忙"),
        Map.entry("NO_ANSWER", "无人接听"),
        Map.entry("NO_USER_RESPONSE", "客户无响应"),
        Map.entry("CALL_REJECTED", "客户拒接"),
        Map.entry("ORIGINATOR_CANCEL", "主叫取消"),
        Map.entry("UNALLOCATED_NUMBER", "号码不存在"),
        Map.entry("INVALID_NUMBER_FORMAT", "号码格式无效"),
        Map.entry("NORMAL_TEMPORARY_FAILURE", "线路临时故障"),
        Map.entry("NETWORK_OUT_OF_ORDER", "网络故障"),
        Map.entry("DESTINATION_OUT_OF_ORDER", "被叫不可达"),
        Map.entry("SYSTEM_RECOVERED", "系统异常恢复"),
        Map.entry("ORIGINATE_FAILED", "发起外呼失败")
    );

    public String suggest(String hangupCause, Boolean destinationAnswered) {
        if (Boolean.TRUE.equals(destinationAnswered)) return "CONNECTED";
        if ("USER_BUSY".equals(hangupCause)) return "BUSY";
        if ("NO_ANSWER".equals(hangupCause) || "NO_USER_RESPONSE".equals(hangupCause)
            || "CALL_REJECTED".equals(hangupCause)) return "NO_ANSWER";
        if ("UNALLOCATED_NUMBER".equals(hangupCause) || "INVALID_NUMBER_FORMAT".equals(hangupCause)) {
            return "INVALID_NUMBER";
        }
        return "OTHER";
    }

    public String resultLabel(String resultCode) {
        if (resultCode == null) return null;
        return switch (resultCode) {
            case "CONNECTED" -> "已接通";
            case "NO_ANSWER" -> "无人接听";
            case "BUSY" -> "客户忙";
            case "INVALID_NUMBER" -> "号码无效";
            case "NOT_INTERESTED" -> "无意向";
            case "FOLLOW_UP" -> "需要跟进";
            default -> "其他";
        };
    }

    public String hangupCauseLabel(String hangupCause) {
        if (hangupCause == null || hangupCause.isBlank()) return null;
        return HANGUP_CAUSE_LABELS.getOrDefault(hangupCause, hangupCause);
    }

    public FailureClassification classify(String hangupCause, Boolean destinationAnswered) {
        if (Boolean.TRUE.equals(destinationAnswered)) {
            return new FailureClassification(null, false);
        }
        String cause = hangupCause == null ? "" : hangupCause.trim().toUpperCase();
        if (CUSTOMER_RETRYABLE.contains(cause)) return new FailureClassification("CUSTOMER", true);
        if (NUMBER_FAILURES.contains(cause)) return new FailureClassification("NUMBER", false);
        if (NETWORK_FAILURES.contains(cause)) return new FailureClassification("NETWORK", true);
        if (PLATFORM_FAILURES.contains(cause)) return new FailureClassification("PLATFORM", true);
        return new FailureClassification("UNKNOWN", true);
    }

    public String failureCategoryLabel(String category) {
        if (category == null) return null;
        return switch (category) {
            case "CUSTOMER" -> "客户侧未接通";
            case "NUMBER" -> "号码问题";
            case "NETWORK" -> "线路或网络问题";
            case "PLATFORM" -> "平台执行异常";
            default -> "未分类失败";
        };
    }

    public record FailureClassification(String category, boolean retryable) {
    }
}
