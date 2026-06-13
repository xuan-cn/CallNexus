package org.dromara.resource.media.service;

import jakarta.servlet.http.HttpServletResponse;
import org.dromara.resource.media.domain.request.AgentResultRequest;
import org.dromara.resource.media.domain.response.AgentTaskResponse;
import org.dromara.resource.media.domain.response.MediaSyncResponse;
import org.dromara.resource.media.domain.response.MediaVersionResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface MediaPublicationService {
    Long uploadVersion(Long mediaId, Long durationMs, MultipartFile file);
    List<MediaVersionResponse> versions(Long mediaId);
    List<Long> publishedGroupIds(Long mediaId);
    void publish(Long mediaId, List<Long> nodeGroupIds);
    void unpublish(Long mediaId);
    List<MediaSyncResponse> syncs(Long mediaId);
    void retryFailed(Long mediaId);
    void syncNewGroupMembers(Long groupId, List<Long> nodeIds);
    void heartbeat(String nodeCode, String token, String agentVersion);
    AgentTaskResponse claim(String nodeCode, String token);
    void downloadSource(Long taskId, String nodeCode, String token, String leaseToken, HttpServletResponse response) throws IOException;
    void report(Long taskId, String nodeCode, String token, AgentResultRequest request);
}
