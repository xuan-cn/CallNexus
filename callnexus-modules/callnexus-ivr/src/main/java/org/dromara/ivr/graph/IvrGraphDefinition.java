package org.dromara.ivr.graph;

import java.util.List;
import java.util.Map;

public record IvrGraphDefinition(
    List<IvrNodeDefinition> nodes,
    List<IvrEdgeDefinition> edges,
    Map<String, IvrNodeDefinition> nodeById,
    Map<String, List<IvrEdgeDefinition>> outgoingBySource
) {

    public List<IvrEdgeDefinition> outgoing(String nodeId) {
        return outgoingBySource.getOrDefault(nodeId, List.of());
    }

    public String defaultTarget(String nodeId) {
        return outgoing(nodeId).stream()
            .filter(edge -> edge.condition() == null || edge.condition().isBlank())
            .map(IvrEdgeDefinition::target)
            .findFirst()
            .orElse(null);
    }
}
