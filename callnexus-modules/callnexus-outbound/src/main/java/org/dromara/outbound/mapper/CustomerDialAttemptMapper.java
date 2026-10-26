package org.dromara.outbound.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.outbound.domain.CustomerDialAttempt;
import org.dromara.outbound.domain.request.ClickDialCustomerQuery;
import org.dromara.outbound.domain.response.ClickDialCustomerResponse;
import org.dromara.outbound.domain.response.ClickDialSummaryResponse;

@Mapper
public interface CustomerDialAttemptMapper extends BaseMapperPlus<CustomerDialAttempt, CustomerDialAttempt> {

    @Select("""
        <script>
        SELECT a.id AS assignment_id,
               a.customer_id,
               c.customer_name,
               c.primary_phone,
               c.template_id,
               a.customer_type,
               a.source_channel,
               a.tags,
               a.skill_group_id,
               a.remark AS assignment_remark,
               a.create_time AS assigned_at,
               a.dial_status,
               a.attempt_count,
               a.last_called_at,
               (SELECT d.business_call_id FROM cc_customer_dial_attempt d
                WHERE d.assignment_id = a.id AND d.deleted = 0
                ORDER BY d.started_at DESC LIMIT 1) AS last_business_call_id,
               a.last_result_code,
               a.last_result_remark,
               a.next_follow_up_at,
               a.completed_at
        FROM cc_customer_assignment a
        JOIN cc_customer c ON c.id = a.customer_id AND c.deleted = 0
        WHERE a.deleted = 0
          AND a.enabled = 1
          AND a.agent_id = #{agentId}
        <if test="query.keyword != null and query.keyword != ''">
          AND (c.customer_name LIKE CONCAT('%', #{query.keyword}, '%')
               OR c.primary_phone LIKE CONCAT('%', #{query.keyword}, '%')
               OR EXISTS (SELECT 1 FROM cc_customer_phone p
                          WHERE p.customer_id = c.id AND p.enabled = 1
                            AND p.normalized_phone LIKE CONCAT('%', #{query.keyword}, '%')))
        </if>
        <if test="query.customerType != null and query.customerType != ''">
          AND a.customer_type = #{query.customerType}
        </if>
        <if test="query.sourceChannel != null and query.sourceChannel != ''">
          AND a.source_channel = #{query.sourceChannel}
        </if>
        <if test="query.tags != null and query.tags != ''">
          AND a.tags LIKE CONCAT('%', #{query.tags}, '%')
        </if>
        <if test="query.skillGroupId != null">
          AND a.skill_group_id = #{query.skillGroupId}
        </if>
        <if test="query.resultCode != null and query.resultCode != ''">
          AND a.last_result_code = #{query.resultCode}
        </if>
        <if test="query.lastCalledStart != null">
          AND a.last_called_at >= #{query.lastCalledStart}
        </if>
        <if test="query.lastCalledEnd != null">
          AND NOT (a.last_called_at > #{query.lastCalledEnd})
        </if>
        <if test="query.followUpStart != null">
          AND a.next_follow_up_at >= #{query.followUpStart}
        </if>
        <if test="query.followUpEnd != null">
          AND NOT (a.next_follow_up_at > #{query.followUpEnd})
        </if>
        <choose>
          <when test="query.workFilter == 'UNCALLED'">AND a.attempt_count = 0</when>
          <when test="query.workFilter == 'DIALED'">AND a.attempt_count > 0</when>
          <when test="query.workFilter == 'PROCESSING'">AND a.dial_status = 'PROCESSING'</when>
          <when test="query.workFilter == 'FOLLOW_UP'">AND a.dial_status = 'FOLLOW_UP'</when>
          <when test="query.workFilter == 'DUE_FOLLOW_UP'">AND a.dial_status = 'FOLLOW_UP' AND NOT (a.next_follow_up_at > NOW())</when>
          <when test="query.workFilter == 'COMPLETED'">AND a.dial_status = 'COMPLETED'</when>
        </choose>
        ORDER BY CASE WHEN a.dial_status = 'FOLLOW_UP' AND NOT (a.next_follow_up_at > NOW()) THEN 0 ELSE 1 END,
                 a.next_follow_up_at ASC,
                 a.create_time DESC
        </script>
        """)
    IPage<ClickDialCustomerResponse> selectCustomerPage(Page<ClickDialCustomerResponse> page,
                                                         @Param("agentId") Long agentId,
                                                         @Param("query") ClickDialCustomerQuery query);

    @Select("""
        SELECT COUNT(*) AS total_count,
               COALESCE(SUM(CASE WHEN attempt_count = 0 THEN 1 ELSE 0 END), 0) AS uncalled_count,
               COALESCE(SUM(CASE WHEN dial_status = 'PROCESSING' THEN 1 ELSE 0 END), 0) AS processing_count,
               COALESCE(SUM(CASE WHEN dial_status = 'FOLLOW_UP' THEN 1 ELSE 0 END), 0) AS follow_up_count,
               COALESCE(SUM(CASE WHEN dial_status = 'FOLLOW_UP' AND NOT (next_follow_up_at > NOW()) THEN 1 ELSE 0 END), 0) AS due_follow_up_count,
               COALESCE(SUM(CASE WHEN dial_status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed_count,
               COALESCE(SUM(CASE WHEN attempt_count > 0 THEN 1 ELSE 0 END), 0) AS dialed_count
        FROM cc_customer_assignment
        WHERE deleted = 0 AND enabled = 1 AND agent_id = #{agentId}
        """)
    ClickDialSummaryResponse selectSummary(@Param("agentId") Long agentId);
}
