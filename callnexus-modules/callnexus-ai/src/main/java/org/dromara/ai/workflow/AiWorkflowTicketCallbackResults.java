package org.dromara.ai.workflow;

import java.util.Map;

public final class AiWorkflowTicketCallbackResults {
    private static final Map<String, String> LABELS = Map.of(
        "TICKET_SATISFIED", "已解决、满意",
        "TICKET_ACCEPTABLE", "已解决、基本满意",
        "TICKET_DISSATISFIED", "已解决、不满意",
        "TICKET_UNRESOLVED", "未解决",
        "TICKET_UNCLEAR", "待确认",
        "TICKET_DECLINED", "拒绝评价"
    );

    private AiWorkflowTicketCallbackResults() {}

    public static boolean contains(String code) { return code != null && LABELS.containsKey(code); }
    public static String label(String code) { return LABELS.getOrDefault(code, code); }
}
