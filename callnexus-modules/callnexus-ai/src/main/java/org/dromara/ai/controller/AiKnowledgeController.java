package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiKnowledgeChunk;
import org.dromara.ai.domain.request.AiKnowledgeBaseRequest;
import org.dromara.ai.domain.request.AiKnowledgeFaqRequest;
import org.dromara.ai.domain.request.AiKnowledgeSearchRequest;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.service.AiKnowledgeApplicationService;
import org.dromara.ai.service.AiFaqCandidateApplicationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiKnowledgeController {

    private final AiKnowledgeApplicationService service;
    private final AiFaqCandidateApplicationService candidateService;

    @GetMapping("/knowledge-bases")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public R<List<AiKnowledgeBaseResponse>> knowledgeBases() {
        return R.ok(service.knowledgeBases());
    }

    @GetMapping("/knowledge-bases/page")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public TableDataInfo<AiKnowledgeBaseResponse> knowledgeBasePage(PageQuery pageQuery) {
        return service.knowledgeBasePage(pageQuery);
    }

    @GetMapping("/knowledge-bases/{id}")
    @SaCheckPermission("callcenter:ai-knowledge:query")
    public R<AiKnowledgeBaseResponse> knowledgeBase(@PathVariable Long id) {
        return R.ok(service.knowledgeBase(id));
    }

    @PostMapping("/knowledge-bases")
    @SaCheckPermission("callcenter:ai-knowledge:create")
    public R<Long> createKnowledgeBase(@Valid @RequestBody AiKnowledgeBaseRequest request) {
        return R.ok(service.createKnowledgeBase(request));
    }

    @PutMapping("/knowledge-bases/{id}")
    @SaCheckPermission("callcenter:ai-knowledge:update")
    public R<Void> updateKnowledgeBase(@PathVariable Long id, @Valid @RequestBody AiKnowledgeBaseRequest request) {
        service.updateKnowledgeBase(id, request);
        return R.ok();
    }

    @DeleteMapping("/knowledge-bases/{id}")
    @SaCheckPermission("callcenter:ai-knowledge:delete")
    public R<Void> deleteKnowledgeBase(@PathVariable Long id) {
        service.deleteKnowledgeBase(id);
        return R.ok();
    }

    @PostMapping("/knowledge-bases/{id}/{action:enable|disable}")
    @SaCheckPermission("callcenter:ai-knowledge:update")
    public R<Void> setKnowledgeBaseEnabled(@PathVariable Long id, @PathVariable String action) {
        service.setKnowledgeBaseEnabled(id, "enable".equals(action));
        return R.ok();
    }

    @PostMapping("/knowledge-bases/{id}/rebuild")
    @SaCheckPermission("callcenter:ai-knowledge:update")
    public R<Void> rebuild(@PathVariable Long id, @RequestParam(required = false) Long embeddingModelId) {
        service.rebuildKnowledgeBase(id, embeddingModelId);
        return R.ok();
    }

    @PostMapping("/knowledge-bases/{id}/search-test")
    @SaCheckPermission("callcenter:ai-knowledge:query")
    public R<List<AiKnowledgeSearchHitResponse>> search(@PathVariable Long id,
                                                        @Valid @RequestBody AiKnowledgeSearchRequest request) {
        return R.ok(service.search(id, request));
    }

    @GetMapping("/knowledge-documents")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public R<List<AiKnowledgeDocumentResponse>> documents(@RequestParam Long knowledgeBaseId) {
        return R.ok(service.documents(knowledgeBaseId));
    }

    @GetMapping("/knowledge-documents/page")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public TableDataInfo<AiKnowledgeDocumentResponse> documentPage(@RequestParam Long knowledgeBaseId, PageQuery pageQuery) {
        return service.documentPage(knowledgeBaseId, pageQuery);
    }

    @PostMapping(value = "/knowledge-documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission("callcenter:ai-knowledge:create")
    public R<Long> uploadDocument(@RequestParam Long knowledgeBaseId,
                                  @RequestParam(required = false) Long documentId,
                                  @RequestPart MultipartFile file) {
        return R.ok(service.uploadDocument(knowledgeBaseId, documentId, file));
    }

    @DeleteMapping("/knowledge-documents/{id}")
    @SaCheckPermission("callcenter:ai-knowledge:delete")
    public R<Void> deleteDocument(@PathVariable Long id) {
        service.deleteDocument(id);
        return R.ok();
    }

    @GetMapping("/knowledge-documents/{id}/chunks")
    @SaCheckPermission("callcenter:ai-knowledge:query")
    public R<List<AiKnowledgeChunk>> chunks(@PathVariable Long id) {
        return R.ok(service.chunks(id));
    }

    @GetMapping("/knowledge-documents/{id}/versions")
    @SaCheckPermission("callcenter:ai-knowledge:query")
    public R<List<org.dromara.ai.domain.AiKnowledgeDocumentVersion>> documentVersions(@PathVariable Long id) {
        return R.ok(service.documentVersions(id));
    }

    @GetMapping("/knowledge-faqs")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public R<List<AiKnowledgeFaqResponse>> faqs(@RequestParam Long knowledgeBaseId) {
        return R.ok(service.faqs(knowledgeBaseId));
    }

    @GetMapping("/knowledge-faqs/page")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public TableDataInfo<AiKnowledgeFaqResponse> faqPage(@RequestParam Long knowledgeBaseId, PageQuery pageQuery) {
        return service.faqPage(knowledgeBaseId, pageQuery);
    }

    @GetMapping("/knowledge-faqs/{id}/versions")
    @SaCheckPermission("callcenter:ai-knowledge:query")
    public R<List<org.dromara.ai.domain.AiKnowledgeFaqVersion>> faqVersions(@PathVariable Long id) {
        return R.ok(service.faqVersions(id));
    }

    @PostMapping("/knowledge-faqs")
    @SaCheckPermission("callcenter:ai-knowledge:create")
    public R<Long> createFaq(@RequestParam Long knowledgeBaseId, @Valid @RequestBody AiKnowledgeFaqRequest request) {
        return R.ok(service.createFaq(knowledgeBaseId, request));
    }

    @PutMapping("/knowledge-faqs/{id}")
    @SaCheckPermission("callcenter:ai-knowledge:update")
    public R<Void> updateFaq(@PathVariable Long id, @Valid @RequestBody AiKnowledgeFaqRequest request) {
        service.updateFaq(id, request);
        return R.ok();
    }

    @DeleteMapping("/knowledge-faqs/{id}")
    @SaCheckPermission("callcenter:ai-knowledge:delete")
    public R<Void> deleteFaq(@PathVariable Long id) {
        service.deleteFaq(id);
        return R.ok();
    }

    @PostMapping("/knowledge-faqs/{id}/{action:enable|disable}")
    @SaCheckPermission("callcenter:ai-knowledge:update")
    public R<Void> setFaqEnabled(@PathVariable Long id, @PathVariable String action) {
        service.setFaqEnabled(id, "enable".equals(action));
        return R.ok();
    }

    @GetMapping("/knowledge-tasks")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public R<List<AiKnowledgeTaskResponse>> tasks(@RequestParam(required = false) Long knowledgeBaseId) {
        return R.ok(service.tasks(knowledgeBaseId));
    }

    @PostMapping("/knowledge-tasks/{id}/retry")
    @SaCheckPermission("callcenter:ai-knowledge:update")
    public R<Void> retryTask(@PathVariable Long id) {
        service.retryTask(id);
        return R.ok();
    }

    @GetMapping("/faq-candidates/template")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public ResponseEntity<byte[]> faqTemplate() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=faq-import-template.xlsx")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(candidateService.template());
    }

    @PostMapping(value = "/faq-candidates/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission("callcenter:ai-knowledge:create")
    public R<Long> importFaq(@RequestParam Long knowledgeBaseId, @RequestPart MultipartFile file) {
        return R.ok(candidateService.importExcel(knowledgeBaseId, file));
    }

    @PostMapping("/faq-candidates/extract")
    @SaCheckPermission("callcenter:ai-knowledge:create")
    public R<Long> extractFaq(@RequestParam Long knowledgeBaseId,
                              @Valid @RequestBody org.dromara.ai.domain.request.AiFaqExtractionRequest request) {
        return R.ok(candidateService.extractDocument(knowledgeBaseId, request));
    }

    @GetMapping("/faq-candidate-batches")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public R<List<AiFaqCandidateBatchResponse>> candidateBatches(@RequestParam Long knowledgeBaseId) {
        return R.ok(candidateService.batches(knowledgeBaseId));
    }

    @GetMapping("/faq-candidates")
    @SaCheckPermission("callcenter:ai-knowledge:list")
    public R<List<AiFaqCandidateResponse>> candidates(@RequestParam Long batchId) {
        return R.ok(candidateService.candidates(batchId));
    }

    @PutMapping("/faq-candidates/{id}")
    @SaCheckPermission("callcenter:ai-knowledge:update")
    public R<Void> updateCandidate(@PathVariable Long id,
                                   @Valid @RequestBody org.dromara.ai.domain.request.AiFaqCandidateUpdateRequest request) {
        candidateService.updateCandidate(id, request);
        return R.ok();
    }

    @PostMapping("/faq-candidate-batches/{id}/confirm")
    @SaCheckPermission("callcenter:ai-knowledge:create")
    public R<Integer> confirmCandidates(@PathVariable Long id,
                                        @RequestBody(required = false) org.dromara.ai.domain.request.AiFaqCandidateConfirmRequest request) {
        return R.ok(candidateService.confirm(id, request));
    }
}
