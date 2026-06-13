package org.dromara.ivr.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.ivr.compiler.IvrNodeCompilerRegistry;
import org.dromara.ivr.compiler.IvrNodeContext;
import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.domain.IvrFlowVersion;
import org.dromara.ivr.graph.IvrGraphDefinition;
import org.dromara.ivr.graph.IvrGraphParser;
import org.dromara.ivr.graph.IvrNodeDefinition;
import org.dromara.ivr.support.IvrDialplanRenderSupport;
import org.dromara.ivr.support.IvrMediaPathResolver;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class IvrDialplanQueryServiceImpl implements IvrDialplanQueryService {

    private final IvrFlowService flowService;
    private final FreeSwitchNodeGroupMemberMapper memberMapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final IvrGraphParser graphParser;
    private final IvrNodeCompilerRegistry compilerRegistry;
    private final IvrDialplanRenderSupport renderSupport;
    private final IvrMediaPathResolver mediaPathResolver;

    @Override
    public boolean isPublishedFlowAvailable(String tenantId, Long flowId, Long nodeId) {
        if (flowId == null || nodeId == null) {
            return false;
        }
        return TenantHelper.dynamic(tenantId, () -> {
            try {
                IvrFlow flow = flowService.requirePublished(flowId);
                return memberMapper.exists(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                    .eq(FreeSwitchNodeGroupMember::getGroupId, flow.getNodeGroupId())
                    .eq(FreeSwitchNodeGroupMember::getNodeId, nodeId));
            } catch (Exception exception) {
                return false;
            }
        });
    }

    @Override
    public String renderPublishedFlow(String tenantId, Long flowId, Long nodeId, String number, String context, String sipDomain) {
        return TenantHelper.dynamic(tenantId, () -> {
            try {
                Long targetNodeId = nodeId == null ? resolveNodeId(sipDomain) : nodeId;
                if (!isPublishedFlowAvailable(tenantId, flowId, targetNodeId)) {
                    return FreeSwitchXmlRenderer.notFound();
                }
                IvrFlow flow = flowService.requirePublished(flowId);
                IvrFlowVersion version = flowService.latestVersion(flow);
                return compile(flow, version, targetNodeId, number, context, sipDomain);
            } catch (Exception exception) {
                log.warn("生成IVR动态拨号计划失败，flowId={}，nodeId={}，number={}，error={}",
                    flowId, nodeId, number, exception.getMessage());
                return FreeSwitchXmlRenderer.notFound();
            }
        });
    }

    private Long resolveNodeId(String sipDomain) {
        if (sipDomain == null || sipDomain.isBlank()) {
            return null;
        }
        FreeSwitchNode node = nodeMapper.selectOne(new LambdaQueryWrapper<FreeSwitchNode>()
            .and(wrapper -> wrapper.eq(FreeSwitchNode::getSipDomain, sipDomain)
                .or()
                .eq(FreeSwitchNode::getEslHost, sipDomain))
            .eq(FreeSwitchNode::getEnabled, true)
            .last("limit 1"));
        return node == null ? null : node.getId();
    }

    private String compile(IvrFlow flow, IvrFlowVersion version, Long nodeId, String number, String context, String sipDomain) {
        IvrGraphDefinition graph = graphParser.parse(version.getGraphJson());
        IvrNodeDefinition start = graph.nodes().stream()
            .filter(node -> "START".equals(node.type()))
            .findFirst()
            .orElseThrow();
        StringBuilder xml = new StringBuilder();
        renderSupport.appendDocumentStart(xml, context);
        if (number == null || !number.startsWith("callnexus_ivr_")) {
            renderSupport.appendEntry(xml, flow, number, start.id());
        }
        for (IvrNodeDefinition node : graph.nodes()) {
            compilerRegistry.require(node.type()).compile(
                new IvrNodeContext(flow, nodeId, sipDomain, graph, node, xml, renderSupport, mediaPathResolver)
            );
        }
        renderSupport.appendDocumentEnd(xml);
        return xml.toString();
    }
}
