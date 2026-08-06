-- OpenAPI 事件中心：支持 WebSocket 订阅、事件持久化补拉和可靠 Webhook 投递。
ALTER TABLE cc_openapi_application
    ADD COLUMN websocket_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用 WebSocket 事件推送' AFTER max_concurrent_calls,
    ADD COLUMN webhook_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用 Webhook 事件推送' AFTER websocket_enabled,
    ADD COLUMN webhook_url VARCHAR(500) NULL COMMENT 'Webhook 回调地址' AFTER webhook_enabled,
    ADD COLUMN webhook_secret VARCHAR(500) NULL COMMENT '加密存储的 Webhook 签名密钥' AFTER webhook_url,
    ADD COLUMN subscribed_events TEXT NULL COMMENT '订阅事件类型 JSON 数组' AFTER webhook_secret;

CREATE TABLE IF NOT EXISTS cc_openapi_event (
    id BIGINT NOT NULL COMMENT '事件ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型',
    business_call_id VARCHAR(64) NULL COMMENT '业务通话ID',
    node_id BIGINT NULL COMMENT 'FreeSWITCH 节点ID',
    occurred_at DATETIME NOT NULL COMMENT '事件发生时间',
    payload_json MEDIUMTEXT NOT NULL COMMENT '事件负载 JSON',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_cc_openapi_event_call (tenant_id, business_call_id, id),
    KEY idx_cc_openapi_event_type (tenant_id, event_type, id)
) ENGINE=InnoDB COMMENT='OpenAPI 标准事件表';

CREATE TABLE IF NOT EXISTS cc_openapi_event_delivery (
    id BIGINT NOT NULL COMMENT '投递记录ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    event_id BIGINT NOT NULL COMMENT '事件ID',
    application_id BIGINT NOT NULL COMMENT 'OpenAPI 应用ID',
    delivery_type VARCHAR(16) NOT NULL DEFAULT 'WEBHOOK' COMMENT '投递类型',
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING待投递、PROCESSING处理中、RETRY等待重试、SUCCESS成功、FAILED失败',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    next_retry_at DATETIME NULL COMMENT '下次重试时间',
    last_http_status INT NULL COMMENT '最近一次 HTTP 状态码',
    last_response VARCHAR(1000) NULL COMMENT '最近一次响应内容摘要',
    failure_reason VARCHAR(1000) NULL COMMENT '失败原因',
    delivered_at DATETIME NULL COMMENT '投递成功时间',
    processing_started_at DATETIME NULL COMMENT '本次处理开始时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_openapi_event_delivery (tenant_id, event_id, application_id, delivery_type),
    KEY idx_cc_openapi_delivery_retry (delivery_status, next_retry_at),
    KEY idx_cc_openapi_delivery_app (tenant_id, application_id, id)
) ENGINE=InnoDB COMMENT='OpenAPI 事件投递记录表';
