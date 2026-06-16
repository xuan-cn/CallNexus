package org.dromara.call.service;

import org.dromara.call.domain.request.VoiceMailHandleRequest;
import org.dromara.call.domain.request.VoiceMailMessagePageQuery;
import org.dromara.call.domain.response.VoiceMailMessageResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.multipart.MultipartFile;

public interface VoiceMailMessageApplicationService {
    TableDataInfo<VoiceMailMessageResponse> page(VoiceMailMessagePageQuery query, PageQuery pageQuery);

    VoiceMailMessageResponse get(Long id);

    void handle(Long id, VoiceMailHandleRequest request);

    void upload(String tenantId, Long voicemailBoxId, String businessCallId, String callerNumber,
                String calledNumber, Long durationMs, MultipartFile file);
}
