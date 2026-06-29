package org.dromara.resource.event.queue;

/**
 * 队列挂机评价结果信号。
 *
 * <p>FreeSWITCH 完成 {@code play_and_get_digits} 后，通过内部 XML-Curl 路由携带按键结果。
 * resource 模块发布该信号，call 模块同步完成评价落库。</p>
 */
public record QueueSatisfactionSignalEvent(
    String tenantId,
    String businessCallId,
    String customerLegUuid,
    Long queueId,
    Long nodeId,
    String digit
) {
}
