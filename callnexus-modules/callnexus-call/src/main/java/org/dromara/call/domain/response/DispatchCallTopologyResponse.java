package org.dromara.call.domain.response;

import lombok.Data;

import java.util.List;

@Data
public class DispatchCallTopologyResponse {
    private DispatchActiveCallResponse call;
    private List<CallDiagnosticLegResponse> legs;
    private List<CallDiagnosticBridgeResponse> bridges;
    private List<AgentCallSessionResponse> agentSessions;
}
