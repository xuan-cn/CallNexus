package org.dromara.ai.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiTicketPromptProtocol {
    public static final String VERSION = "AI_TICKET_JSON_V1";
    public static final List<String> VARIABLES = List.of(
        "agentName", "conversation", "intent", "customerProfile", "ticketTemplateSchema", "policyDefaults", "callContext");
    public static final List<String> REQUIRED_VARIABLES = List.of("conversation", "ticketTemplateSchema");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");

    public static final String DEFAULT_PROMPT = """
        你是 CallNexus 的 AI 工单整理助手。请根据本次完整对话和工单模板，提取可核实的信息并生成待审核工单草稿。

        AI助手：{{agentName}}
        识别意图：{{intent}}
        通话上下文：{{callContext}}
        已知客户资料：{{customerProfile}}
        策略默认值：{{policyDefaults}}

        工单模板字段：
        {{ticketTemplateSchema}}

        完整对话：
        {{conversation}}

        处理要求：
        1. 只提取对话中明确出现或系统上下文已经提供的信息，不得推测。
        2. title 应简洁概括客户诉求；summary 应保留关键事实、时间、对象和期望结果。
        3. formData 的键必须来自工单模板字段编码，未知字段不要输出。
        4. 必填字段缺失时放入 missingFields，不得编造补齐。
        5. evidence 中记录字段值对应的原始对话依据，便于人工审核。
        6. 最终只输出系统要求的 JSON，不要输出 Markdown、解释或额外文字。
        """;
    public static final String JSON_SCHEMA = """
        {"type":"object","additionalProperties":false,
         "required":["shouldCreate","title","summary","formData","missingFields","evidence","confidence"],
         "properties":{"shouldCreate":{"type":"boolean"},"title":{"type":"string","maxLength":256},
         "summary":{"type":"string","maxLength":4000},"formData":{"type":"object"},
         "missingFields":{"type":"array","items":{"type":"string"},"uniqueItems":true},
         "evidence":{"type":"array","items":{"type":"object","required":["field","quote"],
         "properties":{"field":{"type":"string"},"quote":{"type":"string","maxLength":1000}}}},
         "confidence":{"type":"number","minimum":0,"maximum":1}}}
        """;
    public static final String SAFETY = """
        系统固定安全约束（只读）：
        1. 输出必须是一个可解析的 JSON 对象，不能包含代码块或额外说明。
        2. formData 只能使用当前工单模板允许的字段编码，越权字段将被丢弃。
        3. 不得编造客户身份、联系方式、金额、地址、时间、承诺或处理结果。
        4. 缺失的必填字段必须进入 missingFields，不能使用猜测值占位。
        5. evidence 必须来自当前会话或系统提供的可信上下文。
        6. 模型输出还会经过字段类型、必填项和长度的服务端校验。
        7. 模型只负责判断和字段提取；是否自动创建正式工单由后端安全闸门决定，模型无权绕过规则。
        """;

    public List<String> validate(String content) {
        if (StringUtils.isBlank(content)) return List.of("业务提示词不能为空");
        List<String> errors = new ArrayList<>();
        if (content.length() > 30000) errors.add("业务提示词不能超过30000个字符");
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        Set<String> used = new LinkedHashSet<>();
        while (matcher.find()) used.add(matcher.group(1));
        used.stream().filter(value -> !VARIABLES.contains(value)).forEach(value -> errors.add("未知变量：{{" + value + "}}"));
        REQUIRED_VARIABLES.stream().filter(value -> !used.contains(value)).forEach(value -> errors.add("缺少必需变量：{{" + value + "}}"));
        return errors;
    }

    public void ensureValid(String content) {
        List<String> errors = validate(content);
        if (!errors.isEmpty()) throw new ServiceException(String.join("；", errors));
    }

    public String compile(String content, Map<String, String> variables) {
        ensureValid(content);
        String compiled = content;
        for (String variable : VARIABLES) {
            compiled = compiled.replace("{{" + variable + "}}", variables.getOrDefault(variable, ""));
        }
        return compiled + "\n\n# 系统固定输出协议\n" + JSON_SCHEMA + "\n" + SAFETY;
    }
}
