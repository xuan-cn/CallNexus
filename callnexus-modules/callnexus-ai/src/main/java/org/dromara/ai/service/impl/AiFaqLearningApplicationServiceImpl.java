package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.knowledge.KnowledgeTextUtils;
import org.dromara.ai.mapper.*;
import org.dromara.ai.service.AiFaqLearningApplicationService;
import org.dromara.ai.service.AiKnowledgeApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiFaqLearningApplicationServiceImpl implements AiFaqLearningApplicationService {
    private static final Set<String> REVIEWED = Set.of("APPROVED", "MERGED", "REJECTED");

    private final AiFaqLearningCandidateMapper candidateMapper;
    private final AiKnowledgeBaseMapper baseMapper;
    private final AiAgentMapper agentMapper;
    private final AiKnowledgeFaqMapper faqMapper;
    private final AiKnowledgeFaqVersionMapper faqVersionMapper;
    private final AiKnowledgeFaqAliasMapper aliasMapper;
    private final AiKnowledgeApplicationService knowledgeService;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Async("aiKnowledgeTaskExecutor")
    public void captureFallbackAsync(String tenantId, AiAgent agent, AiMessage userMessage, AiMessage assistantMessage,
                                     Double bestFaqScore, Double bestDocumentScore, String sourceChannel) {
        try {
            TenantHelper.dynamic(tenantId, () -> capture(agent, userMessage, assistantMessage,
                bestFaqScore, bestDocumentScore, sourceChannel));
        } catch (Exception exception) {
            log.error("模型兜底 FAQ 候选采集失败，agentId={}，conversationId={}，userMessageId={}",
                agent.getId(), userMessage.getConversationId(), userMessage.getId(), exception);
        }
    }

    private void capture(AiAgent agent, AiMessage userMessage, AiMessage assistantMessage,
                         Double bestFaqScore, Double bestDocumentScore, String sourceChannel) {
        if (!Boolean.TRUE.equals(agent.getFaqLearningEnabled()) || agent.getFaqLearningKnowledgeBaseId() == null
            || StringUtils.isBlank(userMessage.getContent()) || StringUtils.isBlank(assistantMessage.getContent())) return;
        AiKnowledgeBase base = baseMapper.selectById(agent.getFaqLearningKnowledgeBaseId());
        if (base == null || !Boolean.TRUE.equals(base.getEnabled())) {
            log.warn("跳过模型兜底 FAQ 采集，目标知识库不存在或已停用，agentId={}，knowledgeBaseId={}",
                agent.getId(), agent.getFaqLearningKnowledgeBaseId());
            return;
        }
        String normalized = KnowledgeTextUtils.normalizeQuestion(userMessage.getContent());
        String questionHash = sha256(normalized);
        AiFaqLearningCandidate existing = candidateMapper.selectOne(new LambdaQueryWrapper<AiFaqLearningCandidate>()
            .eq(AiFaqLearningCandidate::getKnowledgeBaseId, base.getId())
            .eq(AiFaqLearningCandidate::getQuestionHash, questionHash).last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setOccurrenceCount(existing.getOccurrenceCount() + 1);
            existing.setLastOccurredAt(now);
            existing.setConversationId(userMessage.getConversationId());
            existing.setUserMessageId(userMessage.getId());
            existing.setAssistantMessageId(assistantMessage.getId());
            if ("PENDING".equals(existing.getStatus())) {
                existing.setStandardAnswer(assistantMessage.getContent());
                existing.setAnswerHash(sha256(assistantMessage.getContent()));
                existing.setBestFaqScore(decimal(bestFaqScore));
                existing.setBestDocumentScore(decimal(bestDocumentScore));
            }
            candidateMapper.updateById(existing);
            return;
        }
        AiFaqLearningCandidate value = new AiFaqLearningCandidate();
        value.setKnowledgeBaseId(base.getId());
        value.setAgentId(agent.getId());
        value.setConversationId(userMessage.getConversationId());
        value.setUserMessageId(userMessage.getId());
        value.setAssistantMessageId(assistantMessage.getId());
        value.setSourceChannel(StringUtils.blankToDefault(sourceChannel, "ONLINE_CHAT"));
        value.setStandardQuestion(userMessage.getContent().trim());
        value.setNormalizedQuestion(normalized);
        value.setQuestionHash(questionHash);
        value.setStandardAnswer(assistantMessage.getContent().trim());
        value.setAnswerHash(sha256(assistantMessage.getContent()));
        value.setFaqCode("LEARN_" + questionHash.substring(0, 12).toUpperCase(Locale.ROOT));
        value.setFaqName(shorten(userMessage.getContent(), 128));
        value.setAliasesJson("[]");
        value.setAnswerMode("DIRECT");
        value.setBestFaqScore(decimal(bestFaqScore));
        value.setBestDocumentScore(decimal(bestDocumentScore));
        value.setOccurrenceCount(1);
        value.setFirstOccurredAt(now);
        value.setLastOccurredAt(now);
        value.setStatus("PENDING");
        try {
            candidateMapper.insert(value);
        } catch (DuplicateKeyException duplicate) {
            log.debug("模型兜底 FAQ 候选已由并发请求创建，knowledgeBaseId={}，questionHash={}", base.getId(), questionHash);
        }
    }

    @Override
    public TableDataInfo<AiFaqLearningCandidateResponse> page(AiFaqLearningQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<AiFaqLearningCandidate> wrapper = new LambdaQueryWrapper<AiFaqLearningCandidate>()
            .eq(StringUtils.isNotBlank(query.getStatus()), AiFaqLearningCandidate::getStatus, query.getStatus())
            .eq(query.getKnowledgeBaseId() != null, AiFaqLearningCandidate::getKnowledgeBaseId, query.getKnowledgeBaseId())
            .eq(query.getAgentId() != null, AiFaqLearningCandidate::getAgentId, query.getAgentId())
            .and(StringUtils.isNotBlank(query.getKeyword()), value -> value
                .like(AiFaqLearningCandidate::getStandardQuestion, query.getKeyword())
                .or().like(AiFaqLearningCandidate::getStandardAnswer, query.getKeyword()))
            .orderByDesc(AiFaqLearningCandidate::getOccurrenceCount)
            .orderByDesc(AiFaqLearningCandidate::getLastOccurredAt);
        Page<AiFaqLearningCandidate> page = candidateMapper.selectPage(pageQuery.build(), wrapper);
        if (page.getRecords().isEmpty()) return new TableDataInfo<>(List.of(), page.getTotal());
        Map<Long, String> bases = baseMapper.selectBatchIds(page.getRecords().stream().map(AiFaqLearningCandidate::getKnowledgeBaseId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(AiKnowledgeBase::getId, AiKnowledgeBase::getKnowledgeName));
        Map<Long, String> agents = agentMapper.selectBatchIds(page.getRecords().stream().map(AiFaqLearningCandidate::getAgentId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(AiAgent::getId, AiAgent::getAgentName));
        return new TableDataInfo<>(page.getRecords().stream().map(item -> response(item, bases.get(item.getKnowledgeBaseId()),
            agents.get(item.getAgentId()))).toList(), page.getTotal());
    }

    @Override
    public AiFaqLearningCandidateResponse detail(Long id) {
        AiFaqLearningCandidate value = require(id);
        AiKnowledgeBase base = baseMapper.selectById(value.getKnowledgeBaseId());
        AiAgent agent = agentMapper.selectById(value.getAgentId());
        return response(value, base == null ? null : base.getKnowledgeName(), agent == null ? null : agent.getAgentName());
    }

    @Override
    public AiFaqLearningStatisticsResponse statistics() {
        return new AiFaqLearningStatisticsResponse(count("PENDING"), count("APPROVED"), count("MERGED"), count("REJECTED"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, AiFaqLearningApproveRequest request) {
        doApprove(id, request);
    }

    private void doApprove(Long id, AiFaqLearningApproveRequest request) {
        AiFaqLearningCandidate value = requirePending(id);
        AiKnowledgeFaqRequest faq = new AiKnowledgeFaqRequest();
        faq.setFaqCode(request.getFaqCode());
        faq.setFaqName(request.getFaqName());
        faq.setStandardQuestion(request.getStandardQuestion());
        faq.setStandardAnswer(request.getStandardAnswer());
        faq.setAliases(cleanAliases(request.getAliases()));
        faq.setAnswerMode(StringUtils.blankToDefault(request.getAnswerMode(), "DIRECT"));
        faq.setEnabled(true);
        Long faqId = knowledgeService.createFaq(value.getKnowledgeBaseId(), faq);
        value.setFaqCode(request.getFaqCode().trim().toUpperCase(Locale.ROOT));
        value.setFaqName(request.getFaqName().trim());
        value.setStandardQuestion(request.getStandardQuestion().trim());
        value.setStandardAnswer(request.getStandardAnswer().trim());
        value.setAliasesJson(JsonUtils.toJsonString(cleanAliases(request.getAliases())));
        markReviewed(value, "APPROVED", faqId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void merge(Long id, AiFaqLearningMergeRequest request) {
        doMerge(id, request.getTargetFaqId());
    }

    private void doMerge(Long id, Long targetFaqId) {
        AiFaqLearningCandidate value = requirePending(id);
        AiKnowledgeFaq target = faqMapper.selectById(targetFaqId);
        if (target == null || !Objects.equals(target.getKnowledgeBaseId(), value.getKnowledgeBaseId()) || target.getCurrentVersionId() == null)
            throw new ServiceException("目标 FAQ 不存在或不属于候选知识库");
        AiKnowledgeFaqVersion version = faqVersionMapper.selectById(target.getCurrentVersionId());
        if (version == null) throw new ServiceException("目标 FAQ 当前版本不存在");
        List<String> aliases = aliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>()
            .eq(AiKnowledgeFaqAlias::getFaqVersionId, version.getId())).stream()
            .map(AiKnowledgeFaqAlias::getAliasQuestion).collect(Collectors.toCollection(ArrayList::new));
        aliases.add(value.getStandardQuestion());
        AiKnowledgeFaqRequest update = new AiKnowledgeFaqRequest();
        update.setFaqCode(target.getFaqCode());
        update.setFaqName(target.getFaqName());
        update.setStandardQuestion(version.getStandardQuestion());
        update.setStandardAnswer(version.getStandardAnswer());
        update.setAliases(cleanAliases(aliases));
        update.setAnswerMode(target.getAnswerMode());
        update.setEnabled(target.getEnabled());
        update.setVersion(target.getVersion());
        knowledgeService.updateFaq(target.getId(), update);
        markReviewed(value, "MERGED", target.getId(), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, AiFaqLearningRejectRequest request) {
        AiFaqLearningCandidate value = requirePending(id);
        markReviewed(value, "REJECTED", null, request.getReason().trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reopen(Long id) {
        AiFaqLearningCandidate value = require(id);
        if (!REVIEWED.contains(value.getStatus())) throw new ServiceException("只有已审核候选可以重新打开");
        value.setStatus("PENDING");
        value.setTargetFaqId(null);
        value.setReviewReason(null);
        value.setReviewedBy(null);
        value.setReviewedAt(null);
        candidateMapper.updateById(value);
    }

    @Override
    public AiFaqLearningBatchResponse batchApprove(AiFaqLearningBatchRequest request) {
        return batch(request.getCandidateIds(), id -> {
            AiFaqLearningCandidate value = requirePending(id);
            AiFaqLearningApproveRequest approve = new AiFaqLearningApproveRequest();
            approve.setFaqCode(StringUtils.blankToDefault(value.getFaqCode(), "LEARN_" + id));
            approve.setFaqName(StringUtils.blankToDefault(value.getFaqName(), shorten(value.getStandardQuestion(), 128)));
            approve.setStandardQuestion(value.getStandardQuestion());
            approve.setStandardAnswer(value.getStandardAnswer());
            approve.setAliases(aliases(value.getAliasesJson()));
            approve.setAnswerMode(value.getAnswerMode());
            doApprove(id, approve);
        });
    }

    @Override
    public AiFaqLearningBatchResponse batchMerge(AiFaqLearningBatchRequest request) {
        if (request.getTargetFaqId() == null) throw new ServiceException("请选择要合并到的目标 FAQ");
        return batch(request.getCandidateIds(), id -> doMerge(id, request.getTargetFaqId()));
    }

    @Override
    public AiFaqLearningBatchResponse batchReject(AiFaqLearningBatchRequest request) {
        if (StringUtils.isBlank(request.getReason())) throw new ServiceException("请填写批量驳回原因");
        return batch(request.getCandidateIds(), id -> {
            AiFaqLearningCandidate value = requirePending(id);
            markReviewed(value, "REJECTED", null, request.getReason().trim());
        });
    }

    private AiFaqLearningBatchResponse batch(List<Long> ids, Consumer<Long> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<AiFaqLearningBatchResponse.Item> items = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            try {
                transaction.executeWithoutResult(status -> action.accept(id));
                items.add(new AiFaqLearningBatchResponse.Item(id, true, "处理成功"));
            } catch (Exception exception) {
                items.add(new AiFaqLearningBatchResponse.Item(id, false, StringUtils.blankToDefault(exception.getMessage(), "处理失败")));
            }
        }
        int success = (int) items.stream().filter(AiFaqLearningBatchResponse.Item::success).count();
        return new AiFaqLearningBatchResponse(items.size(), success, items.size() - success, items);
    }

    private void markReviewed(AiFaqLearningCandidate value, String status, Long faqId, String reason) {
        value.setStatus(status);
        value.setTargetFaqId(faqId);
        value.setReviewReason(reason);
        value.setReviewedBy(LoginHelper.getUserId());
        value.setReviewedAt(LocalDateTime.now());
        if (candidateMapper.updateById(value) != 1) throw new ServiceException("候选已被其他用户处理，请刷新后重试");
    }

    private long count(String status) {
        return candidateMapper.selectCount(new LambdaQueryWrapper<AiFaqLearningCandidate>().eq(AiFaqLearningCandidate::getStatus, status));
    }

    private AiFaqLearningCandidate require(Long id) {
        AiFaqLearningCandidate value = candidateMapper.selectById(id);
        if (value == null) throw new ServiceException("FAQ 学习候选不存在");
        return value;
    }

    private AiFaqLearningCandidate requirePending(Long id) {
        AiFaqLearningCandidate value = require(id);
        if (!"PENDING".equals(value.getStatus())) throw new ServiceException("当前候选已被审核");
        return value;
    }

    private AiFaqLearningCandidateResponse response(AiFaqLearningCandidate value, String baseName, String agentName) {
        AiFaqLearningCandidateResponse result = new AiFaqLearningCandidateResponse();
        result.setId(value.getId()); result.setKnowledgeBaseId(value.getKnowledgeBaseId()); result.setKnowledgeBaseName(baseName);
        result.setAgentId(value.getAgentId()); result.setAgentName(agentName); result.setConversationId(value.getConversationId());
        result.setSourceChannel(value.getSourceChannel()); result.setStandardQuestion(value.getStandardQuestion());
        result.setStandardAnswer(value.getStandardAnswer()); result.setFaqCode(value.getFaqCode()); result.setFaqName(value.getFaqName());
        result.setAliases(aliases(value.getAliasesJson())); result.setAnswerMode(value.getAnswerMode());
        result.setBestFaqScore(value.getBestFaqScore()); result.setBestDocumentScore(value.getBestDocumentScore());
        result.setOccurrenceCount(value.getOccurrenceCount()); result.setFirstOccurredAt(value.getFirstOccurredAt());
        result.setLastOccurredAt(value.getLastOccurredAt()); result.setStatus(value.getStatus()); result.setTargetFaqId(value.getTargetFaqId());
        result.setReviewReason(value.getReviewReason()); result.setReviewedBy(value.getReviewedBy()); result.setReviewedAt(value.getReviewedAt());
        result.setVersion(value.getVersion());
        return result;
    }

    private BigDecimal decimal(Double value) { return value == null ? null : BigDecimal.valueOf(value); }
    private String shorten(String value, int max) { String text = value == null ? "未命名FAQ" : value.trim(); return text.length() > max ? text.substring(0, max) : text; }
    private List<String> cleanAliases(List<String> values) { return values == null ? List.of() : values.stream().filter(StringUtils::isNotBlank).map(String::trim).distinct().toList(); }
    private List<String> aliases(String json) { if (StringUtils.isBlank(json)) return List.of(); try { return JsonUtils.getObjectMapper().readValue(json, JsonUtils.getObjectMapper().getTypeFactory().constructCollectionType(List.class, String.class)); } catch (Exception ignored) { return List.of(); } }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
