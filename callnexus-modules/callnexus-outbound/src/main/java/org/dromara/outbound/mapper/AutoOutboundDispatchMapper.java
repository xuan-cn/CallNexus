package org.dromara.outbound.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.outbound.domain.AutoOutboundDispatch;

import java.time.LocalDateTime;
import java.util.List;

public interface AutoOutboundDispatchMapper extends BaseMapperPlus<AutoOutboundDispatch, AutoOutboundDispatch> {

    @Select("""
        SELECT DISTINCT tenant_id
        FROM cc_auto_outbound_dispatch
        WHERE deleted = 0 AND status = 'READY'
        ORDER BY tenant_id
        """)
    List<String> listReadyTenantIds();

    @Update("""
        UPDATE cc_auto_outbound_dispatch
        SET status = 'PROCESSING', lease_owner = #{owner}, lease_expires_at = #{leaseUntil},
            started_at = COALESCE(started_at, #{now}), update_time = #{now}
        WHERE id = #{dispatchId} AND tenant_id = #{tenantId} AND deleted = 0 AND status = 'READY'
        """)
    int claimReady(@Param("dispatchId") Long dispatchId, @Param("tenantId") String tenantId,
                   @Param("owner") String owner, @Param("now") LocalDateTime now,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
        SELECT COUNT(*)
        FROM cc_auto_outbound_dispatch
        WHERE tenant_id = #{tenantId} AND deleted = 0 AND status IN ('READY', 'PROCESSING')
        """)
    long countTenantActive(@Param("tenantId") String tenantId);

    @Select("""
        SELECT COUNT(*)
        FROM cc_auto_outbound_dispatch d
        JOIN cc_outbound_task t ON t.id = d.task_id AND t.tenant_id = d.tenant_id AND t.deleted = 0
        WHERE d.tenant_id = #{tenantId} AND d.deleted = 0 AND d.status IN ('READY', 'PROCESSING')
          AND t.caller_number_id = #{callerNumberId}
        """)
    long countCallerActive(@Param("tenantId") String tenantId, @Param("callerNumberId") Long callerNumberId);

    @Select("""
        SELECT COUNT(*)
        FROM cc_auto_outbound_dispatch d
        JOIN cc_outbound_task t ON t.id = d.task_id AND t.tenant_id = d.tenant_id AND t.deleted = 0
        WHERE d.tenant_id = #{tenantId} AND d.deleted = 0 AND d.status IN ('READY', 'PROCESSING')
          AND t.node_id = #{nodeId}
        """)
    long countNodeActive(@Param("tenantId") String tenantId, @Param("nodeId") Long nodeId);

    @Update("""
        UPDATE cc_auto_outbound_dispatch
        SET status = 'READY', lease_owner = NULL, lease_expires_at = NULL,
            failure_reason = '消费租约超时，已恢复待拨', update_time = #{now}
        WHERE tenant_id = #{tenantId} AND deleted = 0 AND status = 'PROCESSING'
          AND lease_expires_at IS NOT NULL AND lease_expires_at < #{now}
        """)
    int recoverExpired(@Param("tenantId") String tenantId, @Param("now") LocalDateTime now);
}
