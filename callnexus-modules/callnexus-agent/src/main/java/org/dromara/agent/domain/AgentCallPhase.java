package org.dromara.agent.domain;

/**
 * 坐席当前业务通话的权威阶段。
 */
public enum AgentCallPhase {
    IDLE,
    INCOMING_RINGING,
    OUTBOUND_DIALING,
    CONNECTED,
    HELD,
    ENDING,
    ENDED
}
