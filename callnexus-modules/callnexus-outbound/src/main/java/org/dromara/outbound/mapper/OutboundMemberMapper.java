package org.dromara.outbound.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.outbound.domain.OutboundMember;

import java.time.LocalDateTime;

public interface OutboundMemberMapper extends BaseMapperPlus<OutboundMember, OutboundMember> {

    @Update("""
        UPDATE cc_outbound_member m
        JOIN cc_outbound_task t ON t.id = m.task_id AND t.tenant_id = m.tenant_id AND t.deleted = 0
        SET m.status = 'SCHEDULED', m.schedule_key = #{scheduleKey}, m.scheduled_at = #{now},
            m.lease_expires_at = #{leaseUntil}, m.update_time = #{now}
        WHERE m.id = #{memberId} AND m.task_id = #{taskId} AND m.tenant_id = #{tenantId}
          AND m.deleted = 0 AND t.task_type = 'AUTO' AND t.status = 'RUNNING'
          AND (m.status = 'PENDING' OR (m.status = 'RETRY'
              AND (m.next_follow_up_at IS NULL OR m.next_follow_up_at <= #{now})))
        """)
    int schedule(
        @Param("memberId") Long memberId,
        @Param("taskId") Long taskId,
        @Param("tenantId") String tenantId,
        @Param("scheduleKey") String scheduleKey,
        @Param("now") LocalDateTime now,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );
}
