package org.dromara.resource.freeswitch.callcenter;

/**
 * FreeSWITCH mod_callcenter 完整动态配置提供器。
 * <p>
 * 应用中必须只有一个实现。业务模块扩展配置时应实现
 * {@link FreeSwitchCallCenterConfigurationContributor}，由默认提供器统一聚合。
 */
public interface FreeSwitchCallCenterConfigurationProvider {

    String render(String tenantId, Long nodeId);
}
