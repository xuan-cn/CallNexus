package org.dromara.resource.acl.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.acl.domain.request.FreeSwitchAclPageQuery;
import org.dromara.resource.acl.domain.request.FreeSwitchAclSaveRequest;
import org.dromara.resource.acl.domain.response.FreeSwitchAclIpTestResponse;
import org.dromara.resource.acl.domain.response.FreeSwitchAclResponse;

public interface FreeSwitchAclApplicationService {
    TableDataInfo<FreeSwitchAclResponse> page(FreeSwitchAclPageQuery query, PageQuery pageQuery);
    FreeSwitchAclResponse get(Long id);
    Long create(FreeSwitchAclSaveRequest request);
    void update(Long id, FreeSwitchAclSaveRequest request);
    void delete(Long id);
    void publish(Long id);
    void rollback(Long id);
    FreeSwitchAclIpTestResponse testIp(Long id, String ip);
    String preview(Long id);
}
