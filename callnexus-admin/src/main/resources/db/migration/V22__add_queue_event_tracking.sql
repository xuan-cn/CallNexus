-- 队列事件轨迹支持：记录本次业务通话实际接听的呼叫队列，
-- 用于通话详情时间线展示队列处理过程，并按实际接听队列计算话后整理时长。
ALTER TABLE cc_call_session
    ADD COLUMN handling_queue_id BIGINT NULL COMMENT '本次通话实际接听的呼叫队列ID，用于话后整理时长计算' AFTER agent_extension,
    ADD COLUMN handling_queue_name VARCHAR(64) NULL COMMENT '本次通话实际接听的呼叫队列名称，便于详情直接展示' AFTER handling_queue_id;
