package org.dromara.resource.queue.domain.response;

import lombok.Data;

@Data
public class CallQueueDialplanResponse {
    private Long id;
    private String queueCode;
    private String queueName;
    private Boolean maskCallerNumber;
    /**
     * 是否启用手动接听。
     *
     * <p>true：号码直接进入队列时，客户腿采用 {@code pre_answer}（早媒体），
     * 客户能听到等待音、提醒音，但运营商侧通话状态仍为"振铃中"；
     * 当 {@code mod_callcenter} 把客户腿桥接到坐席时，FreeSWITCH 会自动 answer 客户腿，
     * 此刻才进入正式接通状态，运营商开始计费。
     *
     * <p>false：兼容历史行为，号码进入队列即 {@code answer}，客户腿立即接通。
     *
     * <p>说明：IVR 转队列场景下 IVR 流程已 answer 客户腿，该开关对 IVR 节点不再生效。
     */
    private Boolean manualAnswer;
    private Integer forceWaitSeconds;
    private String forceWaitMediaPath;
    private String timeoutAction;
    private String timeoutTarget;
    private String timeoutTargetQueueCode;
    private String noAgentAction;
    private String noAgentTarget;
    private String noAgentTargetQueueCode;
    private Integer noAgentWaitSeconds;

    /**
     * 记忆坐席：true 时尝试把同一客户号码再次路由到上次接听的坐席。
     * 由 {@code QueueDialplanRouteHandler} 在 xml-curl 阶段根据 Redis 记录解析得到，
     * 命中时绕开 mod_callcenter 直接 {@code bridge user/分机@域}，未命中则回落到正常队列。
     */
    private Boolean stickyAgentEnabled;
    /** Redis 命中的坐席分机号（含域名），形如 1001@example.com；未命中为 null。 */
    private String stickyAgentTarget;

    /**
     * 遇忙转手机：当 mod_callcenter 因 {@code cc_cause=max-wait} 退出（无可用坐席/超过最大等待）时，
     * 桥接到运营商外呼网关上的手机号继续通话。
     */
    private Boolean busyTransferMobile;
    private String busyTransferNumber;

    /**
     * 坐席超时转手机：当 mod_callcenter 因 {@code cc_cause=max-no-answer} 退出
     * （达到最大无应答次数，所有可用坐席均未接）时，桥接到手机号继续通话。
     */
    private Boolean agentTimeoutTransferMobile;
    private String agentTimeoutTransferNumber;

    /** 转手机时使用的默认外呼网关编码；为空则忽略所有手机转接动作。 */
    private String outboundGatewayCode;
}