package org.dromara.ai.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiModel;
import org.dromara.ai.domain.AiModelProvider;
import org.dromara.ai.mapper.AiModelMapper;
import org.dromara.ai.mapper.AiModelProviderMapper;
import org.dromara.ai.provider.ChatMessage;
import org.dromara.ai.provider.ChatProviderRegistry;
import org.dromara.ai.provider.ChatRequest;
import org.dromara.ai.provider.ChatResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiQualityReviewModelServiceImpl implements AiQualityReviewModelService {
    private static final Set<String> RESULTS = Set.of("PASSED", "FAILED", "NOT_APPLICABLE", "NEEDS_MANUAL_REVIEW");
    private final AiModelMapper modelMapper;
    private final AiModelProviderMapper providerMapper;
    private final ChatProviderRegistry chatRegistry;

    @Override
    public AiQualityReviewResult review(AiQualityReviewRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ServiceException("AI 质检没有可分析的评分项");
        }
        if (request.segments() == null || request.segments().isEmpty()) {
            throw new ServiceException("通话尚未生成可用的语音转写，无法执行 AI 初审");
        }
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
            .eq(AiModel::getCapability, "CHAT").eq(AiModel::getEnabled, true)
            .eq(AiModel::getDefaultModel, true)
            .orderByAsc(AiModel::getId).last("LIMIT 1"));
        if (model == null) throw new ServiceException("未配置可用的默认聊天模型");
        AiModelProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            throw new ServiceException("默认聊天模型的服务商不存在或已停用");
        }
        provider.setConnectTimeoutSeconds(Math.min(10,
            provider.getConnectTimeoutSeconds() == null || provider.getConnectTimeoutSeconds() <= 0 ? 10 : provider.getConnectTimeoutSeconds()));
        provider.setReadTimeoutSeconds(Math.min(90,
            provider.getReadTimeoutSeconds() == null || provider.getReadTimeoutSeconds() <= 0 ? 90 : provider.getReadTimeoutSeconds()));
        int inputChars = request.segments().stream().mapToInt(item -> item.text() == null ? 0 : item.text().length()).sum();
        int maxTokens = Math.min(2048, Math.max(768, request.items().size() * 180 + 400));
        long startedAt = System.currentTimeMillis();
        log.info("AI 质检模型调用开始，modelId={}，model={}，items={}，segments={}，inputChars={}，maxTokens={}，timeoutSeconds={}",
            model.getId(), model.getModelName(), request.items().size(), request.segments().size(), inputChars, maxTokens,
            provider.getReadTimeoutSeconds());
        ChatResult chat = chatRegistry.get(provider.getProviderType()).chat(new ChatRequest(provider, model,
            List.of(new ChatMessage("system", systemPrompt(request)),
                new ChatMessage("user", JsonUtils.toJsonString(request))), BigDecimal.ZERO, maxTokens));
        AiQualityReviewResult result = parse(model.getId(), chat == null ? null : chat.content(), request);
        log.info("AI 质检模型调用完成，modelId={}，items={}，segments={}，inputChars={}，costMs={}",
            model.getId(), request.items().size(), request.segments().size(), inputChars, System.currentTimeMillis() - startedAt);
        return result;
    }

    private String systemPrompt(AiQualityReviewRequest request) {
        return """
            你是呼叫中心通话质检初审引擎。只能依据提供的通话分句和评分项判断，不得推测。
            必须为每个评分项输出一项，itemCode 必须原样使用。无法可靠判断时输出 NEEDS_MANUAL_REVIEW。
            result 只能是 PASSED、FAILED、NOT_APPLICABLE、NEEDS_MANUAL_REVIEW；confidence 为 0 到 1。
            evidence 只能引用输入中真实存在的 segmentId，quote 必须是该分句原文中的连续文本。
            评分和是否合格由后端重新计算，不要输出总分。
            只输出 JSON：
            {"summary":"客观总结","improvementSuggestion":"改进建议","items":[{"itemCode":"编码","result":"PASSED","confidence":0.95,"reason":"依据","evidence":[{"segmentId":1,"quote":"原文"}]}]}
            """;
    }

    private AiQualityReviewResult parse(Long modelId, String content, AiQualityReviewRequest request) {
        if (StringUtils.isBlank(content)) throw new ServiceException("AI 质检模型返回为空");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) throw new ServiceException("AI 质检模型未返回 JSON 对象");
        String json = content.substring(start, end + 1);
        try {
            JsonNode root = JsonUtils.getObjectMapper().readTree(json);
            Map<String, AiQualityReviewRequest.Segment> segments = request.segments().stream()
                .filter(item -> item.segmentId() != null)
                .collect(Collectors.toMap(item -> String.valueOf(item.segmentId()), Function.identity(), (left, right) -> left));
            Map<String, JsonNode> outputs = new LinkedHashMap<>();
            if (root.path("items").isArray()) {
                root.path("items").forEach(item -> outputs.putIfAbsent(item.path("itemCode").asText(), item));
            }
            List<AiQualityReviewResult.Item> items = new ArrayList<>();
            for (AiQualityReviewRequest.Item expected : request.items()) {
                JsonNode node = outputs.get(expected.itemCode());
                if (node == null) {
                    items.add(new AiQualityReviewResult.Item(expected.itemCode(), "NEEDS_MANUAL_REVIEW",
                        BigDecimal.ZERO, "模型未返回该评分项", List.of()));
                    continue;
                }
                String result = node.path("result").asText("NEEDS_MANUAL_REVIEW").toUpperCase(Locale.ROOT);
                if (!RESULTS.contains(result) || ("NOT_APPLICABLE".equals(result) && !Boolean.TRUE.equals(expected.allowNotApplicable()))) {
                    result = "NEEDS_MANUAL_REVIEW";
                }
                BigDecimal confidence = node.path("confidence").isNumber()
                    ? node.path("confidence").decimalValue().max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    : BigDecimal.ZERO;
                List<AiQualityReviewResult.Evidence> evidence = new ArrayList<>();
                if (node.path("evidence").isArray()) {
                    node.path("evidence").forEach(value -> {
                        AiQualityReviewRequest.Segment segment = segments.get(value.path("segmentId").asText());
                        String quote = value.path("quote").asText("").trim();
                        if (segment == null) throw new ServiceException("AI 质检引用了不属于当前通话的分句");
                        if (StringUtils.isBlank(quote) || segment.text() == null || !segment.text().contains(quote)) {
                            throw new ServiceException("AI 质检证据原文与通话分句不一致");
                        }
                        evidence.add(new AiQualityReviewResult.Evidence(segment.segmentId(), segment.startMs(), segment.endMs(), quote));
                    });
                }
                items.add(new AiQualityReviewResult.Item(expected.itemCode(), result, confidence,
                    node.path("reason").asText(""), evidence));
            }
            return new AiQualityReviewResult(modelId, json, root.path("summary").asText(""),
                root.path("improvementSuggestion").asText(""), items);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("AI 质检模型 JSON 解析失败：" + exception.getMessage());
        }
    }
}
