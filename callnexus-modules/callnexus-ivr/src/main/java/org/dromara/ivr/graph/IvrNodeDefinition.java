package org.dromara.ivr.graph;

import com.fasterxml.jackson.databind.JsonNode;

public record IvrNodeDefinition(String id, String type, String name, JsonNode config) {
}
