package org.dromara.ai.domain.response;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Date;
@Data
public class AiFaqCandidateBatchResponse {
    private Long id; private Long knowledgeBaseId; private String sourceType; private Long documentId;
    private Long chatModelId; private String sourceFileName; private String status;
    private Integer totalCount; private Integer validCount; private Integer invalidCount; private Integer confirmedCount;
    private String failureReason; private Date createTime; private LocalDateTime finishedAt;
}
