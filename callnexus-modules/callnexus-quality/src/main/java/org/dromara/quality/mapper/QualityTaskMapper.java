package org.dromara.quality.mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.annotation.DataColumn;
import org.dromara.common.mybatis.annotation.DataPermission;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quality.domain.QualityTask;
import org.dromara.quality.domain.response.QualityUserOptionResponse;
import java.util.Collection;
import java.util.List;
public interface QualityTaskMapper extends BaseMapperPlus<QualityTask, QualityTask> {
    @DataPermission({
        @DataColumn(key = "deptName", value = "u.dept_id"),
        @DataColumn(key = "userName", value = "a.user_id")
    })
    @Select("""
        SELECT a.id
        FROM cc_agent a
        JOIN sys_user u ON u.tenant_id = a.tenant_id AND u.user_id = a.user_id AND u.del_flag = '0'
        WHERE a.tenant_id = #{tenantId} AND a.deleted = 0 AND a.enabled = 1
        ORDER BY a.id
        """)
    List<Long> selectScopedAgentIds(@Param("tenantId") String tenantId);

    @Select({
        "<script>",
        "SELECT DISTINCT q.id FROM cc_call_queue q",
        "JOIN cc_skill_group_member member ON member.tenant_id = q.tenant_id",
        " AND member.skill_group_id = q.skill_group_id AND member.deleted = 0",
        "WHERE q.tenant_id = #{tenantId} AND q.deleted = 0 AND q.enabled = 1",
        " AND member.agent_id IN",
        "<foreach collection='agentIds' item='agentId' open='(' separator=',' close=')'>#{agentId}</foreach>",
        "</script>"
    })
    List<Long> selectQueueIdsByAgentIds(@Param("tenantId") String tenantId, @Param("agentIds") Collection<Long> agentIds);

    @Select("""
        SELECT DISTINCT u.user_id AS id, COALESCE(NULLIF(u.nick_name, ''), u.user_name) AS name
        FROM sys_user u
        JOIN sys_user_role ur ON ur.user_id = u.user_id
        JOIN sys_role r ON r.role_id = ur.role_id AND r.del_flag = '0'
        WHERE u.tenant_id = #{tenantId} AND u.del_flag = '0' AND u.status = '0'
          AND r.role_key IN ('admin', 'cc_supervisor', 'cc_qa')
        ORDER BY name
        """)
    List<QualityUserOptionResponse> selectReviewerOptions(@Param("tenantId") String tenantId);

    @Select("""
        SELECT COALESCE(NULLIF(nick_name, ''), user_name)
        FROM sys_user WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND del_flag = '0' LIMIT 1
        """)
    String selectUserName(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    @Select("""
        SELECT COALESCE(NULLIF(u.nick_name, ''), NULLIF(u.user_name, ''), NULLIF(a.agent_name, ''), a.agent_code)
        FROM cc_agent a LEFT JOIN sys_user u ON u.tenant_id = a.tenant_id AND u.user_id = a.user_id AND u.del_flag = '0'
        WHERE a.tenant_id = #{tenantId} AND a.id = #{agentId} AND a.deleted = 0 LIMIT 1
        """)
    String selectAgentName(@Param("tenantId") String tenantId, @Param("agentId") Long agentId);

    @Select("""
        SELECT id FROM cc_agent
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND deleted = 0 AND enabled = 1
        ORDER BY id LIMIT 1
        """)
    Long selectAgentIdByUserId(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
