package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.domain.*;
import org.dromara.ai.knowledge.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.*;
import org.dromara.ai.vector.*;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.OssService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiKnowledgeTaskDispatcherImpl implements AiKnowledgeTaskDispatcher {
    private final AiKnowledgeTaskMapper taskMapper;
    private final AiKnowledgeBaseMapper baseMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeDocumentVersionMapper documentVersionMapper;
    private final AiKnowledgeChunkMapper chunkMapper;
    private final AiKnowledgeFaqMapper faqMapper;
    private final AiKnowledgeFaqVersionMapper faqVersionMapper;
    private final AiKnowledgeFaqAliasMapper faqAliasMapper;
    private final AiFaqCandidateBatchMapper candidateBatchMapper;
    private final AiFaqCandidateMapper candidateMapper;
    private final AiModelMapper modelMapper;
    private final AiModelProviderMapper providerMapper;
    private final KnowledgeDocumentParserRegistry parserRegistry;
    private final KnowledgeTextSplitter splitter;
    private final EmbeddingProviderRegistry embeddingRegistry;
    private final ChatProviderRegistry chatRegistry;
    private final VectorStore vectorStore;
    private final OssService ossService;
    private final AiKnowledgeProperties properties;
    private final ScheduledExecutorService scheduledExecutorService;
    @Resource(name = "aiKnowledgeTaskExecutor")
    private Executor executor;
    private final String leaseOwner = UUID.randomUUID().toString();

    @PostConstruct
    public void scheduleRecovery() {
        scheduledExecutorService.scheduleWithFixedDelay(this::scanSafely, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void dispatchAfterCommit(Long taskId, String tenantId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch(taskId, tenantId); }
            });
        } else dispatch(taskId, tenantId);
    }

    @Override public void dispatch(Long taskId, String tenantId) {
        executor.execute(() -> TenantHelper.dynamic(tenantId, () -> process(taskId)));
    }

    private void scanSafely() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<AiKnowledgeTask> tasks = TenantHelper.ignore(() -> taskMapper.selectList(new LambdaQueryWrapper<AiKnowledgeTask>()
                .and(w -> w.eq(AiKnowledgeTask::getStatus, "PENDING")
                    .or(x -> x.eq(AiKnowledgeTask::getStatus, "FAILED")
                        .lt(AiKnowledgeTask::getRetryCount, 3)
                        .le(AiKnowledgeTask::getNextRetryAt, now))
                    .or(x -> x.eq(AiKnowledgeTask::getStatus, "PROCESSING").le(AiKnowledgeTask::getLeaseExpiresAt, now)))
                .orderByAsc(AiKnowledgeTask::getCreateTime).last("limit 20")));
            tasks.forEach(task -> dispatch(task.getId(), task.getTenantId()));
        } catch (Exception e) {
            log.error("扫描 AI 知识任务失败，error={}", e.getMessage(), e);
        }
    }

    private void process(Long taskId) {
        if (!claim(taskId)) return;
        AiKnowledgeTask task = taskMapper.selectById(taskId);
        try {
            switch (task.getTaskType()) {
                case "PARSE_AND_INDEX", "REINDEX_DOCUMENT" -> processDocument(task);
                case "FAQ_INDEX", "REINDEX_FAQ" -> processFaq(task);
                case "FAQ_EXTRACT" -> processFaqExtraction(task);
                case "DELETE_INDEX" -> deleteDocumentIndex(task);
                case "DELETE_FAQ_INDEX" -> deleteFaqIndex(task);
                default -> throw new ServiceException("不支持的知识任务类型：" + task.getTaskType());
            }
            complete(task);
        } catch (Exception e) {
            fail(task, e);
        }
    }

    private boolean claim(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AiKnowledgeTask> update = new LambdaUpdateWrapper<AiKnowledgeTask>()
            .eq(AiKnowledgeTask::getId, taskId)
            .and(w -> w.eq(AiKnowledgeTask::getStatus, "PENDING")
                .or(x -> x.eq(AiKnowledgeTask::getStatus, "FAILED")
                    .lt(AiKnowledgeTask::getRetryCount, 3)
                    .le(AiKnowledgeTask::getNextRetryAt, now))
                .or(x -> x.eq(AiKnowledgeTask::getStatus, "PROCESSING").le(AiKnowledgeTask::getLeaseExpiresAt, now)))
            .set(AiKnowledgeTask::getStatus, "PROCESSING")
            .set(AiKnowledgeTask::getLeaseOwner, leaseOwner)
            .set(AiKnowledgeTask::getLeaseExpiresAt, now.plusMinutes(Math.max(1, properties.getIndexLeaseMinutes())))
            .set(AiKnowledgeTask::getStartedAt, now)
            .set(AiKnowledgeTask::getFailureReason, null);
        return taskMapper.update(null, update) == 1;
    }

    private void processDocument(AiKnowledgeTask task) throws Exception {
        AiKnowledgeBase base = require(baseMapper.selectById(task.getKnowledgeBaseId()), "知识库不存在");
        AiKnowledgeDocument document = require(documentMapper.selectById(task.getDocumentId()), "知识文档不存在");
        AiKnowledgeDocumentVersion version = require(documentVersionMapper.selectById(task.getDocumentVersionId()), "文档版本不存在");
        AiModel model = requireEmbeddingModel(task.getTargetEmbeddingModelId() == null ? base.getEmbeddingModelId() : task.getTargetEmbeddingModelId());
        AiModelProvider provider = requireProvider(model.getProviderId());
        String collection = task.getTargetCollectionName() == null ? base.getCollectionName() : task.getTargetCollectionName();
        version.setParseStatus("PROCESSING"); version.setIndexStatus("PROCESSING"); version.setStartedAt(LocalDateTime.now());
        version.setFailureReason(null); documentVersionMapper.updateById(version);

        String url = ossService.selectUrlById(version.getOssId(), Duration.ofHours(2));
        if (url == null) throw new ServiceException("知识文档 OSS 文件不存在");
        byte[] bytes = download(url);
        String suffix = suffix(version.getOriginalFileName());
        ParsedDocument parsed = parserRegistry.get(suffix).parse(new ByteArrayInputStream(bytes), version.getOriginalFileName());
        List<KnowledgeChunkDraft> drafts = splitter.split(parsed, base.getChunkSize(), base.getChunkOverlap());
        if (drafts.isEmpty()) throw new ServiceException("文档未提取到可索引文本，扫描 PDF 需要 OCR 后重新上传");
        if (drafts.size() > properties.getMaxChunkCountPerDocument()) throw new ServiceException("文档切片数量超过系统限制");

        vectorStore.ensureCollection(collection, model.getVectorDimension());
        vectorStore.deleteByFilter(collection, Map.of(
            "tenantId", TenantHelper.getTenantId(),
            "knowledgeBaseId", base.getId(),
            "sourceType", "DOCUMENT",
            "documentVersionId", version.getId()));
        chunkMapper.deletePhysicallyByDocumentVersionId(TenantHelper.getTenantId(), version.getId());
        List<AiKnowledgeChunk> chunks = new ArrayList<>();
        for (KnowledgeChunkDraft draft : drafts) {
            AiKnowledgeChunk chunk = new AiKnowledgeChunk();
            chunk.setId(IdGeneratorUtil.nextLongId());
            chunk.setKnowledgeBaseId(base.getId()); chunk.setDocumentId(document.getId()); chunk.setDocumentVersionId(version.getId());
            chunk.setChunkIndex(draft.index()); chunk.setTitlePath(draft.titlePath()); chunk.setPageNumber(draft.pageNumber());
            chunk.setSheetName(draft.sheetName()); chunk.setRowStart(draft.rowStart()); chunk.setRowEnd(draft.rowEnd());
            chunk.setTextContent(draft.content()); chunk.setTextHash(KnowledgeTextUtils.sha256(draft.content()));
            chunk.setTokenEstimate(Math.max(1, draft.content().length() / 2)); chunk.setIndexState("STAGING");
            chunk.setQdrantPointId(pointId("chunk", chunk.getId()));
            chunkMapper.insert(chunk); chunks.add(chunk);
        }
        updateProgress(task, chunks.size(), 0);
        int batchSize = Math.max(1, model.getMaxBatchSize() == null ? 16 : model.getMaxBatchSize());
        for (int offset = 0; offset < chunks.size(); offset += batchSize) {
            List<AiKnowledgeChunk> batch = chunks.subList(offset, Math.min(chunks.size(), offset + batchSize));
            EmbeddingResult result = embeddingRegistry.get(provider.getProviderType()).embed(new EmbeddingRequest(provider, model,
                batch.stream().map(AiKnowledgeChunk::getTextContent).toList()));
            validateDimension(result, model);
            List<VectorPoint> points = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) points.add(documentPoint(base, document, version, batch.get(i), result.vectors().get(i)));
            vectorStore.upsert(collection, points);
            updateProgress(task, chunks.size(), Math.min(chunks.size(), offset + batch.size()));
        }
        List<String> ids = chunks.stream().map(AiKnowledgeChunk::getQdrantPointId).toList();
        vectorStore.setPayload(collection, ids, Map.of("indexState", "ACTIVE"));
        chunkMapper.update(null, new LambdaUpdateWrapper<AiKnowledgeChunk>().eq(AiKnowledgeChunk::getDocumentVersionId, version.getId())
            .set(AiKnowledgeChunk::getIndexState, "ACTIVE"));
        retireOldDocument(base, document, version);
        version.setParseStatus("SUCCESS"); version.setIndexStatus("SUCCESS"); version.setPageCount(parsed.pageCount());
        version.setCharacterCount(parsed.characterCount()); version.setChunkCount(chunks.size()); version.setFinishedAt(LocalDateTime.now());
        documentVersionMapper.updateById(version);
        document.setCurrentVersionId(version.getId()); document.setStatus("READY"); document.setEnabled(true); documentMapper.updateById(document);
        refreshBase(base);
    }

    private void processFaq(AiKnowledgeTask task) {
        AiKnowledgeBase base = require(baseMapper.selectById(task.getKnowledgeBaseId()), "知识库不存在");
        AiKnowledgeFaq faq = require(faqMapper.selectById(task.getFaqId()), "FAQ 不存在");
        AiKnowledgeFaqVersion version = require(faqVersionMapper.selectById(task.getFaqVersionId()), "FAQ 版本不存在");
        AiModel model = requireEmbeddingModel(task.getTargetEmbeddingModelId() == null ? base.getEmbeddingModelId() : task.getTargetEmbeddingModelId());
        AiModelProvider provider = requireProvider(model.getProviderId());
        String collection = task.getTargetCollectionName() == null ? base.getCollectionName() : task.getTargetCollectionName();
        version.setIndexStatus("PROCESSING"); version.setStartedAt(LocalDateTime.now()); version.setFailureReason(null); faqVersionMapper.updateById(version);
        List<AiKnowledgeFaqAlias> aliases = faqAliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>()
            .eq(AiKnowledgeFaqAlias::getFaqVersionId, version.getId()).orderByAsc(AiKnowledgeFaqAlias::getId));
        List<String> questions = new ArrayList<>(); questions.add(version.getStandardQuestion());
        aliases.forEach(item -> questions.add(item.getAliasQuestion()));
        EmbeddingResult result = embeddingRegistry.get(provider.getProviderType()).embed(new EmbeddingRequest(provider, model, questions));
        validateDimension(result, model); vectorStore.ensureCollection(collection, model.getVectorDimension());
        String primaryId = pointId("faq", version.getId()); version.setPrimaryQdrantPointId(primaryId);
        List<VectorPoint> points = new ArrayList<>();
        points.add(faqPoint(base, faq, version, primaryId, "PRIMARY", version.getStandardQuestion(), result.vectors().get(0)));
        for (int i = 0; i < aliases.size(); i++) points.add(faqPoint(base, faq, version, aliases.get(i).getQdrantPointId(),
            "ALIAS", aliases.get(i).getAliasQuestion(), result.vectors().get(i + 1)));
        vectorStore.upsert(collection, points);
        List<String> ids = new ArrayList<>(); ids.add(primaryId); ids.addAll(aliases.stream().map(AiKnowledgeFaqAlias::getQdrantPointId).toList());
        vectorStore.setPayload(collection, ids, Map.of("indexState", "ACTIVE"));
        faqAliasMapper.update(null, new LambdaUpdateWrapper<AiKnowledgeFaqAlias>().eq(AiKnowledgeFaqAlias::getFaqVersionId, version.getId())
            .set(AiKnowledgeFaqAlias::getIndexState, "ACTIVE"));
        retireOldFaq(base, faq, version);
        version.setIndexStatus("SUCCESS"); version.setFinishedAt(LocalDateTime.now()); faqVersionMapper.updateById(version);
        faq.setCurrentVersionId(version.getId()); faq.setStatus(Boolean.TRUE.equals(faq.getEnabled()) ? "READY" : "DISABLED"); faqMapper.updateById(faq);
        refreshBase(base); updateProgress(task, questions.size(), questions.size());
    }

    private void processFaqExtraction(AiKnowledgeTask task) throws Exception {
        AiFaqCandidateBatch batch = require(candidateBatchMapper.selectById(task.getCandidateBatchId()), "FAQ 候选批次不存在");
        AiKnowledgeDocument document = require(documentMapper.selectById(task.getDocumentId()), "知识文档不存在");
        AiModel model = requireChatModel(batch.getChatModelId());
        AiModelProvider provider = requireProvider(model.getProviderId());
        List<AiKnowledgeChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AiKnowledgeChunk>()
            .eq(AiKnowledgeChunk::getDocumentVersionId, task.getDocumentVersionId())
            .eq(AiKnowledgeChunk::getIndexState, "ACTIVE").orderByAsc(AiKnowledgeChunk::getChunkIndex));
        if (chunks.isEmpty()) throw new ServiceException("知识文档没有可用于 FAQ 提取的生效切片");
        candidateMapper.deletePhysicallyByBatchId(TenantHelper.getTenantId(), batch.getId());
        batch.setStatus("PROCESSING"); batch.setStartedAt(LocalDateTime.now()); batch.setFinishedAt(null); batch.setFailureReason(null);
        batch.setTotalCount(0); batch.setValidCount(0); batch.setInvalidCount(0); candidateBatchMapper.updateById(batch);
        updateProgress(task, chunks.size(), 0);
        Set<String> existing = existingFaqQuestions(batch.getKnowledgeBaseId());
        Set<String> generated = new HashSet<>();
        int sequence = 0;
        for (int offset = 0; offset < chunks.size(); offset += 5) {
            List<AiKnowledgeChunk> group = chunks.subList(offset, Math.min(chunks.size(), offset + 5));
            StringBuilder source = new StringBuilder();
            for (int i = 0; i < group.size(); i++) {
                AiKnowledgeChunk chunk = group.get(i);
                source.append("\n[片段").append(i).append("，位置：").append(location(chunk)).append("]\n")
                    .append(chunk.getTextContent()).append("\n");
            }
            String instruction = """
                请从资料中提取适合作为客服知识库的 FAQ。只输出 JSON 数组，不要输出 Markdown 或解释。
                每个元素格式：{"question":"标准问题","answer":"仅依据原文的简洁答案","aliases":["相似问法"],"sourceChunkIndex":0,"confidence":0.9}。
                要求：问题和答案必须能从原文直接得到；不要编造；答案包含完整必要信息；无有效 FAQ 时返回 []。
                资料如下：
                """ + source;
            ChatResult result = chatRegistry.get(provider.getProviderType()).chat(new ChatRequest(provider, model,
                List.of(new ChatMessage("system", "你是严谨的 FAQ 提取器，只能依据提供的原文输出结构化结果。"),
                    new ChatMessage("user", instruction)), new BigDecimal("0.10"), 4096));
            JsonNode array = parseJsonArray(result.content());
            for (JsonNode item : array) {
                String question = item.path("question").asText("").trim();
                String answer = item.path("answer").asText("").trim();
                String normalized = KnowledgeTextUtils.normalizeQuestion(question);
                int sourceIndex = Math.max(0, Math.min(group.size() - 1, item.path("sourceChunkIndex").asInt(0)));
                AiKnowledgeChunk sourceChunk = group.get(sourceIndex);
                AiFaqCandidate candidate = new AiFaqCandidate();
                candidate.setBatchId(batch.getId()); candidate.setKnowledgeBaseId(batch.getKnowledgeBaseId());
                candidate.setRowNumber(++sequence); candidate.setFaqCode("AI_" + batch.getId() + "_" + sequence);
                candidate.setFaqName(shorten(question, 128)); candidate.setStandardQuestion(question); candidate.setNormalizedQuestion(normalized);
                candidate.setStandardAnswer(answer); candidate.setAliasesJson(JsonUtils.toJsonString(jsonStrings(item.path("aliases"))));
                candidate.setAnswerMode("DIRECT"); candidate.setSourceLocation(location(sourceChunk));
                candidate.setSourceText(shorten(sourceChunk.getTextContent(), 4000));
                candidate.setConfidence(BigDecimal.valueOf(item.path("confidence").asDouble(0.5D)));
                String error = null;
                if (StringUtils.isBlank(normalized)) error = "模型未生成标准问题";
                else if (StringUtils.isBlank(answer)) error = "模型未生成标准答案";
                else if (existing.contains(normalized)) error = "标准问题已存在于当前知识库";
                else if (!generated.add(normalized)) error = "本批次生成了重复问题";
                else if (candidate.getConfidence().compareTo(new BigDecimal("0.50")) < 0) error = "AI 提取可信度低于 0.50";
                candidate.setStatus(error == null ? "VALID" : "INVALID"); candidate.setErrorMessage(error); candidateMapper.insert(candidate);
            }
            updateProgress(task, chunks.size(), Math.min(chunks.size(), offset + group.size()));
        }
        refreshCandidateBatch(batch, "REVIEW");
        log.info("知识文档 AI 提取 FAQ 完成，knowledgeBaseId={}，documentId={}，batchId={}，candidateCount={}",
            batch.getKnowledgeBaseId(), document.getId(), batch.getId(), batch.getTotalCount());
    }

    private void deleteDocumentIndex(AiKnowledgeTask task) {
        AiKnowledgeBase base = require(baseMapper.selectById(task.getKnowledgeBaseId()), "知识库不存在");
        vectorStore.deleteByFilter(base.getCollectionName(), Map.of("tenantId", TenantHelper.getTenantId(), "sourceType", "DOCUMENT", "documentId", task.getDocumentId()));
        chunkMapper.update(null, new LambdaUpdateWrapper<AiKnowledgeChunk>().eq(AiKnowledgeChunk::getDocumentId, task.getDocumentId())
            .set(AiKnowledgeChunk::getIndexState, "RETIRED"));
        refreshBase(base);
    }

    private void deleteFaqIndex(AiKnowledgeTask task) {
        AiKnowledgeBase base = require(baseMapper.selectById(task.getKnowledgeBaseId()), "知识库不存在");
        vectorStore.deleteByFilter(base.getCollectionName(), Map.of("tenantId", TenantHelper.getTenantId(), "sourceType", "FAQ", "faqId", task.getFaqId()));
        faqAliasMapper.update(null, new LambdaUpdateWrapper<AiKnowledgeFaqAlias>().eq(AiKnowledgeFaqAlias::getFaqId, task.getFaqId())
            .set(AiKnowledgeFaqAlias::getIndexState, "RETIRED"));
        refreshBase(base);
    }

    private void retireOldDocument(AiKnowledgeBase base, AiKnowledgeDocument document, AiKnowledgeDocumentVersion current) {
        if (document.getCurrentVersionId() == null || Objects.equals(document.getCurrentVersionId(), current.getId())) return;
        List<AiKnowledgeChunk> old = chunkMapper.selectList(new LambdaQueryWrapper<AiKnowledgeChunk>()
            .eq(AiKnowledgeChunk::getDocumentVersionId, document.getCurrentVersionId()).eq(AiKnowledgeChunk::getIndexState, "ACTIVE"));
        if (!old.isEmpty()) vectorStore.setPayload(base.getCollectionName(), old.stream().map(AiKnowledgeChunk::getQdrantPointId).toList(), Map.of("indexState", "RETIRED"));
        chunkMapper.update(null, new LambdaUpdateWrapper<AiKnowledgeChunk>().eq(AiKnowledgeChunk::getDocumentVersionId, document.getCurrentVersionId())
            .set(AiKnowledgeChunk::getIndexState, "RETIRED"));
    }

    private void retireOldFaq(AiKnowledgeBase base, AiKnowledgeFaq faq, AiKnowledgeFaqVersion current) {
        if (faq.getCurrentVersionId() == null || Objects.equals(faq.getCurrentVersionId(), current.getId())) return;
        AiKnowledgeFaqVersion old = faqVersionMapper.selectById(faq.getCurrentVersionId());
        List<AiKnowledgeFaqAlias> aliases = faqAliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>()
            .eq(AiKnowledgeFaqAlias::getFaqVersionId, old.getId()).eq(AiKnowledgeFaqAlias::getIndexState, "ACTIVE"));
        List<String> ids = new ArrayList<>(); if (old.getPrimaryQdrantPointId() != null) ids.add(old.getPrimaryQdrantPointId());
        ids.addAll(aliases.stream().map(AiKnowledgeFaqAlias::getQdrantPointId).toList());
        if (!ids.isEmpty()) vectorStore.setPayload(base.getCollectionName(), ids, Map.of("indexState", "RETIRED"));
        faqAliasMapper.update(null, new LambdaUpdateWrapper<AiKnowledgeFaqAlias>().eq(AiKnowledgeFaqAlias::getFaqVersionId, old.getId())
            .set(AiKnowledgeFaqAlias::getIndexState, "RETIRED"));
    }

    private VectorPoint documentPoint(AiKnowledgeBase base, AiKnowledgeDocument document, AiKnowledgeDocumentVersion version,
                                      AiKnowledgeChunk chunk, List<Double> vector) {
        Map<String, Object> p = new LinkedHashMap<>(); p.put("tenantId", TenantHelper.getTenantId()); p.put("knowledgeBaseId", base.getId());
        p.put("sourceType", "DOCUMENT"); p.put("documentId", document.getId()); p.put("documentVersionId", version.getId());
        p.put("chunkId", chunk.getId()); p.put("title", document.getDocumentName()); p.put("content", chunk.getTextContent());
        p.put("location", location(chunk)); p.put("indexState", "STAGING");
        return new VectorPoint(chunk.getQdrantPointId(), vector, p);
    }

    private VectorPoint faqPoint(AiKnowledgeBase base, AiKnowledgeFaq faq, AiKnowledgeFaqVersion version, String pointId,
                                 String questionType, String question, List<Double> vector) {
        Map<String, Object> p = new LinkedHashMap<>(); p.put("tenantId", TenantHelper.getTenantId()); p.put("knowledgeBaseId", base.getId());
        p.put("sourceType", "FAQ"); p.put("faqId", faq.getId()); p.put("faqVersionId", version.getId()); p.put("title", faq.getFaqName());
        p.put("questionType", questionType); p.put("question", question); p.put("answer", version.getStandardAnswer());
        p.put("answerMode", faq.getAnswerMode()); p.put("indexState", "STAGING");
        return new VectorPoint(pointId, vector, p);
    }

    private void refreshBase(AiKnowledgeBase base) {
        base.setDocumentCount(Math.toIntExact(documentMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeDocument>()
            .eq(AiKnowledgeDocument::getKnowledgeBaseId, base.getId()).eq(AiKnowledgeDocument::getEnabled, true))));
        base.setFaqCount(Math.toIntExact(faqMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeFaq>()
            .eq(AiKnowledgeFaq::getKnowledgeBaseId, base.getId()).eq(AiKnowledgeFaq::getEnabled, true))));
        base.setChunkCount(Math.toIntExact(chunkMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeChunk>()
            .eq(AiKnowledgeChunk::getKnowledgeBaseId, base.getId()).eq(AiKnowledgeChunk::getIndexState, "ACTIVE"))));
        base.setStatus(StringUtils.isNotBlank(base.getRebuildBatchId()) ? "INDEXING" : (base.getDocumentCount() + base.getFaqCount() > 0 ? "READY" : "DRAFT"));
        base.setLastIndexedAt(LocalDateTime.now()); base.setFailureReason(null); baseMapper.updateById(base);
    }

    private void complete(AiKnowledgeTask task) {
        task.setStatus("SUCCESS"); task.setProgressCompleted(task.getProgressTotal()); task.setFinishedAt(LocalDateTime.now());
        task.setFailureReason(null);
        task.setLeaseOwner(null); task.setLeaseExpiresAt(null); task.setNextRetryAt(null); taskMapper.updateById(task);
        tryCompleteRebuild(task);
        log.info("AI 知识任务执行成功，tenantId={}，taskId={}，type={}", task.getTenantId(), task.getId(), task.getTaskType());
    }

    private void fail(AiKnowledgeTask task, Exception error) {
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        task.setStatus("FAILED"); task.setRetryCount(retry); task.setFailureReason(limit(error.getMessage()));
        task.setLeaseOwner(null); task.setLeaseExpiresAt(null); task.setFinishedAt(LocalDateTime.now());
        task.setNextRetryAt(retry <= 3 ? LocalDateTime.now().plusSeconds(switch (retry) { case 1 -> 30; case 2 -> 120; default -> 600; }) : null);
        taskMapper.updateById(task);
        markBusinessFailed(task, task.getFailureReason());
        log.error("AI 知识任务执行失败，tenantId={}，taskId={}，type={}，retry={}，error={}", task.getTenantId(), task.getId(), task.getTaskType(), retry, task.getFailureReason(), error);
    }

    private void markBusinessFailed(AiKnowledgeTask task, String reason) {
        if ("FAQ_EXTRACT".equals(task.getTaskType())) {
            AiFaqCandidateBatch batch = candidateBatchMapper.selectById(task.getCandidateBatchId());
            if (batch != null) {
                batch.setStatus("FAILED"); batch.setFailureReason(reason); batch.setFinishedAt(LocalDateTime.now());
                candidateBatchMapper.updateById(batch);
            }
            return;
        }
        if (task.getDocumentVersionId() != null) {
            AiKnowledgeDocumentVersion v = documentVersionMapper.selectById(task.getDocumentVersionId());
            if (v != null) { v.setParseStatus("FAILED"); v.setIndexStatus("FAILED"); v.setFailureReason(reason); v.setFinishedAt(LocalDateTime.now()); documentVersionMapper.updateById(v); }
            AiKnowledgeDocument d = documentMapper.selectById(task.getDocumentId());
            if (d != null && d.getCurrentVersionId() == null) { d.setStatus("FAILED"); documentMapper.updateById(d); }
        }
        if (task.getFaqVersionId() != null) {
            AiKnowledgeFaqVersion v = faqVersionMapper.selectById(task.getFaqVersionId());
            if (v != null) { v.setIndexStatus("FAILED"); v.setFailureReason(reason); v.setFinishedAt(LocalDateTime.now()); faqVersionMapper.updateById(v); }
            AiKnowledgeFaq f = faqMapper.selectById(task.getFaqId());
            if (f != null && f.getCurrentVersionId() == null) { f.setStatus("FAILED"); faqMapper.updateById(f); }
        }
        AiKnowledgeBase base = baseMapper.selectById(task.getKnowledgeBaseId());
        if (base != null) {
            base.setStatus(hasReadyContent(base.getId()) ? "PARTIAL" : "FAILED");
            base.setFailureReason(reason); baseMapper.updateById(base);
        }
    }

    private void tryCompleteRebuild(AiKnowledgeTask task) {
        if (StringUtils.isBlank(task.getRebuildBatchId())) return;
        long unfinished = taskMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeTask>()
            .eq(AiKnowledgeTask::getRebuildBatchId, task.getRebuildBatchId()).ne(AiKnowledgeTask::getStatus, "SUCCESS"));
        if (unfinished > 0) return;
        AiKnowledgeBase base = baseMapper.selectById(task.getKnowledgeBaseId());
        if (base == null || !Objects.equals(base.getRebuildBatchId(), task.getRebuildBatchId())) return;
        String oldCollection = base.getCollectionName();
        base.setEmbeddingModelId(base.getPendingEmbeddingModelId()); base.setCollectionName(base.getPendingCollectionName());
        base.setPendingEmbeddingModelId(null); base.setPendingCollectionName(null); base.setRebuildBatchId(null);
        base.setStatus("READY"); base.setFailureReason(null); base.setLastIndexedAt(LocalDateTime.now()); baseMapper.updateById(base);
        if (StringUtils.isNotBlank(oldCollection) && !Objects.equals(oldCollection, base.getCollectionName())) {
            try {
                vectorStore.deleteByFilter(oldCollection, Map.of("tenantId", TenantHelper.getTenantId(), "knowledgeBaseId", base.getId()));
            } catch (Exception cleanupError) {
                log.warn("知识库重建完成，但清理旧 Collection 向量失败，knowledgeBaseId={}，collection={}，error={}",
                    base.getId(), oldCollection, cleanupError.getMessage());
            }
        }
        log.info("AI 知识库重建批次切换成功，knowledgeBaseId={}，batchId={}，embeddingModelId={}，collection={}",
            base.getId(), task.getRebuildBatchId(), base.getEmbeddingModelId(), base.getCollectionName());
    }

    private boolean hasReadyContent(Long kbId) {
        return documentMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeDocument>().eq(AiKnowledgeDocument::getKnowledgeBaseId, kbId).eq(AiKnowledgeDocument::getStatus, "READY")) > 0
            || faqMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeFaq>().eq(AiKnowledgeFaq::getKnowledgeBaseId, kbId).eq(AiKnowledgeFaq::getStatus, "READY")) > 0;
    }
    private void updateProgress(AiKnowledgeTask task, int total, int completed) { task.setProgressTotal(total); task.setProgressCompleted(completed); taskMapper.updateById(task); }
    private byte[] download(String url) throws Exception { HttpResponse<byte[]> r=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()); if(r.statusCode()<200||r.statusCode()>=300)throw new ServiceException("下载知识源文件失败，HTTP状态码="+r.statusCode());return r.body(); }
    private void validateDimension(EmbeddingResult result, AiModel model) { if(result.dimension()!=model.getVectorDimension())throw new ServiceException("向量维度不一致，配置="+model.getVectorDimension()+"，实际="+result.dimension()); }
    private AiModel requireEmbeddingModel(Long id) { AiModel v=require(modelMapper.selectById(id),"向量模型不存在");if(!"EMBEDDING".equals(v.getCapability())||!Boolean.TRUE.equals(v.getEnabled()))throw new ServiceException("向量模型未启用");return v; }
    private AiModel requireChatModel(Long id) { AiModel v=require(modelMapper.selectById(id),"Chat 模型不存在");if(!"CHAT".equals(v.getCapability())||!Boolean.TRUE.equals(v.getEnabled()))throw new ServiceException("Chat 模型未启用");return v; }
    private AiModelProvider requireProvider(Long id) { AiModelProvider v=require(providerMapper.selectById(id),"模型服务商不存在");if(!Boolean.TRUE.equals(v.getEnabled()))throw new ServiceException("模型服务商已停用");return v; }
    private <T> T require(T value,String message){if(value==null)throw new ServiceException(message);return value;}
    private String pointId(String type,Object id){return UUID.nameUUIDFromBytes((type+":"+id).getBytes(StandardCharsets.UTF_8)).toString();}
    private String suffix(String name){return name==null||!name.contains(".")?"":name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);}
    private String location(AiKnowledgeChunk c){if(c.getPageNumber()!=null)return "第"+c.getPageNumber()+"页";if(c.getSheetName()!=null)return "Sheet："+c.getSheetName()+(c.getRowStart()==null?"":"，第"+c.getRowStart()+"-"+c.getRowEnd()+"行");return c.getTitlePath()==null?"":c.getTitlePath();}
    private String limit(String value){if(value==null)return "未知错误";return value.length()>1000?value.substring(0,1000):value;}
    private JsonNode parseJsonArray(String value) throws Exception { String text=value==null?"":value.trim();int start=text.indexOf('['),end=text.lastIndexOf(']');if(start<0||end<start)throw new ServiceException("Chat 模型未返回 FAQ JSON 数组");JsonNode node=JsonUtils.getObjectMapper().readTree(text.substring(start,end+1));if(!node.isArray())throw new ServiceException("Chat 模型返回的 FAQ 结果不是数组");return node; }
    private List<String> jsonStrings(JsonNode node){if(!node.isArray())return List.of();List<String> values=new ArrayList<>();for(JsonNode item:node){String value=item.asText("").trim();if(StringUtils.isNotBlank(value)&&!values.contains(value))values.add(value);}return values;}
    private Set<String> existingFaqQuestions(Long kbId){List<AiKnowledgeFaq> faqs=faqMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaq>().eq(AiKnowledgeFaq::getKnowledgeBaseId,kbId).isNotNull(AiKnowledgeFaq::getCurrentVersionId));if(faqs.isEmpty())return new HashSet<>();Set<Long> versions=new HashSet<>();faqs.forEach(v->versions.add(v.getCurrentVersionId()));Set<String> values=new HashSet<>();faqVersionMapper.selectBatchIds(versions).forEach(v->values.add(v.getNormalizedQuestion()));faqAliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>().in(AiKnowledgeFaqAlias::getFaqVersionId,versions)).forEach(v->values.add(v.getNormalizedQuestion()));return values;}
    private void refreshCandidateBatch(AiFaqCandidateBatch batch,String status){List<AiFaqCandidate> values=candidateMapper.selectList(new LambdaQueryWrapper<AiFaqCandidate>().eq(AiFaqCandidate::getBatchId,batch.getId()));batch.setTotalCount(values.size());batch.setValidCount((int)values.stream().filter(v->"VALID".equals(v.getStatus())).count());batch.setInvalidCount((int)values.stream().filter(v->"INVALID".equals(v.getStatus())).count());batch.setConfirmedCount((int)values.stream().filter(v->"CONFIRMED".equals(v.getStatus())).count());batch.setStatus(status);batch.setFailureReason(null);batch.setFinishedAt(LocalDateTime.now());candidateBatchMapper.updateById(batch);}
    private String shorten(String value,int max){if(value==null)return "";return value.length()>max?value.substring(0,max):value;}
}
