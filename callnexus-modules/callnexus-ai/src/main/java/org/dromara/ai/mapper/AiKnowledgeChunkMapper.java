package org.dromara.ai.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.dromara.ai.domain.AiKnowledgeChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface AiKnowledgeChunkMapper extends BaseMapper<AiKnowledgeChunk> {
    @Delete("DELETE FROM cc_ai_knowledge_chunk WHERE tenant_id = #{tenantId} AND document_version_id = #{documentVersionId}")
    int deletePhysicallyByDocumentVersionId(@Param("tenantId") String tenantId,
                                            @Param("documentVersionId") Long documentVersionId);
}
