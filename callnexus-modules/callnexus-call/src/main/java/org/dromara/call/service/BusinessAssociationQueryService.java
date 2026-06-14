package org.dromara.call.service;

/**
 * 业务关联按号码回查契约。
 *
 * <p>通话详情展示关联客户和工单时，优先使用业务通话主记录上的显式关联字段（坐席创建客户/工单时写入）。
 * 若显式关联为空（例如队列来电坐席未创建客户/工单），则按通话号码回查历史客户和工单，
 * 让详情仍能展示关联关系。本契约由 customer 模块实现，避免 call 模块直接访问 customer 的 Mapper。
 */
public interface BusinessAssociationQueryService {

    /**
     * 按号码查询历史客户 ID。
     *
     * @param phone 客户主号码（主叫或被叫号码）
     * @return 客户 ID；不存在返回 null
     */
    Long findCustomerIdByPhone(String phone);

    /**
     * 按来电号码查询最新工单 ID。
     *
     * @param callerNumber 工单的来电号码
     * @return 最新工单 ID；不存在返回 null
     */
    Long findLatestTicketIdByCallerNumber(String callerNumber);
}
