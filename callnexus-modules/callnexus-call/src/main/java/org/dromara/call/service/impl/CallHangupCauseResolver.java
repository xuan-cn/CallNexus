package org.dromara.call.service.impl;

import org.dromara.call.domain.CallLeg;
import org.dromara.common.core.utils.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

final class CallHangupCauseResolver {

    private CallHangupCauseResolver() {
    }

    static String preserveFirst(String current, String incoming) {
        return StringUtils.isNotBlank(current) ? current : incoming;
    }

    static LocalDateTime preserveFirst(LocalDateTime current, LocalDateTime incoming) {
        return current == null ? incoming : current;
    }

    static String resolveSessionCause(List<CallLeg> legs, String current, String fallback) {
        if (legs != null) {
            String cause = legs.stream()
                .filter(leg -> StringUtils.isNotBlank(leg.getHangupCause()))
                .min(Comparator.comparingInt(CallHangupCauseResolver::rolePriority)
                    .thenComparing(CallLeg::getEndedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(CallLeg::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(CallLeg::getHangupCause)
                .orElse(null);
            if (StringUtils.isNotBlank(cause)) {
                return cause;
            }
        }
        return preserveFirst(current, fallback);
    }

    private static int rolePriority(CallLeg leg) {
        return switch (StringUtils.defaultString(leg.getLegRole())) {
            case "CUSTOMER" -> 0;
            case "AGENT", "EXTENSION", "PICKUP", "CONSULT_AGENT" -> 1;
            default -> 2;
        };
    }
}
