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
            throw new ServiceException("IVR_NODE_DEFAULT_ROUTE_REQUIRED");
        }
    }

    public void requireTerminal() {
        if (!outgoing().isEmpty()) {
            throw new ServiceException("IVR_TERMINAL_NODE_ROUTE_NOT_ALLOWED");
        }
    }

    public void rejectConditions() {
        if (outgoing().stream().anyMatch(edge -> edge.condition() != null && !edge.condition().isBlank())) {
            throw new ServiceException("IVR_EDGE_CONDITION_NOT_ALLOWED");
        }
    }
}
