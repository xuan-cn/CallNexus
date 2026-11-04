package org.dromara.ai.service;

import org.dromara.ai.domain.AiIntentMatchRule;
import org.dromara.common.core.exception.ServiceException;
import java.util.*;

/** Literal, bounded matching: user input is never executed as a regex. */
public final class IntentRuleMatcher {
    private IntentRuleMatcher() {}
    public static void validate(List<AiIntentMatchRule> rules) {
        if (rules == null) return;
        if (rules.size() > 40) throw new ServiceException("匹配规则最多 40 条");
        for (AiIntentMatchRule rule : rules) {
            if (rule == null || !Set.of("POSITIVE", "NEGATIVE").contains(Objects.toString(rule.getPolarity(), ""))
                || !Set.of("EXACT", "ANY", "ALL", "PREFIX", "SUFFIX").contains(Objects.toString(rule.getMode(), "")))
                throw new ServiceException("匹配规则类型无效");
            List<List<String>> groups = rule.getGroups();
            if (groups == null || groups.isEmpty() || groups.size() > 8 || (!"ALL".equals(rule.getMode()) && groups.size() != 1))
                throw new ServiceException("每条规则需配置 1 至 8 组词，只有同时包含支持多组");
            for (List<String> group : groups) {
                if (group == null || group.isEmpty() || group.size() > 100) throw new ServiceException("每组需配置 1 至 100 个词");
                for (String word : group) {
                    if (word == null || word.length() > 128 || normalize(word, rule).isBlank())
                        throw new ServiceException("关键词不能为空、仅含标点或超过 128 字");
                }
            }
        }
    }
    public static List<String> matchedWords(AiIntentMatchRule rule, String input) {
        String text = normalize(input, rule);
        List<String> matched = new ArrayList<>();
        for (List<String> group : rule.getGroups()) {
            List<String> hits = group.stream().filter(word -> {
                String term = normalize(word, rule);
                return switch (rule.getMode()) {
                    case "EXACT" -> text.equals(term);
                    case "PREFIX" -> text.startsWith(term);
                    case "SUFFIX" -> text.endsWith(term);
                    default -> text.contains(term);
                };
            }).toList();
            if (hits.isEmpty()) return List.of();
            matched.addAll(hits);
        }
        return matched;
    }
    private static String normalize(String text, AiIntentMatchRule rule) {
        String value = Objects.toString(text, "").trim().toLowerCase(Locale.ROOT);
        return Boolean.FALSE.equals(rule.getIgnorePunctuation()) ? value : value.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s\\p{Z}]+", "");
    }
}
