package org.dromara.agent.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.agent.domain.Agent;
import org.dromara.common.mybatis.annotation.DataColumn;
import org.dromara.common.mybatis.annotation.DataPermission;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

public interface AgentMapper extends BaseMapperPlus<Agent, Agent> {

    @DataPermission({
        @DataColumn(key = "deptName", value = "u.dept_id"),
        @DataColumn(key = "userName", value = "a.user_id")
    })
    @Select("""
        SELECT a.id
        FROM cc_agent a
        JOIN sys_user u ON u.tenant_id = a.tenant_id
            AND u.user_id = a.user_id
            AND u.del_flag = '0'
        WHERE a.deleted = 0 AND a.enabled = 1
        ORDER BY a.id
        """)
    List<Long> selectDataScopeAgentIds();
}
