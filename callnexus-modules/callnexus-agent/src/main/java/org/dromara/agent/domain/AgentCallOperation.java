package org.dromara.agent.domain;

/**
 * 通话阶段之外的临时控制操作，避免转接过程覆盖主通话状态。
 */
public enum AgentCallOperation {
    NONE,
    TRANSFERRING_IVR,
    CONSULTING,
    CONFERENCE,
    BLIND_TRANSFERRING
}
