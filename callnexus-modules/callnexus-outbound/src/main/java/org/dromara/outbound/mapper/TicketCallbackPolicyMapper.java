package org.dromara.outbound.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

// All ticket, policy and contact queries explicitly constrain the tenant.
@InterceptorIgnore(tenantLine = "true")
public interface TicketCallbackPolicyMapper {
    @Select("""
        SELECT mode, task_id AS taskId, delay_minutes AS delayMinutes, expiry_minutes AS expiryMinutes,
               revision, enabled_since AS enabledSince, allow_inbound_caller AS allowInboundCaller
        FROM cc_ticket_callback_policy WHERE tenant_id = #{tenant} AND template_id = #{templateId}
        """)
    Policy policy(@Param("tenant") String tenant, @Param("templateId") Long templateId);

    @Insert("""
        INSERT INTO cc_ticket_callback_policy
            (tenant_id, template_id, mode, task_id, delay_minutes, expiry_minutes, revision, enabled_since, allow_inbound_caller)
        VALUES (#{tenant}, #{templateId}, #{p.mode}, #{p.taskId}, #{p.delayMinutes}, #{p.expiryMinutes},
                #{p.revision}, #{p.enabledSince}, #{p.allowInboundCaller}) AS new_policy
        ON DUPLICATE KEY UPDATE mode = new_policy.mode, task_id = new_policy.task_id,
            delay_minutes = new_policy.delay_minutes, expiry_minutes = new_policy.expiry_minutes,
            revision = new_policy.revision, enabled_since = new_policy.enabled_since,
            allow_inbound_caller = new_policy.allow_inbound_caller
        """)
    void savePolicy(@Param("tenant") String tenant, @Param("templateId") Long templateId, @Param("p") Policy policy);

    @Select("""
        SELECT phone, revision FROM cc_ticket_callback_contact
        WHERE tenant_id = #{tenant} AND ticket_id = #{ticketId}
        """)
    Contact contact(@Param("tenant") String tenant, @Param("ticketId") Long ticketId);

    @Insert("""
        INSERT INTO cc_ticket_callback_contact (tenant_id, ticket_id, phone, revision)
        VALUES (#{tenant}, #{ticketId}, #{c.phone}, #{c.revision}) AS new_contact
        ON DUPLICATE KEY UPDATE phone = new_contact.phone, revision = new_contact.revision
        """)
    void saveContact(@Param("tenant") String tenant, @Param("ticketId") Long ticketId, @Param("c") Contact contact);

    @Delete("DELETE FROM cc_ticket_callback_contact WHERE tenant_id = #{tenant} AND ticket_id = #{ticketId}")
    void revokeContact(@Param("tenant") String tenant, @Param("ticketId") Long ticketId);

    @Select("SELECT DISTINCT tenant_id FROM cc_ticket_callback_policy WHERE mode = 'AUTO'")
    List<String> automaticTenants();

    @Select("""
        SELECT t.id FROM cc_ticket_callback_policy p
        JOIN cc_ticket t ON t.tenant_id = p.tenant_id AND t.template_id = p.template_id
        JOIN cc_ticket_callback_contact c ON c.tenant_id = t.tenant_id AND c.ticket_id = t.id
        JOIN cc_form_template f ON f.tenant_id = p.tenant_id AND f.id = p.template_id
        JOIN cc_outbound_task a ON a.tenant_id = p.tenant_id AND a.id = p.task_id
        WHERE p.tenant_id = #{tenant} AND p.mode = 'AUTO'
          AND f.deleted = 0 AND f.enabled = 1 AND f.business_type = 'TICKET'
          AND a.deleted = 0 AND a.task_type = 'AUTO' AND a.dial_mode = 'AGENTLESS_AI'
          AND a.target_id IS NOT NULL AND a.status IN ('DRAFT', 'RUNNING', 'COMPLETED')
          AND t.deleted = 0 AND t.ticket_status IN ('RESOLVED', 'CLOSED')
          AND t.resolved_at > p.enabled_since
          AND t.resolved_at <= TIMESTAMPADD(MINUTE, -p.delay_minutes, #{now})
          AND t.resolved_at > TIMESTAMPADD(MINUTE, -p.expiry_minutes, #{now})
          AND NOT EXISTS (
              SELECT 1 FROM cc_outbound_member m
              WHERE m.tenant_id = t.tenant_id AND m.source_type = 'TICKET_CALLBACK'
                AND m.source_id = t.id AND m.deleted = 0
                AND (m.callback_resolved_at = t.resolved_at OR m.status IN ('PENDING','RETRY','SCHEDULED','CLAIMED','DIALING'))
          )
        ORDER BY t.resolved_at, t.id
        LIMIT 100
        """)
    List<Long> dueTickets(@Param("tenant") String tenant, @Param("now") LocalDateTime now);

    @Select("""
        SELECT COUNT(*) FROM cc_ticket_callback_policy
        WHERE tenant_id = #{tenant} AND task_id = #{taskId} AND mode = 'AUTO'
        """)
    long countAutomaticPolicies(@Param("tenant") String tenant, @Param("taskId") Long taskId);

    record Policy(String mode, Long taskId, int delayMinutes, int expiryMinutes, String revision, LocalDateTime enabledSince,
                  boolean allowInboundCaller) {}
    record Contact(String phone, String revision) {}
}
