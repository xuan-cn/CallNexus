package org.dromara.customer.ticket.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.annotation.DataColumn;
import org.dromara.common.mybatis.annotation.DataPermission;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.customer.ticket.domain.Ticket;

public interface TicketMapper extends BaseMapperPlus<Ticket, Ticket> {

    @DataPermission({
        @DataColumn(key = "deptName", value = "create_dept"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default Page<Ticket> selectDataScopePage(Page<Ticket> page, Wrapper<Ticket> wrapper) {
        return selectPage(page, wrapper);
    }

    @DataPermission({
        @DataColumn(key = "deptName", value = "create_dept"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default Ticket selectDataScopeById(Long id) {
        return selectOne(new LambdaQueryWrapper<Ticket>().eq(Ticket::getId, id));
    }
}
