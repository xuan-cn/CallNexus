package org.dromara.ivr.graph;

import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class IvrGraphParser {

    public IvrGraphDefinition parse(String graphJson) {
        try {
            JsonNode root = JsonUtils.getObjectMapper().readTree(graphJson);
            JsonNode rawNodes = root.path("nodes");
            JsonNode rawEdges = root.path("edges");
            if (!rawNodes.isArray() || !rawEdges.isArray()) {
                throw new ServiceException("IVR_GRAPH_JSON_INVALID");
            }
            List<IvrNodeDefinition> nodes = new ArrayList<>();
            Map<String, IvrNodeDefinition> nodeById = new LinkedHashMap<>();
            for (JsonNode rawNode : rawNodes) {
                IvrNodeDefinition node = new IvrNodeDefinition(
                    rawNode.path("id").asText(),
                    rawNode.path("type").asText(),
                    rawNode.path("name").asText(),
                    rawNode.path("config")
                );
                if (nodeById.putIfAbsent(node.id(), node) != null) {
                    throw new ServiceException("IVR_NODE_ID_DUPLICATED");
                }
                nodes.add(node);
            }
            List<IvrEdgeDefinition> edges = new ArrayList<>();
            Map<String, List<IvrEdgeDefinition>> outgoingBySource = new LinkedHashMap<>();
            for (JsonNode rawEdge : rawEdges) {
                IvrEdgeDefinition edge = new IvrEdgeDefinition(
                    rawEdge.path("id").asText(),
                    rawEdge.path("source").asText(),
                    rawEdge.path("target").asText(),
                    rawEdge.path("condition").asText("")
                );
                edges.add(edge);
                outgoingBySource.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge);
            }
            return new IvrGraphDefinition(
                List.copyOf(nodes),
                List.copyOf(edges),
                Map.copyOf(nodeById),
                immutableOutgoing(outgoingBySource)
            );
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("IVR_GRAPH_JSON_INVALID");
        }
    }

    private Map<String, List<IvrEdgeDefinition>> immutableOutgoing(Map<String, List<IvrEdgeDefinition>> outgoingBySource) {
        Map<String, List<IvrEdgeDefinition>> result = new LinkedHashMap<>();
        outgoingBySource.forEach((source, edges) -> result.put(source, List.copyOf(edges)));
        return Map.copyOf(result);
    }
}
