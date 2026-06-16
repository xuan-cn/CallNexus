package org.dromara.resource.voicemail.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.voicemail.domain.request.VoiceMailBoxPageQuery;
import org.dromara.resource.voicemail.domain.request.VoiceMailBoxRequest;
import org.dromara.resource.voicemail.domain.response.VoiceMailBoxResponse;

public interface VoiceMailBoxApplicationService {
    TableDataInfo<VoiceMailBoxResponse> page(VoiceMailBoxPageQuery query, PageQuery pageQuery);

    VoiceMailBoxResponse get(Long id);

    Long create(VoiceMailBoxRequest request);

    void update(Long id, VoiceMailBoxRequest request);

    void delete(Long id);
}
