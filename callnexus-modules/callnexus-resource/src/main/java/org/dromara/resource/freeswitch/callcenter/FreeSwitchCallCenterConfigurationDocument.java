package org.dromara.resource.freeswitch.callcenter;

import org.dromara.common.core.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FreeSWITCH mod_callcenter 配置文档。
 * <p>
 * Contributor 只向对应区域添加已经完成 XML 转义的配置片段，最终 XML 由唯一聚合器生成。
 */
public class FreeSwitchCallCenterConfigurationDocument {
    private final Map<String, String> settings = new LinkedHashMap<>();
    private final Map<String, String> queues = new LinkedHashMap<>();
    private final Map<String, String> agents = new LinkedHashMap<>();
    private final Map<String, String> tiers = new LinkedHashMap<>();

    public void addSetting(String name, String xml) {
        add(settings, "Setting", name, xml);
    }

    public void addQueue(String queueName, String xml) {
        add(queues, "Queue", queueName, xml);
    }

    public void addAgent(String agentName, String xml) {
        add(agents, "Agent", agentName, xml);
    }

    public void addTier(String tierName, String xml) {
        add(tiers, "Tier", tierName, xml);
    }

    public List<String> settings() {
        return List.copyOf(settings.values());
    }

    public List<String> queues() {
        return List.copyOf(queues.values());
    }

    public List<String> agents() {
        return List.copyOf(agents.values());
    }

    public List<String> tiers() {
        return List.copyOf(tiers.values());
    }

    private void add(Map<String, String> target, String type, String name, String xml) {
        if (name == null || name.isBlank() || xml == null || xml.isBlank()) {
            throw new ServiceException("FreeSWITCH callcenter " + type + " 配置名称和内容不能为空");
        }
        if (target.putIfAbsent(name, xml.strip()) != null) {
            throw new ServiceException("FreeSWITCH callcenter " + type + " 配置重复：" + name);
        }
    }
}
