package org.dromara.resource.media.domain.response;
import lombok.Data;
@Data
public class AgentTaskResponse {
    private Long taskId;
    private String leaseToken;
    private Long mediaId;
    private Long versionId;
    private Integer versionNo;
    private String category;
    private String targetPath;
    private String contentType;
    private String originalFileName;
}
