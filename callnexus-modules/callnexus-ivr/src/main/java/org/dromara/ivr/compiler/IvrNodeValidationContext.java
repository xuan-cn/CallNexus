package org.dromara.ivr.compiler;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.graph.IvrEdgeDefinition;
import org.dromara.ivr.graph.IvrGraphDefinition;
import org.dromara.ivr.graph.IvrNodeDefinition;
import org.dromara.ivr.support.IvrMediaPathResolver;

import java.util.List;

public record IvrNodeValidationContext(
    IvrFlow flow,
    IvrGraphDefinition graph,
    IvrNodeDefinition node,
    IvrMediaPathResolver mediaPathResolver
) {

    public List<IvrEdgeDefinition> outgoing() {
        return graph.outgoing(node.id());
    }

    public void requireSingleDefaultRoute() {
        rejectConditions();
        if (outgoing().size() != 1) {
            throw new ServiceException("请为 IVR 节点配置默认路由");
        }
    }

    public void requireTerminal() {
        if (!outgoing().isEmpty()) {
            throw new ServiceException("终止节点不能配置后续路由");
        }
    }

    public void rejectConditions() {
        if (outgoing().stream().anyMatch(edge -> edge.condition() != null && !edge.condition().isBlank())) {
            throw new ServiceException("当前 IVR 连线不允许配置条件");
        }
    }
}
