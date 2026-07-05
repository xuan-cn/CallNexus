package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.knowledge.KnowledgeTextUtils;
import org.dromara.ai.mapper.*;
import org.dromara.ai.service.*;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AiFaqCandidateApplicationServiceImpl implements AiFaqCandidateApplicationService {
    private static final List<String> HEADERS = List.of("FAQ编码", "FAQ名称", "标准问题", "标准答案", "相似问法", "回答模式");
    private final AiFaqCandidateBatchMapper batchMapper;
    private final AiFaqCandidateMapper candidateMapper;
    private final AiKnowledgeBaseMapper baseMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeFaqMapper faqMapper;
    private final AiKnowledgeFaqVersionMapper faqVersionMapper;
    private final AiKnowledgeFaqAliasMapper aliasMapper;
    private final AiModelMapper modelMapper;
    private final AiKnowledgeTaskMapper taskMapper;
    private final AiKnowledgeApplicationService knowledgeService;
    private final AiKnowledgeTaskDispatcher dispatcher;

    @Override
    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("FAQ导入");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
            Row example = sheet.createRow(1);
            example.createCell(0).setCellValue("FAQ_SEAT_EXTENSION");
            example.createCell(1).setCellValue("坐席与分机区别");
            example.createCell(2).setCellValue("坐席和分机有什么区别？");
            example.createCell(3).setCellValue("坐席代表业务人员，分机代表具体的通话终端。");
            example.createCell(4).setCellValue("坐席是不是分机？|agent和extension有什么区别？");
            example.createCell(5).setCellValue("DIRECT");
            for (int i = 0; i < HEADERS.size(); i++) sheet.setColumnWidth(i, i == 3 ? 14000 : 7000);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new ServiceException("生成 FAQ 导入模板失败：" + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long importExcel(Long knowledgeBaseId, MultipartFile file) {
        requireBase(knowledgeBaseId);
        if (file == null || file.isEmpty()) throw new ServiceException("请选择 FAQ Excel 文件");
        AiFaqCandidateBatch batch = newBatch(knowledgeBaseId, "EXCEL");
        batch.setSourceFileName(file.getOriginalFilename());
        batch.setStatus("PROCESSING");
        batchMapper.insert(batch);
        Set<String> usedQuestions = existingQuestions(knowledgeBaseId);
        Set<String> batchQuestions = new HashSet<>();
        Set<String> usedCodes = existingCodes(knowledgeBaseId);
        DataFormatter formatter = new DataFormatter();
        int total = 0;
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            validateHeader(sheet.getRow(0), formatter);
            if (sheet.getLastRowNum() > 5000) throw new ServiceException("单次最多导入 5000 条 FAQ");
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || rowEmpty(row, formatter)) continue;
                total++;
                AiFaqCandidate candidate = new AiFaqCandidate();
                candidate.setBatchId(batch.getId()); candidate.setKnowledgeBaseId(knowledgeBaseId); candidate.setRowNumber(rowIndex + 1);
                candidate.setFaqCode(value(row, 0, formatter)); candidate.setFaqName(value(row, 1, formatter));
                candidate.setStandardQuestion(value(row, 2, formatter)); candidate.setStandardAnswer(value(row, 3, formatter));
                candidate.setAliasesJson(JsonUtils.toJsonString(splitAliases(value(row, 4, formatter))));
                candidate.setAnswerMode(answerMode(value(row, 5, formatter)));
                prepareCandidate(candidate, usedQuestions, batchQuestions, usedCodes);
                candidateMapper.insert(candidate);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("读取 FAQ Excel 失败：" + e.getMessage());
        }
        refreshBatch(batch, "REVIEW");
        if (total == 0) throw new ServiceException("Excel 中没有可预检的数据");
        return batch.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long extractDocument(Long knowledgeBaseId, AiFaqExtractionRequest request) {
        requireBase(knowledgeBaseId);
        AiKnowledgeDocument document = documentMapper.selectById(request.getDocumentId());
        if (document == null || !Objects.equals(document.getKnowledgeBaseId(), knowledgeBaseId)) throw new ServiceException("知识文档不存在");
        if (document.getCurrentVersionId() == null || !"READY".equals(document.getStatus())) throw new ServiceException("知识文档尚未完成索引");
        AiModel model = modelMapper.selectById(request.getChatModelId());
        if (model == null || !"CHAT".equals(model.getCapability()) || !Boolean.TRUE.equals(model.getEnabled())) throw new ServiceException("Chat 模型不存在或未启用");
        AiFaqCandidateBatch batch = newBatch(knowledgeBaseId, "AI_DOCUMENT");
        batch.setDocumentId(document.getId()); batch.setDocumentVersionId(document.getCurrentVersionId());
        batch.setChatModelId(model.getId()); batch.setSourceFileName(document.getDocumentName()); batch.setStatus("PENDING");
        batchMapper.insert(batch);
        AiKnowledgeTask task = new AiKnowledgeTask();
        task.setTaskType("FAQ_EXTRACT"); task.setKnowledgeBaseId(knowledgeBaseId); task.setDocumentId(document.getId());
        task.setDocumentVersionId(document.getCurrentVersionId()); task.setCandidateBatchId(batch.getId()); task.setStatus("PENDING");
        task.setRetryCount(0); task.setProgressTotal(0); task.setProgressCompleted(0); taskMapper.insert(task);
        dispatcher.dispatchAfterCommit(task.getId(), TenantHelper.getTenantId());
        return batch.getId();
    }

    @Override
    public List<AiFaqCandidateBatchResponse> batches(Long knowledgeBaseId) {
        requireBase(knowledgeBaseId);
        return batchMapper.selectList(new LambdaQueryWrapper<AiFaqCandidateBatch>()
            .eq(AiFaqCandidateBatch::getKnowledgeBaseId, knowledgeBaseId).orderByDesc(AiFaqCandidateBatch::getCreateTime))
            .stream().map(this::batchResponse).toList();
    }

    @Override
    public List<AiFaqCandidateResponse> candidates(Long batchId) {
        requireBatch(batchId);
        return candidateMapper.selectList(new LambdaQueryWrapper<AiFaqCandidate>()
            .eq(AiFaqCandidate::getBatchId, batchId).orderByAsc(AiFaqCandidate::getRowNumber)).stream().map(this::candidateResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCandidate(Long id, AiFaqCandidateUpdateRequest request) {
        AiFaqCandidate value = candidateMapper.selectById(id);
        if (value == null || "CONFIRMED".equals(value.getStatus())) throw new ServiceException("FAQ 候选不存在或已发布");
        value.setFaqCode(request.getFaqCode().trim().toUpperCase(Locale.ROOT)); value.setFaqName(request.getFaqName().trim());
        value.setStandardQuestion(request.getStandardQuestion().trim()); value.setNormalizedQuestion(KnowledgeTextUtils.normalizeQuestion(request.getStandardQuestion()));
        value.setStandardAnswer(request.getStandardAnswer().trim()); value.setAliasesJson(JsonUtils.toJsonString(cleanAliases(request.getAliases())));
        value.setAnswerMode(answerMode(request.getAnswerMode())); value.setVersion(request.getVersion()); value.setErrorMessage(null); value.setStatus("VALID");
        if (candidateMapper.updateById(value) != 1) throw new ServiceException("FAQ 候选已被其他用户修改，请刷新后重试");
        refreshBatch(requireBatch(value.getBatchId()), "REVIEW");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirm(Long batchId, AiFaqCandidateConfirmRequest request) {
        AiFaqCandidateBatch batch = requireBatch(batchId);
        if (!Set.of("REVIEW", "CONFIRMED").contains(batch.getStatus())) throw new ServiceException("当前批次尚不可发布");
        Set<Long> selected = request == null || request.getCandidateIds() == null ? Set.of() : new HashSet<>(request.getCandidateIds());
        List<AiFaqCandidate> values = candidateMapper.selectList(new LambdaQueryWrapper<AiFaqCandidate>()
            .eq(AiFaqCandidate::getBatchId, batchId).eq(AiFaqCandidate::getStatus, "VALID"));
        if (!selected.isEmpty()) values = values.stream().filter(item -> selected.contains(item.getId())).toList();
        if (values.isEmpty()) throw new ServiceException("没有选择可发布的 FAQ 候选");
        values = prepareForConfirmation(batch.getKnowledgeBaseId(), values);
        if (values.isEmpty()) throw new ServiceException("选中的 FAQ 候选均存在问法冲突，请修改后重试");
        int confirmed = 0;
        for (AiFaqCandidate candidate : values) {
            AiKnowledgeFaqRequest faq = new AiKnowledgeFaqRequest();
            faq.setFaqCode(candidate.getFaqCode()); faq.setFaqName(candidate.getFaqName()); faq.setStandardQuestion(candidate.getStandardQuestion());
            faq.setStandardAnswer(candidate.getStandardAnswer()); faq.setAliases(aliases(candidate.getAliasesJson())); faq.setAnswerMode(candidate.getAnswerMode()); faq.setEnabled(true);
            Long faqId = knowledgeService.createFaq(batch.getKnowledgeBaseId(), faq);
            candidate.setFaqId(faqId); candidate.setStatus("CONFIRMED"); candidate.setErrorMessage(null); candidateMapper.updateById(candidate); confirmed++;
        }
        refreshBatch(batch, "CONFIRMED");
        return confirmed;
    }

    private List<AiFaqCandidate> prepareForConfirmation(Long knowledgeBaseId, List<AiFaqCandidate> values) {
        Set<String> occupiedQuestions = existingQuestions(knowledgeBaseId);
        aliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>()
                .eq(AiKnowledgeFaqAlias::getKnowledgeBaseId, knowledgeBaseId))
            .forEach(alias -> occupiedQuestions.add(alias.getNormalizedQuestion()));

        List<AiFaqCandidate> publishable = new ArrayList<>();
        values.stream().sorted(Comparator.comparing(AiFaqCandidate::getRowNumber)).forEach(candidate -> {
            String standardQuestion = KnowledgeTextUtils.normalizeQuestion(candidate.getStandardQuestion());
            if (StringUtils.isBlank(standardQuestion) || occupiedQuestions.contains(standardQuestion)) {
                candidate.setStatus("INVALID");
                candidate.setErrorMessage("标准问题已被当前知识库或本次其他候选使用");
                candidateMapper.updateById(candidate);
                return;
            }

            Set<String> candidateQuestions = new HashSet<>();
            candidateQuestions.add(standardQuestion);
            List<String> retainedAliases = new ArrayList<>();
            for (String alias : aliases(candidate.getAliasesJson())) {
                String normalizedAlias = KnowledgeTextUtils.normalizeQuestion(alias);
                if (StringUtils.isBlank(normalizedAlias)
                    || !candidateQuestions.add(normalizedAlias)
                    || occupiedQuestions.contains(normalizedAlias)) {
                    continue;
                }
                retainedAliases.add(alias.trim());
            }
            candidate.setAliasesJson(JsonUtils.toJsonString(retainedAliases));
            candidate.setErrorMessage(null);
            candidateMapper.updateById(candidate);
            occupiedQuestions.addAll(candidateQuestions);
            publishable.add(candidate);
        });
        return publishable;
    }

    private void prepareCandidate(AiFaqCandidate value, Set<String> existingQuestions, Set<String> batchQuestions, Set<String> usedCodes) {
        if (StringUtils.isBlank(value.getFaqCode())) value.setFaqCode("FAQ_" + value.getBatchId() + "_" + value.getRowNumber());
        value.setFaqCode(value.getFaqCode().trim().toUpperCase(Locale.ROOT));
        if (StringUtils.isBlank(value.getFaqName())) value.setFaqName(shorten(value.getStandardQuestion(), 128));
        String normalized = KnowledgeTextUtils.normalizeQuestion(value.getStandardQuestion()); value.setNormalizedQuestion(normalized);
        String error = null;
        if (StringUtils.isBlank(normalized)) error = "标准问题不能为空";
        else if (StringUtils.isBlank(value.getStandardAnswer())) error = "标准答案不能为空";
        else if (existingQuestions.contains(normalized)) error = "标准问题已存在于当前知识库";
        else if (!batchQuestions.add(normalized)) error = "批次内标准问题重复";
        else if (!usedCodes.add(value.getFaqCode())) error = "FAQ 编码重复";
        value.setStatus(error == null ? "VALID" : "INVALID"); value.setErrorMessage(error);
    }

    private void refreshBatch(AiFaqCandidateBatch batch, String status) {
        List<AiFaqCandidate> values = candidateMapper.selectList(new LambdaQueryWrapper<AiFaqCandidate>().eq(AiFaqCandidate::getBatchId, batch.getId()));
        batch.setTotalCount(values.size()); batch.setValidCount((int) values.stream().filter(v -> "VALID".equals(v.getStatus())).count());
        batch.setInvalidCount((int) values.stream().filter(v -> "INVALID".equals(v.getStatus())).count());
        batch.setConfirmedCount((int) values.stream().filter(v -> "CONFIRMED".equals(v.getStatus())).count());
        batch.setStatus(status); batch.setFailureReason(null); batch.setFinishedAt(java.time.LocalDateTime.now()); batchMapper.updateById(batch);
    }

    private Set<String> existingQuestions(Long kbId) {
        List<AiKnowledgeFaq> faqs = faqMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaq>()
            .eq(AiKnowledgeFaq::getKnowledgeBaseId, kbId).isNotNull(AiKnowledgeFaq::getCurrentVersionId));
        if (faqs.isEmpty()) return new HashSet<>();
        Set<Long> versions = new HashSet<>(); faqs.forEach(f -> versions.add(f.getCurrentVersionId()));
        Set<String> result = new HashSet<>(); faqVersionMapper.selectBatchIds(versions).forEach(v -> result.add(v.getNormalizedQuestion()));
        aliasMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaqAlias>().in(AiKnowledgeFaqAlias::getFaqVersionId, versions))
            .forEach(v -> result.add(v.getNormalizedQuestion())); return result;
    }
    private Set<String> existingCodes(Long kbId) { Set<String> result=new HashSet<>(); faqMapper.selectList(new LambdaQueryWrapper<AiKnowledgeFaq>().eq(AiKnowledgeFaq::getKnowledgeBaseId,kbId)).forEach(v->result.add(v.getFaqCode())); return result; }
    private AiFaqCandidateBatch newBatch(Long kbId,String source){AiFaqCandidateBatch b=new AiFaqCandidateBatch();b.setKnowledgeBaseId(kbId);b.setSourceType(source);b.setStatus("PENDING");b.setTotalCount(0);b.setValidCount(0);b.setInvalidCount(0);b.setConfirmedCount(0);return b;}
    private AiKnowledgeBase requireBase(Long id){AiKnowledgeBase v=baseMapper.selectById(id);if(v==null||!Boolean.TRUE.equals(v.getEnabled()))throw new ServiceException("知识库不存在或已停用");return v;}
    private AiFaqCandidateBatch requireBatch(Long id){AiFaqCandidateBatch v=batchMapper.selectById(id);if(v==null)throw new ServiceException("FAQ 候选批次不存在");return v;}
    private void validateHeader(Row row,DataFormatter f){if(row==null)throw new ServiceException("Excel 缺少表头");for(int i=0;i<HEADERS.size();i++)if(!HEADERS.get(i).equals(value(row,i,f)))throw new ServiceException("第"+(i+1)+"列表头必须为“"+HEADERS.get(i)+"”");}
    private boolean rowEmpty(Row row,DataFormatter f){for(int i=0;i<HEADERS.size();i++)if(StringUtils.isNotBlank(value(row,i,f)))return false;return true;}
    private String value(Row row,int index,DataFormatter f){Cell c=row.getCell(index);return c==null?"":f.formatCellValue(c).trim();}
    private List<String> splitAliases(String value){return StringUtils.isBlank(value)?List.of():cleanAliases(Arrays.asList(value.split("[|｜]")));}
    private List<String> cleanAliases(List<String> values){if(values==null)return List.of();return values.stream().filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();}
    private String answerMode(String value){return "CONTEXT".equalsIgnoreCase(value)?"CONTEXT":"DIRECT";}
    private String shorten(String value,int max){if(value==null)return "未命名FAQ";String v=value.trim();return v.length()>max?v.substring(0,max):v;}
    private List<String> aliases(String json){if(StringUtils.isBlank(json))return List.of();try{return JsonUtils.getObjectMapper().readValue(json,JsonUtils.getObjectMapper().getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){return List.of();}}
    private AiFaqCandidateBatchResponse batchResponse(AiFaqCandidateBatch v){AiFaqCandidateBatchResponse r=new AiFaqCandidateBatchResponse();r.setId(v.getId());r.setKnowledgeBaseId(v.getKnowledgeBaseId());r.setSourceType(v.getSourceType());r.setDocumentId(v.getDocumentId());r.setChatModelId(v.getChatModelId());r.setSourceFileName(v.getSourceFileName());r.setStatus(v.getStatus());r.setTotalCount(v.getTotalCount());r.setValidCount(v.getValidCount());r.setInvalidCount(v.getInvalidCount());r.setConfirmedCount(v.getConfirmedCount());r.setFailureReason(v.getFailureReason());r.setCreateTime(v.getCreateTime());r.setFinishedAt(v.getFinishedAt());return r;}
    private AiFaqCandidateResponse candidateResponse(AiFaqCandidate v){AiFaqCandidateResponse r=new AiFaqCandidateResponse();r.setId(v.getId());r.setBatchId(v.getBatchId());r.setRowNumber(v.getRowNumber());r.setFaqCode(v.getFaqCode());r.setFaqName(v.getFaqName());r.setStandardQuestion(v.getStandardQuestion());r.setStandardAnswer(v.getStandardAnswer());r.setAliases(aliases(v.getAliasesJson()));r.setAnswerMode(v.getAnswerMode());r.setSourceLocation(v.getSourceLocation());r.setSourceText(v.getSourceText());r.setConfidence(v.getConfidence());r.setStatus(v.getStatus());r.setErrorMessage(v.getErrorMessage());r.setFaqId(v.getFaqId());r.setVersion(v.getVersion());return r;}
}
