-- Replace generic report menu icons with clearer business-specific icons.
UPDATE sys_menu
SET icon = 'people', update_time = SYSDATE()
WHERE path = 'agents' AND component = 'callcenter/report-agent/index';

UPDATE sys_menu
SET icon = 'monitor', update_time = SYSDATE()
WHERE path = 'queues' AND component = 'callcenter/report-queue/index';
