package org.dromara.resource.event.sip;

/**
 * SIP 分机删除事件。
 *
 * <p>资源模块只负责持久化分机，FreeSWITCH 运行时清理由 ESL 模块在事务提交后处理，
 * 避免 resource 与 esl 模块形成循环依赖。</p>
 */
public record SipAccountDeletedEvent(
    String tenantId,
    Long nodeId,
    String extension,
    String authUsername,
    String domain
) {
}
