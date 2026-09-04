package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.AiIntentBatchUpdateRequest;
import org.dromara.ai.domain.request.AiIntentQuery;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.request.AiIntentRequest;
import org.dromara.ai.domain.request.AiIntentUtteranceRequest;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.domain.response.AiIntentResponse;
import org.dromara.ai.domain.response.AiIntentUtteranceResponse;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.ai.knowledge.KnowledgeTextUtils;
import org.dromara.ai.vector.VectorPoint;
import org.dromara.ai.vector.VectorSearchHit;
import org.dromara.ai.vector.VectorStore;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiIntentApplicationServiceImpl implements AiIntentApplicationService {
    private static final Set<String> INTENT_TYPES = Set.of("CONVERSATION", "CONTROL", "ROUTING", "BUSINESS");
    private static final Set<String> ACTION_TYPES = Set.of(
        "NONE", "CHAT_REPLY", "REPEAT_LAST_REPLY", "STOP_PLAYBACK",
        "TRANSFER_QUEUE", "TRANSFER_EXTENSION", "TRANSFER_IVR", "TRANSFER_ONLINE_SERVICE", "CREATE_TICKET", "END_CALL", "KNOWLEDGE_QUERY");
    private static final Set<String> UTTERANCE_TYPES = Set.of("POSITIVE", "NEGATIVE");
    private static final String VECTOR_SOURCE_TYPE = "INTENT_UTTERANCE";
    private static final int VECTOR_SEARCH_LIMIT = 100;

    private final AiIntentMapper intentMapper;
    private final AiIntentGroupMapper intentGroupMapper;
    private final AiIntentUtteranceMapper utteranceMapper;
    private final AiAgentIntentMapper agentIntentMapper;
    private final AiIntentRecognitionLogMapper recognitionLogMapper;
    private final AiAgentMapper agentMapper;
    private final AiModelMapper modelMapper;
    private final AiModelProviderMapper providerMapper;
    private final EmbeddingProviderRegistry embeddingRegistry;
    private final VectorStore vectorStore;
    private final Map<String, String> indexedCatalogFingerprints = new ConcurrentHashMap<>();
    private final Map<String, Object> indexLocks = new ConcurrentHashMap<>();

    @Override
    public List<AiIntentResponse> intents() {
        return responses(intentMapper.selectList(new LambdaQueryWrapper<AiIntent>()
            .orderByDesc(AiIntent::getCreateTime).orderByDesc(AiIntent::getId)));
    }

    @Override
    public TableDataInfo<AiIntentResponse> page(AiIntentQuery query, PageQuery pageQuery) {
        Page<AiIntent> page = intentMapper.selectPage(pageQuery.build(), intentQuery(query));
        return new TableDataInfo<>(responses(page.getRecords()), page.getTotal());
    }

    @Override
    public AiIntentResponse intent(Long id) {
        return response(requireIntent(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createIntent(AiIntentRequest request) {
        ensureCode(request.getIntentCode(), null);
        AiIntent intent = new AiIntent();
        fill(intent, request);
        intentMapper.insert(intent);
        saveUtterances(intent.getId(), request.getUtterances());
        saveBindings(intent.getId(), request.getAgentIds());
        scheduleIntentReindex(intent.getId());
        return intent.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateIntent(Long id, AiIntentRequest request) {
        AiIntent intent = requireIntent(id);
        ensureCode(request.getIntentCode(), id);
        fill(intent, request);
        intentMapper.updateById(intent);
        String tenantId = TenantHelper.getTenantId();
        utteranceMapper.deletePhysicallyByIntentId(tenantId, id);
        agentIntentMapper.deletePhysicallyByIntentId(tenantId, id);
        saveUtterances(id, request.getUtterances());
        saveBindings(id, request.getAgentIds());
        scheduleIntentReindex(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteIntent(Long id) {
        requireIntent(id);
        String tenantId = TenantHelper.getTenantId();
        utteranceMapper.deletePhysicallyByIntentId(tenantId, id);
        agentIntentMapper.deletePhysicallyByIntentId(tenantId, id);
        intentMapper.deleteById(id);
        scheduleIntentVectorDelete(tenantId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdate(AiIntentBatchUpdateRequest request) {
        List<Long> ids = request.getIntentIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) throw new ServiceException("请选择需要处理的意图");
        if (request.getGroupId() != null && intentGroupMapper.selectById(request.getGroupId()) == null) {
            throw new ServiceException("意图分类不存在");
        }
        boolean updateGroup = request.getGroupId() != null || Boolean.TRUE.equals(request.getClearGroup());
        if (!updateGroup && request.getEnabled() == null) throw new ServiceException("没有需要更新的内容");
        for (AiIntent intent : intentMapper.selectBatchIds(ids)) {
            if (updateGroup) intent.setGroupId(Boolean.TRUE.equals(request.getClearGroup()) ? null : request.getGroupId());
            if (request.getEnabled() != null) intent.setEnabled(request.getEnabled());
            intentMapper.updateById(intent);
            scheduleIntentReindex(intent.getId());
        }
    }

    @Override
    public AiIntentRecognitionResponse recognize(AiIntentRecognitionRequest request) {
        long started = System.currentTimeMillis();
        if (request == null || request.getAgentId() == null) {
            throw new ServiceException("AI 助手不能为空");
        }
        String inputText = StringUtils.trim(request.getText());
        if (StringUtils.isBlank(inputText)) {
            throw new ServiceException("意图识别文本不能为空");
        }
        AiAgent agent = agentMapper.selectById(request.getAgentId());
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) {
            throw new ServiceException("AI 助手不存在或未启用");
        }
        String normalized = normalize(inputText);
        List<AiIntent> candidates = scopedCandidates(boundIntents(agent.getId()), request);
        if (candidates.isEmpty()) {
            return persist(request, inputText, normalized,
                unmatched("NONE", "当前 AI 助手未绑定可用意图"), started, null);
        }
        Map<Long, List<AiIntentUtterance>> utteranceCatalog = utterancesByIntent(candidates);

        Set<Long> negativeMatches = new HashSet<>();
        for (AiIntent intent : candidates) {
            List<AiIntentUtterance> utterances = utteranceCatalog.getOrDefault(intent.getId(), List.of());
            boolean negative = utterances.stream().anyMatch(item ->
                "NEGATIVE".equals(item.getUtteranceType()) && normalized.equals(item.getNormalizedText()));
            if (negative) {
                negativeMatches.add(intent.getId());
                continue;
            }
            boolean positive = utterances.stream().anyMatch(item ->
                "POSITIVE".equals(item.getUtteranceType()) && normalized.equals(item.getNormalizedText()));
            if (positive) {
                return persist(request, inputText, normalized, matched(intent, BigDecimal.ONE, "EXACT",
                    "与正例话术精确匹配", null), started, null);
            }
        }

        List<AiIntent> vectorCandidates = candidates.stream()
            .filter(item -> !negativeMatches.contains(item.getId())).toList();
        if (vectorCandidates.isEmpty()) {
            return persist(request, inputText, normalized,
                unmatched("NONE", "文本命中反例话术，已排除全部候选意图"), started, null);
        }
        Long embeddingModelId = null;
        try {
            AiModel model = requireDefaultEmbeddingModel();
            embeddingModelId = model.getId();
            AiModelProvider provider = requireEnabledProvider(model.getProviderId());
            ensureCatalogIndexed(agent.getId(), model, candidates, utteranceCatalog);
            EmbeddingResult embedding = embeddingRegistry.get(provider.getProviderType()).embed(
                new EmbeddingRequest(provider, model, List.of(inputText)));
            validateEmbedding(embedding, model, 1);
            List<VectorSearchHit> hits = vectorStore.search(intentCollection(TenantHelper.getTenantId(), model),
                embedding.vectors().get(0), Map.of(
                    "tenantId", TenantHelper.getTenantId(),
                    "sourceType", VECTOR_SOURCE_TYPE,
                    "intentId", vectorCandidates.stream().map(AiIntent::getId).toList()),
                Math.min(VECTOR_SEARCH_LIMIT, Math.max(20, vectorCandidates.size() * 6)));
            AiIntentRecognitionResponse recognition = recognizeByVector(vectorCandidates, hits);
            return persist(request, inputText, normalized, recognition, started, model.getId());
        } catch (Exception exception) {
            log.warn("AI 意图向量识别失败，agentId={}，error={}", request.getAgentId(), exception.getMessage(), exception);
            AiIntentRecognitionResponse failed = unmatched("VECTOR", "向量识别失败：" + limit(exception.getMessage(), 800));
            return persist(request, inputText, normalized, failed, started, embeddingModelId, "FAILED");
        }
    }

    private AiIntentRecognitionResponse recognizeByVector(List<AiIntent> candidates, List<VectorSearchHit> hits) {
        Map<Long, AiIntent> candidateMap = candidates.stream()
            .collect(Collectors.toMap(AiIntent::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Long, Double> positiveScores = new HashMap<>();
        Map<Long, Double> negativeScores = new HashMap<>();
        Map<Long, String> matchedUtterances = new HashMap<>();
        for (VectorSearchHit hit : hits) {
            Long intentId = payloadLong(hit.payload(), "intentId");
            if (intentId == null || !candidateMap.containsKey(intentId)) {
                continue;
            }
            String type = Objects.toString(hit.payload().get("utteranceType"), "");
            if ("NEGATIVE".equals(type)) {
                negativeScores.merge(intentId, hit.score(), Math::max);
            } else if ("POSITIVE".equals(type)) {
                if (hit.score() >= positiveScores.getOrDefault(intentId, -1D)) {
                    positiveScores.put(intentId, hit.score());
                    matchedUtterances.put(intentId, Objects.toString(hit.payload().get("utteranceText"), ""));
                }
            }
        }

        Map<Long, Integer> priorities = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            priorities.put(candidates.get(index).getId(), index);
        }
        AiIntent best = candidates.stream()
            .filter(intent -> {
                double positive = positiveScores.getOrDefault(intent.getId(), -1D);
                double threshold = intent.getConfidenceThreshold() == null ? 0.80D : intent.getConfidenceThreshold().doubleValue();
                double negative = negativeScores.getOrDefault(intent.getId(), -1D);
                return positive >= threshold && positive > negative;
            })
            .sorted(Comparator
                .<AiIntent>comparingDouble(intent -> positiveScores.getOrDefault(intent.getId(), -1D)).reversed()
                .thenComparingInt(intent -> priorities.getOrDefault(intent.getId(), Integer.MAX_VALUE)))
            .findFirst()
            .orElse(null);
        if (best == null) {
            double bestScore = positiveScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0D);
            AiIntentRecognitionResponse result = unmatched("VECTOR", positiveScores.isEmpty()
                ? "当前助手的意图话术没有可用向量命中"
                : "最高向量相似度未达到意图阈值，或反例相似度更高");
            result.setConfidence(confidence(bestScore));
            return result;
        }
        double score = positiveScores.get(best.getId());
        String utterance = matchedUtterances.get(best.getId());
        return matched(best, confidence(score), "VECTOR",
            "与正例话术“" + limit(utterance, 120) + "”向量相似度为 " + confidence(score), null);
    }

    private void ensureCatalogIndexed(Long agentId, AiModel model, List<AiIntent> candidates,
                                      Map<Long, List<AiIntentUtterance>> utteranceCatalog) {
        String tenantId = TenantHelper.getTenantId();
        List<AiIntentUtterance> utterances = candidates.stream()
            .flatMap(intent -> utteranceCatalog.getOrDefault(intent.getId(), List.of()).stream())
            .toList();
        String fingerprint = catalogFingerprint(model, candidates, utterances);
        String cacheKey = tenantId + ":" + model.getId() + ":" + agentId;
        if (fingerprint.equals(indexedCatalogFingerprints.get(cacheKey))) {
            return;
        }
        Object lock = indexLocks.computeIfAbsent(cacheKey, ignored -> new Object());
        synchronized (lock) {
            if (fingerprint.equals(indexedCatalogFingerprints.get(cacheKey))) {
                return;
            }
            indexUtterances(tenantId, model, candidates, utterances);
            indexedCatalogFingerprints.put(cacheKey, fingerprint);
        }
    }

    private void indexUtterances(String tenantId, AiModel model, List<AiIntent> intents,
                                 List<AiIntentUtterance> utterances) {
        String collection = intentCollection(tenantId, model);
        vectorStore.ensureCollection(collection, model.getVectorDimension());
        List<Long> intentIds = intents.stream().map(AiIntent::getId).distinct().toList();
        if (!intentIds.isEmpty()) {
            vectorStore.deleteByFilter(collection, Map.of(
                "tenantId", tenantId,
                "sourceType", VECTOR_SOURCE_TYPE,
                "intentId", intentIds));
        }
        if (utterances.isEmpty()) {
            return;
        }
        AiModelProvider provider = requireEnabledProvider(model.getProviderId());
        Map<Long, AiIntent> intentMap = intents.stream()
            .collect(Collectors.toMap(AiIntent::getId, item -> item));
        int batchSize = Math.max(1, model.getMaxBatchSize() == null ? 16 : model.getMaxBatchSize());
        for (int offset = 0; offset < utterances.size(); offset += batchSize) {
            List<AiIntentUtterance> batch = utterances.subList(offset, Math.min(utterances.size(), offset + batchSize));
            EmbeddingResult result = embeddingRegistry.get(provider.getProviderType()).embed(
                new EmbeddingRequest(provider, model, batch.stream().map(AiIntentUtterance::getUtteranceText).toList()));
            validateEmbedding(result, model, batch.size());
            List<VectorPoint> points = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                AiIntentUtterance utterance = batch.get(index);
                AiIntent intent = intentMap.get(utterance.getIntentId());
                if (intent == null) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tenantId", tenantId);
                payload.put("sourceType", VECTOR_SOURCE_TYPE);
                payload.put("embeddingModelId", model.getId());
                payload.put("intentId", intent.getId());
                payload.put("intentCode", intent.getIntentCode());
                payload.put("utteranceId", utterance.getId());
                payload.put("utteranceType", utterance.getUtteranceType());
                payload.put("utteranceText", utterance.getUtteranceText());
                points.add(new VectorPoint(intentPointId(model.getId(), utterance.getId()),
                    result.vectors().get(index), payload));
            }
            vectorStore.upsert(collection, points);
        }
        log.info("AI 意图话术向量已更新，tenantId={}，embeddingModelId={}，intentCount={}，utteranceCount={}，collection={}",
            tenantId, model.getId(), intents.size(), utterances.size(), collection);
    }

    private void scheduleIntentReindex(Long intentId) {
        String tenantId = TenantHelper.getTenantId();
        runAfterCommit(() -> TenantHelper.dynamic(tenantId, () -> {
            indexedCatalogFingerprints.clear();
            try {
                AiIntent intent = intentMapper.selectById(intentId);
                AiModel model = requireDefaultEmbeddingModel();
                if (intent == null || !Boolean.TRUE.equals(intent.getEnabled())) {
                    deleteIntentVectors(tenantId, model, intentId);
                    return;
                }
                indexUtterances(tenantId, model, List.of(intent), utterances(intentId));
            } catch (Exception exception) {
                log.warn("AI 意图保存成功，但话术向量更新失败，将在首次识别时重试，intentId={}，error={}",
                    intentId, exception.getMessage(), exception);
            }
        }));
    }

    private void scheduleIntentVectorDelete(String tenantId, Long intentId) {
        runAfterCommit(() -> TenantHelper.dynamic(tenantId, () -> {
            indexedCatalogFingerprints.clear();
            try {
                deleteIntentVectors(tenantId, requireDefaultEmbeddingModel(), intentId);
            } catch (Exception exception) {
                log.warn("AI 意图已删除，但历史话术向量清理失败，intentId={}，error={}",
                    intentId, exception.getMessage(), exception);
            }
        }));
    }

    private void deleteIntentVectors(String tenantId, AiModel model, Long intentId) {
        String collection = intentCollection(tenantId, model);
        vectorStore.ensureCollection(collection, model.getVectorDimension());
        vectorStore.deleteByFilter(collection, Map.of(
            "tenantId", tenantId,
            "sourceType", VECTOR_SOURCE_TYPE,
            "intentId", intentId));
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private AiModel requireDefaultEmbeddingModel() {
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
            .eq(AiModel::getCapability, "EMBEDDING")
            .eq(AiModel::getEnabled, true)
            .eq(AiModel::getDefaultModel, true)
            .orderByAsc(AiModel::getId)
            .last("limit 1"));
        if (model == null) {
            throw new ServiceException("未配置默认 Embedding 模型，无法执行意图向量识别");
        }
        if (model.getVectorDimension() == null || model.getVectorDimension() <= 0) {
            throw new ServiceException("默认 Embedding 模型未检测向量维度，请先执行模型测试");
        }
        return model;
    }

    private AiModelProvider requireEnabledProvider(Long providerId) {
        AiModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            throw new ServiceException("默认 Embedding 模型的服务商不存在或未启用");
        }
        return provider;
    }

    private void validateEmbedding(EmbeddingResult result, AiModel model, int expectedCount) {
        if (result == null || result.vectors() == null || result.vectors().size() != expectedCount) {
            throw new ServiceException("Embedding 模型返回的向量数量不正确");
        }
        if (result.dimension() != model.getVectorDimension()) {
            throw new ServiceException("Embedding 向量维度不一致，配置=" + model.getVectorDimension()
                + "，实际=" + result.dimension());
        }
    }

    private String catalogFingerprint(AiModel model, List<AiIntent> intents, List<AiIntentUtterance> utterances) {
        String intentPart = intents.stream().map(intent -> intent.getId() + ":" + intent.getVersion())
            .collect(Collectors.joining(","));
        String utterancePart = utterances.stream().map(utterance -> utterance.getId() + ":" + utterance.getTextHash())
            .collect(Collectors.joining(","));
        return sha256(model.getId() + "|" + model.getVectorDimension() + "|" + intentPart + "|" + utterancePart);
    }

    private String intentCollection(String tenantId, AiModel model) {
        return "cnx_intent_" + KnowledgeTextUtils.sha256(tenantId).substring(0, 12)
            + "_" + model.getId() + "_" + model.getVectorDimension();
    }

    private String intentPointId(Long modelId, Long utteranceId) {
        return UUID.nameUUIDFromBytes(("intent:" + modelId + ":" + utteranceId)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Long payloadLong(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal confidence(double score) {
        return BigDecimal.valueOf(Math.max(0D, Math.min(1D, score))).setScale(6, RoundingMode.HALF_UP);
    }

    private AiIntentRecognitionResponse persist(AiIntentRecognitionRequest request, String inputText, String normalized,
                                                AiIntentRecognitionResponse result, long started, Long modelId) {
        return persist(request, inputText, normalized, result, started, modelId,
            result.isMatched() ? "MATCHED" : "UNMATCHED");
    }

    private AiIntentRecognitionResponse persist(AiIntentRecognitionRequest request, String inputText, String normalized,
                                                AiIntentRecognitionResponse result, long started, Long modelId,
                                                String status) {
        result.setLatencyMs(System.currentTimeMillis() - started);
        AiIntentRecognitionLog value = new AiIntentRecognitionLog();
        value.setAgentId(request.getAgentId());
        value.setInputText(inputText);
        value.setNormalizedText(normalized);
        value.setIntentId(result.getIntentId());
        value.setIntentCode(result.getIntentCode());
        value.setIntentName(result.getIntentName());
        value.setConfidence(result.getConfidence());
        value.setMatchMethod(result.getMatchMethod());
        value.setRecognitionStatus(status);
        value.setReason(result.getReason());
        value.setLatencyMs(result.getLatencyMs());
        value.setModelId(modelId);
        value.setRawResponse(result.getRawResponse());
        recognitionLogMapper.insert(value);
        return result;
    }

    private AiIntentRecognitionResponse matched(AiIntent intent, BigDecimal confidence, String method,
                                                String reason, String raw) {
        AiIntentRecognitionResponse value = new AiIntentRecognitionResponse();
        value.setMatched(true);
        value.setIntentId(intent.getId());
        value.setIntentCode(intent.getIntentCode());
        value.setIntentName(intent.getIntentName());
        value.setIntentType(intent.getIntentType());
        value.setActionType(intent.getActionType());
        value.setActionConfigJson(intent.getActionConfigJson());
        value.setResponseTemplate(intent.getResponseTemplate());
        value.setConfirmationRequired(intent.getConfirmationRequired());
        value.setConfidence(confidence);
        value.setMatchMethod(method);
        value.setReason(reason);
        value.setRawResponse(raw);
        return value;
    }

    private AiIntentRecognitionResponse unmatched(String method, String reason) {
        AiIntentRecognitionResponse value = new AiIntentRecognitionResponse();
        value.setMatched(false);
        value.setConfidence(BigDecimal.ZERO);
        value.setMatchMethod(method);
        value.setReason(reason);
        return value;
    }

    private List<AiIntent> boundIntents(Long agentId) {
        List<AiAgentIntent> bindings = agentIntentMapper.selectList(new LambdaQueryWrapper<AiAgentIntent>()
            .eq(AiAgentIntent::getAgentId, agentId).eq(AiAgentIntent::getEnabled, true)
            .orderByAsc(AiAgentIntent::getPriority));
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, AiIntent> intents = intentMapper.selectBatchIds(bindings.stream()
                .map(AiAgentIntent::getIntentId).distinct().toList())
            .stream()
            .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
            .collect(Collectors.toMap(AiIntent::getId, item -> item));
        return bindings.stream()
            .map(binding -> intents.get(binding.getIntentId()))
            .filter(Objects::nonNull)
            .toList();
    }

    private List<AiIntent> scopedCandidates(List<AiIntent> candidates, AiIntentRecognitionRequest request) {
        Set<String> codes = request.getIntentCodes() == null ? Set.of() : request.getIntentCodes().stream()
            .filter(StringUtils::isNotBlank).map(this::upper).collect(Collectors.toSet());
        Set<Long> groupIds = request.getGroupIds() == null ? Set.of() : request.getGroupIds().stream()
            .filter(Objects::nonNull).collect(Collectors.toSet());
        if (codes.isEmpty() && groupIds.isEmpty()) return candidates;
        return candidates.stream().filter(intent -> codes.contains(intent.getIntentCode())
            || intent.getGroupId() != null && groupIds.contains(intent.getGroupId())).toList();
    }

    private LambdaQueryWrapper<AiIntent> intentQuery(AiIntentQuery query) {
        LambdaQueryWrapper<AiIntent> wrapper = new LambdaQueryWrapper<>();
        AiIntentQuery criteria = query == null ? new AiIntentQuery() : query;
        if (criteria.getAgentId() != null) {
            List<Long> intentIds = agentIntentMapper.selectList(new LambdaQueryWrapper<AiAgentIntent>()
                    .eq(AiAgentIntent::getAgentId, criteria.getAgentId()).eq(AiAgentIntent::getEnabled, true))
                .stream().map(AiAgentIntent::getIntentId).distinct().toList();
            if (intentIds.isEmpty()) return wrapper.eq(AiIntent::getId, -1L);
            wrapper.in(AiIntent::getId, intentIds);
        }
        return wrapper
            .eq(criteria.getGroupId() != null, AiIntent::getGroupId, criteria.getGroupId())
            .isNull(Boolean.TRUE.equals(criteria.getUngrouped()), AiIntent::getGroupId)
            .eq(StringUtils.isNotBlank(criteria.getIntentType()), AiIntent::getIntentType, upper(criteria.getIntentType()))
            .eq(criteria.getEnabled() != null, AiIntent::getEnabled, criteria.getEnabled())
            .and(StringUtils.isNotBlank(criteria.getKeyword()), value -> value
                .like(AiIntent::getIntentName, criteria.getKeyword()).or()
                .like(AiIntent::getIntentCode, criteria.getKeyword()).or()
                .like(AiIntent::getDescription, criteria.getKeyword()))
            .orderByDesc(AiIntent::getCreateTime).orderByDesc(AiIntent::getId);
    }

    private List<AiIntentUtterance> utterances(Long intentId) {
        return utteranceMapper.selectList(new LambdaQueryWrapper<AiIntentUtterance>()
            .eq(AiIntentUtterance::getIntentId, intentId).orderByAsc(AiIntentUtterance::getSortOrder));
    }

    private Map<Long, List<AiIntentUtterance>> utterancesByIntent(List<AiIntent> intents) {
        if (intents.isEmpty()) {
            return Map.of();
        }
        return utteranceMapper.selectList(new LambdaQueryWrapper<AiIntentUtterance>()
                .in(AiIntentUtterance::getIntentId, intents.stream().map(AiIntent::getId).toList())
                .orderByAsc(AiIntentUtterance::getIntentId)
                .orderByAsc(AiIntentUtterance::getSortOrder))
            .stream()
            .collect(Collectors.groupingBy(
                AiIntentUtterance::getIntentId,
                LinkedHashMap::new,
                Collectors.toList()));
    }

    private void fill(AiIntent intent, AiIntentRequest request) {
        String type = upper(request.getIntentType());
        String action = upper(request.getActionType());
        if (!INTENT_TYPES.contains(type)) throw new ServiceException("未知的意图类型：" + type);
        if (!ACTION_TYPES.contains(action)) throw new ServiceException("未知的受控动作类型：" + action);
        validateActionConfig(action, request.getActionConfigJson());
        if (request.getGroupId() != null && intentGroupMapper.selectById(request.getGroupId()) == null) {
            throw new ServiceException("意图分类不存在");
        }
        intent.setGroupId(request.getGroupId());
        intent.setIntentCode(upper(request.getIntentCode()));
        intent.setIntentName(request.getIntentName().trim());
        intent.setIntentType(type);
        intent.setDescription(request.getDescription());
        intent.setActionType(action);
        intent.setActionConfigJson(StringUtils.isBlank(request.getActionConfigJson()) ? null : request.getActionConfigJson().trim());
        intent.setResponseTemplate(request.getResponseTemplate());
        intent.setConfidenceThreshold(request.getConfidenceThreshold() == null ? new BigDecimal("0.80") : request.getConfidenceThreshold());
        intent.setPriority(request.getPriority() == null ? 100 : request.getPriority());
        intent.setConfirmationRequired(Boolean.TRUE.equals(request.getConfirmationRequired()));
        intent.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private void validateActionConfig(String action, String actionConfigJson) {
        JsonNode config = null;
        if (StringUtils.isNotBlank(actionConfigJson)) {
            try {
                config = JsonUtils.getObjectMapper().readTree(actionConfigJson);
            } catch (Exception exception) {
                throw new ServiceException("动作参数格式不正确");
            }
        }
        if ("TRANSFER_QUEUE".equals(action)
            && (config == null || StringUtils.isBlank(config.path("queueCode").asText()))) {
            throw new ServiceException("转技能组动作必须选择目标技能组");
        }
        if ("TRANSFER_EXTENSION".equals(action)
            && (config == null || StringUtils.isBlank(config.path("extension").asText()))) {
            throw new ServiceException("转分机动作必须选择目标分机");
        }
        if ("TRANSFER_IVR".equals(action)
            && (config == null || !config.path("ivrFlowId").asText("").matches("^[0-9]{1,20}$"))) {
            throw new ServiceException("转 IVR 动作必须选择目标 IVR 流程");
        }
        if ("TRANSFER_ONLINE_SERVICE".equals(action)
            && (config == null || !config.path("skillGroupId").asText("").matches("^[0-9]{1,20}$"))) {
            throw new ServiceException("转在线客服动作必须选择目标在线客服技能组");
        }
        if ("CREATE_TICKET".equals(action)
            && (config == null || !config.path("templateId").asText("").matches("^[0-9]{1,20}$"))) {
            throw new ServiceException("创建工单动作必须选择工单模板");
        }
    }

    private void saveUtterances(Long intentId, List<AiIntentUtteranceRequest> requests) {
        if (requests == null) return;
        Set<String> unique = new HashSet<>();
        int order = 1;
        for (AiIntentUtteranceRequest request : requests) {
            String type = upper(request.getUtteranceType());
            if (!UTTERANCE_TYPES.contains(type)) throw new ServiceException("未知的话术类型：" + type);
            String normalized = normalize(request.getUtteranceText());
            if (normalized.isBlank()) continue;
            String key = type + ":" + normalized;
            if (!unique.add(key)) continue;
            AiIntentUtterance value = new AiIntentUtterance();
            value.setIntentId(intentId);
            value.setUtteranceType(type);
            value.setUtteranceText(request.getUtteranceText().trim());
            value.setNormalizedText(normalized);
            value.setTextHash(sha256(normalized));
            value.setSortOrder(order++);
            utteranceMapper.insert(value);
        }
    }

    private void saveBindings(Long intentId, List<Long> agentIds) {
        if (agentIds == null) return;
        int priority = 1;
        for (Long agentId : agentIds.stream().filter(Objects::nonNull).distinct().toList()) {
            if (agentMapper.selectById(agentId) == null) throw new ServiceException("绑定的 AI 助手不存在：" + agentId);
            AiAgentIntent value = new AiAgentIntent();
            value.setAgentId(agentId);
            value.setIntentId(intentId);
            value.setPriority(priority++);
            value.setEnabled(true);
            agentIntentMapper.insert(value);
        }
    }

    private AiIntentResponse response(AiIntent intent) {
        return responses(List.of(intent)).get(0);
    }

    private List<AiIntentResponse> responses(List<AiIntent> intents) {
        if (intents.isEmpty()) return List.of();
        List<Long> intentIds = intents.stream().map(AiIntent::getId).toList();
        Map<Long, List<AiIntentUtterance>> utteranceMap = utteranceMapper.selectList(
                new LambdaQueryWrapper<AiIntentUtterance>().in(AiIntentUtterance::getIntentId, intentIds)
                    .orderByAsc(AiIntentUtterance::getIntentId).orderByAsc(AiIntentUtterance::getSortOrder))
            .stream().collect(Collectors.groupingBy(AiIntentUtterance::getIntentId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<AiAgentIntent>> bindingMap = agentIntentMapper.selectList(
                new LambdaQueryWrapper<AiAgentIntent>().in(AiAgentIntent::getIntentId, intentIds)
                    .orderByAsc(AiAgentIntent::getIntentId).orderByAsc(AiAgentIntent::getPriority))
            .stream().collect(Collectors.groupingBy(AiAgentIntent::getIntentId, LinkedHashMap::new, Collectors.toList()));
        Set<Long> agentIds = bindingMap.values().stream().flatMap(Collection::stream)
            .map(AiAgentIntent::getAgentId).collect(Collectors.toSet());
        Map<Long, String> agentNames = agentIds.isEmpty() ? Map.of() : agentMapper.selectBatchIds(agentIds).stream()
            .collect(Collectors.toMap(AiAgent::getId, AiAgent::getAgentName));
        Set<Long> groupIds = intents.stream().map(AiIntent::getGroupId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> groupNames = groupIds.isEmpty() ? Map.of() : intentGroupMapper.selectBatchIds(groupIds).stream()
            .collect(Collectors.toMap(AiIntentGroup::getId, AiIntentGroup::getGroupName));
        return intents.stream().map(intent -> response(intent, utteranceMap, bindingMap, agentNames, groupNames)).toList();
    }

    private AiIntentResponse response(AiIntent intent,
                                      Map<Long, List<AiIntentUtterance>> utteranceMap,
                                      Map<Long, List<AiAgentIntent>> bindingMap,
                                      Map<Long, String> agentNames,
                                      Map<Long, String> groupNames) {
        AiIntentResponse value = new AiIntentResponse();
        value.setId(intent.getId());
        value.setGroupId(intent.getGroupId());
        value.setGroupName(intent.getGroupId() == null ? null : groupNames.get(intent.getGroupId()));
        value.setIntentCode(intent.getIntentCode());
        value.setIntentName(intent.getIntentName());
        value.setIntentType(intent.getIntentType());
        value.setDescription(intent.getDescription());
        value.setActionType(intent.getActionType());
        value.setActionConfigJson(intent.getActionConfigJson());
        value.setResponseTemplate(intent.getResponseTemplate());
        value.setConfidenceThreshold(intent.getConfidenceThreshold());
        value.setPriority(intent.getPriority());
        value.setConfirmationRequired(intent.getConfirmationRequired());
        value.setEnabled(intent.getEnabled());
        value.setVersion(intent.getVersion());
        value.setUtterances(utteranceMap.getOrDefault(intent.getId(), List.of()).stream().map(item -> {
            AiIntentUtteranceResponse response = new AiIntentUtteranceResponse();
            response.setId(item.getId());
            response.setUtteranceType(item.getUtteranceType());
            response.setUtteranceText(item.getUtteranceText());
            return response;
        }).toList());
        List<AiAgentIntent> bindings = bindingMap.getOrDefault(intent.getId(), List.of());
        value.setAgentIds(bindings.stream().map(AiAgentIntent::getAgentId).toList());
        value.setAgentNames(value.getAgentIds().stream().map(id -> agentNames.getOrDefault(id, String.valueOf(id))).toList());
        return value;
    }

    private AiIntent requireIntent(Long id) {
        AiIntent value = intentMapper.selectById(id);
        if (value == null) throw new ServiceException("AI 意图不存在");
        return value;
    }

    private void ensureCode(String code, Long excludeId) {
        long count = intentMapper.selectCount(new LambdaQueryWrapper<AiIntent>()
            .eq(AiIntent::getIntentCode, upper(code))
            .ne(excludeId != null, AiIntent::getId, excludeId));
        if (count > 0) throw new ServiceException("意图编码已存在");
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算话术摘要", exception);
        }
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String limit(String value, int max) {
        String text = Objects.toString(value, "未知错误");
        return text.length() <= max ? text : text.substring(0, max);
    }
}
