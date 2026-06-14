package org.dromara.resource.freeswitch.callcenter;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FreeSWITCH mod_callcenter 唯一配置聚合器。
 */
@Component
@Slf4j
public class DefaultFreeSwitchCallCenterConfigurationProvider implements FreeSwitchCallCenterConfigurationProvider {
    private final List<FreeSwitchCallCenterConfigurationContributor> contributors;

    public DefaultFreeSwitchCallCenterConfigurationProvider(List<FreeSwitchCallCenterConfigurationContributor> contributors) {
        validateContributors(contributors);
        this.contributors = contributors.stream()
            .sorted(Comparator.comparingInt(FreeSwitchCallCenterConfigurationContributor::order)
                .thenComparing(FreeSwitchCallCenterConfigurationContributor::contributorCode))
            .toList();
        log.info("FreeSWITCH callcenter 配置聚合器初始化完成，contributorCount={}，contributors={}",
            this.contributors.size(), this.contributors.stream()
                .map(FreeSwitchCallCenterConfigurationContributor::contributorCode).toList());
    }

    private void validateContributors(List<FreeSwitchCallCenterConfigurationContributor> contributors) {
        Set<String> codes = new HashSet<>();
        for (FreeSwitchCallCenterConfigurationContributor contributor : contributors) {
            String code = contributor.contributorCode();
            if (code == null || code.isBlank()) {
                throw new ServiceException("FreeSWITCH callcenter 配置贡献器编码不能为空");
            }
            if (!codes.add(code)) {
                throw new ServiceException("FreeSWITCH callcenter 配置贡献器编码重复：" + code);
            }
        }
    }

    @Override
    public String render(String tenantId, Long nodeId) {
        FreeSwitchCallCenterConfigurationContext context = new FreeSwitchCallCenterConfigurationContext(tenantId, nodeId);
        FreeSwitchCallCenterConfigurationDocument document = new FreeSwitchCallCenterConfigurationDocument();
        for (FreeSwitchCallCenterConfigurationContributor contributor : contributors) {
            contributor.contribute(context, document);
        }
        log.info("已聚合 FreeSWITCH callcenter 动态配置，tenantId={}，nodeId={}，queueCount={}，agentCount={}，tierCount={}",
            tenantId, nodeId, document.queues().size(), document.agents().size(), document.tiers().size());
        return renderDocument(document);
    }

    private String renderDocument(FreeSwitchCallCenterConfigurationDocument document) {
        return """
            <document type="freeswitch/xml">
              <section name="configuration">
                <configuration name="callcenter.conf" description="CallNexus 动态呼叫队列">
                  <settings>
            """ + renderItems(document.settings(), 10) + """
                  </settings>
                  <queues>
            """ + renderItems(document.queues(), 10) + """
                  </queues>
                  <agents>
            """ + renderItems(document.agents(), 10) + """
                  </agents>
                  <tiers>
            """ + renderItems(document.tiers(), 10) + """
                  </tiers>
                </configuration>
              </section>
            </document>
            """;
    }

    private String renderItems(List<String> items, int indent) {
        if (items.isEmpty()) {
            return "";
        }
        String padding = " ".repeat(indent);
        StringBuilder xml = new StringBuilder();
        for (String item : items) {
            for (String line : item.lines().toList()) {
                xml.append(padding).append(line).append('\n');
            }
        }
        return xml.toString();
    }
}
