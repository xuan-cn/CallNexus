package org.dromara.quality.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.quality.domain.request.QualityReportQuery;
import org.dromara.quality.domain.response.QualityOptionResponse;
import org.dromara.quality.domain.response.QualityAiAdoptionSummaryResponse;
import org.dromara.quality.domain.response.QualityAiAdoptionRankingResponse;
import org.dromara.quality.domain.response.QualityAiModificationResponse;
import org.dromara.quality.domain.response.QualityAppealRankingResponse;
import org.dromara.quality.domain.response.QualityAppealSummaryResponse;
import org.dromara.quality.domain.response.QualityDeductionItemResponse;
import org.dromara.quality.domain.response.QualityReportDetailResponse;
import org.dromara.quality.domain.response.QualityReportRankingResponse;
import org.dromara.quality.domain.response.QualityReportSummaryResponse;
import org.dromara.quality.domain.response.QualityReportTrendResponse;
import org.dromara.quality.service.QualityDataScopeService;

import java.time.LocalDateTime;
import java.util.List;

public interface QualityReportMapper {
    @Select({
        "<script>",
        "SELECT COUNT(DISTINCT cs.id) FROM cc_call_session cs",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = cs.tenant_id AND q.id = cs.handling_queue_id AND q.deleted = 0",
        "WHERE cs.tenant_id = #{tenantId} AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND cs.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND cs.handling_queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'>",
        " AND ((cs.agent_id IS NOT NULL AND cs.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (cs.agent_id IS NULL AND cs.handling_queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)",
        "</if>",
        "</script>"
    })
    long countEligibleCalls(@Param("tenantId") String tenantId,
                            @Param("startAt") LocalDateTime startAt,
                            @Param("endAt") LocalDateTime endAt,
                            @Param("query") QualityReportQuery query,
                            @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT COUNT(DISTINCT qt.call_session_id) reviewedCallCount, COUNT(qr.id) resultCount,",
        " COALESCE(AVG(qr.total_score), 0) averageScore,",
        " COALESCE(SUM(CASE WHEN qr.qualified = 1 THEN 1 ELSE 0 END), 0) qualifiedCount,",
        " COALESCE(SUM(CASE WHEN qr.fatal_flag = 1 THEN 1 ELSE 0 END), 0) fatalCount",
        "FROM cc_quality_task qt",
        "JOIN cc_call_session cs ON cs.tenant_id = qt.tenant_id AND cs.id = qt.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = qt.tenant_id AND q.id = qt.queue_id AND q.deleted = 0",
        "LEFT JOIN cc_quality_result qr ON qr.tenant_id = qt.tenant_id AND qr.task_id = qt.id",
        " AND qr.effective_flag = 1 AND qr.deleted = 0",
        "WHERE qt.tenant_id = #{tenantId} AND qt.deleted = 0",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND qt.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND qt.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'>",
        " AND ((qt.agent_id IS NOT NULL AND qt.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (qt.agent_id IS NULL AND qt.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)",
        "</if>",
        "</script>"
    })
    QualityReportSummaryResponse selectSummary(@Param("tenantId") String tenantId,
                                               @Param("startAt") LocalDateTime startAt,
                                               @Param("endAt") LocalDateTime endAt,
                                               @Param("query") QualityReportQuery query,
                                               @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT DATE_FORMAT(cs.ended_at, '%Y-%m-%d') bucket, COUNT(DISTINCT cs.id) eligibleCallCount",
        "FROM cc_call_session cs",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = cs.tenant_id AND q.id = cs.handling_queue_id AND q.deleted = 0",
        "WHERE cs.tenant_id = #{tenantId} AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND cs.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND cs.handling_queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'>",
        " AND ((cs.agent_id IS NOT NULL AND cs.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (cs.agent_id IS NULL AND cs.handling_queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)",
        "</if>",
        "GROUP BY DATE_FORMAT(cs.ended_at, '%Y-%m-%d') ORDER BY bucket",
        "</script>"
    })
    List<QualityReportTrendResponse> selectEligibleTrend(@Param("tenantId") String tenantId,
                                                         @Param("startAt") LocalDateTime startAt,
                                                         @Param("endAt") LocalDateTime endAt,
                                                         @Param("query") QualityReportQuery query,
                                                         @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT DATE_FORMAT(cs.ended_at, '%Y-%m-%d') bucket,",
        " COUNT(DISTINCT qt.call_session_id) reviewedCallCount, COUNT(qr.id) resultCount,",
        " COALESCE(AVG(qr.total_score), 0) averageScore,",
        " COALESCE(SUM(CASE WHEN qr.qualified = 1 THEN 1 ELSE 0 END), 0) qualifiedCount",
        "FROM cc_quality_task qt",
        "JOIN cc_call_session cs ON cs.tenant_id = qt.tenant_id AND cs.id = qt.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = qt.tenant_id AND q.id = qt.queue_id AND q.deleted = 0",
        "LEFT JOIN cc_quality_result qr ON qr.tenant_id = qt.tenant_id AND qr.task_id = qt.id",
        " AND qr.effective_flag = 1 AND qr.deleted = 0",
        "WHERE qt.tenant_id = #{tenantId} AND qt.deleted = 0",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND qt.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND qt.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'>",
        " AND ((qt.agent_id IS NOT NULL AND qt.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (qt.agent_id IS NULL AND qt.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)",
        "</if>",
        "GROUP BY DATE_FORMAT(cs.ended_at, '%Y-%m-%d') ORDER BY bucket",
        "</script>"
    })
    List<QualityReportTrendResponse> selectResultTrend(@Param("tenantId") String tenantId,
                                                       @Param("startAt") LocalDateTime startAt,
                                                       @Param("endAt") LocalDateTime endAt,
                                                       @Param("query") QualityReportQuery query,
                                                       @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT qt.agent_id dimensionId, COALESCE(NULLIF(qt.agent_name, ''), NULLIF(qt.agent_extension, ''), '未关联坐席') dimensionName,",
        " COUNT(qr.id) resultCount, COALESCE(AVG(qr.total_score), 0) averageScore,",
        " COALESCE(SUM(CASE WHEN qr.qualified = 1 THEN 1 ELSE 0 END), 0) qualifiedCount,",
        " COALESCE(SUM(CASE WHEN qr.fatal_flag = 1 THEN 1 ELSE 0 END), 0) fatalCount",
        "FROM cc_quality_task qt JOIN cc_call_session cs ON cs.tenant_id = qt.tenant_id AND cs.id = qt.call_session_id",
        "JOIN cc_quality_result qr ON qr.tenant_id = qt.tenant_id AND qr.task_id = qt.id AND qr.effective_flag = 1 AND qr.deleted = 0",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = qt.tenant_id AND q.id = qt.queue_id AND q.deleted = 0",
        "WHERE qt.tenant_id = #{tenantId} AND qt.deleted = 0 AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND qt.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND qt.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND qt.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>",
        "GROUP BY qt.agent_id, qt.agent_name, qt.agent_extension ORDER BY averageScore DESC, resultCount DESC LIMIT 20",
        "</script>"
    })
    List<QualityReportRankingResponse> selectAgentRanking(@Param("tenantId") String tenantId,
                                                          @Param("startAt") LocalDateTime startAt,
                                                          @Param("endAt") LocalDateTime endAt,
                                                          @Param("query") QualityReportQuery query,
                                                          @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT q.skill_group_id dimensionId, COALESCE(NULLIF(sg.group_name, ''), '未关联技能组') dimensionName,",
        " COUNT(qr.id) resultCount, COALESCE(AVG(qr.total_score), 0) averageScore,",
        " COALESCE(SUM(CASE WHEN qr.qualified = 1 THEN 1 ELSE 0 END), 0) qualifiedCount,",
        " COALESCE(SUM(CASE WHEN qr.fatal_flag = 1 THEN 1 ELSE 0 END), 0) fatalCount",
        "FROM cc_quality_task qt JOIN cc_call_session cs ON cs.tenant_id = qt.tenant_id AND cs.id = qt.call_session_id",
        "JOIN cc_quality_result qr ON qr.tenant_id = qt.tenant_id AND qr.task_id = qt.id AND qr.effective_flag = 1 AND qr.deleted = 0",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = qt.tenant_id AND q.id = qt.queue_id AND q.deleted = 0",
        "LEFT JOIN cc_skill_group sg ON sg.tenant_id = q.tenant_id AND sg.id = q.skill_group_id AND sg.deleted = 0",
        "WHERE qt.tenant_id = #{tenantId} AND qt.deleted = 0 AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND qt.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND qt.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND ((qt.agent_id IS NOT NULL AND qt.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (qt.agent_id IS NULL AND qt.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "GROUP BY q.skill_group_id, sg.group_name ORDER BY averageScore DESC, resultCount DESC LIMIT 20",
        "</script>"
    })
    List<QualityReportRankingResponse> selectSkillGroupRanking(@Param("tenantId") String tenantId,
                                                               @Param("startAt") LocalDateTime startAt,
                                                               @Param("endAt") LocalDateTime endAt,
                                                               @Param("query") QualityReportQuery query,
                                                               @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT item.item_code itemCode, item.item_name itemName, COUNT(*) occurrenceCount,",
        " COUNT(DISTINCT task.call_session_id) affectedCallCount,",
        " COALESCE(SUM(ABS(LEAST(item.score_change, 0))), 0) totalDeduction",
        "FROM cc_quality_item_result item",
        "JOIN cc_quality_result result ON result.tenant_id = item.tenant_id AND result.id = item.quality_result_id",
        " AND result.effective_flag = 1 AND result.deleted = 0",
        "JOIN cc_quality_task task ON task.tenant_id = result.tenant_id AND task.id = result.task_id AND task.deleted = 0",
        "JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "WHERE item.tenant_id = #{tenantId} AND item.deleted = 0",
        " AND (item.result = 'FAILED' OR item.score_change &lt; 0)",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND ((task.agent_id IS NOT NULL AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (task.agent_id IS NULL AND task.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "GROUP BY item.item_code, item.item_name",
        "ORDER BY occurrenceCount DESC, totalDeduction DESC LIMIT 20",
        "</script>"
    })
    List<QualityDeductionItemResponse> selectDeductionItems(@Param("tenantId") String tenantId,
                                                            @Param("startAt") LocalDateTime startAt,
                                                            @Param("endAt") LocalDateTime endAt,
                                                            @Param("query") QualityReportQuery query,
                                                            @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT task.id taskId, task.task_code taskCode, task.call_session_id callSessionId,",
        " task.business_call_id businessCallId, cs.ended_at callEndedAt,",
        " task.agent_id agentId, task.agent_name agentName, task.agent_extension agentExtension,",
        " task.queue_id queueId, task.queue_name queueName, q.skill_group_id skillGroupId, sg.group_name skillGroupName,",
        " task.reviewer_id reviewerId, task.reviewer_name reviewerName, template.template_name templateName,",
        " version.version_no templateVersionNo, result.total_score totalScore, result.qualified qualified,",
        " result.fatal_flag fatalFlag, result.summary summary, task.published_at publishedAt,",
        " (SELECT appeal.status FROM cc_quality_appeal appeal",
        "  WHERE appeal.tenant_id = task.tenant_id AND appeal.task_id = task.id AND appeal.deleted = 0",
        "  ORDER BY appeal.appealed_at DESC, appeal.id DESC LIMIT 1) appealStatus",
        "FROM cc_quality_task task",
        "JOIN cc_quality_result result ON result.tenant_id = task.tenant_id AND result.task_id = task.id",
        " AND result.effective_flag = 1 AND result.deleted = 0",
        "JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "LEFT JOIN cc_skill_group sg ON sg.tenant_id = q.tenant_id AND sg.id = q.skill_group_id AND sg.deleted = 0",
        "LEFT JOIN cc_quality_template template ON template.tenant_id = task.tenant_id AND template.id = task.template_id",
        " AND template.deleted = 0",
        "LEFT JOIN cc_quality_template_version version ON version.tenant_id = task.tenant_id",
        " AND version.id = task.template_version_id AND version.deleted = 0",
        "WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND task.published_at IS NOT NULL",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='query.qualified != null'> AND result.qualified = #{query.qualified}</if>",
        "<if test='query.fatalFlag != null'> AND result.fatal_flag = #{query.fatalFlag}</if>",
        "<if test='query.keyword != null and query.keyword != &quot;&quot;'>",
        " AND (task.task_code LIKE CONCAT('%', #{query.keyword}, '%')",
        " OR task.business_call_id LIKE CONCAT('%', #{query.keyword}, '%')",
        " OR task.agent_name LIKE CONCAT('%', #{query.keyword}, '%')",
        " OR task.reviewer_name LIKE CONCAT('%', #{query.keyword}, '%'))</if>",
        "<if test='scope.restricted'> AND ((task.agent_id IS NOT NULL AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (task.agent_id IS NULL AND task.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "ORDER BY task.published_at DESC, task.id DESC",
        "</script>"
    })
    Page<QualityReportDetailResponse> selectDetailPage(@Param("page") Page<QualityReportDetailResponse> page,
                                                       @Param("tenantId") String tenantId,
                                                       @Param("startAt") LocalDateTime startAt,
                                                       @Param("endAt") LocalDateTime endAt,
                                                       @Param("query") QualityReportQuery query,
                                                       @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT COUNT(DISTINCT task.id) aiTaskCount, COUNT(*) evaluatedItemCount,",
        " COALESCE(SUM(CASE WHEN final_item.manually_modified = 0 THEN 1 ELSE 0 END), 0) adoptedItemCount,",
        " COALESCE(SUM(CASE WHEN final_item.manually_modified = 1 THEN 1 ELSE 0 END), 0) modifiedItemCount",
        "FROM cc_quality_task task",
        "JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "JOIN cc_quality_result final_result ON final_result.tenant_id = task.tenant_id AND final_result.task_id = task.id",
        " AND final_result.effective_flag = 1 AND final_result.deleted = 0",
        "JOIN cc_quality_item_result final_item ON final_item.tenant_id = final_result.tenant_id",
        " AND final_item.quality_result_id = final_result.id AND final_item.deleted = 0",
        "JOIN cc_quality_result ai_result ON ai_result.tenant_id = task.tenant_id AND ai_result.task_id = task.id",
        " AND ai_result.source = 'AI' AND ai_result.deleted = 0",
        " AND ai_result.id = (SELECT MAX(ai_latest.id) FROM cc_quality_result ai_latest",
        "  WHERE ai_latest.tenant_id = task.tenant_id AND ai_latest.task_id = task.id",
        "    AND ai_latest.source = 'AI' AND ai_latest.deleted = 0)",
        "JOIN cc_quality_item_result ai_item ON ai_item.tenant_id = ai_result.tenant_id",
        " AND ai_item.quality_result_id = ai_result.id AND ai_item.template_item_id = final_item.template_item_id",
        " AND ai_item.result != 'NEEDS_MANUAL_REVIEW' AND ai_item.deleted = 0",
        "WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND task.published_at IS NOT NULL",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND ((task.agent_id IS NOT NULL AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (task.agent_id IS NULL AND task.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "</script>"
    })
    QualityAiAdoptionSummaryResponse selectAiAdoptionSummary(@Param("tenantId") String tenantId,
                                                             @Param("startAt") LocalDateTime startAt,
                                                             @Param("endAt") LocalDateTime endAt,
                                                             @Param("query") QualityReportQuery query,
                                                             @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT final_item.item_code itemCode, final_item.item_name itemName, COUNT(*) evaluatedCount,",
        " SUM(CASE WHEN final_item.manually_modified = 1 THEN 1 ELSE 0 END) modifiedCount",
        "FROM cc_quality_task task",
        "JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "JOIN cc_quality_result final_result ON final_result.tenant_id = task.tenant_id AND final_result.task_id = task.id",
        " AND final_result.effective_flag = 1 AND final_result.deleted = 0",
        "JOIN cc_quality_item_result final_item ON final_item.tenant_id = final_result.tenant_id",
        " AND final_item.quality_result_id = final_result.id AND final_item.deleted = 0",
        "JOIN cc_quality_result ai_result ON ai_result.tenant_id = task.tenant_id AND ai_result.task_id = task.id",
        " AND ai_result.source = 'AI' AND ai_result.deleted = 0",
        " AND ai_result.id = (SELECT MAX(ai_latest.id) FROM cc_quality_result ai_latest",
        "  WHERE ai_latest.tenant_id = task.tenant_id AND ai_latest.task_id = task.id",
        "    AND ai_latest.source = 'AI' AND ai_latest.deleted = 0)",
        "JOIN cc_quality_item_result ai_item ON ai_item.tenant_id = ai_result.tenant_id",
        " AND ai_item.quality_result_id = ai_result.id AND ai_item.template_item_id = final_item.template_item_id",
        " AND ai_item.result != 'NEEDS_MANUAL_REVIEW' AND ai_item.deleted = 0",
        "WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND task.published_at IS NOT NULL",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND ((task.agent_id IS NOT NULL AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (task.agent_id IS NULL AND task.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "GROUP BY final_item.item_code, final_item.item_name",
        "HAVING modifiedCount &gt; 0 ORDER BY modifiedCount DESC, evaluatedCount DESC LIMIT 20",
        "</script>"
    })
    List<QualityAiModificationResponse> selectAiModifications(@Param("tenantId") String tenantId,
                                                               @Param("startAt") LocalDateTime startAt,
                                                               @Param("endAt") LocalDateTime endAt,
                                                               @Param("query") QualityReportQuery query,
                                                               @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT",
        "<choose>",
        " <when test='dimension == \"REVIEWER\"'>task.reviewer_id dimensionId, COALESCE(NULLIF(task.reviewer_name, ''), '未分配质检员') dimensionName,</when>",
        " <when test='dimension == \"TEMPLATE\"'>task.template_id dimensionId, COALESCE(NULLIF(template.template_name, ''), '未知模板') dimensionName,</when>",
        " <otherwise>task.agent_id dimensionId, COALESCE(NULLIF(task.agent_name, ''), NULLIF(task.agent_extension, ''), '未关联坐席') dimensionName,</otherwise>",
        "</choose>",
        " COUNT(DISTINCT task.id) aiTaskCount, COUNT(*) evaluatedItemCount,",
        " SUM(CASE WHEN final_item.manually_modified = 0 THEN 1 ELSE 0 END) adoptedItemCount,",
        " SUM(CASE WHEN final_item.manually_modified = 1 THEN 1 ELSE 0 END) modifiedItemCount",
        "FROM cc_quality_task task",
        "JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "LEFT JOIN cc_quality_template template ON template.tenant_id = task.tenant_id AND template.id = task.template_id AND template.deleted = 0",
        "JOIN cc_quality_result final_result ON final_result.tenant_id = task.tenant_id AND final_result.task_id = task.id",
        " AND final_result.effective_flag = 1 AND final_result.deleted = 0",
        "JOIN cc_quality_item_result final_item ON final_item.tenant_id = final_result.tenant_id",
        " AND final_item.quality_result_id = final_result.id AND final_item.deleted = 0",
        "JOIN cc_quality_result ai_result ON ai_result.tenant_id = task.tenant_id AND ai_result.task_id = task.id",
        " AND ai_result.source = 'AI' AND ai_result.deleted = 0",
        " AND ai_result.id = (SELECT MAX(ai_latest.id) FROM cc_quality_result ai_latest",
        "  WHERE ai_latest.tenant_id = task.tenant_id AND ai_latest.task_id = task.id",
        "    AND ai_latest.source = 'AI' AND ai_latest.deleted = 0)",
        "JOIN cc_quality_item_result ai_item ON ai_item.tenant_id = ai_result.tenant_id",
        " AND ai_item.quality_result_id = ai_result.id AND ai_item.template_item_id = final_item.template_item_id",
        " AND ai_item.result != 'NEEDS_MANUAL_REVIEW' AND ai_item.deleted = 0",
        "WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND task.published_at IS NOT NULL",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND ((task.agent_id IS NOT NULL AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (task.agent_id IS NULL AND task.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "GROUP BY",
        "<choose>",
        " <when test='dimension == \"REVIEWER\"'>task.reviewer_id, task.reviewer_name</when>",
        " <when test='dimension == \"TEMPLATE\"'>task.template_id, template.template_name</when>",
        " <otherwise>task.agent_id, task.agent_name, task.agent_extension</otherwise>",
        "</choose>",
        "ORDER BY modifiedItemCount DESC, evaluatedItemCount DESC LIMIT 20",
        "</script>"
    })
    List<QualityAiAdoptionRankingResponse> selectAiAdoptionRanking(@Param("tenantId") String tenantId,
                                                                   @Param("startAt") LocalDateTime startAt,
                                                                   @Param("endAt") LocalDateTime endAt,
                                                                   @Param("query") QualityReportQuery query,
                                                                   @Param("scope") QualityDataScopeService.Scope scope,
                                                                   @Param("dimension") String dimension);

    @Select({
        "<script>",
        "SELECT COUNT(DISTINCT task.id) publishedTaskCount, COUNT(DISTINCT appeal.id) appealCount,",
        " COUNT(DISTINCT CASE WHEN appeal.id IS NOT NULL THEN task.id END) appealedTaskCount,",
        " COALESCE(SUM(CASE WHEN appeal.status IN ('SUBMITTED', 'RECHECK_REQUESTED') THEN 1 ELSE 0 END), 0) pendingCount,",
        " COALESCE(SUM(CASE WHEN appeal.status = 'REJECTED' THEN 1 ELSE 0 END), 0) maintainedCount,",
        " COALESCE(SUM(CASE WHEN appeal.status = 'ACCEPTED' AND review_result.source != 'RECHECK' THEN 1 ELSE 0 END), 0) adjustedCount,",
        " COALESCE(SUM(CASE WHEN appeal.status = 'ACCEPTED' AND review_result.source = 'RECHECK' THEN 1 ELSE 0 END), 0) recheckedCount",
        "FROM cc_quality_task task",
        "JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "JOIN cc_quality_result result ON result.tenant_id = task.tenant_id AND result.task_id = task.id",
        " AND result.effective_flag = 1 AND result.deleted = 0",
        "LEFT JOIN cc_quality_appeal appeal ON appeal.tenant_id = task.tenant_id AND appeal.task_id = task.id AND appeal.deleted = 0",
        "LEFT JOIN cc_quality_result review_result ON review_result.tenant_id = appeal.tenant_id",
        " AND review_result.id = appeal.review_result_id AND review_result.deleted = 0",
        "WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND task.published_at IS NOT NULL",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND ((task.agent_id IS NOT NULL AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (task.agent_id IS NULL AND task.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "</script>"
    })
    QualityAppealSummaryResponse selectAppealSummary(@Param("tenantId") String tenantId,
                                                     @Param("startAt") LocalDateTime startAt,
                                                     @Param("endAt") LocalDateTime endAt,
                                                     @Param("query") QualityReportQuery query,
                                                     @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT task.agent_id dimensionId, COALESCE(NULLIF(task.agent_name, ''), NULLIF(task.agent_extension, ''), '未关联坐席') dimensionName,",
        " COUNT(DISTINCT task.id) publishedCount, COUNT(DISTINCT appeal.task_id) appealedCount,",
        " COUNT(DISTINCT CASE WHEN appeal.status = 'ACCEPTED' THEN appeal.id END) acceptedCount,",
        " COUNT(DISTINCT CASE WHEN appeal.status IN ('ACCEPTED', 'REJECTED') THEN appeal.id END) completedCount",
        "FROM cc_quality_task task JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "JOIN cc_quality_result result ON result.tenant_id = task.tenant_id AND result.task_id = task.id AND result.effective_flag = 1 AND result.deleted = 0",
        "LEFT JOIN cc_quality_appeal appeal ON appeal.tenant_id = task.tenant_id AND appeal.task_id = task.id AND appeal.deleted = 0",
        "WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND task.published_at IS NOT NULL",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted and scope.agentIds.isEmpty()'> AND 1 = 0</if>",
        "<if test='scope.restricted and !scope.agentIds.isEmpty()'> AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>",
        "GROUP BY task.agent_id, task.agent_name, task.agent_extension HAVING appealedCount &gt; 0",
        "ORDER BY appealedCount DESC, publishedCount DESC LIMIT 20",
        "</script>"
    })
    List<QualityAppealRankingResponse> selectAgentAppealRanking(@Param("tenantId") String tenantId,
                                                                @Param("startAt") LocalDateTime startAt,
                                                                @Param("endAt") LocalDateTime endAt,
                                                                @Param("query") QualityReportQuery query,
                                                                @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT task.reviewer_id dimensionId, COALESCE(NULLIF(task.reviewer_name, ''), '未分配质检员') dimensionName,",
        " COUNT(DISTINCT task.id) publishedCount, COUNT(DISTINCT appeal.task_id) appealedCount,",
        " COUNT(DISTINCT CASE WHEN appeal.status = 'ACCEPTED' THEN appeal.id END) acceptedCount,",
        " COUNT(DISTINCT CASE WHEN appeal.status IN ('ACCEPTED', 'REJECTED') THEN appeal.id END) completedCount",
        "FROM cc_quality_task task JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "JOIN cc_quality_result result ON result.tenant_id = task.tenant_id AND result.task_id = task.id AND result.effective_flag = 1 AND result.deleted = 0",
        "LEFT JOIN cc_quality_appeal appeal ON appeal.tenant_id = task.tenant_id AND appeal.task_id = task.id AND appeal.deleted = 0",
        "WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND task.published_at IS NOT NULL",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND ((task.agent_id IS NOT NULL AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (task.agent_id IS NULL AND task.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "GROUP BY task.reviewer_id, task.reviewer_name HAVING appealedCount &gt; 0",
        "ORDER BY appealedCount DESC, publishedCount DESC LIMIT 20",
        "</script>"
    })
    List<QualityAppealRankingResponse> selectReviewerAppealRanking(@Param("tenantId") String tenantId,
                                                                   @Param("startAt") LocalDateTime startAt,
                                                                   @Param("endAt") LocalDateTime endAt,
                                                                   @Param("query") QualityReportQuery query,
                                                                   @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT q.skill_group_id dimensionId, COALESCE(NULLIF(sg.group_name, ''), '未关联技能组') dimensionName,",
        " COUNT(DISTINCT task.id) publishedCount, COUNT(DISTINCT appeal.task_id) appealedCount,",
        " COUNT(DISTINCT CASE WHEN appeal.status = 'ACCEPTED' THEN appeal.id END) acceptedCount,",
        " COUNT(DISTINCT CASE WHEN appeal.status IN ('ACCEPTED', 'REJECTED') THEN appeal.id END) completedCount",
        "FROM cc_quality_task task JOIN cc_call_session cs ON cs.tenant_id = task.tenant_id AND cs.id = task.call_session_id",
        "LEFT JOIN cc_call_queue q ON q.tenant_id = task.tenant_id AND q.id = task.queue_id AND q.deleted = 0",
        "LEFT JOIN cc_skill_group sg ON sg.tenant_id = q.tenant_id AND sg.id = q.skill_group_id AND sg.deleted = 0",
        "JOIN cc_quality_result result ON result.tenant_id = task.tenant_id AND result.task_id = task.id AND result.effective_flag = 1 AND result.deleted = 0",
        "LEFT JOIN cc_quality_appeal appeal ON appeal.tenant_id = task.tenant_id AND appeal.task_id = task.id AND appeal.deleted = 0",
        "WHERE task.tenant_id = #{tenantId} AND task.deleted = 0 AND task.published_at IS NOT NULL",
        " AND cs.ended_at &gt;= #{startAt} AND cs.ended_at &lt; #{endAt}",
        "<if test='query.agentId != null'> AND task.agent_id = #{query.agentId}</if>",
        "<if test='query.queueId != null'> AND task.queue_id = #{query.queueId}</if>",
        "<if test='query.skillGroupId != null'> AND q.skill_group_id = #{query.skillGroupId}</if>",
        "<if test='scope.restricted'> AND ((task.agent_id IS NOT NULL AND task.agent_id IN",
        " <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)",
        " <if test='!scope.queueIds.isEmpty()'> OR (task.agent_id IS NULL AND task.queue_id IN",
        " <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>)</if>)</if>",
        "GROUP BY q.skill_group_id, sg.group_name HAVING appealedCount &gt; 0",
        "ORDER BY appealedCount DESC, publishedCount DESC LIMIT 20",
        "</script>"
    })
    List<QualityAppealRankingResponse> selectSkillGroupAppealRanking(@Param("tenantId") String tenantId,
                                                                     @Param("startAt") LocalDateTime startAt,
                                                                     @Param("endAt") LocalDateTime endAt,
                                                                     @Param("query") QualityReportQuery query,
                                                                     @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT id, COALESCE(NULLIF(agent_name, ''), agent_code) name FROM cc_agent",
        "WHERE tenant_id = #{tenantId} AND deleted = 0",
        "<if test='scope.restricted'> AND id IN <foreach collection='scope.agentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>",
        "ORDER BY enabled DESC, name",
        "</script>"
    })
    List<QualityOptionResponse> selectAgentOptions(@Param("tenantId") String tenantId,
                                                   @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT id, queue_name name FROM cc_call_queue WHERE tenant_id = #{tenantId} AND deleted = 0",
        "<if test='scope.restricted'> AND id IN <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>",
        "ORDER BY enabled DESC, name",
        "</script>"
    })
    List<QualityOptionResponse> selectQueueOptions(@Param("tenantId") String tenantId,
                                                   @Param("scope") QualityDataScopeService.Scope scope);

    @Select({
        "<script>",
        "SELECT DISTINCT sg.id, sg.group_name name FROM cc_skill_group sg",
        "JOIN cc_call_queue q ON q.tenant_id = sg.tenant_id AND q.skill_group_id = sg.id AND q.deleted = 0",
        "WHERE sg.tenant_id = #{tenantId} AND sg.deleted = 0",
        "<if test='scope.restricted'> AND q.id IN <foreach collection='scope.queueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>",
        "ORDER BY name",
        "</script>"
    })
    List<QualityOptionResponse> selectSkillGroupOptions(@Param("tenantId") String tenantId,
                                                        @Param("scope") QualityDataScopeService.Scope scope);
}
