package org.dromara.ivr.compiler;

import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.graph.IvrGraphDefinition;
import org.dromara.ivr.graph.IvrNodeDefinition;
import org.dromara.ivr.support.IvrDialplanRenderSupport;
import org.dromara.ivr.support.IvrMediaPathResolver;

public record IvrNodeContext(
    String tenantId,
    IvrFlow flow,
    Long freeSwitchNodeId,
    String sipDomain,
    String callerNumber,
    IvrGraphDefinition graph,
    IvrNodeDefinition node,
    StringBuilder xml,
    IvrDialplanRenderSupport renderSupport,
    IvrMediaPathResolver mediaPathResolver
) {
}
