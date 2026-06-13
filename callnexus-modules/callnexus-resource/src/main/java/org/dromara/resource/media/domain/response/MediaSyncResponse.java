package org.dromara.resource.media.domain.response;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class MediaSyncResponse {
    private Long id;
    private Long publicationId;
    private Long nodeId;
    private String nodeName;
    private Integer versionNo;
    private String status;
    private String targetPath;
    private Integer retryCount;
    private String failureReason;
    private LocalDateTime syncedAt;
}
