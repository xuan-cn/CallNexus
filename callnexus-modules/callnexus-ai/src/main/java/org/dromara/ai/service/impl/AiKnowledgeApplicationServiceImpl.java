package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.knowledge.KnowledgeTextUtils;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.*;
import org.dromara.ai.vector.*;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.system.domain.vo.SysOssVo;
import org.dromara.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiKnowledgeApplicationServiceImpl implements AiKnowledgeApplicationService {
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of("txt", "md", "pdf", "docx", "xlsx");
    private final AiKnowledgeBaseMapper baseMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeDocumentVersionMapper documentVersionMapper;
    private final AiKnowledgeChunkMapper chunkMapper;
    private final AiKnowledgeFaqMapper faqMapper;
    private final AiKnowledgeFaqVersionMapper faqVersionMapper;
    private final AiKnowledgeFaqAliasMapper faqAliasMapper;
    private final AiKnowledgeTaskMapper taskMapper;
    private final AiAgentKnowledgeBaseMapper agentKnowledgeMapper;
    private final AiModelMapper modelMapper;
    private final AiModelProviderMapper providerMapper;
    private final ISysOssService ossService;
    private final AiKnowledgeProperties properties;
    private final AiKnowledgeTaskDispatcher dispatcher;
    private final EmbeddingProviderRegistry embeddingRegistry;
    private final VectorStore vectorStore;

    @Override
    public List<AiKnowledgeBaseResponse> knowledgeBases() {
        Map<Long, String> modelNames = new HashMap<>();
        modelMapper.selectList(null).forEach(item -> modelNames.put(item.getId(), item.getModelName()));
        return baseMapper.selectList(new LambdaQueryWrapper<AiKnowledgeBase>()
                .eq(AiKnowledgeBase::getDeleted, false)
                .orderByDesc(AiKnowledgeBase::getCreateTime))
            .stream().map(item -> baseResponse(item, modelNames.get(item.getEmbeddingModelId()))).toList();
    }

    @Override
    public TableDataInfo<AiKnowledgeBaseResponse> knowledgeBasePage(PageQuery pageQuery) {
        Page<AiKnowledgeBase> page = baseMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<AiKnowledgeBase>().orderByDesc(AiKnowledgeBase::getCreateTime));
        Set<Long> modelIds = new HashSet<>();
        page.getRecords().forEach(item -> modelIds.add(item.getEmbeddingModelId()));
        Map<Long, String> modelNames = new HashMap<>();
        if (!modelIds.isEmpty()) {
            modelMapper.selectBatchIds(modelIds).forEach(item -> modelNames.put(item.getId(), item.getModelName()));
        }
        return new TableDataInfo<>(page.getRecords().stream()
            .map(item -> baseResponse(item, modelNames.get(item.getEmbeddingModelId()))).toList(), page.getTotal());
    }

    @Override
    public AiKnowledgeBaseResponse knowledgeBase(Long id) {
        AiKnowledgeBase base = requireBase(id);
        AiModel model = modelMapper.selectById(base.getEmbeddingModelId());
        return baseResponse(base, model == null ? null : model.getModelName());
    }

    @Override
    public Long createKnowledgeBase(AiKnowledgeBaseRequest request) {
        ensureBaseCode(request.getKnowledgeCode(), null);
        AiModel model = requireEmbeddingModel(request.getEmbeddingModelId());
        if (model.getVectorDimension() == null || model.getVectorDimension() <= 0) {
            throw new ServiceException("向量模型尚未测试，无法确定向量维度");
        }
        AiKnowledgeBase base = new AiKnowledgeBase();
        fillBase(base, request);
        base.setCollectionName(collectionName(TenantHelper.getTenantId(), model));
        base.setStatus("DRAFT");
        base.setDocumentCount(0);
        base.setFaqCount(0);
        base.setChunkCount(0);
        baseMapper.insert(base);
        return base.getId();
    }

    @Override
    public void updateKnowledgeBase(Long id, AiKnowledgeBaseRequest request) {
        ensureBaseCode(request.getKnowledgeCode(), id);
        AiKnowledgeBase base = requireBase(id);
        if (!Objects.equals(base.getEmbeddingModelId(), request.getEmbeddingModelId()) && hasReadyContent(id)) {
            throw new ServiceException("知识库已有生效内容，更换向量模型必须执行重建索引");
        }
        AiModel model = requireEmbeddingModel(request.getEmbeddingModelId());
        fillBase(base, request);
        base.setCollectionName(collectionName(TenantHelper.getTenantId(), model));
        base.setVersion(request.getVersion());
        if (baseMapper.updateById(base) != 1) throw new ServiceException("知识库已被其他用户修改，请刷新后重试");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long id) {
        requireBase(id);
        if (agentKnowledgeMapper.selectCount(new LambdaQueryWrapper<AiAgentKnowledgeBase>()
            .eq(AiAgentKnowledgeBase::getKnowledgeBaseId, id).eq(AiAgentKnowledgeBase::getEnabled, true)) > 0) {
            throw new ServiceException("知识库仍被 AI 助手绑定，不能删除");
        }
        setKnowledgeBaseEnabled(id, false);
        if (baseMapper.deleteById(id) != 1) throw new ServiceException("知识库删除失败");
    }

    @Override
    public void setKnowledgeBaseEnabled(Long id, boolean enabled) {
        AiKnowledgeBase base = requireBase(id);
        base.setEnabled(enabled);
        base.setStatus(enabled ? aggregateStatus(id) : "DISABLED");
        baseMapper.updateById(base);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildKnowledgeBase(Long id, Long embeddingModelId) {
        AiKnowledgeBase base = requireEnabledBase(id);
        reconcileRebuildBatch(base);
        AiModel model = requireEmbeddingModel(embeddingModelId == null ? base.getEmbeddingModelId() : embeddingModelId);
        if (model.getVectorDimension() == null || model.getVectorDimension() <= 0)
            throw new ServiceException("目标向量模型尚未测试，无法确定维度");
        validateBoundAgentEmbedding(id, model.getId());
        String collection = collectionName(TenantHelper.getTenantId(), model);
        String batchId = UUID.randomUUID().toString();
        List<AiKnowledgeTask> rebuildTasks = new ArrayList<>();
        for (AiKnowledgeDocument document : documentMapper.selectList(new LambdaQueryWrapper<AiKnowledgeDocument>()
            .eq(AiKnowledgeDocument::getKnowledgeBaseId, id).eq(AiKnowledgeDocument::getEnabled, true).isNotNull(AiKnowledgeDocument::getCurrentVersionId))) {
            rebuildTasks.add(createRebuildTask("REINDEX_DOCUMENT", base, document.getId(), document.getCurrentVersionId(), null, null, model.getId(), collection, batchId));
        }
        for (AiKnowledgeFaq faq : faqMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaq>()
            .eq(AiKnowledgeFaq::getKnowledgeBaseId, id).eq(AiKnowledgeFaq::getEnabled, true).isNotNull(AiKnowledgeFaq::getCurrentVersionId))) {
            rebuildTasks.add(createRebuildTask("REINDEX_FAQ", base, null, null, faq.getId(), faq.getCurrentVersionId(), model.getId(), collection, batchId));
        }
        if (rebuildTasks.isEmpty()) {
            base.setEmbeddingModelId(model.getId());
            base.setCollectionName(collection);
            base.setStatus("DRAFT");
            baseMapper.updateById(base);
            return;
        }
        base.setPendingEmbeddingModelId(model.getId());
        base.setPendingCollectionName(collection);
        base.setRebuildBatchId(batchId);
        base.setStatus("INDEXING");
        base.setFailureReason(null);
        baseMapper.updateById(base);
        rebuildTasks.forEach(task -> dispatcher.dispatchAfterCommit(task.getId(), TenantHelper.getTenantId()));
    }

    private void reconcileRebuildBatch(AiKnowledgeBase base) {
        if (StringUtils.isBlank(base.getRebuildBatchId())) {
            return;
        }
        String batchId = base.getRebuildBatchId();
        List<AiKnowledgeTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AiKnowledgeTask>()
            .eq(AiKnowledgeTask::getKnowledgeBaseId, base.getId())
            .eq(AiKnowledgeTask::getRebuildBatchId, batchId));
        boolean allSucceeded = !tasks.isEmpty() && tasks.stream().allMatch(task -> "SUCCESS".equals(task.getStatus()));
        if (!allSucceeded && !tasks.isEmpty()) {
            Map<String, Long> statusCounts = tasks.stream().collect(java.util.stream.Collectors.groupingBy(
                AiKnowledgeTask::getStatus, TreeMap::new, java.util.stream.Collectors.counting()));
            throw new ServiceException("知识库重建批次尚未完成，任务状态：" + statusCounts);
        }

        String oldCollection = base.getCollectionName();
        if (allSucceeded) {
            if (base.getPendingEmbeddingModelId() != null) {
                base.setEmbeddingModelId(base.getPendingEmbeddingModelId());
            }
            if (StringUtils.isNotBlank(base.getPendingCollectionName())) {
                base.setCollectionName(base.getPendingCollectionName());
            }
            base.setLastIndexedAt(LocalDateTime.now());
            base.setFailureReason(null);
            log.warn("检测到已成功但未收尾的知识库重建批次，现自动完成切换，knowledgeBaseId={}，batchId={}",
                base.getId(), batchId);
        } else {
            log.warn("检测到没有关联任务的知识库重建批次标记，现自动清理，knowledgeBaseId={}，batchId={}",
                base.getId(), batchId);
        }
        base.setPendingEmbeddingModelId(null);
        base.setPendingCollectionName(null);
        base.setRebuildBatchId(null);
        base.setStatus(base.getDocumentCount() + base.getFaqCount() > 0 ? "READY" : "DRAFT");
        if (baseMapper.updateById(base) != 1) {
            throw new ServiceException("知识库重建状态已发生变化，请刷新后重试");
        }
        if (allSucceeded && StringUtils.isNotBlank(oldCollection) && !Objects.equals(oldCollection, base.getCollectionName())) {
            try {
                vectorStore.deleteByFilter(oldCollection,
                    Map.of("tenantId", TenantHelper.getTenantId(), "knowledgeBaseId", base.getId()));
            } catch (Exception cleanupError) {
                log.warn("知识库重建旧向量清理失败，不影响新索引使用，knowledgeBaseId={}，collection={}，error={}",
                    base.getId(), oldCollection, cleanupError.getMessage());
            }
        }
    }

    @Override
    public List<AiKnowledgeDocumentVersion> documentVersions(Long documentId) {
        requireDocument(documentId);
        return documentVersionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeDocumentVersion>()
            .eq(AiKnowledgeDocumentVersion::getDocumentId, documentId).orderByDesc(AiKnowledgeDocumentVersion::getVersionNo));
    }

    @Override
    public List<AiKnowledgeDocumentResponse> documents(Long knowledgeBaseId) {
        requireBase(knowledgeBaseId);
        return documentMapper.selectList(new LambdaQueryWrapper<AiKnowledgeDocument>()
                .eq(AiKnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId).orderByDesc(AiKnowledgeDocument::getCreateTime))
            .stream().map(this::documentResponse).toList();
    }

    @Override
    public TableDataInfo<AiKnowledgeDocumentResponse> documentPage(Long knowledgeBaseId, PageQuery pageQuery) {
        requireBase(knowledgeBaseId);
        Page<AiKnowledgeDocument> page = documentMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<AiKnowledgeDocument>()
                .eq(AiKnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(AiKnowledgeDocument::getCreateTime));
        return new TableDataInfo<>(page.getRecords().stream().map(this::documentResponse).toList(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long uploadDocument(Long knowledgeBaseId, Long documentId, MultipartFile file) {
        AiKnowledgeBase base = requireEnabledBase(knowledgeBaseId);
        validateFile(file);
        String suffix = suffix(file.getOriginalFilename());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new ServiceException("读取知识文件失败：" + e.getMessage());
        }
        SysOssVo oss = ossService.upload(file, properties.getKnowledgeOssConfigKey());
        AiKnowledgeDocument document;
        if (documentId == null) {
            document = new AiKnowledgeDocument();
            document.setKnowledgeBaseId(knowledgeBaseId);
            document.setDocumentName(file.getOriginalFilename());
            document.setDocumentType(suffix.toUpperCase(Locale.ROOT));
            document.setStatus("UPLOADED");
            document.setEnabled(true);
            documentMapper.insert(document);
        } else {
            document = requireDocument(documentId);
            if (!Objects.equals(document.getKnowledgeBaseId(), knowledgeBaseId))
                throw new ServiceException("文档不属于当前知识库");
            document.setStatus("PROCESSING");
            documentMapper.updateById(document);
        }
        Integer next = documentVersionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeDocumentVersion>()
                .eq(AiKnowledgeDocumentVersion::getDocumentId, document.getId())
                .orderByDesc(AiKnowledgeDocumentVersion::getVersionNo).last("limit 1"))
            .stream().findFirst().map(item -> item.getVersionNo() + 1).orElse(1);
        AiKnowledgeDocumentVersion version = new AiKnowledgeDocumentVersion();
        version.setKnowledgeBaseId(knowledgeBaseId);
        version.setDocumentId(document.getId());
        version.setVersionNo(next);
        version.setOssId(oss.getOssId());
        version.setOriginalFileName(file.getOriginalFilename());
        version.setContentType(file.getContentType());
        version.setFileSize(file.getSize());
        version.setChecksum(hexSha256(bytes));
        version.setParseStatus("PENDING");
        version.setIndexStatus("PENDING");
        documentVersionMapper.insert(version);
        document.setStatus("PROCESSING");
        documentMapper.updateById(document);
        AiKnowledgeTask task = createTask("PARSE_AND_INDEX", base.getId(), document.getId(), version.getId(), null, null);
        base.setStatus("INDEXING");
        base.setFailureReason(null);
        baseMapper.updateById(base);
        dispatcher.dispatchAfterCommit(task.getId(), TenantHelper.getTenantId());
        return document.getId();
    }

    @Override
    public void deleteDocument(Long id) {
        AiKnowledgeDocument document = requireDocument(id);
        document.setEnabled(false);
        document.setStatus("DISABLED");
        documentMapper.updateById(document);
        createAndDispatch("DELETE_INDEX", document.getKnowledgeBaseId(), document.getId(), document.getCurrentVersionId(), null, null);
    }

    @Override
    public List<AiKnowledgeChunk> chunks(Long documentId) {
        requireDocument(documentId);
        return chunkMapper.selectList(new LambdaQueryWrapper<AiKnowledgeChunk>().eq(AiKnowledgeChunk::getDocumentId, documentId)
            .orderByDesc(AiKnowledgeChunk::getDocumentVersionId).orderByAsc(AiKnowledgeChunk::getChunkIndex));
    }

    @Override
    public List<AiKnowledgeFaqResponse> faqs(Long knowledgeBaseId) {
        requireBase(knowledgeBaseId);
        return faqMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaq>().eq(AiKnowledgeFaq::getKnowledgeBaseId, knowledgeBaseId)
            .orderByDesc(AiKnowledgeFaq::getCreateTime)).stream().map(this::faqResponse).toList();
    }

    @Override
    public TableDataInfo<AiKnowledgeFaqResponse> faqPage(Long knowledgeBaseId, PageQuery pageQuery) {
        requireBase(knowledgeBaseId);
        Page<AiKnowledgeFaq> page = faqMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<AiKnowledgeFaq>()
                .eq(AiKnowledgeFaq::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(AiKnowledgeFaq::getCreateTime));
        return new TableDataInfo<>(page.getRecords().stream().map(this::faqResponse).toList(), page.getTotal());
    }

    @Override
    public List<AiKnowledgeFaqVersion> faqVersions(Long faqId) {
        requireFaq(faqId);
        return faqVersionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqVersion>()
            .eq(AiKnowledgeFaqVersion::getFaqId, faqId).orderByDesc(AiKnowledgeFaqVersion::getVersionNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFaq(Long knowledgeBaseId, AiKnowledgeFaqRequest request) {
        requireEnabledBase(knowledgeBaseId);
        ensureFaqCode(knowledgeBaseId, request.getFaqCode(), null);
        AiKnowledgeFaq faq = new AiKnowledgeFaq();
        faq.setKnowledgeBaseId(knowledgeBaseId);
        faq.setFaqCode(normalizeCode(request.getFaqCode()));
        faq.setFaqName(request.getFaqName().trim());
        faq.setAnswerMode(answerMode(request.getAnswerMode()));
        faq.setStatus("DRAFT");
        faq.setEnabled(request.getEnabled() == null || request.getEnabled());
        faqMapper.insert(faq);
        createFaqVersion(faq, request);
        return faq.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFaq(Long id, AiKnowledgeFaqRequest request) {
        AiKnowledgeFaq faq = requireFaq(id);
        ensureFaqCode(faq.getKnowledgeBaseId(), request.getFaqCode(), id);
        faq.setFaqCode(normalizeCode(request.getFaqCode()));
        faq.setFaqName(request.getFaqName().trim());
        faq.setAnswerMode(answerMode(request.getAnswerMode()));
        faq.setEnabled(request.getEnabled() == null || request.getEnabled());
        faq.setStatus("INDEXING");
        faq.setVersion(request.getVersion());
        if (faqMapper.updateById(faq) != 1) throw new ServiceException("FAQ 已被其他用户修改，请刷新后重试");
        createFaqVersion(faq, request);
    }

    @Override
    public void deleteFaq(Long id) {
        AiKnowledgeFaq faq = requireFaq(id);
        faq.setEnabled(false);
        faq.setStatus("DISABLED");
        faqMapper.updateById(faq);
        createAndDispatch("DELETE_FAQ_INDEX", faq.getKnowledgeBaseId(), null, null, faq.getId(), faq.getCurrentVersionId());
    }

    @Override
    public void setFaqEnabled(Long id, boolean enabled) {
        AiKnowledgeFaq faq = requireFaq(id);
        faq.setEnabled(enabled);
        faq.setStatus(enabled ? (faq.getCurrentVersionId() == null ? "DRAFT" : "READY") : "DISABLED");
        faqMapper.updateById(faq);
    }

    @Override
    public List<AiKnowledgeTaskResponse> tasks(Long knowledgeBaseId) {
        return taskMapper.selectList(new LambdaQueryWrapper<AiKnowledgeTask>()
            .eq(knowledgeBaseId != null, AiKnowledgeTask::getKnowledgeBaseId, knowledgeBaseId)
            .orderByDesc(AiKnowledgeTask::getCreateTime)).stream().map(this::taskResponse).toList();
    }

    @Override
    public void retryTask(Long taskId) {
        AiKnowledgeTask task = taskMapper.selectById(taskId);
        if (task == null) throw new ServiceException("知识处理任务不存在");
        if (!"FAILED".equals(task.getStatus())) throw new ServiceException("只有失败任务可以人工重试");
        task.setStatus("PENDING");
        task.setNextRetryAt(null);
        task.setFailureReason(null);
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(null);
        taskMapper.updateById(task);
        dispatcher.dispatch(task.getId(), TenantHelper.getTenantId());
    }

    @Override
    public List<AiKnowledgeSearchHitResponse> search(Long knowledgeBaseId, AiKnowledgeSearchRequest request) {
        AiKnowledgeBase base = requireEnabledBase(knowledgeBaseId);
        AiModel model = requireEmbeddingModel(base.getEmbeddingModelId());
        AiModelProvider provider = requireEnabledProvider(model.getProviderId());
        EmbeddingResult embedding = embeddingRegistry.get(provider.getProviderType()).embed(
            new EmbeddingRequest(provider, model, List.of(request.getQuery())));
        String source = StringUtils.isBlank(request.getSourceType()) ? "DOCUMENT" : request.getSourceType().trim().toUpperCase(Locale.ROOT);
        List<VectorSearchHit> hits = vectorStore.search(base.getCollectionName(), embedding.vectors().get(0),
            Map.of("tenantId", TenantHelper.getTenantId(), "knowledgeBaseId", knowledgeBaseId,
                "sourceType", source, "indexState", "ACTIVE"), request.getLimit() == null ? 5 : request.getLimit());
        return hits.stream().map(this::searchResponse).toList();
    }

    private void createFaqVersion(AiKnowledgeFaq faq, AiKnowledgeFaqRequest request) {
        List<String> normalized = new ArrayList<>();
        normalized.add(KnowledgeTextUtils.normalizeQuestion(request.getStandardQuestion()));
        if (request.getAliases() != null) request.getAliases().stream().map(KnowledgeTextUtils::normalizeQuestion)
            .filter(StringUtils::isNotBlank).forEach(normalized::add);
        if (new HashSet<>(normalized).size() != normalized.size())
            throw new ServiceException("标准问题和相似问法存在重复");
        ensureQuestionsAvailable(faq.getKnowledgeBaseId(), faq.getId(), normalized);
        Integer next = faqVersionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqVersion>().eq(AiKnowledgeFaqVersion::getFaqId, faq.getId())
                .orderByDesc(AiKnowledgeFaqVersion::getVersionNo).last("limit 1"))
            .stream().findFirst().map(item -> item.getVersionNo() + 1).orElse(1);
        AiKnowledgeFaqVersion version = new AiKnowledgeFaqVersion();
        version.setKnowledgeBaseId(faq.getKnowledgeBaseId());
        version.setFaqId(faq.getId());
        version.setVersionNo(next);
        version.setStandardQuestion(request.getStandardQuestion().trim());
        version.setNormalizedQuestion(normalized.get(0));
        version.setStandardAnswer(request.getStandardAnswer().trim());
        version.setQuestionHash(KnowledgeTextUtils.sha256(normalized.get(0)));
        version.setAnswerHash(KnowledgeTextUtils.sha256(version.getStandardAnswer()));
        version.setIndexStatus("PENDING");
        faqVersionMapper.insert(version);
        for (int i = 1; i < normalized.size(); i++) {
            AiKnowledgeFaqAlias alias = new AiKnowledgeFaqAlias();
            alias.setId(IdGeneratorUtil.nextLongId());
            alias.setKnowledgeBaseId(faq.getKnowledgeBaseId());
            alias.setFaqId(faq.getId());
            alias.setFaqVersionId(version.getId());
            alias.setAliasQuestion(request.getAliases().get(i - 1).trim());
            alias.setNormalizedQuestion(normalized.get(i));
            alias.setQuestionHash(KnowledgeTextUtils.sha256(normalized.get(i)));
            alias.setQdrantPointId(pointId("faq-alias", alias.getId().toString()));
            alias.setIndexState("STAGING");
            faqAliasMapper.insert(alias);
        }
        faq.setStatus("INDEXING");
        faqMapper.updateById(faq);
        createAndDispatch("FAQ_INDEX", faq.getKnowledgeBaseId(), null, null, faq.getId(), version.getId());
    }

    private void ensureQuestionsAvailable(Long kbId, Long faqId, List<String> questions) {
        List<AiKnowledgeFaq> activeFaqs = faqMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaq>()
            .eq(AiKnowledgeFaq::getKnowledgeBaseId, kbId).ne(AiKnowledgeFaq::getId, faqId).isNotNull(AiKnowledgeFaq::getCurrentVersionId));
        Set<Long> versions = new HashSet<>();
        activeFaqs.forEach(item -> versions.add(item.getCurrentVersionId()));
        if (versions.isEmpty()) return;
        Set<String> used = new HashSet<>();
        faqVersionMapper.selectBatchIds(versions).forEach(item -> used.add(item.getNormalizedQuestion()));
        faqAliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>().in(AiKnowledgeFaqAlias::getFaqVersionId, versions)
            .eq(AiKnowledgeFaqAlias::getIndexState, "ACTIVE")).forEach(item -> used.add(item.getNormalizedQuestion()));
        if (questions.stream().anyMatch(used::contains))
            throw new ServiceException("FAQ 问题或相似问法已在当前知识库中使用");
    }

    private AiKnowledgeTask createTask(String type, Long kbId, Long documentId, Long documentVersionId, Long faqId, Long faqVersionId) {
        AiKnowledgeTask task = new AiKnowledgeTask();
        task.setTaskType(type);
        task.setKnowledgeBaseId(kbId);
        task.setDocumentId(documentId);
        task.setDocumentVersionId(documentVersionId);
        task.setFaqId(faqId);
        task.setFaqVersionId(faqVersionId);
        task.setStatus("PENDING");
        task.setRetryCount(0);
        task.setProgressTotal(0);
        task.setProgressCompleted(0);
        taskMapper.insert(task);
        return task;
    }

    private AiKnowledgeTask createRebuildTask(String type, AiKnowledgeBase base, Long documentId, Long documentVersionId,
                                              Long faqId, Long faqVersionId, Long modelId, String collection, String batchId) {
        AiKnowledgeTask task = createTask(type, base.getId(), documentId, documentVersionId, faqId, faqVersionId);
        task.setTargetEmbeddingModelId(modelId);
        task.setTargetCollectionName(collection);
        task.setRebuildBatchId(batchId);
        taskMapper.updateById(task);
        return task;
    }

    private void createAndDispatch(String type, Long kbId, Long documentId, Long versionId, Long faqId, Long faqVersionId) {
        AiKnowledgeTask task = createTask(type, kbId, documentId, versionId, faqId, faqVersionId);
        dispatcher.dispatchAfterCommit(task.getId(), TenantHelper.getTenantId());
    }

    private void fillBase(AiKnowledgeBase base, AiKnowledgeBaseRequest request) {
        int size = request.getChunkSize() == null ? 800 : request.getChunkSize();
        int overlap = request.getChunkOverlap() == null ? 100 : request.getChunkOverlap();
        if (overlap >= size / 2) throw new ServiceException("切片重叠长度必须小于切片长度的一半");
        base.setKnowledgeCode(normalizeCode(request.getKnowledgeCode()));
        base.setKnowledgeName(request.getKnowledgeName().trim());
        base.setDescription(request.getDescription());
        base.setEmbeddingModelId(request.getEmbeddingModelId());
        base.setChunkSize(size);
        base.setChunkOverlap(overlap);
        base.setDefaultTopK(request.getDefaultTopK() == null ? 5 : request.getDefaultTopK());
        base.setScoreThreshold(request.getScoreThreshold() == null ? new BigDecimal("0.5") : request.getScoreThreshold());
        base.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ServiceException("知识文件不能为空");
        long max = Math.max(1, properties.getMaxDocumentSizeMb()) * 1024L * 1024L;
        if (file.getSize() > max)
            throw new ServiceException("知识文件不能超过 " + properties.getMaxDocumentSizeMb() + "MB");
        if (!SUPPORTED_SUFFIXES.contains(suffix(file.getOriginalFilename())))
            throw new ServiceException("仅支持 TXT、MD、PDF、DOCX、XLSX");
    }

    private boolean hasReadyContent(Long kbId) {
        return documentMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeDocument>().eq(AiKnowledgeDocument::getKnowledgeBaseId, kbId)
            .eq(AiKnowledgeDocument::getStatus, "READY")) > 0 || faqMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeFaq>()
            .eq(AiKnowledgeFaq::getKnowledgeBaseId, kbId).eq(AiKnowledgeFaq::getStatus, "READY")) > 0;
    }

    private void validateBoundAgentEmbedding(Long kbId, Long targetModelId) {
        List<AiAgentKnowledgeBase> currentBindings = agentKnowledgeMapper.selectList(new LambdaQueryWrapper<AiAgentKnowledgeBase>()
            .eq(AiAgentKnowledgeBase::getKnowledgeBaseId, kbId).eq(AiAgentKnowledgeBase::getEnabled, true));
        for (AiAgentKnowledgeBase current : currentBindings) {
            List<AiAgentKnowledgeBase> others = agentKnowledgeMapper.selectList(new LambdaQueryWrapper<AiAgentKnowledgeBase>()
                .eq(AiAgentKnowledgeBase::getAgentId, current.getAgentId()).eq(AiAgentKnowledgeBase::getEnabled, true)
                .ne(AiAgentKnowledgeBase::getKnowledgeBaseId, kbId));
            for (AiAgentKnowledgeBase other : others) {
                AiKnowledgeBase otherBase = baseMapper.selectById(other.getKnowledgeBaseId());
                if (otherBase != null && !Objects.equals(targetModelId, otherBase.getEmbeddingModelId())) {
                    throw new ServiceException("该知识库被 AI 助手与其他知识库共同绑定，目标向量模型不一致，不能重建");
                }
            }
        }
    }

    private String aggregateStatus(Long kbId) {
        long processing = taskMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeTask>().eq(AiKnowledgeTask::getKnowledgeBaseId, kbId)
            .in(AiKnowledgeTask::getStatus, "PENDING", "PROCESSING"));
        if (processing > 0) return "INDEXING";
        return hasReadyContent(kbId) ? "READY" : "DRAFT";
    }

    private AiKnowledgeBase requireBase(Long id) {
        AiKnowledgeBase item = baseMapper.selectById(id);
        if (item == null) throw new ServiceException("知识库不存在");
        return item;
    }

    private AiKnowledgeBase requireEnabledBase(Long id) {
        AiKnowledgeBase item = requireBase(id);
        if (!Boolean.TRUE.equals(item.getEnabled())) throw new ServiceException("知识库已停用");
        return item;
    }

    private AiKnowledgeDocument requireDocument(Long id) {
        AiKnowledgeDocument item = documentMapper.selectById(id);
        if (item == null) throw new ServiceException("知识文档不存在");
        return item;
    }

    private AiKnowledgeFaq requireFaq(Long id) {
        AiKnowledgeFaq item = faqMapper.selectById(id);
        if (item == null) throw new ServiceException("FAQ 不存在");
        return item;
    }

    private AiModel requireEmbeddingModel(Long id) {
        AiModel item = modelMapper.selectById(id);
        if (item == null || !"EMBEDDING".equals(item.getCapability()) || !Boolean.TRUE.equals(item.getEnabled()))
            throw new ServiceException("向量模型不存在或未启用");
        return item;
    }

    private AiModelProvider requireEnabledProvider(Long id) {
        AiModelProvider item = providerMapper.selectById(id);
        if (item == null || !Boolean.TRUE.equals(item.getEnabled()))
            throw new ServiceException("模型服务商不存在或未启用");
        return item;
    }

    private void ensureBaseCode(String code, Long exclude) {
        if (baseMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeBase>().eq(AiKnowledgeBase::getKnowledgeCode, normalizeCode(code)).ne(exclude != null, AiKnowledgeBase::getId, exclude)) > 0)
            throw new ServiceException("知识库编码已存在");
    }

    private void ensureFaqCode(Long kb, String code, Long exclude) {
        if (faqMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeFaq>().eq(AiKnowledgeFaq::getKnowledgeBaseId, kb).eq(AiKnowledgeFaq::getFaqCode, normalizeCode(code)).ne(exclude != null, AiKnowledgeFaq::getId, exclude)) > 0)
            throw new ServiceException("FAQ 编码已存在");
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String answerMode(String value) {
        String result = StringUtils.isBlank(value) ? "DIRECT" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DIRECT", "CONTEXT").contains(result)) throw new ServiceException("FAQ 回答模式不支持");
        return result;
    }

    private String suffix(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String hexSha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String pointId(String type, String value) {
        return UUID.nameUUIDFromBytes((type + ":" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private String collectionName(String tenantId, AiModel model) {
        String hash = KnowledgeTextUtils.sha256(tenantId).substring(0, 12);
        return "cnx_kb_" + hash + "_" + model.getId() + "_" + model.getVectorDimension();
    }

    private AiKnowledgeBaseResponse baseResponse(AiKnowledgeBase item, String modelName) {
        AiKnowledgeBaseResponse r = new AiKnowledgeBaseResponse();
        r.setId(item.getId());
        r.setKnowledgeCode(item.getKnowledgeCode());
        r.setKnowledgeName(item.getKnowledgeName());
        r.setDescription(item.getDescription());
        r.setEmbeddingModelId(item.getEmbeddingModelId());
        r.setEmbeddingModelName(modelName);
        r.setCollectionName(item.getCollectionName());
        r.setChunkSize(item.getChunkSize());
        r.setChunkOverlap(item.getChunkOverlap());
        r.setDefaultTopK(item.getDefaultTopK());
        r.setScoreThreshold(item.getScoreThreshold());
        r.setStatus(item.getStatus());
        r.setDocumentCount(item.getDocumentCount());
        r.setFaqCount(item.getFaqCount());
        r.setChunkCount(item.getChunkCount());
        r.setLastIndexedAt(item.getLastIndexedAt());
        r.setFailureReason(item.getFailureReason());
        r.setEnabled(item.getEnabled());
        r.setVersion(item.getVersion());
        return r;
    }

    private AiKnowledgeDocumentResponse documentResponse(AiKnowledgeDocument item) {
        AiKnowledgeDocumentResponse r = new AiKnowledgeDocumentResponse();
        r.setId(item.getId());
        r.setKnowledgeBaseId(item.getKnowledgeBaseId());
        r.setDocumentName(item.getDocumentName());
        r.setDocumentType(item.getDocumentType());
        r.setCurrentVersionId(item.getCurrentVersionId());
        r.setStatus(item.getStatus());
        r.setEnabled(item.getEnabled());
        r.setCreateTime(item.getCreateTime());
        AiKnowledgeDocumentVersion v = item.getCurrentVersionId() == null ? documentVersionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeDocumentVersion>().eq(AiKnowledgeDocumentVersion::getDocumentId, item.getId()).orderByDesc(AiKnowledgeDocumentVersion::getVersionNo).last("limit 1")).stream().findFirst().orElse(null) : documentVersionMapper.selectById(item.getCurrentVersionId());
        if (v != null) {
            r.setVersionNo(v.getVersionNo());
            r.setParseStatus(v.getParseStatus());
            r.setIndexStatus(v.getIndexStatus());
            r.setChunkCount(v.getChunkCount());
            r.setFailureReason(v.getFailureReason());
        }
        return r;
    }

    private AiKnowledgeFaqResponse faqResponse(AiKnowledgeFaq item) {
        AiKnowledgeFaqResponse r = new AiKnowledgeFaqResponse();
        r.setId(item.getId());
        r.setKnowledgeBaseId(item.getKnowledgeBaseId());
        r.setFaqCode(item.getFaqCode());
        r.setFaqName(item.getFaqName());
        r.setCurrentVersionId(item.getCurrentVersionId());
        r.setStatus(item.getStatus());
        r.setAnswerMode(item.getAnswerMode());
        r.setEnabled(item.getEnabled());
        r.setVersion(item.getVersion());
        r.setCreateTime(item.getCreateTime());
        AiKnowledgeFaqVersion v = item.getCurrentVersionId() == null ? faqVersionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqVersion>().eq(AiKnowledgeFaqVersion::getFaqId, item.getId()).orderByDesc(AiKnowledgeFaqVersion::getVersionNo).last("limit 1")).stream().findFirst().orElse(null) : faqVersionMapper.selectById(item.getCurrentVersionId());
        if (v != null) {
            r.setVersionNo(v.getVersionNo());
            r.setStandardQuestion(v.getStandardQuestion());
            r.setStandardAnswer(v.getStandardAnswer());
            r.setIndexStatus(v.getIndexStatus());
            r.setFailureReason(v.getFailureReason());
            r.setAliases(faqAliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>().eq(AiKnowledgeFaqAlias::getFaqVersionId, v.getId())).stream().map(AiKnowledgeFaqAlias::getAliasQuestion).toList());
        }
        return r;
    }

    private AiKnowledgeTaskResponse taskResponse(AiKnowledgeTask item) {
        AiKnowledgeTaskResponse r = new AiKnowledgeTaskResponse();
        r.setId(item.getId());
        r.setTaskType(item.getTaskType());
        r.setKnowledgeBaseId(item.getKnowledgeBaseId());
        r.setDocumentId(item.getDocumentId());
        r.setFaqId(item.getFaqId());
        r.setStatus(item.getStatus());
        r.setRetryCount(item.getRetryCount());
        r.setProgressTotal(item.getProgressTotal());
        r.setProgressCompleted(item.getProgressCompleted());
        r.setFailureReason(item.getFailureReason());
        r.setNextRetryAt(item.getNextRetryAt());
        r.setStartedAt(item.getStartedAt());
        r.setFinishedAt(item.getFinishedAt());
        return r;
    }

    private AiKnowledgeSearchHitResponse searchResponse(VectorSearchHit hit) {
        AiKnowledgeSearchHitResponse r = new AiKnowledgeSearchHitResponse();
        r.setSourceType(String.valueOf(hit.payload().get("sourceType")));
        r.setScore(hit.score());
        r.setTitle(String.valueOf(hit.payload().getOrDefault("title", hit.payload().getOrDefault("question", ""))));
        r.setContent(String.valueOf(hit.payload().getOrDefault("content", hit.payload().getOrDefault("answer", ""))));
        r.setLocation(String.valueOf(hit.payload().getOrDefault("location", "")));
        r.setMetadata(hit.payload());
        return r;
    }
}
