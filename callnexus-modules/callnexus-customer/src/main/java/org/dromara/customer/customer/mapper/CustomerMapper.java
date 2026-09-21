package org.dromara.customer.customer.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.annotation.DataColumn;
import org.dromara.common.mybatis.annotation.DataPermission;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.customer.customer.domain.Customer;

import java.util.List;

public interface CustomerMapper extends BaseMapperPlus<Customer, Customer> {

    @DataPermission({
        @DataColumn(key = "deptName", value = "create_dept"),
        @DataColumn(key = "userName", value = "create_by")
    })
    @Select("SELECT id FROM cc_customer WHERE deleted = 0")
    List<Long> selectDataScopeCustomerIds();

    @DataPermission({
        @DataColumn(key = "deptName", value = "create_dept"),
        @DataColumn(key = "userName", value = "create_by")
    })
    @Select("SELECT COUNT(*) FROM cc_customer WHERE id = #{id} AND deleted = 0")
    long countDataScopeCustomerById(@Param("id") Long id);
}
