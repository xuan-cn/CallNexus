package org.dromara.ai.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.dromara.ai.domain.AiFaqCandidate;
public interface AiFaqCandidateMapper extends BaseMapper<AiFaqCandidate> {
    @Delete("DELETE FROM cc_ai_faq_candidate WHERE tenant_id = #{tenantId} AND batch_id = #{batchId}")
    int deletePhysicallyByBatchId(@Param("tenantId") String tenantId, @Param("batchId") Long batchId);
}
