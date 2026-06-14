package org.dromara.resource.freeswitch.callcenter;

/**
 * FreeSWITCH mod_callcenter 动态配置上下文。
 *
 * @param tenantId 租户 ID
 * @param nodeId   FreeSWITCH 节点 ID
 */
public record FreeSwitchCallCenterConfigurationContext(String tenantId, Long nodeId) {
}
