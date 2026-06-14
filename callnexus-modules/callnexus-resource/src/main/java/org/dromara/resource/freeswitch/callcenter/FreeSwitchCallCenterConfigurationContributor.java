package org.dromara.resource.freeswitch.callcenter;

/**
 * FreeSWITCH mod_callcenter 配置贡献器。
 * <p>
 * 各业务模块通过实现该接口贡献 Queue、Agent 或 Tier 配置，不应再实现完整配置提供器。
 */
public interface FreeSwitchCallCenterConfigurationContributor {

    /**
     * 全局唯一的贡献器编码，用于启动时检查重复实现。
     */
    String contributorCode();

    /**
     * 执行顺序，数值越小越先执行。
     */
    default int order() {
        return 0;
    }

    void contribute(FreeSwitchCallCenterConfigurationContext context, FreeSwitchCallCenterConfigurationDocument document);
}
