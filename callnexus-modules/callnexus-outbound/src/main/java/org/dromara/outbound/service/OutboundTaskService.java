package org.dromara.outbound.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.outbound.domain.request.CompleteOutboundMemberRequest;
import org.dromara.outbound.domain.request.OutboundAttemptPageQuery;
import org.dromara.outbound.domain.request.OutboundTaskRequest;
import org.dromara.outbound.domain.response.OutboundMemberResponse;
import org.dromara.outbound.domain.response.OutboundAttemptResponse;
import org.dromara.outbound.domain.response.OutboundTaskStatisticsResponse;
import org.dromara.outbound.domain.response.OutboundTaskResponse;
import org.dromara.outbound.domain.response.AddOutboundMembersResponse;

import java.util.List;

public interface OutboundTaskService {
    List<OutboundTaskResponse> list();
    OutboundTaskResponse get(Long id);
    Long create(OutboundTaskRequest request);
    void update(Long id, OutboundTaskRequest request);
    void delete(Long id);
    void start(Long id);
    void pause(Long id);
    AddOutboundMembersResponse addCustomers(Long id, List<Long> customerIds);
    List<OutboundMemberResponse> listMembers(Long taskId);
    List<OutboundAttemptResponse> listAttempts(Long memberId);
    TableDataInfo<OutboundAttemptResponse> pageAttempts(OutboundAttemptPageQuery query, PageQuery pageQuery);
    OutboundTaskStatisticsResponse statistics(Long taskId);
    int recoverExpired(Long taskId);
    OutboundMemberResponse claimNext(Long taskId);
    OutboundMemberResponse currentAssigned();
    OutboundMemberResponse renewLease(Long memberId);
    OutboundMemberResponse dial(Long memberId);
    void complete(Long memberId, CompleteOutboundMemberRequest request);
}
