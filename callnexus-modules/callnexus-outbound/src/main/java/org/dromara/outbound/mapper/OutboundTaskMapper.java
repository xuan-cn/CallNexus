package org.dromara.outbound.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.outbound.domain.OutboundTask;

import java.time.LocalDateTime;

public interface OutboundTaskMapper extends BaseMapperPlus<OutboundTask, OutboundTask> {

    @Update("""
        UPDATE cc_outbound_task
        SET scheduler_owner = #{owner}, scheduler_lease_until = #{leaseUntil},
            scheduler_heartbeat_at = #{now}, update_time = #{now}
        WHERE id = #{taskId} AND tenant_id = #{tenantId} AND deleted = 0
          AND task_type = 'AUTO' AND status = 'RUNNING'
          AND (scheduler_lease_until IS NULL OR scheduler_lease_until < #{now} OR scheduler_owner = #{owner})
        """)
    int acquireSchedulerLease(
        @Param("taskId") Long taskId,
        @Param("tenantId") String tenantId,
        @Param("owner") String owner,
        @Param("now") LocalDateTime now,
        @Param("leaseUntil") LocalDateTime leaseUntil
    );
}
