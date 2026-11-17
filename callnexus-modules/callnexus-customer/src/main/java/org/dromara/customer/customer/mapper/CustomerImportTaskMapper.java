package org.dromara.customer.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.dromara.customer.customer.domain.CustomerImportTask;

@Mapper
public interface CustomerImportTaskMapper extends BaseMapper<CustomerImportTask> {
    default CustomerImportTask selectForUpdate(Long taskId) {
        return selectOne(new LambdaQueryWrapper<CustomerImportTask>()
            .eq(CustomerImportTask::getId, taskId).last("FOR UPDATE"));
    }

    @Select("SELECT EXISTS (SELECT 1 FROM cc_outbound_task_source WHERE import_task_id = #{taskId} AND deleted = 0)")
    boolean hasOutboundSource(Long taskId);
}
