package org.dromara.ivr.compiler;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.graph.IvrEdgeDefinition;
import org.dromara.resource.businesshours.domain.response.BusinessHoursEvaluation;
import org.dromara.resource.businesshours.service.BusinessHoursQueryService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class BusinessHoursNodeCompiler implements IvrNodeCompiler {
    private static final Set<String> REQUIRED_BRANCHES = Set.of("IN_HOURS", "OUT_OF_HOURS");
    private final BusinessHoursQueryService businessHoursQueryService;

    @Override
    public String nodeType() {
        return "BUSINESS_HOURS";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        Long planId = planId(context.node().config().path("planId").asText());
        if (!businessHoursQueryService.isPlanAvailable(context.flow().getTenantId(), planId)) {
            throw new ServiceException("工作时间方案不存在或未启用");
        }
        Set<String> branches = context.outgoing().stream().map(IvrEdgeDefinition::condition).collect(Collectors.toSet());
        if (context.outgoing().size() != 2 || !branches.equals(REQUIRED_BRANCHES)) {
            throw new ServiceException("工作时间节点必须配置工作时间内和工作时间外两个出口");
        }
    }

    @Override
    public void compile(IvrNodeContext context) {
        Long planId = planId(context.node().config().path("planId").asText());
        BusinessHoursEvaluation evaluation = businessHoursQueryService.evaluate(
            context.flow().getTenantId(), planId, Instant.now());
        String branch = evaluation.isInBusinessHours() ? "IN_HOURS" : "OUT_OF_HOURS";
        String target = context.graph().outgoing(context.node().id()).stream()
            .filter(edge -> branch.equals(edge.condition()))
            .map(IvrEdgeDefinition::target)
            .findFirst()
            .orElseThrow(() -> new ServiceException("工作时间节点出口配置不完整"));
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"set\" data=\"callnexus_business_hours_plan_id=")
            .append(planId).append("\"/>\n");
        context.xml().append("      <action application=\"set\" data=\"callnexus_business_hours_result=")
            .append(branch).append("\"/>\n");
        context.renderSupport().appendTransfer(context.xml(), context.flow().getId(), target);
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private Long planId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            throw new ServiceException("请选择工作时间方案");
        }
    }
}
