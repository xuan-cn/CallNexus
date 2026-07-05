package org.dromara.ai.service;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
public interface AiFaqCandidateApplicationService {
    byte[] template();
    Long importExcel(Long knowledgeBaseId, MultipartFile file);
    Long extractDocument(Long knowledgeBaseId, AiFaqExtractionRequest request);
    List<AiFaqCandidateBatchResponse> batches(Long knowledgeBaseId);
    List<AiFaqCandidateResponse> candidates(Long batchId);
    void updateCandidate(Long id, AiFaqCandidateUpdateRequest request);
    int confirm(Long batchId, AiFaqCandidateConfirmRequest request);
}
