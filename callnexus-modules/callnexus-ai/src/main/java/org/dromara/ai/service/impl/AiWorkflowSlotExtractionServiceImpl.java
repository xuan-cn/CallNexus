package org.dromara.ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiModel;
import org.dromara.ai.domain.AiModelProvider;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiModelMapper;
import org.dromara.ai.mapper.AiModelProviderMapper;
import org.dromara.ai.provider.ChatMessage;
import org.dromara.ai.provider.ChatProviderRegistry;
import org.dromara.ai.provider.ChatRequest;
import org.dromara.ai.provider.ChatResult;
import org.dromara.ai.workflow.AiWorkflowSlotExtractionService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AiWorkflowSlotExtractionServiceImpl implements AiWorkflowSlotExtractionService {
    private final AiAgentMapper agentMapper;
    private final AiModelMapper modelMapper;
    private final AiModelProviderMapper providerMapper;
    private final ChatProviderRegistry chatRegistry;

    @Override
    public Map<String, Object> extract(Long aiAgentId, String sourceText, List<Target> targets) {
        if (aiAgentId == null) throw new ServiceException("信息提取节点未关联 AI 助手");
        if (StringUtils.isBlank(sourceText)) throw new ServiceException("信息提取节点没有可分析的文本");
        if (targets == null || targets.isEmpty()) throw new ServiceException("信息提取节点未选择提取字段");
        AiAgent agent = require(agentMapper.selectById(aiAgentId), "AI 助手不存在");
        AiModel model = require(modelMapper.selectById(agent.getChatModelId()), "AI 助手未配置聊天模型");
        if (!Boolean.TRUE.equals(model.getEnabled()) || !"CHAT".equals(model.getCapability())) {
            throw new ServiceException("AI 助手聊天模型不可用");
        }
        AiModelProvider provider = require(providerMapper.selectById(model.getProviderId()), "AI 模型服务商不存在");
        String fields = targets.stream()
            .map(item -> "- " + item.key() + "：" + StringUtils.blankToDefault(item.label(), item.key())
                + "，类型=" + StringUtils.blankToDefault(item.type(), "STRING"))
            .reduce((left, right) -> left + "\n" + right).orElse("");
        String systemPrompt = """
            你是通话信息结构化提取器。只能依据用户原文提取字段，不得推测或补全。
            仅返回一个 JSON 对象，键必须来自字段清单；未提及的字段不要返回。
            NUMBER 返回 JSON 数字，BOOLEAN 返回 true 或 false，其他类型返回字符串。
            字段清单：
            """ + fields;
        ChatResult result = chatRegistry.get(provider.getProviderType()).chat(new ChatRequest(provider, model,
            List.of(new ChatMessage("system", systemPrompt), new ChatMessage("user", sourceText)),
            BigDecimal.ZERO, Math.min(512, agent.getMaxOutputTokens() == null ? 512 : agent.getMaxOutputTokens())));
        return parse(result.content(), targets);
    }

    private Map<String, Object> parse(String content, List<Target> targets) {
        if (StringUtils.isBlank(content)) throw new ServiceException("信息提取模型返回为空");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) throw new ServiceException("信息提取模型未返回 JSON 对象");
        try {
            JsonNode root = JsonUtils.getObjectMapper().readTree(content.substring(start, end + 1));
            if (!root.isObject()) throw new ServiceException("信息提取模型返回格式无效");
            Set<String> allowed = targets.stream().map(Target::key).collect(java.util.stream.Collectors.toSet());
            Map<String, Object> values = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                if (!allowed.contains(entry.getKey()) || entry.getValue().isNull()) return;
                Target target = targets.stream().filter(item -> item.key().equals(entry.getKey())).findFirst().orElseThrow();
                values.put(entry.getKey(), convert(entry.getValue(), target.type()));
            });
            return values;
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("信息提取模型 JSON 解析失败：" + exception.getMessage());
        }
    }

    private Object convert(JsonNode value, String type) {
        return switch (StringUtils.blankToDefault(type, "STRING").toUpperCase()) {
            case "NUMBER" -> value.isNumber() ? value.numberValue() : new BigDecimal(value.asText());
            case "BOOLEAN" -> value.isBoolean() ? value.booleanValue() : Boolean.parseBoolean(value.asText());
            default -> value.isValueNode() ? value.asText() : value.toString();
        };
    }

    private <T> T require(T value, String message) {
        if (value == null) throw new ServiceException(message);
        return value;
    }
}
