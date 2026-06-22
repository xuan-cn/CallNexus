package org.dromara.agent.domain;

public enum AgentConsultCallStatus {
    CUSTOMER_HOLDING,
    DIALING,
    TARGET_RINGING,
    TARGET_ANSWERED,
    CONSULT_BRIDGING,
    CONSULT_TALKING,
    CONNECTED,
    COMPLETING,
    COMPLETED,
    CANCELLING,
    CANCELLED,
    FAILED
}
