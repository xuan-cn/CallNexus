package org.dromara.outbound.service;

import org.dromara.outbound.domain.request.AutoOutboundTaskRequest;
import org.dromara.outbound.domain.request.AutoOutboundSourceRequest;
import org.dromara.outbound.domain.response.AutoOutboundMaterializeResponse;
import org.dromara.outbound.domain.response.AutoOutboundMemberResponse;
import org.dromara.outbound.domain.response.AutoOutboundSourceResponse;
import org.dromara.outbound.domain.response.AutoOutboundTaskResponse;
import org.dromara.outbound.domain.response.AutoOutboundMonitorResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

import java.util.List;

public interface AutoOutboundTaskService {
    List<AutoOutboundTaskResponse> list();
    AutoOutboundTaskResponse get(Long id);
    Long create(AutoOutboundTaskRequest request);
    void update(Long id, AutoOutboundTaskRequest request);
    void delete(Long id);
    void start(Long id);
    void pause(Long id);
    void resume(Long id);
    void stop(Long id);
    List<AutoOutboundSourceResponse> listSources(Long taskId);
    Long addSource(Long taskId, AutoOutboundSourceRequest request);
    void deleteSource(Long taskId, Long sourceId);
    AutoOutboundMaterializeResponse materialize(Long taskId);
    TableDataInfo<AutoOutboundMemberResponse> pageMembers(Long taskId, String status, String phoneNumber, PageQuery pageQuery);
    AutoOutboundMonitorResponse monitor(Long taskId);
}
