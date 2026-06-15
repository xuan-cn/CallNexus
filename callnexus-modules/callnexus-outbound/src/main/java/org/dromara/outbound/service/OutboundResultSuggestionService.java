package org.dromara.outbound.service;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class OutboundResultSuggestionService {
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
}
