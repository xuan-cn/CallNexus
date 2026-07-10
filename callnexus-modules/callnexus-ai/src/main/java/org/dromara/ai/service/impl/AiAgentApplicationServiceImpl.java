package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.AiAgentRequest;
import org.dromara.ai.domain.request.AiChatRequest;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.knowledge.KnowledgeTextUtils;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.AiAgentApplicationService;
import org.dromara.ai.vector.*;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiAgentApplicationServiceImpl implements AiAgentApplicationService {

    private final AiAgentMapper agentMapper;
    private final AiAgentKnowledgeBaseMapper bindingMapper;
    private final AiKnowledgeBaseMapper baseMapper;
    private final AiKnowledgeFaqMapper faqMapper;
    private final AiKnowledgeFaqVersionMapper faqVersionMapper;
    private final AiKnowledgeFaqAliasMapper faqAliasMapper;
    private final AiModelMapper modelMapper;
    private final AiModelProviderMapper providerMapper;
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiMessageCitationMapper citationMapper;
    private final AiModelUsageMapper usageMapper;
    private final EmbeddingProviderRegistry embeddingRegistry;
    private final ChatProviderRegistry chatRegistry;
    private final VectorStore vectorStore;

    @Override
    public List<AiAgentResponse> agents() {
        Map<Long, String> modelNames = modelMapper.selectList(null).stream()
            .collect(Collectors.toMap(AiModel::getId, AiModel::getModelName));
        return agentMapper.selectList(new LambdaQueryWrapper<AiAgent>().orderByDesc(AiAgent::getCreateTime)).stream()
            .map(item -> response(item, modelNames.get(item.getChatModelId()))).toList();
    }

    @Override
    public AiAgentResponse agent(Long id) {
        AiAgent item = requireAgent(id);
        AiModel model = modelMapper.selectById(item.getChatModelId());
        return response(item, model == null ? null : model.getModelName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAgent(AiAgentRequest request) {
        ensureCode(request.getAgentCode(), null);
        validateAgentRequest(request);
        AiAgent item = new AiAgent();
        fill(item, request);
        clearSystemAssistant(request.getSystemAssistant());
        agentMapper.insert(item);
        saveBindings(item.getId(), request.getKnowledgeBaseIds());
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAgent(Long id, AiAgentRequest request) {
        AiAgent item = requireAgent(id);
        ensureCode(request.getAgentCode(), id);
        validateAgentRequest(request);
        fill(item, request);
        clearSystemAssistant(request.getSystemAssistant());
        agentMapper.updateById(item);
        bindingMapper.deletePhysicallyByAgentId(TenantHelper.getTenantId(), id);
        saveBindings(id, request.getKnowledgeBaseIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAgent(Long id) {
        AiAgent item = requireAgent(id);
        if (Boolean.TRUE.equals(item.getSystemAssistant())) {
            throw new ServiceException("系统内部助手不能直接删除，请先指定其他系统内部助手");
        }
        if (conversationMapper.selectCount(new LambdaQueryWrapper<AiConversation>().eq(AiConversation::getAgentId, id)) > 0) {
            throw new ServiceException("AI 助手已有对话记录，不能删除，可改为停用");
        }
        bindingMapper.deletePhysicallyByAgentId(TenantHelper.getTenantId(), id);
        agentMapper.deleteById(id);
    }

    @Override
    public void setAgentEnabled(Long id, boolean enabled) {
        AiAgent item = requireAgent(id);
        if (!enabled && Boolean.TRUE.equals(item.getSystemAssistant())) {
            throw new ServiceException("系统内部助手不能停用，请先指定其他系统内部助手");
        }
        item.setEnabled(enabled);
        agentMapper.updateById(item);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiConversationStartResponse startConversation(Long agentId, Long userId) {
        AiAgent agent = requireEnabledAgent(agentId);
        AiConversation conversation = new AiConversation();
        conversation.setAgentId(agent.getId());
        conversation.setUserId(userId);
        conversation.setTitle("新对话");
        conversation.setStatus("ACTIVE");
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationMapper.insert(conversation);

        String welcome = StringUtils.isBlank(agent.getWelcomeMessage())
            ? "您好，我是" + agent.getAgentName() + "，请问有什么可以帮您？"
            : agent.getWelcomeMessage().trim();
        AiMessage message = saveMessage(conversation, agent, "ASSISTANT", welcome, "GREETING", "COMPLETED", null);

        AiConversationStartResponse response = new AiConversationStartResponse();
        response.setConversation(conversationResponse(conversation, agent.getAgentName()));
        response.setMessage(messageResponse(message, List.of()));
        return response;
    }

    @Override
    public List<AiConversationResponse> conversations(Long agentId) {
        Long userId = LoginHelper.getUserId();
        Map<Long, String> names = agentMapper.selectList(null).stream()
            .collect(Collectors.toMap(AiAgent::getId, AiAgent::getAgentName));
        return conversationMapper.selectList(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, userId).eq(agentId != null, AiConversation::getAgentId, agentId)
                .orderByDesc(AiConversation::getLastMessageAt)).stream()
            .map(item -> conversationResponse(item, names.get(item.getAgentId()))).toList();
    }

    @Override
    public List<AiMessageResponse> messages(Long conversationId) {
        requireOwnedConversation(conversationId);
        List<AiMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
            .eq(AiMessage::getConversationId, conversationId).orderByAsc(AiMessage::getCreateTime));
        if (messages.isEmpty()) return List.of();
        List<Long> ids = messages.stream().map(AiMessage::getId).toList();
        Map<Long, List<AiMessageCitation>> citations = citationMapper.selectList(new LambdaQueryWrapper<AiMessageCitation>()
            .in(AiMessageCitation::getMessageId, ids)).stream().collect(Collectors.groupingBy(AiMessageCitation::getMessageId));
        return messages.stream().map(item -> messageResponse(item, citations.getOrDefault(item.getId(), List.of()))).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        AiConversation conversation = requireOwnedConversation(conversationId);
        int messageCount = logicalDeleteConversations(List.of(conversation));
        log.info("AI 历史对话已逻辑删除，conversationId={}，messageCount={}", conversationId, messageCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversations(Long agentId) {
        requireAgent(agentId);
        List<AiConversation> conversations = conversationMapper.selectList(new LambdaQueryWrapper<AiConversation>()
            .eq(AiConversation::getAgentId, agentId)
            .eq(AiConversation::getUserId, LoginHelper.getUserId()));
        int messageCount = logicalDeleteConversations(conversations);
        log.info("AI 助手历史对话已全部逻辑删除，agentId={}，conversationCount={}，messageCount={}",
            agentId, conversations.size(), messageCount);
    }

    private int logicalDeleteConversations(List<AiConversation> conversations) {
        if (conversations.isEmpty()) return 0;
        List<Long> conversationIds = conversations.stream().map(AiConversation::getId).toList();
        List<Long> messageIds = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .select(AiMessage::getId)
                .in(AiMessage::getConversationId, conversationIds)).stream()
            .map(AiMessage::getId)
            .toList();
        if (!messageIds.isEmpty()) {
            citationMapper.delete(new LambdaQueryWrapper<AiMessageCitation>()
                .in(AiMessageCitation::getMessageId, messageIds));
        }
        usageMapper.delete(new LambdaQueryWrapper<AiModelUsage>()
            .in(AiModelUsage::getConversationId, conversationIds));
        messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
            .in(AiMessage::getConversationId, conversationIds));
        conversationMapper.delete(new LambdaQueryWrapper<AiConversation>()
            .in(AiConversation::getId, conversationIds));
        return messageIds.size();
    }

    @Override
    public void streamChat(Long agentId, Long userId, AiChatRequest request, BiConsumer<String, Object> eventConsumer) {
        AiAgent agent = requireEnabledAgent(agentId);
        AiConversation conversation = prepareConversation(agent, userId, request);
        eventConsumer.accept("conversation", Map.of("conversationId", conversation.getId()));
        AiMessage userMessage = saveMessage(conversation, agent, "USER", request.getMessage().trim(), "USER", "COMPLETED", null);
        AiMessage assistantMessage = saveMessage(conversation, agent, "ASSISTANT", "", "PENDING", "PROCESSING", UUID.randomUUID().toString());
        long started = System.currentTimeMillis();
        try {
            Retrieval retrieval = retrieve(agent, request.getMessage().trim());
            long retrievalCompleted = System.currentTimeMillis();
            eventConsumer.accept("retrieval", retrieval.summary());
            if (retrieval.directAnswer() != null) {
                eventConsumer.accept("delta", Map.of("content", retrieval.directAnswer()));
                completeMessage(assistantMessage, retrieval.directAnswer(), retrieval.sourceType());
                persistCitations(assistantMessage.getId(), retrieval.hits());
                emitCitations(eventConsumer, retrieval.hits());
                eventConsumer.accept("completed", Map.of("messageId", assistantMessage.getId(), "sourceType", retrieval.sourceType()));
                log.info("AI 助手直接回答完成，agentId={}，conversationId={}，sourceType={}，retrievalMs={}，totalMs={}",
                    agentId, conversation.getId(), retrieval.sourceType(), retrievalCompleted - started,
                    System.currentTimeMillis() - started);
                return;
            }
            AiModel chatModel = requireModel(agent.getChatModelId(), "CHAT");
            AiModelProvider provider = requireProvider(chatModel.getProviderId());
            List<ChatMessage> prompt = buildPrompt(agent, conversation.getId(), userMessage.getId(), retrieval);
            int knowledgeChars = retrieval.hits().stream().mapToInt(hit -> hit.content().length()).sum();
            int promptChars = prompt.stream().mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum();
            int historyMessages = Math.max(0, prompt.size() - 2);
            log.info("AI Chat 请求开始，agentId={}，conversationId={}，model={}，sourceType={}，messageCount={}，historyMessages={}，knowledgeHits={}，knowledgeChars={}，promptChars={}，maxOutputTokens={}，retrievalMs={}",
                agentId, conversation.getId(), chatModel.getModelName(), retrieval.sourceType(), prompt.size(),
                historyMessages, retrieval.hits().size(), knowledgeChars, promptChars, agent.getMaxOutputTokens(),
                retrievalCompleted - started);
            AtomicLong firstTokenAt = new AtomicLong();
            ChatResult result = chatRegistry.get(provider.getProviderType()).stream(
                new ChatRequest(provider, chatModel, prompt, agent.getTemperature(), agent.getMaxOutputTokens()),
                delta -> {
                    firstTokenAt.compareAndSet(0L, System.currentTimeMillis());
                    eventConsumer.accept("delta", Map.of("content", delta));
                });
            completeMessage(assistantMessage, result.content(), retrieval.sourceType());
            persistCitations(assistantMessage.getId(), retrieval.hits());
            persistUsage(conversation.getId(), assistantMessage.getId(), provider, chatModel, result,
                System.currentTimeMillis() - started, "SUCCESS", null);
            emitCitations(eventConsumer, retrieval.hits());
            eventConsumer.accept("completed", Map.of("messageId", assistantMessage.getId(), "sourceType", retrieval.sourceType()));
            long completedAt = System.currentTimeMillis();
            Long firstTokenMs = firstTokenAt.get() == 0L ? null : firstTokenAt.get() - started;
            log.info("AI 助手回答完成，agentId={}，conversationId={}，sourceType={}，retrievalMs={}，firstTokenMs={}，chatMs={}，totalMs={}",
                agentId, conversation.getId(), retrieval.sourceType(), retrievalCompleted - started, firstTokenMs,
                completedAt - retrievalCompleted, completedAt - started);
        } catch (Exception e) {
            assistantMessage.setStatus("FAILED");
            assistantMessage.setFailureReason(limit(e.getMessage()));
            messageMapper.updateById(assistantMessage);
            eventConsumer.accept("error", Map.of("message", userError(e)));
        }
    }

    @Override
    public AiConversationStartResponse startRealtimeConversation(Long agentId) {
        return startConversation(agentId, 0L);
    }

    @Override
    public AiChatTurnResult chatOnce(Long agentId, Long conversationId, String message) {
        AiChatRequest request = new AiChatRequest();
        request.setConversationId(conversationId);
        request.setMessage(message);
        StringBuilder answer = new StringBuilder();
        AtomicReference<Long> resolvedConversationId = new AtomicReference<>(conversationId);
        AtomicReference<String> sourceType = new AtomicReference<>("MODEL");
        AtomicReference<String> failure = new AtomicReference<>();
        streamChat(agentId, 0L, request, (event, data) -> {
            if (!(data instanceof Map<?, ?> values)) {
                return;
            }
            if ("conversation".equals(event) && values.get("conversationId") != null) {
                resolvedConversationId.set(Long.valueOf(String.valueOf(values.get("conversationId"))));
            } else if ("delta".equals(event) && values.get("content") != null) {
                answer.append(values.get("content"));
            } else if ("completed".equals(event) && values.get("sourceType") != null) {
                sourceType.set(String.valueOf(values.get("sourceType")));
            } else if ("error".equals(event)) {
                failure.set(String.valueOf(values.get("message")));
            }
        });
        if (StringUtils.isNotBlank(failure.get())) {
            throw new ServiceException(failure.get());
        }
        if (answer.isEmpty()) {
            throw new ServiceException("AI 助手未返回可播报内容");
        }
        return new AiChatTurnResult(resolvedConversationId.get(), answer.toString(), sourceType.get());
    }

    private Retrieval retrieve(AiAgent agent, String question) {
        List<Binding> bindings = bindings(agent.getId());
        boolean directRetrieval = "DIRECT_RETRIEVAL".equals(agent.getRetrievalMode());
        String normalized = KnowledgeTextUtils.normalizeQuestion(question);
        Optional<Hit> exact = exactFaq(bindings, normalized);
        if (exact.isPresent()) {
            Hit hit = exact.get();
            if (directRetrieval || "DIRECT".equals(hit.answerMode()))
                return new Retrieval("FAQ_EXACT", hit.content(), List.of(hit), false);
            return new Retrieval("FAQ_EXACT", null, List.of(hit), false);
        }
        if (bindings.isEmpty()) return noKnowledge(agent, null, null);
        Long embeddingModelId = bindings.get(0).base().getEmbeddingModelId();
        if (bindings.stream().anyMatch(item -> !Objects.equals(embeddingModelId, item.base().getEmbeddingModelId()))) {
            throw new ServiceException("AI 助手绑定的知识库向量模型不一致，请重新配置");
        }
        AiModel model = requireModel(embeddingModelId, "EMBEDDING");
        AiModelProvider provider = requireProvider(model.getProviderId());
        long embeddingStarted = System.currentTimeMillis();
        EmbeddingResult embedding = embeddingRegistry.get(provider.getProviderType()).embed(
            new EmbeddingRequest(provider, model, List.of(question)));
        long embeddingCompleted = System.currentTimeMillis();
        List<Long> kbIds = bindings.stream().map(item -> item.base().getId()).toList();
        String collection = bindings.get(0).base().getCollectionName();
        List<VectorSearchHit> faqHits = vectorStore.search(collection, embedding.vectors().get(0),
            Map.of("tenantId", TenantHelper.getTenantId(), "knowledgeBaseId", kbIds, "sourceType", "FAQ", "indexState", "ACTIVE"),
            Math.max(agent.getTopK(), 10));
        long faqSearchCompleted = System.currentTimeMillis();
        log.info("AI 知识检索阶段完成，agentId={}，embeddingMs={}，faqSearchMs={}", agent.getId(),
            embeddingCompleted - embeddingStarted, faqSearchCompleted - embeddingCompleted);
        Double bestFaqScore = faqHits.stream().mapToDouble(VectorSearchHit::score).max().stream().boxed().findFirst().orElse(null);
        double faqThreshold = agent.getFaqScoreThreshold().doubleValue();
        Optional<Hit> faq = faqHits.stream().filter(item -> item.score() >= faqThreshold).map(this::hit)
            .sorted(hitComparator(bindings)).findFirst();
        if (faq.isPresent()) {
            Hit hit = faq.get();
            if (directRetrieval || "DIRECT".equals(hit.answerMode()))
                return new Retrieval("FAQ_SEMANTIC", hit.content(), List.of(hit), false, bestFaqScore, null);
            return new Retrieval("FAQ_SEMANTIC", null, List.of(hit), false, bestFaqScore, null);
        }
        List<VectorSearchHit> docs = vectorStore.search(collection, embedding.vectors().get(0),
            Map.of("tenantId", TenantHelper.getTenantId(), "knowledgeBaseId", kbIds, "sourceType", "DOCUMENT", "indexState", "ACTIVE"),
            agent.getTopK());
        log.info("AI 文档检索阶段完成，agentId={}，documentSearchMs={}", agent.getId(),
            System.currentTimeMillis() - faqSearchCompleted);
        Double bestDocumentScore = docs.stream().mapToDouble(VectorSearchHit::score).max().stream().boxed().findFirst().orElse(null);
        List<Hit> hits = docs.stream().filter(item -> item.score() >= agent.getScoreThreshold().doubleValue())
            .map(this::hit).sorted(hitComparator(bindings)).limit(agent.getTopK()).toList();
        if (!hits.isEmpty()) {
            String directAnswer = directRetrieval ? hits.get(0).content() : null;
            return new Retrieval(directRetrieval ? "DOCUMENT_DIRECT" : "DOCUMENT", directAnswer, hits, false, bestFaqScore, bestDocumentScore);
        }
        return noKnowledge(agent, bestFaqScore, bestDocumentScore);
    }

    private Retrieval noKnowledge(AiAgent agent, Double bestFaqScore, Double bestDocumentScore) {
        if ("FALLBACK_MODEL".equals(agent.getRetrievalFailurePolicy())) {
            return new Retrieval("MODEL", null, List.of(), true, bestFaqScore, bestDocumentScore);
        }
        return new Retrieval("STRICT", "抱歉，当前知识库中没有找到可以支持该问题的内容。", List.of(), false,
            bestFaqScore, bestDocumentScore);
    }

    private Optional<Hit> exactFaq(List<Binding> bindings, String normalized) {
        for (Binding binding : bindings) {
            List<AiKnowledgeFaq> faqs = faqMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaq>()
                .eq(AiKnowledgeFaq::getKnowledgeBaseId, binding.base().getId()).eq(AiKnowledgeFaq::getEnabled, true)
                .eq(AiKnowledgeFaq::getStatus, "READY").isNotNull(AiKnowledgeFaq::getCurrentVersionId)
                .orderByAsc(AiKnowledgeFaq::getId));
            if (faqs.isEmpty()) continue;
            Map<Long, AiKnowledgeFaq> byVersion = faqs.stream().collect(Collectors.toMap(AiKnowledgeFaq::getCurrentVersionId, item -> item));
            List<AiKnowledgeFaqVersion> versions = faqVersionMapper.selectBatchIds(byVersion.keySet());
            for (AiKnowledgeFaqVersion version : versions) {
                if (normalized.equals(version.getNormalizedQuestion()))
                    return Optional.of(faqHit(binding.base(), byVersion.get(version.getId()), version, 1D));
            }
            List<AiKnowledgeFaqAlias> aliases = faqAliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>()
                .in(AiKnowledgeFaqAlias::getFaqVersionId, byVersion.keySet()).eq(AiKnowledgeFaqAlias::getNormalizedQuestion, normalized)
                .eq(AiKnowledgeFaqAlias::getIndexState, "ACTIVE").orderByAsc(AiKnowledgeFaqAlias::getFaqId));
            if (!aliases.isEmpty()) {
                AiKnowledgeFaqVersion version = versions.stream().filter(item -> Objects.equals(item.getId(), aliases.get(0).getFaqVersionId())).findFirst().orElse(null);
                if (version != null)
                    return Optional.of(faqHit(binding.base(), byVersion.get(version.getId()), version, 1D));
            }
        }
        return Optional.empty();
    }

    private List<ChatMessage> buildPrompt(AiAgent agent, Long conversationId, Long currentUserMessageId, Retrieval retrieval) {
        List<ChatMessage> messages = new ArrayList<>();
        String system = StringUtils.isBlank(agent.getSystemPrompt()) ? "你是 CallNexus 的业务助手。回答必须准确、简洁。" : agent.getSystemPrompt();
        if (!retrieval.hits().isEmpty()) {
            String context = retrieval.hits().stream().map(item -> "[来源：" + item.title() + "]\n" + item.content())
                .collect(Collectors.joining("\n\n"));
            system += "\n\n请只依据以下知识内容回答；无法确认时明确说明，不得编造：\n" + context;
        } else if (retrieval.fallback()) {
            system += "\n\n当前知识库未命中，本次允许使用模型通用能力回答，并明确避免虚构业务事实。";
        }
        messages.add(new ChatMessage("system", system));
        int limit = Math.max(0, agent.getHistoryMessageLimit());
        if (limit > 0) {
            List<AiMessage> history = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId).eq(AiMessage::getStatus, "COMPLETED")
                .ne(AiMessage::getId, currentUserMessageId).orderByDesc(AiMessage::getCreateTime).last("limit " + limit));
            Collections.reverse(history);
            history.forEach(item -> messages.add(new ChatMessage(item.getRole().toLowerCase(Locale.ROOT), item.getContent())));
        }
        AiMessage current = messageMapper.selectById(currentUserMessageId);
        messages.add(new ChatMessage("user", current.getContent()));
        return messages;
    }

    private AiConversation prepareConversation(AiAgent agent, Long userId, AiChatRequest request) {
        if (request.getConversationId() != null) {
            AiConversation value = requireOwnedConversation(request.getConversationId(), userId);
            if (!Objects.equals(value.getAgentId(), agent.getId()))
                throw new ServiceException("对话与当前 AI 助手不匹配");
            if (!"ACTIVE".equals(value.getStatus())) throw new ServiceException("对话已归档");
            value.setLastMessageAt(LocalDateTime.now());
            conversationMapper.updateById(value);
            return value;
        }
        AiConversation value = new AiConversation();
        value.setAgentId(agent.getId());
        value.setUserId(userId);
        value.setTitle(request.getMessage().trim().substring(0, Math.min(50, request.getMessage().trim().length())));
        value.setStatus("ACTIVE");
        value.setLastMessageAt(LocalDateTime.now());
        conversationMapper.insert(value);
        return value;
    }

    private AiMessage saveMessage(AiConversation conversation, AiAgent agent, String role, String content,
                                  String sourceType, String status, String requestId) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversation.getId());
        message.setAgentId(agent.getId());
        message.setRole(role);
        message.setContent(content);
        message.setSourceType(sourceType);
        message.setStatus(status);
        message.setRequestId(requestId);
        messageMapper.insert(message);
        return message;
    }

    private void completeMessage(AiMessage message, String content, String sourceType) {
        message.setContent(content);
        message.setSourceType(sourceType);
        message.setStatus("COMPLETED");
        message.setFailureReason(null);
        messageMapper.updateById(message);
    }

    private void persistCitations(Long messageId, List<Hit> hits) {
        for (Hit hit : hits) {
            AiMessageCitation citation = new AiMessageCitation();
            citation.setMessageId(messageId);
            citation.setSourceType(hit.sourceType());
            citation.setKnowledgeBaseId(hit.knowledgeBaseId());
            citation.setDocumentId(hit.documentId());
            citation.setDocumentVersionId(hit.documentVersionId());
            citation.setChunkId(hit.chunkId());
            citation.setFaqId(hit.faqId());
            citation.setFaqVersionId(hit.faqVersionId());
            citation.setSourceName(hit.title());
            citation.setSourceLocation(hit.location());
            citation.setQuotedContent(limitContent(hit.content()));
            citation.setScore(BigDecimal.valueOf(hit.score()));
            citationMapper.insert(citation);
        }
    }

    private void persistUsage(Long conversationId, Long messageId, AiModelProvider provider, AiModel model,
                              ChatResult result, long elapsed, String status, String error) {
        AiModelUsage usage = new AiModelUsage();
        usage.setConversationId(conversationId);
        usage.setMessageId(messageId);
        usage.setProviderId(provider.getId());
        usage.setModelId(model.getId());
        usage.setCapability("CHAT");
        usage.setRequestId(UUID.randomUUID().toString());
        usage.setInputTokens(result.inputTokens());
        usage.setOutputTokens(result.outputTokens());
        usage.setElapsedMs(elapsed);
        usage.setStatus(status);
        usage.setErrorMessage(error);
        usageMapper.insert(usage);
    }

    private void emitCitations(BiConsumer<String, Object> consumer, List<Hit> hits) {
        for (Hit hit : hits)
            consumer.accept("citation", Map.of("sourceType", hit.sourceType(), "sourceName", hit.title(),
                "location", nullToEmpty(hit.location()), "content", limitContent(hit.content()), "score", hit.score()));
    }

    private List<Binding> bindings(Long agentId) {
        List<AiAgentKnowledgeBase> values = bindingMapper.selectList(new LambdaQueryWrapper<AiAgentKnowledgeBase>()
            .eq(AiAgentKnowledgeBase::getAgentId, agentId).eq(AiAgentKnowledgeBase::getEnabled, true)
            .orderByAsc(AiAgentKnowledgeBase::getPriority).orderByAsc(AiAgentKnowledgeBase::getKnowledgeBaseId));
        List<Binding> result = new ArrayList<>();
        for (AiAgentKnowledgeBase value : values) {
            AiKnowledgeBase base = baseMapper.selectById(value.getKnowledgeBaseId());
            if (base != null && Boolean.TRUE.equals(base.getEnabled()) && StringUtils.isNotBlank(base.getCollectionName())
                && Set.of("READY", "PARTIAL", "INDEXING").contains(base.getStatus()))
                result.add(new Binding(value, base));
        }
        return result;
    }

    private void validateAgentRequest(AiAgentRequest request) {
        requireModel(request.getChatModelId(), "CHAT");
        if (Boolean.TRUE.equals(request.getSystemAssistant()) && Boolean.FALSE.equals(request.getEnabled())) {
            throw new ServiceException("系统内部助手必须处于启用状态");
        }
        List<Long> ids = distinctIds(request.getKnowledgeBaseIds());
        Long embedding = null;
        for (Long id : ids) {
            AiKnowledgeBase base = baseMapper.selectById(id);
            if (base == null || !Boolean.TRUE.equals(base.getEnabled()))
                throw new ServiceException("绑定的知识库不存在或已停用：" + id);
            if (embedding == null) embedding = base.getEmbeddingModelId();
            else if (!Objects.equals(embedding, base.getEmbeddingModelId()))
                throw new ServiceException("同一 AI 助手绑定的知识库必须使用相同向量模型");
        }
    }

    private void fill(AiAgent item, AiAgentRequest request) {
        item.setAgentCode(request.getAgentCode().trim().toUpperCase(Locale.ROOT));
        item.setAgentName(request.getAgentName().trim());
        item.setDescription(request.getDescription());
        item.setChatModelId(request.getChatModelId());
        item.setSystemPrompt(request.getSystemPrompt());
        item.setWelcomeMessage(request.getWelcomeMessage());
        String voiceTransport = defaultValue(request.getVoiceTransport(), "HTTP");
        if (!Set.of("HTTP", "WS").contains(voiceTransport)) {
            throw new ServiceException("未知的语音传输模式");
        }
        item.setVoiceTransport(voiceTransport);
        String wsUrl = request.getVoiceTransportWsUrl() == null ? null : request.getVoiceTransportWsUrl().trim();
        if (StringUtils.isNotBlank(wsUrl) && !(wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://"))) {
            throw new ServiceException("WS 端点地址必须以 ws:// 或 wss:// 开头");
        }
        item.setVoiceTransportWsUrl(StringUtils.isBlank(wsUrl) ? null : wsUrl);
        item.setRetrievalMode(defaultValue(request.getRetrievalMode(), "RAG").trim().toUpperCase(Locale.ROOT));
        if (!Set.of("RAG", "DIRECT_RETRIEVAL").contains(item.getRetrievalMode())) {
            throw new ServiceException("未知的知识库回答模式");
        }
        item.setRetrievalFailurePolicy(defaultValue(request.getRetrievalFailurePolicy(), "STRICT"));
        if (!Set.of("STRICT", "FALLBACK_MODEL").contains(item.getRetrievalFailurePolicy()))
            throw new ServiceException("未知的知识未命中处理策略");
        item.setTopK(request.getTopK() == null ? 5 : request.getTopK());
        item.setScoreThreshold(request.getScoreThreshold() == null ? new BigDecimal("0.50") : request.getScoreThreshold());
        item.setFaqScoreThreshold(request.getFaqScoreThreshold() == null ? new BigDecimal("0.80") : request.getFaqScoreThreshold());
        item.setTemperature(request.getTemperature() == null ? new BigDecimal("0.20") : request.getTemperature());
        item.setMaxOutputTokens(request.getMaxOutputTokens() == null ? 2048 : request.getMaxOutputTokens());
        item.setHistoryMessageLimit(request.getHistoryMessageLimit() == null ? 10 : request.getHistoryMessageLimit());
        item.setSystemAssistant(Boolean.TRUE.equals(request.getSystemAssistant()));
        item.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private void clearSystemAssistant(Boolean systemAssistant) {
        if (!Boolean.TRUE.equals(systemAssistant)) return;
        agentMapper.update(null, new LambdaUpdateWrapper<AiAgent>()
            .eq(AiAgent::getSystemAssistant, true)
            .set(AiAgent::getSystemAssistant, false));
    }

    private void saveBindings(Long agentId, List<Long> ids) {
        int priority = 1;
        for (Long id : distinctIds(ids)) {
            AiAgentKnowledgeBase value = new AiAgentKnowledgeBase();
            value.setAgentId(agentId);
            value.setKnowledgeBaseId(id);
            value.setPriority(priority++);
            value.setEnabled(true);
            bindingMapper.insert(value);
        }
    }

    private AiAgentResponse response(AiAgent item, String modelName) {
        AiAgentResponse value = new AiAgentResponse();
        value.setId(item.getId());
        value.setAgentCode(item.getAgentCode());
        value.setAgentName(item.getAgentName());
        value.setDescription(item.getDescription());
        value.setChatModelId(item.getChatModelId());
        value.setChatModelName(modelName);
        value.setSystemPrompt(item.getSystemPrompt());
        value.setWelcomeMessage(item.getWelcomeMessage());
        value.setVoiceTransport(item.getVoiceTransport());
        value.setVoiceTransportWsUrl(item.getVoiceTransportWsUrl());
        value.setRetrievalMode(item.getRetrievalMode());
        value.setRetrievalFailurePolicy(item.getRetrievalFailurePolicy());
        value.setTopK(item.getTopK());
        value.setScoreThreshold(item.getScoreThreshold());
        value.setFaqScoreThreshold(item.getFaqScoreThreshold());
        value.setTemperature(item.getTemperature());
        value.setMaxOutputTokens(item.getMaxOutputTokens());
        value.setHistoryMessageLimit(item.getHistoryMessageLimit());
        value.setSystemAssistant(item.getSystemAssistant());
        value.setEnabled(item.getEnabled());
        value.setVersion(item.getVersion());
        List<Binding> bindings = bindingsAll(item.getId());
        value.setKnowledgeBaseIds(bindings.stream().map(it -> it.base().getId()).toList());
        value.setKnowledgeBaseNames(bindings.stream().map(it -> it.base().getKnowledgeName()).toList());
        return value;
    }

    private List<Binding> bindingsAll(Long agentId) {
        List<Binding> result = new ArrayList<>();
        for (AiAgentKnowledgeBase binding : bindingMapper.selectList(new LambdaQueryWrapper<AiAgentKnowledgeBase>()
            .eq(AiAgentKnowledgeBase::getAgentId, agentId).orderByAsc(AiAgentKnowledgeBase::getPriority))) {
            AiKnowledgeBase base = baseMapper.selectById(binding.getKnowledgeBaseId());
            if (base != null) result.add(new Binding(binding, base));
        }
        return result;
    }

    private Comparator<Hit> hitComparator(List<Binding> bindings) {
        Map<Long, Integer> priorities = bindings.stream().collect(Collectors.toMap(item -> item.base().getId(), item -> item.binding().getPriority()));
        return Comparator.comparingInt((Hit hit) -> priorities.getOrDefault(hit.knowledgeBaseId(), Integer.MAX_VALUE))
            .thenComparing(Comparator.comparingDouble(Hit::score).reversed()).thenComparing(hit -> hit.faqId() == null ? Long.MAX_VALUE : hit.faqId());
    }

    private Hit hit(VectorSearchHit value) {
        Map<String, Object> p = value.payload();
        String type = String.valueOf(p.get("sourceType"));
        return new Hit(type, longValue(p.get("knowledgeBaseId")), longValue(p.get("documentId")), longValue(p.get("documentVersionId")),
            longValue(p.get("chunkId")), longValue(p.get("faqId")), longValue(p.get("faqVersionId")),
            text(p.getOrDefault("title", p.getOrDefault("question", "知识来源"))), text(p.get("location")),
            text(p.getOrDefault("content", p.getOrDefault("answer", ""))), text(p.get("answerMode")), value.score());
    }

    private Hit faqHit(AiKnowledgeBase base, AiKnowledgeFaq faq, AiKnowledgeFaqVersion version, double score) {
        return new Hit("FAQ", base.getId(), null, null, null, faq.getId(), version.getId(), faq.getFaqName(), "FAQ v" + version.getVersionNo(),
            version.getStandardAnswer(), faq.getAnswerMode(), score);
    }

    private AiAgent requireAgent(Long id) {
        AiAgent value = agentMapper.selectById(id);
        if (value == null) throw new ServiceException("AI 助手不存在");
        return value;
    }

    private AiAgent requireEnabledAgent(Long id) {
        AiAgent value = requireAgent(id);
        if (!Boolean.TRUE.equals(value.getEnabled())) throw new ServiceException("AI 助手已停用");
        return value;
    }

    private AiModel requireModel(Long id, String capability) {
        AiModel value = modelMapper.selectById(id);
        if (value == null || !capability.equals(value.getCapability()) || !Boolean.TRUE.equals(value.getEnabled()))
            throw new ServiceException(capability + " 模型不存在或未启用");
        return value;
    }

    private AiModelProvider requireProvider(Long id) {
        AiModelProvider value = providerMapper.selectById(id);
        if (value == null || !Boolean.TRUE.equals(value.getEnabled()))
            throw new ServiceException("模型服务商不存在或未启用");
        return value;
    }

    private AiConversation requireOwnedConversation(Long id) {
        return requireOwnedConversation(id, LoginHelper.getUserId());
    }

    private AiConversation requireOwnedConversation(Long id, Long userId) {
        AiConversation value = conversationMapper.selectById(id);
        if (value == null || !Objects.equals(value.getUserId(), userId))
            throw new ServiceException("对话不存在或无权访问");
        return value;
    }

    private void ensureCode(String code, Long exclude) {
        if (agentMapper.selectCount(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getAgentCode, code.trim().toUpperCase(Locale.ROOT)).ne(exclude != null, AiAgent::getId, exclude)) > 0)
            throw new ServiceException("AI 助手编码已存在");
    }

    private List<Long> distinctIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private String defaultValue(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String limit(String value) {
        if (value == null) return "未知错误";
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private String limitContent(String value) {
        if (value == null) return "";
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private String userError(Exception e) {
        return e instanceof ServiceException ? e.getMessage() : "AI 对话处理失败：" + e.getMessage();
    }

    private AiConversationResponse conversationResponse(AiConversation item, String agentName) {
        AiConversationResponse value = new AiConversationResponse();
        value.setId(item.getId());
        value.setAgentId(item.getAgentId());
        value.setAgentName(agentName);
        value.setTitle(item.getTitle());
        value.setStatus(item.getStatus());
        value.setLastMessageAt(item.getLastMessageAt());
        return value;
    }

    private AiMessageResponse messageResponse(AiMessage item, List<AiMessageCitation> citations) {
        AiMessageResponse value = new AiMessageResponse();
        value.setId(item.getId());
        value.setConversationId(item.getConversationId());
        value.setRole(item.getRole());
        value.setContent(item.getContent());
        value.setSourceType(item.getSourceType());
        value.setStatus(item.getStatus());
        value.setFailureReason(item.getFailureReason());
        value.setCreateTime(item.getCreateTime());
        value.setCitations(citations.stream().map(this::citationResponse).toList());
        return value;
    }

    private AiCitationResponse citationResponse(AiMessageCitation item) {
        AiCitationResponse value = new AiCitationResponse();
        value.setId(item.getId());
        value.setSourceType(item.getSourceType());
        value.setKnowledgeBaseId(item.getKnowledgeBaseId());
        value.setDocumentId(item.getDocumentId());
        value.setFaqId(item.getFaqId());
        value.setSourceName(item.getSourceName());
        value.setSourceLocation(item.getSourceLocation());
        value.setQuotedContent(item.getQuotedContent());
        value.setScore(item.getScore());
        return value;
    }

    private record Binding(AiAgentKnowledgeBase binding, AiKnowledgeBase base) {
    }

    private record Hit(String sourceType, Long knowledgeBaseId, Long documentId, Long documentVersionId, Long chunkId,
                       Long faqId, Long faqVersionId, String title, String location, String content, String answerMode,
                       double score) {
    }

    private record Retrieval(String sourceType, String directAnswer, List<Hit> hits, boolean fallback,
                             Double bestFaqScore, Double bestDocumentScore) {
        Retrieval(String sourceType, String directAnswer, List<Hit> hits, boolean fallback) {
            this(sourceType, directAnswer, hits, fallback, null, null);
        }

        Map<String, Object> summary() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sourceType", sourceType);
            value.put("hitCount", hits.size());
            value.put("fallback", fallback);
            if (bestFaqScore != null) value.put("bestFaqScore", bestFaqScore);
            if (bestDocumentScore != null) value.put("bestDocumentScore", bestDocumentScore);
            return value;
        }
    }
}
