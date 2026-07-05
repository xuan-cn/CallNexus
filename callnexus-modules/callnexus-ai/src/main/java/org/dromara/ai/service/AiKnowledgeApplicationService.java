package org.dromara.ai.service;

import org.dromara.ai.domain.AiKnowledgeChunk;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface AiKnowledgeApplicationService {
    List<AiKnowledgeBaseResponse> knowledgeBases();
    TableDataInfo<AiKnowledgeBaseResponse> knowledgeBasePage(PageQuery pageQuery);
    AiKnowledgeBaseResponse knowledgeBase(Long id);
    Long createKnowledgeBase(AiKnowledgeBaseRequest request);
    void updateKnowledgeBase(Long id, AiKnowledgeBaseRequest request);
    void deleteKnowledgeBase(Long id);
    void setKnowledgeBaseEnabled(Long id, boolean enabled);
    void rebuildKnowledgeBase(Long id, Long embeddingModelId);
    List<org.dromara.ai.domain.AiKnowledgeDocumentVersion> documentVersions(Long documentId);
    List<AiKnowledgeDocumentResponse> documents(Long knowledgeBaseId);
    TableDataInfo<AiKnowledgeDocumentResponse> documentPage(Long knowledgeBaseId, PageQuery pageQuery);
    Long uploadDocument(Long knowledgeBaseId, Long documentId, MultipartFile file);
    void deleteDocument(Long id);
    List<AiKnowledgeChunk> chunks(Long documentId);
    List<AiKnowledgeFaqResponse> faqs(Long knowledgeBaseId);
    TableDataInfo<AiKnowledgeFaqResponse> faqPage(Long knowledgeBaseId, PageQuery pageQuery);
    List<org.dromara.ai.domain.AiKnowledgeFaqVersion> faqVersions(Long faqId);
    Long createFaq(Long knowledgeBaseId, AiKnowledgeFaqRequest request);
    void updateFaq(Long id, AiKnowledgeFaqRequest request);
    void deleteFaq(Long id);
    void setFaqEnabled(Long id, boolean enabled);
    List<AiKnowledgeTaskResponse> tasks(Long knowledgeBaseId);
    void retryTask(Long taskId);
    List<AiKnowledgeSearchHitResponse> search(Long knowledgeBaseId, AiKnowledgeSearchRequest request);
}
