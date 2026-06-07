package org.dromara.agent.domain;

public enum AgentPresenceStatus {
    OFFLINE, //分机未注册或网络断开
    IDLE, //分机已注册，但没有通话，可以接听或拨打电话
    BUSY, //正在通话中
    AFTER_CALL //通话刚结束，处于话后处理状态（通常此时无法接听新来电，直到状态恢复为 IDLE）
}
