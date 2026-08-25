package org.dromara.ai.service;

/**
 * AI 意图创建工单的跨模块契约，由客户工单模块提供实现。
 */
public interface AiIntentTicketActionService {
    Long create(String businessCallId, Long templateId, boolean submitAfterCreate);
}
