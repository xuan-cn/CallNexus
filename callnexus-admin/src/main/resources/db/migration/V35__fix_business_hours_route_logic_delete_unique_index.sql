-- 修复号码工作时间路由逻辑删除时与历史已删除记录发生唯一索引冲突的问题。
-- MySQL 唯一索引允许存在多个 NULL，因此生成列仅在记录未删除时返回业务唯一键；
-- 已删除记录的生成列为 NULL，既保留历史数据，也允许后续重新绑定相同号码。
ALTER TABLE cc_phone_business_hours_route
    DROP INDEX uk_cc_phone_business_hours_route_phone,
    ADD COLUMN active_phone_number_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN phone_number_id ELSE NULL END) STORED
        COMMENT '未删除号码ID，用于工作时间路由逻辑删除唯一约束',
    ADD UNIQUE KEY uk_cc_phone_business_hours_route_active_phone (tenant_id, active_phone_number_id);
