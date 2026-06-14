package org.dromara.agent.mapper;

import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface SkillGroupMemberMapper extends BaseMapperPlus<SkillGroupMember, SkillGroupMember> {
    @Delete("DELETE FROM cc_skill_group_member WHERE tenant_id = #{tenantId} AND skill_group_id = #{skillGroupId}")
    int deletePhysicallyByGroupId(@Param("tenantId") String tenantId, @Param("skillGroupId") Long skillGroupId);
}
