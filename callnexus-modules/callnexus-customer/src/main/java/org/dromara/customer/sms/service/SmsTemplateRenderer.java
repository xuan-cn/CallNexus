package org.dromara.customer.sms.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.sms.domain.SmsRequests.Variable;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import java.util.*;
import java.util.regex.Pattern;

public final class SmsTemplateRenderer {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    private SmsTemplateRenderer() { }

    public static void validate(String content, List<Variable> variables, boolean tencent) {
        Set<String> keys = new LinkedHashSet<>();
        for (Variable variable : variables) if (!keys.add(variable.key())) throw new ServiceException("模板变量不能重复");
        Set<String> placeholders = new HashSet<>();
        var matcher = TOKEN.matcher(content);
        while (matcher.find()) placeholders.add(matcher.group(1));
        if (!keys.equals(placeholders)) throw new ServiceException("模板正文中的 ${变量} 必须与变量配置一一对应");
        if (tencent) for (int i=1; i<=variables.size(); i++) {
            if (!variables.get(i-1).key().equals(String.valueOf(i))) throw new ServiceException("腾讯云变量需按 1、2、3 顺序配置");
        }
    }

    public static LinkedHashMap<String, String> resolve(List<Variable> variables, Map<String,String> supplied, CustomerResponse customer) {
        Set<String> allowed = new HashSet<>();
        variables.forEach(variable -> { if (customer == null || "MANUAL".equals(variable.source())) allowed.add(variable.key()); });
        if (!allowed.containsAll(supplied.keySet())) throw new ServiceException("不能覆盖自动映射变量或传入未知变量");
        LinkedHashMap<String,String> result = new LinkedHashMap<>();
        for (Variable variable : variables) {
            String value = customer == null ? supplied.get(variable.key()) : switch (variable.source()) {
                case "CUSTOMER_NAME" -> customer.getCustomerName();
                case "PRIMARY_PHONE" -> customer.getPrimaryPhone();
                default -> supplied.get(variable.key());
            };
            if (value == null || value.isBlank() || value.length()>200) throw new ServiceException("缺少有效的模板变量：" + variable.key());
            result.put(variable.key(), value.trim());
        }
        return result;
    }

    public static String render(String content, Map<String,String> parameters) {
        return TOKEN.matcher(content).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(parameters.get(match.group(1))));
    }
}
