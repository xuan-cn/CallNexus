package org.dromara.outbound.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.outbound.domain.AutoOutboundDispatch;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.mapper.AutoOutboundDispatchMapper;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AutoOutboundDispatchClaimService {

    private final OutboundMemberMapper memberMapper;
    private final AutoOutboundDispatchMapper dispatchMapper;

    @Transactional(rollbackFor = Exception.class)
    public boolean claim(OutboundTask task, OutboundMember member, LocalDateTime now, int leaseMinutes) {
        int attemptNo = (member.getAttemptCount() == null ? 0 : member.getAttemptCount()) + 1;
        int executionRound = task.getExecutionRound() == null ? 1 : task.getExecutionRound();
        String dispatchKey = task.getTenantId() + ":" + task.getId() + ":" + member.getId()
            + ":r" + executionRound + ":" + attemptNo;
        int updated = memberMapper.schedule(
            member.getId(), task.getId(), task.getTenantId(), dispatchKey, now, now.plusMinutes(leaseMinutes));
        if (updated == 0) {
            return false;
        }
        AutoOutboundDispatch dispatch = new AutoOutboundDispatch();
        dispatch.setTaskId(task.getId());
        dispatch.setMemberId(member.getId());
        dispatch.setDispatchKey(dispatchKey);
        dispatch.setAttemptNo(attemptNo);
        dispatch.setPreviousMemberStatus(member.getStatus());
        dispatch.setStatus("READY");
        dispatch.setScheduledAt(now);
        dispatchMapper.insert(dispatch);
        return true;
    }
}
