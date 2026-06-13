package org.dromara.resource.media.domain.response;
import lombok.Data;
import java.util.Date;
@Data
public class MediaVersionResponse {
    private Long id;
    private Integer versionNo;
    private String originalFileName;
    private Long fileSize;
    private Long durationMs;
    private String status;
    private Date createTime;
}
