package org.dromara.outbound.service;

import org.dromara.outbound.domain.request.CompleteOutboundMemberRequest;
import org.dromara.outbound.domain.request.OutboundTaskRequest;
import org.dromara.outbound.domain.response.OutboundMemberResponse;
import org.dromara.outbound.domain.response.OutboundTaskStatisticsResponse;
import org.dromara.outbound.domain.response.OutboundTaskResponse;

import java.util.List;

public interface OutboundTaskService {
    List<OutboundTaskResponse> list();
    OutboundTaskResponse get(Long id);
    Long create(OutboundTaskRequest request);
    void update(Long id, OutboundTaskRequest request);
    void delete(Long id);
    void start(Long id);
    void pause(Long id);
    void addCustomers(Long id, List<Long> customerIds);
    List<OutboundMemberResponse> listMembers(Long taskId);
    OutboundTaskStatisticsResponse statistics(Long taskId);
    int recoverExpired(Long taskId);
    OutboundMemberResponse claimNext(Long taskId);
    OutboundMemberResponse renewLease(Long memberId);
    OutboundMemberResponse dial(Long memberId);
    void complete(Long memberId, CompleteOutboundMemberRequest request);
}
