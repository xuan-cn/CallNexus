package org.dromara.quality.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quality.domain.QualityAppeal;
import org.dromara.quality.domain.response.QualityCalibrationResponse;
import org.dromara.quality.domain.request.QualityAppealPageQuery;

import java.util.Collection;
import java.util.List;

public interface QualityAppealMapper extends BaseMapperPlus<QualityAppeal, QualityAppeal> {
    @Select({
        "<script>",
        "SELECT appeal.* FROM cc_quality_appeal appeal",
        "JOIN cc_quality_task task ON task.tenant_id = appeal.tenant_id AND task.id = appeal.task_id AND task.deleted = 0",
        "WHERE appeal.tenant_id = #{tenantId} AND appeal.deleted = 0",
        "<if test='query.status != null and query.status != \"\"'>AND appeal.status = #{query.status}</if>",
        "<if test='query.appellantId != null'>AND appeal.appellant_id = #{query.appellantId}</if>",
        "<if test='query.appealedAtFrom != null'>AND appeal.appealed_at &gt;= #{query.appealedAtFrom}</if>",
        "<if test='query.appealedAtTo != null'>AND appeal.appealed_at &lt;= #{query.appealedAtTo}</if>",
        "<if test='query.keyword != null and query.keyword != \"\"'>",
        " AND (appeal.appellant_name LIKE CONCAT('%', #{query.keyword}, '%')",
        " OR appeal.appeal_reason LIKE CONCAT('%', #{query.keyword}, '%'))",
        "</if>",
        "<if test='restricted'>",
        " AND (",
        "<if test='hasAgentScope'>task.agent_id IN",
        "<foreach collection='agentIds' item='agentId' open='(' separator=',' close=')'>#{agentId}</foreach>",
        "</if>",
        "<if test='hasAgentScope and hasQueueScope'> OR </if>",
        "<if test='hasQueueScope'>(task.agent_id IS NULL AND task.queue_id IN",
        "<foreach collection='queueIds' item='queueId' open='(' separator=',' close=')'>#{queueId}</foreach>)",
        "</if>",
        ")",
        "</if>",
        "ORDER BY appeal.status, appeal.appealed_at DESC",
        "</script>"
    })
    Page<QualityAppeal> selectScopedPage(@Param("page") Page<QualityAppeal> page,
                                         @Param("tenantId") String tenantId,
                                         @Param("query") QualityAppealPageQuery query,
                                         @Param("restricted") boolean restricted,
                                         @Param("hasAgentScope") boolean hasAgentScope,
                                         @Param("hasQueueScope") boolean hasQueueScope,
                                         @Param("agentIds") Collection<Long> agentIds,
                                         @Param("queueIds") Collection<Long> queueIds);

    @Select({
        "<script>",
        """
        SELECT result.create_by AS reviewerId,
               MAX(COALESCE(NULLIF(user.nick_name, ''), user.user_name)) AS reviewerName,
               COUNT(*) AS reviewedCount,
               ROUND(AVG(result.total_score), 2) AS averageScore,
               SUM(CASE WHEN EXISTS (
                   SELECT 1 FROM cc_quality_appeal appeal
                   WHERE appeal.tenant_id = task.tenant_id AND appeal.task_id = task.id AND appeal.deleted = 0
               ) THEN 1 ELSE 0 END) AS appealedCount,
               SUM(CASE WHEN EXISTS (
                   SELECT 1 FROM cc_quality_appeal appeal
                   WHERE appeal.tenant_id = task.tenant_id AND appeal.task_id = task.id AND appeal.deleted = 0
                     AND appeal.status = 'ACCEPTED' AND appeal.review_result_id IS NOT NULL
               ) THEN 1 ELSE 0 END) AS adjustedCount,
               ROUND(100 * SUM(CASE WHEN EXISTS (
                   SELECT 1 FROM cc_quality_appeal appeal
                   WHERE appeal.tenant_id = task.tenant_id AND appeal.task_id = task.id AND appeal.deleted = 0
                     AND appeal.status = 'ACCEPTED' AND appeal.review_result_id IS NOT NULL
               ) THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 2) AS adjustmentRate
        FROM cc_quality_task task
        JOIN cc_quality_result result ON result.tenant_id = task.tenant_id AND result.task_id = task.id
             AND result.source = 'MANUAL' AND result.deleted = 0
             AND result.result_version = (SELECT MIN(first_result.result_version) FROM cc_quality_result first_result
                 WHERE first_result.tenant_id = task.tenant_id AND first_result.task_id = task.id
                   AND first_result.source = 'MANUAL' AND first_result.deleted = 0)
        LEFT JOIN sys_user user ON user.tenant_id = task.tenant_id AND user.user_id = result.create_by AND user.del_flag = '0'
        WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND result.create_by IS NOT NULL
          AND task.published_at IS NOT NULL
        """,
        "<if test='restricted'>",
        " AND (",
        "<if test='hasAgentScope'>task.agent_id IN",
        "<foreach collection='agentIds' item='agentId' open='(' separator=',' close=')'>#{agentId}</foreach>",
        "</if>",
        "<if test='hasAgentScope and hasQueueScope'> OR </if>",
        "<if test='hasQueueScope'>(task.agent_id IS NULL AND task.queue_id IN",
        "<foreach collection='queueIds' item='queueId' open='(' separator=',' close=')'>#{queueId}</foreach>)",
        "</if>",
        ")",
        "</if>",
        """
        GROUP BY result.create_by
        ORDER BY adjustmentRate DESC, reviewedCount DESC
        """,
        "</script>"
    })
    List<QualityCalibrationResponse> selectCalibration(@Param("tenantId") String tenantId,
                                                       @Param("restricted") boolean restricted,
                                                       @Param("hasAgentScope") boolean hasAgentScope,
                                                       @Param("hasQueueScope") boolean hasQueueScope,
                                                       @Param("agentIds") Collection<Long> agentIds,
                                                       @Param("queueIds") Collection<Long> queueIds);
}
