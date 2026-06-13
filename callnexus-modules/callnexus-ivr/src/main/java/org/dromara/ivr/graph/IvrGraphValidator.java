package org.dromara.ivr.graph;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.compiler.IvrNodeCompilerRegistry;
import org.dromara.ivr.compiler.IvrNodeValidationContext;
import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.support.IvrMediaPathResolver;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class IvrGraphValidator {

    private final IvrNodeCompilerRegistry compilerRegistry;
    private final IvrMediaPathResolver mediaPathResolver;

    public void validate(IvrFlow flow, IvrGraphDefinition graph) {
        if (graph.nodes().isEmpty()) {
            throw new ServiceException("IVR_GRAPH_EMPTY");
        }
        IvrNodeDefinition start = null;
        for (IvrNodeDefinition node : graph.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                throw new ServiceException("IVR_NODE_INVALID");
            }
            if ("START".equals(node.type())) {
                if (start != null) {
                    throw new ServiceException("IVR_START_NODE_MUST_BE_UNIQUE");
                }
                start = node;
            }
            compilerRegistry.require(node.type());
        }
        if (start == null) {
            throw new ServiceException("IVR_START_NODE_REQUIRED");
        }
        for (IvrEdgeDefinition edge : graph.edges()) {
            if (!graph.nodeById().containsKey(edge.source()) || !graph.nodeById().containsKey(edge.target())) {
                throw new ServiceException("IVR_EDGE_INVALID");
            }
        }
        for (IvrNodeDefinition node : graph.nodes()) {
            compilerRegistry.require(node.type()).validate(
                new IvrNodeValidationContext(flow, graph, node, mediaPathResolver)
            );
        }
        Set<String> reached = new HashSet<>();
        walk(start.id(), graph, reached);
        if (reached.size() != graph.nodes().size()) {
            throw new ServiceException("IVR_GRAPH_HAS_UNREACHABLE_NODE");
        }
    }

    private void walk(String nodeId, IvrGraphDefinition graph, Set<String> reached) {
        if (!reached.add(nodeId)) {
            return;
        }
        graph.outgoing(nodeId).forEach(edge -> walk(edge.target(), graph, reached));
    }
}
