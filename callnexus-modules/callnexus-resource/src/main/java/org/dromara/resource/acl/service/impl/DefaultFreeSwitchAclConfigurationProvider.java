package org.dromara.resource.acl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.acl.domain.FreeSwitchAclEntry;
import org.dromara.resource.acl.domain.FreeSwitchAclSnapshot;
import org.dromara.resource.acl.domain.FreeSwitchAclVersion;
import org.dromara.resource.acl.mapper.FreeSwitchAclVersionMapper;
import org.dromara.resource.acl.service.FreeSwitchAclConfigurationProvider;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultFreeSwitchAclConfigurationProvider implements FreeSwitchAclConfigurationProvider {
    private static final String BUILT_IN_LISTS = """
              <list name="loopback.auto" default="deny">
                <node type="allow" cidr="127.0.0.0/8"/>
                <node type="allow" cidr="::1/128"/>
              </list>
              <list name="rfc1918.auto" default="deny">
                <node type="allow" cidr="10.0.0.0/8"/>
                <node type="allow" cidr="172.16.0.0/12"/>
                <node type="allow" cidr="192.168.0.0/16"/>
              </list>
              <list name="lan" default="deny">
                <node type="allow" cidr="127.0.0.0/8"/>
                <node type="allow" cidr="::1/128"/>
                <node type="allow" cidr="10.0.0.0/8"/>
                <node type="allow" cidr="172.16.0.0/12"/>
                <node type="allow" cidr="192.168.0.0/16"/>
              </list>
              <list name="wan_v4.auto" default="allow">
                <node type="deny" cidr="0.0.0.0/8"/>
                <node type="deny" cidr="10.0.0.0/8"/>
                <node type="deny" cidr="100.64.0.0/10"/>
                <node type="deny" cidr="127.0.0.0/8"/>
                <node type="deny" cidr="169.254.0.0/16"/>
                <node type="deny" cidr="172.16.0.0/12"/>
                <node type="deny" cidr="192.168.0.0/16"/>
              </list>
              <list name="wan_v6.auto" default="allow">
                <node type="deny" cidr="::1/128"/>
                <node type="deny" cidr="fc00::/7"/>
                <node type="deny" cidr="fe80::/10"/>
              </list>
              <list name="localnet.auto" default="deny">
                <node type="allow" cidr="$${local_ip_v4}/$${local_mask_v4}"/>
              </list>
              <list name="domains" default="deny">
                <node type="allow" domain="$${domain}"/>
              </list>
        """;

    private final FreeSwitchAclVersionMapper versionMapper;

    @Override
    public String render(String tenantId, Long nodeId) {
        return TenantHelper.dynamic(tenantId, () -> renderForTenant(nodeId));
    }

    private String renderForTenant(Long nodeId) {
        List<FreeSwitchAclSnapshot> snapshots = versionMapper.selectList(
                new LambdaQueryWrapper<FreeSwitchAclVersion>()
                    .eq(FreeSwitchAclVersion::getNodeId, nodeId)
                    .eq(FreeSwitchAclVersion::getCurrentVersion, true)
                    .orderByAsc(FreeSwitchAclVersion::getAclId))
            .stream()
            .map(version -> JsonUtils.parseObject(version.getSnapshotJson(), FreeSwitchAclSnapshot.class))
            .filter(snapshot -> Boolean.TRUE.equals(snapshot.enabled()))
            .toList();
        StringBuilder lists = new StringBuilder();
        lists.append(BUILT_IN_LISTS);
        for (FreeSwitchAclSnapshot snapshot : snapshots) {
            lists.append("      <list name=\"").append(FreeSwitchXmlRenderer.escape(snapshot.aclCode()))
                .append("\" default=\"").append(action(snapshot.defaultAction())).append("\">\n");
            for (FreeSwitchAclEntry entry : snapshot.entries()) {
                lists.append("        <node type=\"").append(action(entry.action())).append("\" cidr=\"")
                    .append(FreeSwitchXmlRenderer.escape(entry.cidr())).append("\"/>\n");
            }
            lists.append("      </list>\n");
        }
        return """
            <document type="freeswitch/xml">
              <section name="configuration">
                <configuration name="acl.conf" description="CallNexus dynamic ACL">
                  <network-lists>
            """ + lists + """
                  </network-lists>
                </configuration>
              </section>
            </document>
            """;
    }

    private String action(String action) {
        return "ALLOW".equalsIgnoreCase(action) ? "allow" : "deny";
    }
}
