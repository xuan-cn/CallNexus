package org.dromara.ai.workflow;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiWorkflowTemplateResolver {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}");

    public String resolve(String template, Map<String, Object> variables) {
        if (template == null || template.isBlank()) return "";
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = displayValue(key, variables.get(key));
            if (value.isBlank() && "customer.salutation".equals(key)) value = salutation(variables);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String salutation(Map<String, Object> variables) {
        String name = stringValue(variables.get("customer.name"));
        if (name.isBlank()) return "客户";
        if (name.endsWith("先生") || name.endsWith("女士")) return name;
        String gender = stringValue(variables.get("customer.gender"));
        return name + ("FEMALE".equalsIgnoreCase(gender) ? "女士" : "MALE".equalsIgnoreCase(gender) ? "先生" : "");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String displayValue(String key, Object value) {
        String text = stringValue(value);
        if (!"customer.gender".equals(key)) return text;
        if ("MALE".equalsIgnoreCase(text)) return "男";
        if ("FEMALE".equalsIgnoreCase(text)) return "女";
        if ("UNKNOWN".equalsIgnoreCase(text)) return "";
        return text;
    }
}
